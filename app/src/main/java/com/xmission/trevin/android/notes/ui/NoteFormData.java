/*
 * Copyright © 2026 Trevin Beattie
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

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.time.Instant;

/**
 * Container for the current state of UI elements in
 * {@link NoteEditorActivity}.  This is used to preserve the activity
 * state during a configuration change or putting the app in the
 * background.
 *
 * @author Trevin Beattie
 */
public class NoteFormData implements Serializable {

    private static final long serialVersionUID = 6;

    Long noteId;
    String oldNoteText;
    String currentNoteText;
    Instant createTime;
    Instant modTime;
    long categoryId;
    boolean isPrivate;

    @Override
    public int hashCode() {
        int hash = 0;
        if (noteId != null)
            hash += noteId.hashCode();
        hash *= 31;
        if (oldNoteText != null)
            hash += oldNoteText.hashCode();
        hash *= 31;
        if (currentNoteText != null)
            hash += currentNoteText.hashCode();
        hash *= 31;
        if (createTime != null)
            hash += createTime.hashCode();
        hash *= 31;
        if (modTime != null)
            hash += modTime.hashCode();
        hash *= 31;
        hash += Long.hashCode(categoryId);
        hash *= 31;
        hash += Boolean.hashCode(isPrivate);
        return hash;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append('[');
        if (noteId != null)
            sb.append("noteId=").append(noteId).append(", ");
        sb.append("oldNoteText=\"").append(oldNoteText).append("\", ");
        sb.append("currentNoteText=\"").append(currentNoteText).append("\"");
        if (createTime != null)
            sb.append(", createTime=").append(createTime);
        if (modTime != null)
            sb.append(", modTime=").append(modTime);
        sb.append(", categoryId=").append(categoryId);
        sb.append(", isPrivate=").append(isPrivate);
        sb.append(']');
        return sb.toString();
    }

}
