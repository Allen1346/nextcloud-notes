package it.niedermann.owncloud.notes.model;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import it.niedermann.owncloud.notes.persistence.NoteSQLiteOpenHelper;
import it.niedermann.owncloud.notes.util.NoteUtil;

@SuppressWarnings("serial")
public class Note implements Serializable {
	private long id = 0;
	private String title = "";
	private Calendar modified = null;
	private String content = "";
    private String excerpt = "";
    private String spannableContent = null;

	public Note(long id, Calendar modified, String title, String content) {
		this.id = id;
        if(title != null)
            setTitle(title);
        setTitle(title);
        setContent(content);
        this.modified = modified;
    }

	public long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

    public void setTitle(String title) {
        this.title = NoteUtil.removeMarkDown(title);
    }

	@SuppressWarnings("WeakerAccess")
    public Calendar getModified() {
		return modified;
	}

	public String getModified(String format) {
		return new SimpleDateFormat(format, Locale.GERMANY)
                .format(this.getModified().getTimeInMillis());
    }

	public String getContent() {
		return content;
	}

    public void setContent(String content) {
        setExcerpt(content);
        this.content = content;
        this.spannableContent = null;
    }

    public String getExcerpt() {
        return excerpt;
    }

    private void setExcerpt(String content) {
        excerpt = NoteUtil.generateNoteExcerpt(content);
    }

    public String getSpannableContent() {
        if (spannableContent == null && getContent() != null) {
            spannableContent = NoteUtil.parseMarkDown(getContent());
        }
        return spannableContent;
    }

	@Override
	public String toString() {
		return "#" + getId() + " " + getTitle() + " (" + getModified(NoteSQLiteOpenHelper.DATE_FORMAT) + ")";
	}
}