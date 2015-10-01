package it.niedermann.owncloud.notes.persistence;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.View;

import org.json.JSONException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.android.activity.SettingsActivity;
import it.niedermann.owncloud.notes.model.DBStatus;
import it.niedermann.owncloud.notes.model.Note;
import it.niedermann.owncloud.notes.util.ICallback;
import it.niedermann.owncloud.notes.util.NotesClient;

/**
 * Helps to synchronize the Database to the Server.
 * <p/>
 * Created by stefan on 20.09.15.
 */
public class NoteServerSyncHelper {

    private NotesClient client = null;
    private  NoteSQLiteOpenHelper db = null;

    private int operationsCount = 0;
    private int operationsFinished = 0;

    private List<ICallback> callbacks = new ArrayList<>();

    private final View.OnClickListener goToSettingsListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Activity parent = (Activity) db.getContext();
            Intent intent = new Intent(parent, SettingsActivity.class);
            parent.startActivity(intent);
        }
    };

    public void addCallback(ICallback callback) {
        callbacks.add(callback);
    }

    public boolean isFinished() {
        return operationsFinished == operationsCount;
    }

    public NoteServerSyncHelper(NoteSQLiteOpenHelper db) {
        this.db = db;
        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(db.getContext().getApplicationContext());
        String url = preferences.getString(SettingsActivity.SETTINGS_URL,
                SettingsActivity.DEFAULT_SETTINGS);
        String username = preferences.getString(SettingsActivity.SETTINGS_USERNAME,
                SettingsActivity.DEFAULT_SETTINGS);
        String password = preferences.getString(SettingsActivity.SETTINGS_PASSWORD,
                SettingsActivity.DEFAULT_SETTINGS);
        client = new NotesClient(url, username, password);
    }

    public void synchronize() {
        uploadEditedNotes();
        uploadNewNotes();
        uploadDeletedNotes();
        downloadNotes();
        final Handler handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                for (ICallback callback : callbacks) {
                    callback.onFinish();
                }
            }
        };
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                Log.v("Note", "Sync operations: " + operationsFinished + "/" + operationsCount);
                if (isFinished()) {
                    handler.obtainMessage(1).sendToTarget();
                    cancel();
                }
            }
        }, 0, 200);
    }

    public void uploadEditedNotes() {
        List<Note> notes = db.getNotesByStatus(DBStatus.LOCAL_EDITED);
        for (Note note : notes) {
            UploadEditedNotesTask editedNotesTask = new UploadEditedNotesTask();
            editedNotesTask.execute(note);
        }
    }

    public void uploadNewNotes() {
        List<Note> notes = db.getNotesByStatus(DBStatus.LOCAL_CREATED);
        for (Note note : notes) {
            UploadNewNoteTask newNotesTask = new UploadNewNoteTask();
            newNotesTask.execute(note);
        }
    }

    public void uploadDeletedNotes() {
        List<Note> notes = db.getNotesByStatus(DBStatus.LOCAL_DELETED);
        for (Note note : notes) {
            UploadDeletedNoteTask deletedNotesTask = new UploadDeletedNoteTask();
            deletedNotesTask.execute(note);
        }
    }

    public void downloadNotes() {
        DownloadNotesTask downloadNotesTask = new DownloadNotesTask();
        downloadNotesTask.execute();
    }

    private class UploadNewNoteTask extends AsyncTask<Object, Void, Object[]> {
        @Override
        protected Object[] doInBackground(Object... params) {
            operationsCount++;
            Note oldNote = (Note) params[0];
            try {
                Note note = client.createNote(oldNote.getContent());
                return new Object[]{note, oldNote.getId()};
            } catch (MalformedURLException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_url_malformed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Activity parent = (Activity) db.getContext();
                                Intent intent = new Intent(parent, SettingsActivity.class);
                                parent.startActivity(intent);
                            }
                        })
                        .show();
                e.printStackTrace();
            } catch (JSONException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_json, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Activity parent = (Activity) db.getContext();
                                Intent intent = new Intent(parent, SettingsActivity.class);
                                parent.startActivity(intent);
                            }
                        })
                        .show();
                e.printStackTrace();
            } catch (IOException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_io, Snackbar.LENGTH_LONG)
                        .show();
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Object[] params) {
            if(params != null) {
                Long id = (Long) params[1];
                if (id != null) {
                    db.deleteNote(((Long) params[1]));
                }
                db.addNote((Note) params[0]);
            }
            operationsFinished++;
        }
    }

    private class UploadEditedNotesTask extends AsyncTask<Object, Void, Note> {
        @Override
        protected Note doInBackground(Object... params) {
            operationsCount++;
            try {
                Note oldNote = (Note) params[0];
                return client.editNote(oldNote.getId(), oldNote.getContent());
            } catch (MalformedURLException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_url_malformed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, goToSettingsListener)
                        .show();
                e.printStackTrace();
            } catch (JSONException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_json, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, goToSettingsListener)
                        .show();
                e.printStackTrace();
            } catch (IOException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_io, Snackbar.LENGTH_LONG)
                        .show();
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Note note) {
            db.updateNote(note);
            operationsFinished++;
        }
    }

    private class UploadDeletedNoteTask extends AsyncTask<Object, Void, Void> {
        Long id = null;

        @Override
        protected Void doInBackground(Object... params) {
            operationsCount++;
            try {
                id = ((Note) params[0]).getId();
                client.deleteNote(id);
            } catch (MalformedURLException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_url_malformed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, goToSettingsListener)
                        .show();
                e.printStackTrace();
            } catch (IOException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_io, Snackbar.LENGTH_LONG)
                        .show();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            db.deleteNote(id);
            operationsFinished++;
        }
    }

    private class DownloadNotesTask extends AsyncTask<Object, Void, List<Note>> {
        private boolean serverError = false;

        @Override
        protected List<Note> doInBackground(Object... params) {
            operationsCount++;
            List<Note> notes = new ArrayList<>();
            try {
                notes = client.getNotes();
            } catch (MalformedURLException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_url_malformed, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, goToSettingsListener)
                        .show();
                serverError = true;
                e.printStackTrace();
            } catch (JSONException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_json, Snackbar.LENGTH_LONG)
                        .setAction(R.string.snackbar_settings, goToSettingsListener)
                        .show();
                serverError = true;
                e.printStackTrace();
            } catch (IOException e) {
                Snackbar
                        .make(((Activity) db.getContext()).getWindow().getDecorView(), R.string.error_io, Snackbar.LENGTH_LONG)
                        .show();
                serverError = true;
                e.printStackTrace();
            }
            return notes;
        }

        @Override
        protected void onPostExecute(List<Note> result) {
            // Clear Database only if there is an Server
            if(!serverError) {
                db.clearDatabase();
            }
            for (Note note : result) {
                db.addNote(note);
            }
            operationsFinished++;
        }
    }
}
