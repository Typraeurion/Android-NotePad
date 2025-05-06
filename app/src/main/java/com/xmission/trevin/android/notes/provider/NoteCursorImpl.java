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
package com.xmission.trevin.android.notes.provider;

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns.*;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.xmission.trevin.android.notes.data.NoteItem;

/**
 * Run-time implementation of the {@link NoteCursor}.
 * This should only be used by the {@link NoteRepositoryImpl}
 * to provide a wrapper around the {@link Cursor} provided by
 * a {@link SQLiteDatabase}.
 *
 * @author Trevin Beattie
 */
public class NoteCursorImpl implements NoteCursor {

    private static final String TAG = "NoteCursorImpl";

    /**
     * The underlying {@link Cursor} from which
     * we obtain {@link NoteItem} data.
     */
    private final Cursor dbCursor;

    // Indices for our columns are set when we get the underlying cursor.
    // We expect not all queries will use all columns, so these may have
    // values of -1 for columns that are missing.
    private final int idColumn;
    private final int createTimeColumn;
    private final int modTimeColumn;
    private final int privateColumn;
    private final int categoryIdColumn;
    private final int categoryNameColumn;
    private final int noteColumn;

    NoteCursorImpl(Cursor underlyingCursor) {
        dbCursor = underlyingCursor;
        idColumn = dbCursor.getColumnIndex(_ID);
        createTimeColumn = dbCursor.getColumnIndex(CREATE_TIME);
        modTimeColumn = dbCursor.getColumnIndex(MOD_TIME);
        privateColumn = dbCursor.getColumnIndex(PRIVATE);
        categoryIdColumn = dbCursor.getColumnIndex(CATEGORY_ID);
        categoryNameColumn = dbCursor.getColumnIndex(CATEGORY_NAME);
        noteColumn = dbCursor.getColumnIndex(NOTE);

        // Consistency check: in order to read the note column,
        // we also need to know whether the note is encrypted.
        if ((noteColumn >= 0) && (privateColumn < 0)) {
            Log.e(TAG, String.format("Internal error: data cursor"
                    + " includes the %s column but not %s;"
                    + " unable to determine how to read %s.",
                    NOTE, PRIVATE, NOTE));
            throw new SQLException("Query returns notes without privacy level");
        }
    }

    @Override
    public void close() {
        dbCursor.close();
    }

    @Override
    public int getCount() {
        return dbCursor.getCount();
    }

    @Override
    public NoteItem getNote() {
        NoteItem note = new NoteItem();
        if (idColumn >= 0)
            note.setId(dbCursor.getLong(idColumn));
        if (createTimeColumn >= 0)
            note.setCreateTime(dbCursor.getLong(createTimeColumn));
        if (modTimeColumn >= 0)
            note.setModTime(dbCursor.getLong(modTimeColumn));
        if (privateColumn >= 0)
            note.setPrivate(dbCursor.getInt(privateColumn));
        if (categoryIdColumn >= 0)
            note.setCategoryId(dbCursor.getLong(categoryIdColumn));
        if (categoryNameColumn >= 0)
            note.setCategoryName(dbCursor.getString(categoryNameColumn));
        if (noteColumn >= 0) {
            // How we read this column depends on whether the note is encrypted
            if (note.getPrivate() <= 1) {
                // Plain text
                note.setNote(dbCursor.getString(noteColumn));
            } else {
                // Encrypted; stored as a byte array
                note.setEncryptedNote(dbCursor.getBlob(noteColumn));
            }
        }
        return note;
    }

    @Override
    public int getPosition() {
        return dbCursor.getPosition();
    }

    @Override
    public boolean isAfterLast() {
        return dbCursor.isAfterLast();
    }

    @Override
    public boolean isBeforeFirst() {
        return dbCursor.isBeforeFirst();
    }

    @Override
    public boolean isClosed() {
        return dbCursor.isClosed();
    }

    @Override
    public boolean isFirst() {
        return dbCursor.isFirst();
    }

    @Override
    public boolean isLast() {
        return dbCursor.isLast();
    }

    @Override
    public boolean move(int offset) {
        return dbCursor.move(offset);
    }

    @Override
    public boolean moveToFirst() {
        return dbCursor.moveToFirst();
    }

    @Override
    public boolean moveToLast() {
        return dbCursor.moveToLast();
    }

    @Override
    public boolean moveToNext() {
        return dbCursor.moveToNext();
    }

    @Override
    public boolean moveToPosition(int position) {
        return dbCursor.moveToPosition(position);
    }

    @Override
    public boolean moveToPrevious() {
        return dbCursor.moveToPrevious();
    }

}
