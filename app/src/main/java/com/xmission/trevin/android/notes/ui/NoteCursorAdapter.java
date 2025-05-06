/*
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
 */
package com.xmission.trevin.android.notes.ui;

import java.security.GeneralSecurityException;
import java.util.*;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.StringEncryption;

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
 *
 * @deprecated To be replaced with {@link NoteCursorAdapter2}
 */
public class NoteCursorAdapter extends ResourceCursorAdapter {

    public final static String TAG = "NoteCursorAdapter";

    /** The maximum length of a header line we'll show for a note */
    public static final int MAX_HEADER_LENGTH = 50;

    private final NotePreferences prefs;
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
	prefs = NotePreferences.getInstance(context);
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
	int itemID = cursor.getInt(cursor.getColumnIndex(NoteItemColumns._ID));

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
	int privacy = cursor.getInt(cursor.getColumnIndex(NoteItemColumns.PRIVATE));
	if (privacy > 1) {
	    if (encryptor.hasKey()) {
		try {
		    noteHeading = encryptor.decrypt(cursor.getBlob(
			    cursor.getColumnIndex(NoteItemColumns.NOTE)));
		} catch (GeneralSecurityException gsx) {
		    Log.e(TAG, "Unable to decrypt note " + itemID, gsx);
		} catch (IllegalStateException isx) {
		    Log.e(TAG, "Unable to decrypt note " + itemID, isx);
		}
	    }
	} else {
	    noteHeading = cursor.getString(
		    cursor.getColumnIndex(NoteItemColumns.NOTE));
	}
	if (noteHeading.length() > MAX_HEADER_LENGTH)
	    noteHeading = noteHeading.substring(0, MAX_HEADER_LENGTH);
	if (noteHeading.indexOf('\n') >= 0)
	    noteHeading = noteHeading.substring(0, noteHeading.indexOf('\n'));
	noteText.setText(noteHeading);
	categText.setText(cursor.getString(cursor.getColumnIndex(
		NoteItemColumns.CATEGORY_NAME)));
	categText.setVisibility(prefs.showCategory()
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
        NoteListItemClickListener noteClickListener =
	    new NoteListItemClickListener(itemUri);
	view.setOnLongClickListener(noteClickListener);
	TextView editDescription = (TextView)
		view.findViewById(R.id.NoteEditDescription);
	editDescription.setOnClickListener(noteClickListener);

	// To do: set a click listener for the category field
    }

}
