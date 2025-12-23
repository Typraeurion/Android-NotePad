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

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteCategoryColumns.NAME;
import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteMetadataColumns.*;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Data object corresponding to the metadata table in the database
 */
// tableName = NoteRepositoryImpl.METADATA_TABLE_NAME
public class NoteMetadata implements Cloneable, Serializable {

    private static final long serialVersionUID = 3;

    // PrimaryKey
    private Long _id;
    private String name;
    private byte[] value;

    /**
     * Get the database ID of the metadata.
     * This may be null for metadata which has not yet
     * been stored in the database.
     *
     * @return the row ID
     */
    public Long getId() {
        return _id;
    }

    /**
     * Set the database ID of the metadata.
     *
     * @param id the row ID
     */
    public void setId(long id) {
        _id = id;
    }

    /**
     * Get the name of the metadata.
     *
     * @return the metadata name
     */
    public String getName() {
        return name;
    }

    /**
     * Set the name of the metadata.
     *
     * @param name the name of the metadata
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get the value of the metadata.
     * The value may be anything, so it is stored as raw bytes
     * (a BLOB).
     *
     * @return the metadata value
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * Set the value of the metadata.
     * The value may be anything, so it is stored as raw bytes
     * (a BLOB).
     *
     * @param value the value of the metadata
     */
    public void setValue(byte[] value) {
        this.value = value;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName())
                .append('(');
        if (_id != null)
            sb.append(_ID).append('=').append(_id).append(", ");
        sb.append(NAME).append('=');
        if (name != null)
            sb.append('"').append(name).append('"');
        else
            sb.append("null");
        sb.append(", ").append(VALUE).append('=');
        if (value != null) {
            try {
                // If the value looks like a string, represent it as such.
                String decodedString = new String(value, "UTF-8");
                sb.append('"').append(decodedString).append('"');
            } catch (Exception e) {
                // Otherwise we have to dump the byte array
                // (within a reasonable limit)
                if (value.length <= 40)
                    sb.append(Arrays.toString(value));
                else {
                    byte[] subValue = new byte[40];
                    for (int i = 0; i < 40; i++)
                        subValue[i] = value[i];
                    sb.append(Arrays.toString(value)).append('\u2026');
                }
            }
        } else {
            sb.append("null");
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
        if (name != null)
            hash += name.hashCode();
        hash *= 31;
        if (value != null)
            hash += Arrays.hashCode(value);
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NoteMetadata))
            return false;
        NoteMetadata m2 = (NoteMetadata) o;
        if ((_id == null) != (m2._id == null))
            return false;
        if ((_id != null) && !_id.equals(m2._id))
            return false;
        if ((name == null) != (m2.name == null))
            return false;
        if ((name != null) && !name.equals(m2.name))
            return false;
        if ((value == null) != (m2.value == null))
            return false;
        if ((value != null) && !Arrays.equals(value, m2.value))
            return false;
        return true;
    }

    @Override
    @NonNull
    public NoteMetadata clone() {
        try {
            NoteMetadata clone = (NoteMetadata) super.clone();
            if (value != null) {
                clone.value = new byte[value.length];
                System.arraycopy(value, 0, clone.value, 0, value.length);
            }
            return clone;
        } catch (CloneNotSupportedException e) {
            Log.e("NoteCategory", "Clone not supported", e);
            throw new RuntimeException(e);
        }
    }
}
