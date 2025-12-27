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
package com.xmission.trevin.android.notes.data;

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns.*;

import android.util.Log;
import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;

/**
 * Data object corresponding to the notes table in the database
 */
// tableName = NoteRepositoryImpl.NOTE_TABLE_NAME
public class NoteItem implements Cloneable, Serializable {

    private static final long serialVersionUID = 8;

    // PrimaryKey
    private Long _id;
    private Long created;
    private Long modified;
    // The actual name of this column is "private", but that's a reserved word
    private Integer privacy;
    private Long categoryId;
    private String categoryName;
    /** The plain text of the note, if available */
    private String note;
    /** The encrypted contents of the note, if encrypted */
    // Ignore for storage
    private byte[] encryptedNote;

    /**
     * Get the database ID of the note.
     * This may be {@code null} for a note which has not yet
     * been stored in the database.
     *
     * @return the row ID
     */
    public Long getId() {
        return _id;
    }

    /**
     * Set the database ID of the note.
     *
     * @param id the row ID
     */
    public void setId(long id) {
        _id = id;
    }

    /**
     * Clear the ID of a note.  This is used during import
     * when a note needs a new ID.
     */
    public void clearId() {
        _id = null;
    }

    /**
     * Get the creation time of the note
     * in milliseconds since the Epoch.
     *
     * @return the note creation time
     */
    public Long getCreateTime() {
        return created;
    }

    /**
     * Set the creation time of the note
     * in milliseconds since the Epoch.
     *
     * @param timestamp the creation time to set
     */
    public void setCreateTime(long timestamp) {
        created = timestamp;
    }

    /**
     * Set the creation time of the note
     * to the current time.
     */
    public void setCreateTimeNow() {
        created = System.currentTimeMillis();
    }

    /**
     * Get the last modification time of the note
     * in milliseconds since the epoch.
     *
     * @return the note creation time
     */
    public Long getModTime() {
        return modified;
    }

    /**
     * Set the last modification time of the note
     * in milliseconds since the epoch.
     *
     * @param timestamp the creation time to set
     */
    public void setModTime(long timestamp) {
        modified = timestamp;
    }

    /**
     * Set the last modification time of the note
     * to the current time.
     */
    public void setModTimeNow() {
        modified = System.currentTimeMillis();
    }

    /**
     * Get the privacy level of the note.
     * Currently defined values are:
     * <dl>
     *     <dt>0</dt><dd>Public</dd>
     *     <dt>1</dt><dd>Private, but not encrypted</dd>
     *     <dt>2</dt><dd>Private, encrypted using a PBKDF2 key</dd>
     * </dl>
     *
     * @return the note&rsquo;s privacy level
     */
    public Integer getPrivate() {
        return privacy;
    }

    /** @return true if the note is private (or encrypted) */
    // Ignore for storage
    public boolean isPrivate() {
        return privacy > 0;
    }

    /** @return true if the note is encrypted */
    // Ignore for storage
    public boolean isEncrypted() {
        return privacy > 1;
    }

    /**
     * Set the privacy level of the note.
     * Currently defined values are:
     * <dl>
     *     <dt>0</dt><dd>Public</dd>
     *     <dt>1</dt><dd>Private, but not encrypted</dd>
     *     <dt>2</dt><dd>Private, encrypted using a PBKDF2 key</dd>
     * </dl>
     *
     * @param level the privacy level
     */
    public void setPrivate(int level) {
        privacy = level;
    }

    /**
     * Get the category ID of the note.
     *
     * @return the category ID
     */
    public Long getCategoryId() {
        return categoryId;
    }

    /**
     * Set the category ID of the note.
     *
     * @param id the category ID
     */
    public void setCategoryId(long id) {
        categoryId = id;
    }

    /**
     * Get the name of the note&rsquo;s category.
     *
     * @return the category name
     */
    public String getCategoryName() {
        return categoryName;
    }

    /**
     * Set the name of the note&rqsuo;s category.
     *
     * @param name the category name
     */
    public void setCategoryName(String name) {
        categoryName = name;
    }

    /**
     * Get the text of the note, if available.
     *
     * @return the note content, or null if the note hasn&rsquo;t
     * been written yet or is encrypted.
     */
    public String getNote() {
        return note;
    }

    /**
     * Set the plain text of the note.  Should only be used if the
     * note is not encrypted or when decrypting it.
     *
     * @param text the note text
     */
    public void setNote(String text) {
        note = text;
    }

    /**
     * Get the encrypted text of the note,
     * possibly for decryption.
     *
     * @return the encrypted note content, or {@code null} if
     * the note is not encrypted.
     */
    // Ignore for storage
    public byte[] getEncryptedNote() {
        return encryptedNote;
    }

    /**
     * Set the encrypted text of the note.
     *
     * @param crypticData the encrypted note content
     */
    // Ignore for storage
    public void setEncryptedNote(byte[] crypticData) {
        encryptedNote = crypticData;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName())
                .append('(');
        if (_id != null)
            sb.append(_ID).append('=').append(_id).append(", ");
        if (created != null)
            sb.append(CREATE_TIME).append('=')
                    .append(new Date(created)).append(", ");
        if (modified != null)
            sb.append(MOD_TIME).append('=')
                    .append(new Date(modified)).append(", ");
        if (privacy != null)
            sb.append(PRIVATE).append('=').append(privacy).append(", ");
        if (categoryId != null)
            sb.append(CATEGORY_ID).append('=')
                    .append(categoryId).append(", ");
        if (categoryName != null)
            sb.append(CATEGORY_NAME).append("=\"")
                            .append(categoryName).append("\", ");
        if (note != null) {
            sb.append(NOTE).append('=');
            if ((privacy != null) && (privacy > 1))
                sb.append("[Private]");
            else if (note.length() <= 80)
                sb.append('"').append(note).append('"');
            else
                sb.append('"').append(note.substring(0, 77))
                        .append('\u2026').append('"');
        } else if (encryptedNote != null) {
            sb.append(NOTE).append("=[Encrypted]");
        } else {
            sb.append(NOTE).append("=null");
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int hash = 7 * 31;
        if (_id != null)
            hash += _id.hashCode();
        hash *= 31;
        if (created != null)
            hash += created.hashCode();
        hash *= 31;
        if (modified != null)
            hash += modified.hashCode();
        hash *= 31;
        if (privacy != null)
            hash += privacy.hashCode();
        hash *= 31;
        if (categoryId != null)
            hash += categoryId.hashCode();
        // Skip the category name; it's not relevant to note equality
        hash *= 31;
        if (note != null)
            hash += note.hashCode();
        // No bump to the hash here -- note and encryptedNote
        // overload the same column in the database.
        if (encryptedNote != null)
            hash += Arrays.hashCode(encryptedNote);
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NoteItem))
            return false;
        NoteItem n2 = (NoteItem) o;
        if ((_id == null) != (n2._id == null))
            return false;
        if ((_id != null) && !_id.equals(n2._id))
            return false;
        if ((created == null) != (n2.created == null))
            return false;
        if ((created != null) && !created.equals(n2.created))
            return false;
        if ((modified == null) != (n2.modified == null))
            return false;
        if ((modified != null) && !modified.equals(n2.modified))
            return false;
        if ((privacy == null) != (n2.privacy == null))
            return false;
        if ((privacy != null) && !privacy.equals(n2.privacy))
            return false;
        if ((note == null) != (n2.note == null))
            return false;
        if ((note != null) && !note.equals(n2.note))
            return false;
        if ((encryptedNote == null) != (n2.encryptedNote == null))
            return false;
        if ((encryptedNote != null) &&
                !Arrays.equals(encryptedNote, n2.encryptedNote))
            return false;
        return true;
    }

    @Override
    @NonNull
    public NoteItem clone() {
        try {
            NoteItem clone = (NoteItem) super.clone();
            if (encryptedNote != null) {
                clone.encryptedNote = new byte[encryptedNote.length];
                System.arraycopy(encryptedNote, 0,
                        clone.encryptedNote, 0, encryptedNote.length);
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            Log.e("NoteCategory", "Clone not supported", e);
            throw new RuntimeException(e);
        }
    }

}
