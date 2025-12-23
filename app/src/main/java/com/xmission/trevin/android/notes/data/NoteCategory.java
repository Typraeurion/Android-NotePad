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

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteCategoryColumns.*;

import android.util.Log;
import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 * Data object corresponding to the category table in the database
 */
// tableName = NoteRepositoryImpl.CATEGORY_TABLE_NAME
public class NoteCategory implements Cloneable, Serializable {

    private static final long serialVersionUID = 2;

    /** Database ID of the @ldquo;Unfiled&rdquo; category */
    public static final int UNFILED = 0;

    // PrimaryKey
    private Long _id;
    private String name;

    /**
     * Get the database ID of the category.
     * This may be null for a category which has not yet
     * been stored in the database, or
     * {@link NotePreferences#ALL_CATEGORIES} for the
     * built-in pseudo-item representing all categories.
     * The category &ldquo;Unfiled&rdquo; ({@value UNFILED})
     * should be pre-populated in the database.
     *
     * @return the row ID
     */
    public Long getId() {
        return _id;
    }

    /**
     * Set the database ID of the category.
     *
     * @param id the row ID
     */
    public void setId(long id) {
        _id = id;
    }

    /**
     * Get the name of the category.
     *
     * @return the category name
     */
    public String getName() {
        return name;
    }

    /**
     * Set the name of the category.
     *
     * @param name the name of the category
     */
    public void setName(String name) {
        this.name = name;
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
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NoteCategory))
            return false;
        NoteCategory c2 = (NoteCategory) o;
        if ((_id == null) != (c2._id == null))
            return false;
        if ((_id != null) && !_id.equals(c2._id))
            return false;
        if ((name == null) != (c2.name == null))
            return false;
        if ((name != null) && !name.equals(c2.name))
            return false;
        return true;
    }

    @Override
    @NonNull
    public NoteCategory clone() {
        try {
            return (NoteCategory) super.clone();
        } catch (CloneNotSupportedException e) {
            Log.e("NoteCategory", "Clone not supported", e);
            throw new RuntimeException(e);
        }
    }

}
