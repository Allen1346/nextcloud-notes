package it.niedermann.owncloud.notes.main;

import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.arch.core.util.Function;
import androidx.core.util.Pair;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NextcloudHttpRequestFailedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.branding.BrandingUtil;
import it.niedermann.owncloud.notes.main.navigation.NavigationAdapter;
import it.niedermann.owncloud.notes.main.navigation.NavigationItem;
import it.niedermann.owncloud.notes.persistence.CapabilitiesClient;
import it.niedermann.owncloud.notes.persistence.NoteServerSyncHelper;
import it.niedermann.owncloud.notes.persistence.NotesDatabase;
import it.niedermann.owncloud.notes.persistence.entity.Account;
import it.niedermann.owncloud.notes.persistence.entity.Category;
import it.niedermann.owncloud.notes.persistence.entity.CategoryWithNotesCount;
import it.niedermann.owncloud.notes.persistence.entity.NoteWithCategory;
import it.niedermann.owncloud.notes.persistence.entity.SingleNoteWidgetData;
import it.niedermann.owncloud.notes.shared.model.Capabilities;
import it.niedermann.owncloud.notes.shared.model.CategorySortingMethod;
import it.niedermann.owncloud.notes.shared.model.Item;
import it.niedermann.owncloud.notes.shared.model.NavigationCategory;

import static androidx.lifecycle.Transformations.distinctUntilChanged;
import static androidx.lifecycle.Transformations.map;
import static androidx.lifecycle.Transformations.switchMap;
import static it.niedermann.owncloud.notes.main.MainActivity.ADAPTER_KEY_RECENT;
import static it.niedermann.owncloud.notes.main.MainActivity.ADAPTER_KEY_STARRED;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByCategory;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByInitials;
import static it.niedermann.owncloud.notes.main.slots.SlotterUtil.fillListByTime;
import static it.niedermann.owncloud.notes.shared.model.CategorySortingMethod.SORT_MODIFIED_DESC;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.DEFAULT_CATEGORY;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.FAVORITES;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.RECENT;
import static it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType.UNCATEGORIZED;
import static it.niedermann.owncloud.notes.shared.util.DisplayUtils.convertToCategoryNavigationItem;

public class MainViewModel extends AndroidViewModel {

    private static final String TAG = MainViewModel.class.getSimpleName();

    @NonNull
    private final NotesDatabase db;

    @NonNull
    private final MutableLiveData<Account> currentAccount = new MutableLiveData<>();

    @NonNull
    private final MutableLiveData<String> searchTerm = new MutableLiveData<>(null);

    @NonNull
    private final MutableLiveData<NavigationCategory> selectedCategory = new MutableLiveData<>(new NavigationCategory(RECENT));

    @NonNull
    private final MutableLiveData<String> expandedCategory = new MutableLiveData<>(null);

    public MainViewModel(@NonNull Application application) {
        super(application);
        this.db = NotesDatabase.getInstance(application.getApplicationContext());
    }

    @NonNull
    public LiveData<Account> getCurrentAccount() {
        return distinctUntilChanged(currentAccount);
    }

    public void postCurrentAccount(@NonNull Account account) {
        BrandingUtil.saveBrandColors(getApplication(), account.getColor(), account.getTextColor());
        SingleAccountHelper.setCurrentAccount(getApplication(), account.getAccountName());
        this.currentAccount.postValue(account);
        this.searchTerm.postValue("");
        this.selectedCategory.postValue(new NavigationCategory(RECENT));
    }

    @NonNull
    public LiveData<String> getSearchTerm() {
        return distinctUntilChanged(searchTerm);
    }

    public void postSearchTerm(String searchTerm) {
        this.searchTerm.postValue(searchTerm);
    }

    @NonNull
    public LiveData<NavigationCategory> getSelectedCategory() {
        return distinctUntilChanged(selectedCategory);
    }

    public void postSelectedCategory(@NonNull NavigationCategory selectedCategory) {
        this.selectedCategory.postValue(selectedCategory);

        // Close sub categories
        switch (selectedCategory.getType()) {
            case RECENT:
            case FAVORITES:
            case UNCATEGORIZED: {
                postExpandedCategory(null);
                break;
            }
            case DEFAULT_CATEGORY:
            default: {
                Category category = selectedCategory.getCategory();
                if (category == null) {
                    postExpandedCategory(null);
                    Log.e(TAG, "navigation selection is a " + DEFAULT_CATEGORY + ", but the contained category is null.");
                } else {
                    String title = category.getTitle();
                    int slashIndex = title == null ? -1 : title.indexOf('/');
                    String rootCategory = slashIndex < 0 ? title : title.substring(0, slashIndex);
                    String expandedCategory = getExpandedCategory().getValue();
                    if (expandedCategory != null && !expandedCategory.equals(rootCategory)) {
                        postExpandedCategory(null);
                    }
                }
                break;
            }
        }
    }

    @NonNull
    @MainThread
    public LiveData<Pair<NavigationCategory, CategorySortingMethod>> getCategorySortingMethodOfSelectedCategory() {
        return switchMap(getSelectedCategory(), selectedCategory -> map(db.getCategoryOrder(selectedCategory), sortingMethod -> new Pair<>(selectedCategory, sortingMethod)));
    }

    public void postExpandedCategory(@Nullable String expandedCategory) {
        this.expandedCategory.postValue(expandedCategory);
    }

    @NonNull
    public LiveData<String> getExpandedCategory() {
        return distinctUntilChanged(expandedCategory);
    }

    @NonNull
    @MainThread
    public LiveData<List<Item>> getNotesListLiveData() {
        final MutableLiveData<List<Item>> insufficientInformation = new MutableLiveData<>();
        return switchMap(getCurrentAccount(), currentAccount -> {
            Log.v(TAG, "[getNotesListLiveData] - currentAccount: " + currentAccount);
            if (currentAccount == null) {
                return insufficientInformation;
            } else {
                return switchMap(getSelectedCategory(), selectedCategory -> {
                    if (selectedCategory == null) {
                        return insufficientInformation;
                    } else {
                        return switchMap(getSearchTerm(), searchTerm -> {
                            Log.v(TAG, "[getNotesListLiveData] - searchTerm: " + searchTerm);
                            return switchMap(getCategorySortingMethodOfSelectedCategory(), sortingMethod -> {
                                final Long accountId = currentAccount.getId();
                                final String searchQueryOrWildcard = searchTerm == null ? "%" : "%" + searchTerm.trim() + "%";
                                Log.v(TAG, "[getNotesListLiveData] - sortMethod: " + sortingMethod.second);
                                final LiveData<List<NoteWithCategory>> fromDatabase;
                                switch (selectedCategory.getType()) {
                                    case RECENT: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + RECENT);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? db.getNoteDao().searchRecentByModified(accountId, searchQueryOrWildcard)
                                                : db.getNoteDao().searchRecentLexicographically(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case FAVORITES: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + FAVORITES);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? db.getNoteDao().searchFavoritesByModified(accountId, searchQueryOrWildcard)
                                                : db.getNoteDao().searchFavoritesLexicographically(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case UNCATEGORIZED: {
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + UNCATEGORIZED);
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? db.getNoteDao().searchUncategorizedByModified(accountId, searchQueryOrWildcard)
                                                : db.getNoteDao().searchUncategorizedLexicographically(accountId, searchQueryOrWildcard);
                                        break;
                                    }
                                    case DEFAULT_CATEGORY:
                                    default: {
                                        final Category category = selectedCategory.getCategory();
                                        if (category == null) {
                                            throw new IllegalStateException(NavigationCategory.class.getSimpleName() + " type is " + DEFAULT_CATEGORY + ", but category is null.");
                                        }
                                        Log.v(TAG, "[getNotesListLiveData] - category: " + category.getTitle());
                                        fromDatabase = sortingMethod.second == SORT_MODIFIED_DESC
                                                ? db.getNoteDao().searchCategoryByModified(accountId, searchQueryOrWildcard, category.getTitle())
                                                : db.getNoteDao().searchCategoryLexicographically(accountId, searchQueryOrWildcard, category.getTitle());
                                        break;
                                    }
                                }

                                Log.v(TAG, "[getNotesListLiveData] - -------------------------------------");
                                return distinctUntilChanged(map(fromDatabase, noteList -> fromNotesWithCategory(noteList, selectedCategory, sortingMethod.second)));
                            });
                        });
                    }
                });
            }
        });
    }

    private List<Item> fromNotesWithCategory(List<NoteWithCategory> noteList, @NonNull NavigationCategory selectedCategory, @Nullable CategorySortingMethod sortingMethod) {
        if (selectedCategory.getType() == DEFAULT_CATEGORY) {
            final Category category = selectedCategory.getCategory();
            if (category != null) {
                return fillListByCategory(noteList, category.getTitle());
            } else {
                throw new IllegalStateException(NavigationCategory.class.getSimpleName() + " type is " + DEFAULT_CATEGORY + ", but category is null.");
            }
        }
        if (sortingMethod == SORT_MODIFIED_DESC) {
            return fillListByTime(getApplication(), noteList);
        } else {
            return fillListByInitials(getApplication(), noteList);
        }
    }

    @NonNull
    @MainThread
    public LiveData<List<NavigationItem>> getNavigationCategories() {
        final MutableLiveData<List<NavigationItem>> insufficientInformation = new MutableLiveData<>();
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return insufficientInformation;
            } else {
                Log.v(TAG, "[getNavigationCategories] - currentAccount: " + currentAccount.getAccountName());
                return switchMap(getExpandedCategory(), expandedCategory -> {
                    Log.v(TAG, "[getNavigationCategories] - expandedCategory: " + expandedCategory);
                    return distinctUntilChanged(map(db.getCategoryDao().getCategoriesLiveData(currentAccount.getId()), fromDatabase ->
                            fromCategoriesWithNotesCount(getApplication(), expandedCategory, fromDatabase, db.getNoteDao().count(currentAccount.getId()), db.getNoteDao().getFavoritesCount(currentAccount.getId()))
                    ));
                });
            }
        });
    }

    private static List<NavigationItem> fromCategoriesWithNotesCount(@NonNull Context context, @Nullable String expandedCategory, @NonNull List<CategoryWithNotesCount> fromDatabase, int count, int favoritesCount) {
        final List<NavigationItem.CategoryNavigationItem> categories = convertToCategoryNavigationItem(context, fromDatabase);
        final NavigationItem itemRecent = new NavigationItem(ADAPTER_KEY_RECENT, context.getString(R.string.label_all_notes), count, R.drawable.ic_access_time_grey600_24dp, RECENT);
        final NavigationItem itemFavorites = new NavigationItem(ADAPTER_KEY_STARRED, context.getString(R.string.label_favorites), favoritesCount, R.drawable.ic_star_yellow_24dp, FAVORITES);

        final ArrayList<NavigationItem> items = new ArrayList<>(fromDatabase.size() + 3);
        items.add(itemRecent);
        items.add(itemFavorites);
        NavigationItem lastPrimaryCategory = null;
        NavigationItem lastSecondaryCategory = null;
        for (NavigationItem item : categories) {
            int slashIndex = item.label.indexOf('/');
            String currentPrimaryCategory = slashIndex < 0 ? item.label : item.label.substring(0, slashIndex);
            String currentSecondaryCategory = null;
            boolean isCategoryOpen = currentPrimaryCategory.equals(expandedCategory);

            if (isCategoryOpen && !currentPrimaryCategory.equals(item.label)) {
                String currentCategorySuffix = item.label.substring(expandedCategory.length() + 1);
                int subSlashIndex = currentCategorySuffix.indexOf('/');
                currentSecondaryCategory = subSlashIndex < 0 ? currentCategorySuffix : currentCategorySuffix.substring(0, subSlashIndex);
            }

            boolean belongsToLastPrimaryCategory = lastPrimaryCategory != null && currentPrimaryCategory.equals(lastPrimaryCategory.label);
            boolean belongsToLastSecondaryCategory = belongsToLastPrimaryCategory && lastSecondaryCategory != null && lastSecondaryCategory.label.equals(currentPrimaryCategory + "/" + currentSecondaryCategory);

            if (isCategoryOpen && !belongsToLastPrimaryCategory && currentSecondaryCategory != null) {
                lastPrimaryCategory = new NavigationItem("category:" + currentPrimaryCategory, currentPrimaryCategory, 0, NavigationAdapter.ICON_MULTIPLE_OPEN);
                items.add(lastPrimaryCategory);
                belongsToLastPrimaryCategory = true;
            }

            if (belongsToLastPrimaryCategory && belongsToLastSecondaryCategory) {
                lastSecondaryCategory.count += item.count;
                lastSecondaryCategory.icon = NavigationAdapter.ICON_SUB_MULTIPLE;
            } else if (belongsToLastPrimaryCategory) {
                if (isCategoryOpen) {
                    item.label = currentPrimaryCategory + "/" + currentSecondaryCategory;
                    item.id = "category:" + item.label;
                    item.icon = NavigationAdapter.ICON_SUB_FOLDER;
                    items.add(item);
                    lastSecondaryCategory = item;
                } else {
                    lastPrimaryCategory.count += item.count;
                    lastPrimaryCategory.icon = NavigationAdapter.ICON_MULTIPLE;
                    lastSecondaryCategory = null;
                }
            } else {
                if (isCategoryOpen) {
                    item.icon = NavigationAdapter.ICON_MULTIPLE_OPEN;
                } else {
                    item.label = currentPrimaryCategory;
                    item.id = "category:" + item.label;
                }
                items.add(item);
                lastPrimaryCategory = item;
                lastSecondaryCategory = null;
            }
        }
        return items;
    }

    public LiveData<Void> modifyCategoryOrder(@NonNull NavigationCategory selectedCategory, @NonNull CategorySortingMethod sortingMethod) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[modifyCategoryOrder] - currentAccount: " + currentAccount.getAccountName());
                db.modifyCategoryOrder(currentAccount.getId(), selectedCategory, sortingMethod);
                return new MutableLiveData<>(null);
            }
        });
    }

    /**
     * @return <code>true</code>, if a synchronization could successfully be triggered, <code>false</code> if not.
     */
    public LiveData<Boolean> synchronize() {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(false);
            } else {
                Log.v(TAG, "[synchronize] - currentAccount: " + currentAccount.getAccountName());
                NoteServerSyncHelper syncHelper = db.getNoteServerSyncHelper();
                if (!syncHelper.isSyncPossible()) {
                    syncHelper.updateNetworkStatus();
                }
                if (syncHelper.isSyncPossible()) {
                    syncHelper.scheduleSync(currentAccount, false);
                    return new MutableLiveData<>(true);
                } else { // Sync is not possible
                    if (syncHelper.isNetworkConnected() && syncHelper.isSyncOnlyOnWifi()) {
                        Log.d(TAG, "Network is connected, but sync is not possible");
                    } else {
                        Log.d(TAG, "Sync is not possible, because network is not connected");
                    }
                }
                return new MutableLiveData<>(false);
            }
        });
    }

    public LiveData<Boolean> getSyncStatus() {
        return db.getNoteServerSyncHelper().getSyncStatus();
    }

    public LiveData<ArrayList<Throwable>> getSyncErrors() {
        return db.getNoteServerSyncHelper().getSyncErrors();
    }

    public LiveData<Boolean> hasMultipleAccountsConfigured() {
        return map(db.getAccountDao().getAccountsCountLiveData(), (counter) -> counter != null && counter > 1);
    }

    public LiveData<Boolean> performFullSynchronizationForCurrentAccount() {
        final MutableLiveData<Boolean> insufficientInformation = new MutableLiveData<>();
        return switchMap(getCurrentAccount(), localAccount -> {
            Log.v(TAG, "[performFullSynchronizationForCurrentAccount] - currentAccount: " + localAccount);
            if (localAccount == null) {
                return insufficientInformation;
            } else {
                Log.i(TAG, "[performFullSynchronizationForCurrentAccount] Refreshing capabilities for " + localAccount.getAccountName());
                MutableLiveData<Boolean> syncCapabilitiesLiveData = new MutableLiveData<>();
                new Thread(() -> {
                    final Capabilities capabilities;
                    try {
                        capabilities = CapabilitiesClient.getCapabilities(getApplication(), AccountImporter.getSingleSignOnAccount(getApplication(), localAccount.getAccountName()), localAccount.getCapabilitiesETag());
                        db.getAccountDao().updateCapabilitiesETag(localAccount.getId(), capabilities.getETag());
                        db.getAccountDao().updateBrand(localAccount.getId(), capabilities.getColor(), capabilities.getTextColor());
                        localAccount.setColor(capabilities.getColor());
                        localAccount.setTextColor(capabilities.getTextColor());
                        BrandingUtil.saveBrandColors(getApplication(), localAccount.getColor(), localAccount.getTextColor());
                        db.updateApiVersion(localAccount.getId(), capabilities.getApiVersion());
                        Log.i(TAG, capabilities.toString());
                        syncCapabilitiesLiveData.postValue(true);
                    } catch (NextcloudFilesAppAccountNotFoundException e) {
                        e.printStackTrace();
                        db.getAccountDao().deleteAccount(localAccount);
                        syncCapabilitiesLiveData.postValue(false);
                    } catch (Exception e) {
                        if (e instanceof NextcloudHttpRequestFailedException && ((NextcloudHttpRequestFailedException) e).getStatusCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            Log.i(TAG, "[performFullSynchronizationForCurrentAccount] Capabilities not modified.");
                        } else {
                            e.printStackTrace();
                        }
                        // Capabilities couldn't be update correctly, we can still try to sync the notes list.
                        syncCapabilitiesLiveData.postValue(true);
                    }

                }).start();
                return switchMap(syncCapabilitiesLiveData, (Function<Boolean, LiveData<Boolean>>) capabilitiesSyncedSuccessfully -> {
                    if (Boolean.TRUE.equals(capabilitiesSyncedSuccessfully)) {
                        Log.v(TAG, "[performFullSynchronizationForCurrentAccount] Capabilities refreshed successfully - synchronize notes for " + localAccount.getAccountName());
                        return synchronize();
                    } else {
                        Log.w(TAG, "[performFullSynchronizationForCurrentAccount] Capabilities could not be refreshed correctly - end synchronization process here.");
                        return new MutableLiveData<>(true);
                    }
                });
            }
        });
    }

    @WorkerThread
    public Account getLocalAccountByAccountName(String accountName) {
        return db.getAccountDao().getLocalAccountByAccountName(accountName);
    }

    @WorkerThread
    public List<Account> getAccounts() {
        return db.getAccountDao().getAccounts();
    }

    public LiveData<Void> setCategory(Iterable<Long> noteIds, @NonNull String category) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[setCategory] - currentAccount: " + currentAccount.getAccountName());
                for (Long noteId : noteIds) {
                    db.setCategory(currentAccount, noteId, category);
                }
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<NoteWithCategory> moveNoteToAnotherAccount(Account account, Long noteId) {
        return db.moveNoteToAnotherAccount(account, db.getNoteDao().getFullNoteWithCategory(noteId));
    }

    @WorkerThread
    public Category getCategory(long id) {
        return db.getCategoryDao().getCategory(id);
    }

    public LiveData<Void> toggleFavoriteAndSync(long noteId) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[toggleFavoriteAndSync] - currentAccount: " + currentAccount.getAccountName());
                db.toggleFavoriteAndSync(currentAccount, noteId);
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Void> deleteNoteAndSync(long id) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[deleteNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                db.deleteNoteAndSync(currentAccount, id);
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Void> deleteNotesAndSync(@NonNull Collection<Long> ids) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>(null);
            } else {
                Log.v(TAG, "[deleteNotesAndSync] - currentAccount: " + currentAccount.getAccountName());
                for (Long id : ids) {
                    db.deleteNoteAndSync(currentAccount, id);
                }
                return new MutableLiveData<>(null);
            }
        });
    }

    public LiveData<Account> addAccount(@NonNull String url, @NonNull String username, @NonNull String accountName, @NonNull Capabilities capabilities) {
        return db.addAccount(url, username, accountName, capabilities);
    }

    public LiveData<NoteWithCategory> getFullNoteWithCategory(long id) {
        return map(getFullNotesWithCategory(Collections.singleton(id)), input -> input.get(0));
    }

    public LiveData<List<NoteWithCategory>> getFullNotesWithCategory(@NonNull Collection<Long> ids) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>();
            } else {
                Log.v(TAG, "[getNoteWithCategory] - currentAccount: " + currentAccount.getAccountName());
                final MutableLiveData<List<NoteWithCategory>> notes = new MutableLiveData<>();
                new Thread(() -> notes.postValue(
                        ids
                                .stream()
                                .map(id -> db.getNoteDao().getFullNoteWithCategory(id))
                                .collect(Collectors.toList())
                )).start();
                return notes;
            }
        });
    }

    public LiveData<NoteWithCategory> addNoteAndSync(NoteWithCategory note) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount == null) {
                return new MutableLiveData<>();
            } else {
                Log.v(TAG, "[addNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                return db.addNoteAndSync(currentAccount, note);
            }
        });
    }

    public LiveData<Void> updateNoteAndSync(@NonNull NoteWithCategory oldNote, @Nullable String newContent, @Nullable String newTitle) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount != null) {
                Log.v(TAG, "[updateNoteAndSync] - currentAccount: " + currentAccount.getAccountName());
                db.updateNoteAndSync(currentAccount, oldNote, newContent, newTitle, null);
            }
            return new MutableLiveData<>(null);
        });
    }

    public void createOrUpdateSingleNoteWidgetData(SingleNoteWidgetData data) {
        db.getWidgetSingleNoteDao().createOrUpdateSingleNoteWidgetData(data);
    }

    public LiveData<Integer> getAccountsCount() {
        return db.getAccountDao().getAccountsCountLiveData();
    }

    public LiveData<String> collectNoteContents(List<Long> noteIds) {
        return switchMap(getCurrentAccount(), currentAccount -> {
            if (currentAccount != null) {
                Log.v(TAG, "[collectNoteContents] - currentAccount: " + currentAccount.getAccountName());
                final MutableLiveData<String> collectedContent$ = new MutableLiveData<>();
                new Thread(() -> {
                    final StringBuilder noteContents = new StringBuilder();
                    for (Long noteId : noteIds) {
                        final NoteWithCategory fullNote = db.getNoteDao().getFullNoteWithCategory(noteId);
                        final String tempFullNote = fullNote.getContent();
                        if (!TextUtils.isEmpty(tempFullNote)) {
                            if (noteContents.length() > 0) {
                                noteContents.append("\n\n");
                            }
                            noteContents.append(tempFullNote);
                        }
                    }
                }).start();
                return collectedContent$;
            }
            return new MutableLiveData<>(null);
        });
    }
}
