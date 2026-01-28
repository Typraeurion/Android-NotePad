/*
 * Copyright © 2011–2025 Trevin Beattie
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

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * Field definitions for the Note list database
 */
public class NoteSchema {
    /** The content provider part of the URI for locating Note records */
    public static final String AUTHORITY = "com.xmission.trevin.android.notes.provider.NoteRepository";

    // This class cannot be instantiated
    private NoteSchema() {}

    /**
     * Additional data that doesn't fit into a relational schema
     * but needs to be stored with the database
     */
    public static final class NoteMetadataColumns implements BaseColumns {
        // This class cannot be instantiated
        private NoteMetadataColumns() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/misc");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of metadata.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.xmission.trevin.note.misc";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single datum.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.xmission.trevin.note.misc";

        /**
         * The name of the datum
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The value of the datum
         * <P>Type: BLOB</P>
         */
        public static final String VALUE = "value";

    }

    /**
     * Categories table
     */
    public static final class NoteCategoryColumns implements BaseColumns {
        // This class cannot be instantiated
        private NoteCategoryColumns() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/categories");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of Note categories.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.xmission.trevin.note.category";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single category.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.xmission.trevin.note.category";

        /**
         * The name of the category
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = NAME;

        /** The ID to use for unfiled items */
        public static final long UNFILED = 0;
    }

    /**
     * Note table
     */
    public static final class NoteItemColumns implements BaseColumns {
        // This class cannot be instantiated
        private NoteItemColumns() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/note");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of Note items.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.xmission.trevin.note";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single Note item.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.xmission.trevin.note";

        /**
         * The date this Note item was created
         * <P>Type: INTEGER (long from System.curentTimeMillis())</P>
         */
        public static final String CREATE_TIME = "created";

        /**
         * The date this Note item was last modified
         * <P>Type: INTEGER (long from System.curentTimeMillis())</P>
         */
        public static final String MOD_TIME = "modified";

        /**
         * Whether this item is considered private.
         * 0 = public, 1 = private but unencrypted,
         * 2 = encrypted with password key.
         * <P>Type: INTEGER</P>
         */
        public static final String PRIVATE = "private";

        /**
         * The ID of the item's category
         * <P>Type: INTEGER</P>
         */
        public static final String CATEGORY_ID = "category_id";

        /**
         * The name of the item's category
         * <P>Type: TEXT</P>
         */
        public static final String CATEGORY_NAME = "category_name";

        /**
         * The note content
         * <P>Type: TEXT</P>
         */
        public static final String NOTE = "note";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = MOD_TIME + " desc";

        /**
         * Other pre-defined sort orders for this table.
         * The order must match the PrefSortByList string array resource.
         */
        public static final String[] USER_SORT_ORDERS = {
            "lower(" + NOTE + "), " + MOD_TIME + " desc",
            MOD_TIME + " desc",
        };
    }
}
