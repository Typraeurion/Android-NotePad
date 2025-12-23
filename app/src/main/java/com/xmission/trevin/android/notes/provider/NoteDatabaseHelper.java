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

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.xmission.trevin.android.notes.R;

/**
 * This class helps open, create, and upgrade the database file.
 */
class NoteDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "NoteDatabaseHelper";

    private static final String DATABASE_NAME = "notes.db";

    /**
     * Resources
     */
    private Resources res;

    NoteDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, NoteRepositoryImpl.DATABASE_VERSION);
        res = context.getResources();
        Log.d(TAG, "created");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, ".onCreate(" + db + ")");
        db.execSQL("CREATE TABLE " + NoteRepositoryImpl.METADATA_TABLE_NAME + " ("
                + NoteSchema.NoteMetadataColumns._ID + " INTEGER PRIMARY KEY,"
                + NoteSchema.NoteMetadataColumns.NAME + " TEXT UNIQUE,"
                + NoteSchema.NoteMetadataColumns.VALUE + " BLOB);");

        db.execSQL("CREATE TABLE " + NoteRepositoryImpl.CATEGORY_TABLE_NAME + " ("
                + NoteSchema.NoteCategoryColumns._ID + " INTEGER PRIMARY KEY,"
                + NoteSchema.NoteCategoryColumns.NAME + " TEXT UNIQUE"
                + ");");
        ContentValues values = new ContentValues();
        values.put(NoteSchema.NoteCategoryColumns._ID, NoteSchema.NoteCategoryColumns.UNFILED);
        values.put(NoteSchema.NoteCategoryColumns.NAME,
                res.getString(R.string.Category_Unfiled));
        db.insert(NoteRepositoryImpl.CATEGORY_TABLE_NAME, null, values);

        db.execSQL("CREATE TABLE " + NoteRepositoryImpl.NOTE_TABLE_NAME + " ("
                + NoteSchema.NoteItemColumns._ID + " INTEGER PRIMARY KEY,"
                + NoteSchema.NoteItemColumns.CREATE_TIME + " INTEGER,"
                + NoteSchema.NoteItemColumns.MOD_TIME + " INTEGER,"
                + NoteSchema.NoteItemColumns.PRIVATE + " INTEGER,"
                + NoteSchema.NoteItemColumns.CATEGORY_ID + " INTEGER,"
                + NoteSchema.NoteItemColumns.NOTE + " TEXT"
                + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, String.format(".onUpgrade(%s,%d,%d)",
                db.getPath(), oldVersion, newVersion));
        // Not supported at this version
    }

}
