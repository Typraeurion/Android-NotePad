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

import com.xmission.trevin.android.notes.data.NoteItem;

/**
 * An interface which provides random read access to the result set
 * returned by {@link NoteRepository#getNotes(long, boolean, boolean, String)}.
 */
public interface NoteCursor {

    /** Close the cursor and release its resources */
    void close();

    /**
     * @return the number of notes that this cursor&rsquo;s data set contains
     */
    int getCount();

    /** @return the note that this cursor points to */
    NoteItem getNote();

    /** @return the current position if this cursor in the row set */
    int getPosition();

    /**
     * @return {@code true} if this cursor is positioned after the last row
     */
    boolean isAfterLast();

    /**
     * @return {@code true} if this cursor is positioned before the first row
     */
    boolean isBeforeFirst();

    /** @return {@code true} if the cursor is closed */
    boolean isClosed();

    /** @return {@code true} if the cursor is pointing to the first row */
    boolean isFirst();

    /** @return {@code true} if the cursor is pointing to the last row */
    boolean isLast();

    /**
     * Move the cursor by a relative amount from the current position.
     *
     * @param offset the number of rows to move the cursor forward
     * (if positive) or backward (if negative)
     *
     * @return {@code true} if the move succeeded
     */
    boolean move(int offset);

    /**
     * Move the cursor to the first row.
     *
     * @return {@code true} if the move succeeded
     */
    boolean moveToFirst();

    /**
     * Move the cursor to the last row.
     *
     * @return {@code true} if the move succeeded
     */
    boolean moveToLast();

    /**
     * Move the cursor to the next row.
     *
     * @return {@code true} if the move succeeded
     */
    boolean moveToNext();

    /**
     * Move the cursor to an absolute position.  The valid range is
     * {@code -1} &le; {@code position} &le; {@code count}.
     *
     * @param position the position to move to
     * @return {@code true} if the move succeeded
     */
    boolean moveToPosition(int position);

    /**
     * Move the cursor to the previous row.
     *
     * @return {@code true} if the move succeeded
     */
    boolean moveToPrevious();

}
