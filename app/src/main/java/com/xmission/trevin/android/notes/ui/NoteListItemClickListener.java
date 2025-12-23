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

import static com.xmission.trevin.android.notes.ui.NoteEditorActivity.EXTRA_NOTE_ID;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;

/**
 * Listener for (long-)click events on note items in the list activity
 *
 * @author Trevin Beattie
 */
public class NoteListItemClickListener
        implements View.OnLongClickListener, View.OnClickListener {

    public final static String TAG = "NoteListClickListener";

    private final long noteId;

    /** Create a new detail click listener for a specific To-Do item */
    public NoteListItemClickListener(long id) {
        noteId = id;
    }

    @Override
    public void onClick(View v) {
        Log.d(TAG, ".onClick(EditText)");
        Intent intent = new Intent(v.getContext(),
                NoteEditorActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        v.getContext().startActivity(intent);
    }

    @Override
    public boolean onLongClick(View v) {
        Log.d(TAG, ".onLongClick(EditText)");
        Intent intent = new Intent(v.getContext(),
                NoteEditorActivity.class);
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        v.getContext().startActivity(intent);
        return true;
    }

}
