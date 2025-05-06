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

import android.database.CursorIndexOutOfBoundsException;
import android.database.SQLException;

import com.xmission.trevin.android.notes.data.NoteItem;

import java.util.List;

/**
 * Implementation of the {@link NoteCursor} to be used for tests.
 * This should only be used by the {@link MockNoteRepository}
 * to provide a wrapper around an in-memory {@link List}.
 *
 * @author Trevin Beattie
 */
public class MockNoteCursor implements NoteCursor {

    /**
     * The underlying {@link List} from which we obtain
     * {@link NoteItem} data
     */
    private final List<NoteItem> queryRows;

    private boolean isClosed = false;

    private int currentPosition = -1;

    MockNoteCursor(List<NoteItem> noteList) {
        queryRows = noteList;
    }

    @Override
    public void close() {
        isClosed = true;
    }

    /**
     * Determine whether the cursor is open and in a valid position.
     *
     * @throws SQLException if the cursor is closed, is positioned
     * before the first &ldquo;row&rdquo;, or is positioned after
     * the last &rdquo;row&rdquo;.
     */
    private void checkAccess() throws SQLException {
        if (isClosed)
            throw new SQLException("This cursor has been closed");
        if ((currentPosition < 0) || (currentPosition >= queryRows.size()))
            throw new CursorIndexOutOfBoundsException(
                    currentPosition, queryRows.size());
    }

    @Override
    public NoteItem getNote() {
        checkAccess();
        return queryRows.get(currentPosition).clone();
    }

    @Override
    public int getCount() {
        return queryRows.size();
    }

    @Override
    public int getPosition() {
        return currentPosition;
    }

    @Override
    public boolean isAfterLast() {
        return (currentPosition >= queryRows.size());
    }

    @Override
    public boolean isBeforeFirst() {
        return (currentPosition < 0);
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public boolean isFirst() {
        return !queryRows.isEmpty() && (currentPosition == 0);
    }

    @Override
    public boolean isLast() {
        return !queryRows.isEmpty() &&
                (currentPosition == queryRows.size() - 1);
    }

    @Override
    public boolean move(int offset) {
        if ((currentPosition + offset >= 0) &&
                (currentPosition + offset < queryRows.size())) {
            currentPosition += offset;
            return true;
        }
        if (currentPosition + offset >= queryRows.size())
            currentPosition = queryRows.size();
        else
            currentPosition = -1;
        return false;
    }

    @Override
    public boolean moveToFirst() {
        if (queryRows.isEmpty())
            return false;
        currentPosition = 0;
        return true;
    }

    @Override
    public boolean moveToLast() {
        if (queryRows.isEmpty())
            return false;
        currentPosition = queryRows.size() - 1;
        return true;
    }

    @Override
    public boolean moveToNext() {
        if (currentPosition < queryRows.size()) {
            currentPosition++;
            return true;
        }
        return false;
    }

    @Override
    public boolean moveToPosition(int position) {
        if ((position >= -1) && (position <= queryRows.size())) {
            currentPosition = position;
            return true;
        }
        return false;
    }

    @Override
    public boolean moveToPrevious() {
        if (currentPosition >= 0) {
            currentPosition--;
            return true;
        }
        return false;
    }

}
