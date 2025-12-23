/*
 * Copyright © 2025 Trevin Beattie
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

import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import android.support.annotation.Nullable;

/**
 * An adapter to map columns from a Note item cursor to respective
 * widgets and views in the list_item layout.
 *
 * @author Trevin Beattie
 */
public class NoteCursorAdapter extends BaseAdapter {

    public final static String TAG = "NoteCursorAdapter";

    /** The maximum length of a header line we'll show for a note */
    public static final int MAX_HEADER_LENGTH = 50;

    private final Context context;

    /** The cursor providing our note pad data */
    private NoteCursor cursor;

    private final LayoutInflater inflater;

    private final NotePreferences prefs;
    private final Uri listUri;

    /** Encryption in case we're showing private records */
    private final StringEncryption encryptor;

    /**
     * Constructor
     *
     * @param context the Android Context in which this adapter is running
     * @param cursor the cursor that provides the notes for this adapter.
     *               May be {@code null} if the data will be loaded by a
     *               LoaderManager, in which case it must be set by calling
     *               {@link #swapCursor}.
     * @param uri the base URI of notes, used to construct item URI&rsquo;s
     *            for note items in the list.
     * @param encryption a {@link StringEncryption} object used to decrypt
     *                   encrypted notes.  This may be uninitialized (i.e.
     *                   no password set) in which case views for encrypted
     *                   notes will just display &ldquo;[Locked]&rdquo;.
     */
    public NoteCursorAdapter(Context context, @Nullable NoteCursor cursor,
                             Uri uri, StringEncryption encryption) {
        this.context = context;
        this.cursor = cursor;
        prefs = NotePreferences.getInstance(context);
        inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        listUri = uri;
        encryptor = encryption;
    }

    /**
     * Close any existing NoteCursor used by this adapter and replace it
     * with the given NoteCursor.
     *
     * @param newCursor the new NoteCursor to use.  (May be {@code null}.)
     */
    public void swapCursor(@Nullable NoteCursor newCursor) {
        Log.d(TAG, String.format(".swapCursor(%s)", newCursor));
        if (cursor != null)
            cursor.close();
        cursor = newCursor;
        notifyDataSetChanged();
    }

    /**
     * Get the number of items in the data set managed by this adapter
     *
     * @return the number of items in the data set
     */
    @Override
    public int getCount() {
        Log.d(TAG, ".getCount()");
        if (cursor == null) {
            Log.w(TAG, ".getCount: The cursor has not been set!");
            return 0;
        }
        return cursor.getCount();
    }

    /**
     * Get the note item associated with the specified position
     * in the data set.
     *
     * @param position Position of the note whose data we want
     *                 within the adapter&rsquo;s data set
     *
     * @return The note at the specified position
     */
    @Override
    public NoteItem getItem(int position) {
        Log.d(TAG, String.format(".getItem(%d)", position));
        if (cursor == null) {
            Log.w(TAG, ".getItem: The cursor has not been set!");
            return null;
        }
        cursor.moveToPosition(position);
        return cursor.getNote();
    }

    /**
     * Get the row ID associated with the specified position in the list.
     *
     * @param position The position of the item within the adapter&rsquo;s
     *                 data set whose row ID we want.
     *
     * @return the ID of the item at the specified position
     */
    @Override
    public long getItemId(int position) {
        Log.d(TAG, String.format(".getItemId(%d)", position));
        if (cursor == null) {
            Log.w(TAG, ".getItemId: The cursor has not been set!");
            return -1;
        }
        cursor.moveToPosition(position);
        return cursor.getNote().getId();
    }

    /**
     * Get a view that displays the note data at the specified position
     * in the data set.  The parent view will apply the default layout
     * parameters.
     *
     * @param position The position of the item within the adapter&rsquo;s
     *                 data set.
     * @param convertView The old view to use, if possible.  If it is not
     *                    possible to convert this view to display the
     *                    correct data, this method creates a new view.
     * @param parent The parent that this view will eventually be attached to.
     *
     * @return a {@link View} corresponding to the note
     * at the specified position.
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Log.d(TAG, String.format(".getView(%d, %s, %s)",
                position, (convertView == null) ? null
                        : convertView.getClass().getSimpleName(),
                parent.getClass().getSimpleName()));

        if (cursor == null) {
            Log.e(TAG, "The cursor has not been set!");
            return null;
        }

        NoteItem note = getItem(position);
        View noteView = convertView;
        if (noteView == null) {
            Log.d(TAG, "Creating a new list item view");
            noteView = inflater.inflate(R.layout.list_item,
                    parent, false);
        }

        // These are the widgets that need customizing per item
        TextView noteText = (TextView)
                noteView.findViewById(R.id.NoteEditDescription);
        TextView categText = (TextView)
                noteView.findViewById(R.id.NoteTextCateg);

        String noteHeading = context.getString(R.string.PasswordProtected);
        if (note.getPrivate() > 1) {
            if (encryptor.hasKey()) {
                try {
                    noteHeading = encryptor.decrypt(note.getEncryptedNote());
                } catch (Exception gsx) {
                    Log.e(TAG, "Unable to decrypt note " + note.getId(), gsx);
                }
            }
        } else {
            noteHeading = note.getNote();
        }
        if (noteHeading.length() > MAX_HEADER_LENGTH)
            noteHeading = noteHeading.substring(0, MAX_HEADER_LENGTH);
        if (noteHeading.indexOf('\n') >= 0)
            noteHeading = noteHeading.substring(0, noteHeading.indexOf('\n'));
        noteText.setText(noteHeading);
        categText.setText(note.getCategoryName());
        categText.setVisibility(prefs.showCategory()
                ? View.VISIBLE : View.GONE);

        // Set callbacks for the widgets
        installListeners(noteView, note.getId());

        return noteView;

    }

    /**
     * Install onClick and onLongClick listeners onto a view.
     *
     * @param view the view onto which to install the listeners
     * @param noteId the ID of the note that the view represents
     */
    void installListeners(View view, long noteId) {
	// Set a long-click listener to bring up the note editor dialog
        NoteListItemClickListener noteClickListener =
	    new NoteListItemClickListener(noteId);
	view.setOnLongClickListener(noteClickListener);
	TextView editDescription = (TextView)
		view.findViewById(R.id.NoteEditDescription);
	editDescription.setOnClickListener(noteClickListener);
    }

}
