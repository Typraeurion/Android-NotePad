/*
 * $Id: NoteCursorAdapter.java,v 1.2 2014/03/30 23:43:44 trevin Exp trevin $
 * Copyright © 2011 Trevin Beattie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * $Log: NoteCursorAdapter.java,v $
 * Revision 1.2  2014/03/30 23:43:44  trevin
 * Added the copyright notice.
 * Removed the ContentResolver, which was unused.
 *
 * Revision 1.1  2011/03/23 02:20:09  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import static com.xmission.trevin.android.notes.NoteListActivity.*;

import java.security.GeneralSecurityException;
import java.util.*;

import com.xmission.trevin.android.notes.Note.NoteItem;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.*;
import android.widget.*;

/**
 * An adapter to map columns from a Note item cursor to respective
 * widgets and views in the list_item layout.
 *
 * @see android.widget.ResourceCursorAdapter
 */
public class NoteCursorAdapter extends ResourceCursorAdapter {

    public final static String TAG = "ToDoCursorAdapter";

    /** The maximum length of a header line we'll show for a note */
    public static final int MAX_HEADER_LENGTH = 50;

    private final SharedPreferences prefs;
    private final Uri listUri;

    /** Encryption in case we're showing private records */
    private final StringEncryption encryptor;

    /**
     * Keep track of which rows are assigned to which views.
     * Binding occurs over and over again for the same rows,
     * so we want to avoid any unnecessary work.
     */
    private Map<View,Long> bindingMap = new HashMap<>();

    /**
     * Constructor
     */
    public NoteCursorAdapter(Context context, int layout, Cursor cursor,
	    ContentResolver cr, Uri uri, Activity activity,
	    StringEncryption encryption) {
	super(context, layout, cursor);
	prefs = context.getSharedPreferences(NOTE_PREFERENCES, MODE_PRIVATE);
 	listUri = uri;
 	encryptor = encryption;
    }

    /**
     * Called when data from a cursor row needs to be displayed in the
     * given view.  This may include temporary display for the purpose of
     * measurement, in which case the same view may be reused multiple
     * times, so we cannot depend on a permanent connection to the data!
     */
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
	int itemID = cursor.getInt(cursor.getColumnIndex(NoteItem._ID));

	// If this view is already bound to the given row, skip (re-)binding.
	if (bindingMap.containsKey(view) &&
		bindingMap.get(view) == itemID)
	    return;

	/* Log.d(TAG, ".bindView(" + view + ",context,cursor [item #"
		+ itemID + "])"); */

	// These are the widgets that need customizing per item
	TextView noteText = (TextView)
		view.findViewById(R.id.NoteEditDescription);
	TextView categText = (TextView) view.findViewById(R.id.NoteTextCateg);

	/*
	 * Get the item data and set the widgets accordingly.
	 * Note that bindView may be called repeatedly on the same item
	 * which has already been initialized.  In order to avoid
	 * unnecessary callbacks, only set widgets when the value
	 * in the database differs from what is already shown.
	 */
	String noteHeading = context.getString(R.string.PasswordProtected);
	int privacy = cursor.getInt(cursor.getColumnIndex(NoteItem.PRIVATE));
	if (privacy > 1) {
	    if (encryptor.hasKey()) {
		try {
		    noteHeading = encryptor.decrypt(cursor.getBlob(
			    cursor.getColumnIndex(NoteItem.NOTE)));
		} catch (GeneralSecurityException gsx) {
		    Log.e(TAG, "Unable to decrypt note " + itemID, gsx);
		} catch (IllegalStateException isx) {
		    Log.e(TAG, "Unable to decrypt note " + itemID, isx);
		}
	    }
	} else {
	    noteHeading = cursor.getString(
		    cursor.getColumnIndex(NoteItem.NOTE));
	}
	if (noteHeading.length() > MAX_HEADER_LENGTH)
	    noteHeading = noteHeading.substring(0, MAX_HEADER_LENGTH);
	if (noteHeading.indexOf('\n') >= 0)
	    noteHeading = noteHeading.substring(0, noteHeading.indexOf('\n'));
	noteText.setText(noteHeading);
	categText.setText(cursor.getString(cursor.getColumnIndex(
		NoteItem.CATEGORY_NAME)));
	categText.setVisibility(prefs.getBoolean(NPREF_SHOW_CATEGORY, false)
		? View.VISIBLE : View.GONE);

	// Set callbacks for the widgets
	Uri itemUri = ContentUris.withAppendedId(listUri, itemID);
	installListeners(view, itemUri);
    }

    /**
     * Install listeners onto a view.
     * This must be done after binding.
     */
    void installListeners(View view, Uri itemUri) {
	// Set a long-click listener to bring up the note editor dialog
	OnNoteClickListener noteClickListener =
	    new OnNoteClickListener(itemUri);
	view.setOnLongClickListener(noteClickListener);
	TextView editDescription = (TextView)
		view.findViewById(R.id.NoteEditDescription);
	editDescription.setOnClickListener(noteClickListener);

	// To do: set a click listener for the category field
    }

    /** Listener for (long-)click events on the To Do item */
    class OnNoteClickListener
    implements View.OnLongClickListener, View.OnClickListener {
	private final Uri itemUri;

	/** Create a new detail click listener for a specific To-Do item */
	public OnNoteClickListener(Uri itemUri) {
	    this.itemUri = itemUri;
	}

	@Override
	public void onClick(View v) {
	    Log.d(TAG, ".onClick(EditText)");
	    Intent intent = new Intent(v.getContext(),
		    NoteEditorActivity.class);
	    intent.setData(itemUri);
	    v.getContext().startActivity(intent);
	}

	@Override
	public boolean onLongClick(View v) {
	    Log.d(TAG, ".onLongClick(EditText)");
	    Intent intent = new Intent(v.getContext(),
		    NoteEditorActivity.class);
	    intent.setData(itemUri);
	    v.getContext().startActivity(intent);
	    return true;
	}
    }
}
