/*
 * $Id: NoteProvider.java,v 1.2 2014/04/06 21:27:57 trevin Exp trevin $
 * Copyright © 2011 Trevin Beattie
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
 *
 * $Log: NoteProvider.java,v $
 * Revision 1.2  2014/04/06 21:27:57  trevin
 * Added the copyright notice.
 * Made several class constants package-visible.
 * Fixed a bug in NOT deleting the default category.
 *
 * Revision 1.1  2011/03/20 19:54:30  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import com.xmission.trevin.android.notes.Note.NoteCategory;
import com.xmission.trevin.android.notes.Note.NoteItem;
import com.xmission.trevin.android.notes.Note.NoteMetadata;

import java.util.HashMap;

import android.content.*;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.*;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

/**
 * Provides access to a database of Note items and categories.
 */
public class NoteProvider extends ContentProvider {

    private static final String TAG = "NoteProvider";

    private static final String DATABASE_NAME = "notes.db";
    static final int DATABASE_VERSION = 1;
    static final String CATEGORY_TABLE_NAME = "category";
    private static final String METADATA_TABLE_NAME = "misc";
    static final String NOTE_TABLE_NAME = "notes";

    /** Projection fields which are available in a category query */
    private static HashMap<String, String> categoryProjectionMap;

    /** Projection fields which are available in a metadata query */
    private static HashMap<String, String> metadataProjectionMap;

    /** Projection fields which are available in a note item query */
    private static HashMap<String, String> itemProjectionMap;

    private static final int CATEGORIES = 3;
    private static final int CATEGORY_ID = 4;
    private static final int METADATA = 5;
    private static final int METADATUM_ID = 6;
    private static final int NOTES = 1;
    private static final int NOTE_ID = 2;

    private static final UriMatcher sUriMatcher;

    /**
     * This class helps open, create, and upgrade the database file.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {

	/** Resources */
	private Resources res;

	DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            res = context.getResources();
            Log.d(TAG, getClass().getName() + " created");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, getClass().getName() + ".onCreate(" + db + ")");
            db.execSQL("CREATE TABLE " + METADATA_TABLE_NAME + " ("
        	    + NoteMetadata._ID + " INTEGER PRIMARY KEY,"
        	    + NoteMetadata.NAME + " TEXT UNIQUE,"
        	    + NoteMetadata.VALUE + " BLOB);");

            db.execSQL("CREATE TABLE " + CATEGORY_TABLE_NAME + " ("
                    + NoteCategory._ID + " INTEGER PRIMARY KEY,"
                    + NoteCategory.NAME + " TEXT UNIQUE"
                    + ");");
            ContentValues values = new ContentValues();
            values.put(NoteCategory._ID, NoteCategory.UNFILED);
            values.put(NoteCategory.NAME,
        	    res.getString(R.string.Category_Unfiled));
            db.insert(CATEGORY_TABLE_NAME, null, values);

            db.execSQL("CREATE TABLE " + NOTE_TABLE_NAME + " ("
                    + NoteItem._ID + " INTEGER PRIMARY KEY,"
                    + NoteItem.CREATE_TIME + " INTEGER,"
                    + NoteItem.MOD_TIME + " INTEGER,"
                    + NoteItem.PRIVATE + " INTEGER,"
                    + NoteItem.CATEGORY_ID + " INTEGER,"
                    + NoteItem.NOTE + " TEXT"
                    + ");");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, getClass().getName() + ".onUpgrade("
        	    + db + "," + oldVersion + "," + newVersion + ")");
            // Not supported at this version
	}
    }

    private DatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
	Log.d(TAG, getClass().getSimpleName() + ".onCreate");
        mOpenHelper = new DatabaseHelper(getContext());
	return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection,
	    String[] selectionArgs, String sortOrder) {
	//Log.d(TAG, getClass().getSimpleName() + ".query(" + uri.toString() + ")");
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        // In case no sort order is specified set the default
        String orderBy;
        switch (sUriMatcher.match(uri)) {
        case CATEGORIES:
            qb.setTables(CATEGORY_TABLE_NAME);
            qb.setProjectionMap(categoryProjectionMap);
            orderBy = NoteCategory.DEFAULT_SORT_ORDER;
            break;

        case CATEGORY_ID:
            qb.setTables(CATEGORY_TABLE_NAME);
            qb.setProjectionMap(categoryProjectionMap);
            qb.appendWhere(NoteCategory._ID + " = "
        	    + uri.getPathSegments().get(1));
            orderBy = null;
            break;

        case METADATA:
            qb.setTables(METADATA_TABLE_NAME);
            qb.setProjectionMap(metadataProjectionMap);
            orderBy = NoteMetadata.NAME;
            break;

        case METADATUM_ID:
            qb.setTables(METADATA_TABLE_NAME);
            qb.setProjectionMap(metadataProjectionMap);
            qb.appendWhere(NoteMetadata._ID + " = "
        	    + uri.getPathSegments().get(1));
            orderBy = null;
            break;

        case NOTES:
            qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
        	    + " ON (" + NOTE_TABLE_NAME + "." + NoteItem.CATEGORY_ID
        	    + " = " + CATEGORY_TABLE_NAME + "." + NoteCategory._ID + ")");
            qb.setProjectionMap(itemProjectionMap);
            orderBy = NoteItem.DEFAULT_SORT_ORDER;
            break;

        case NOTE_ID:
            qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
        	    + " ON (" + NOTE_TABLE_NAME + "." + NoteItem.CATEGORY_ID
        	    + " = " + CATEGORY_TABLE_NAME + "." + NoteCategory._ID + ")");
            qb.setProjectionMap(itemProjectionMap);
            qb.appendWhere(NOTE_TABLE_NAME + "." + NoteItem._ID
        	    + " = " + uri.getPathSegments().get(1));
            orderBy = null;
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (!TextUtils.isEmpty(sortOrder))
            orderBy = sortOrder;

        // Get the database and run the query
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);

        // Tell the cursor what uri to watch, so it knows when its source data changes
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public String getType(@NonNull Uri uri) {
	Log.d(TAG, getClass().getSimpleName() + ".getType("
		+ uri.toString() + ")");
        switch (sUriMatcher.match(uri)) {
        case CATEGORIES:
            return NoteCategory.CONTENT_TYPE;

        case CATEGORY_ID:
            return NoteCategory.CONTENT_ITEM_TYPE;

        case METADATA:
            return NoteMetadata.CONTENT_TYPE;

        case METADATUM_ID:
            return NoteMetadata.CONTENT_ITEM_TYPE;

        case NOTES:
            return NoteItem.CONTENT_TYPE;

        case NOTE_ID:
            return NoteItem.CONTENT_ITEM_TYPE;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues initialValues) {
	//Log.d(TAG, getClass().getSimpleName() + ".insert(" + uri.toString()
	//	+ "," + initialValues + ")");
	ContentValues values;
        if (initialValues != null) {
            values = new ContentValues(initialValues);
        } else {
            values = new ContentValues();
        }

        SQLiteDatabase db;
        long rowId;

        // Validate the requested uri
        switch (sUriMatcher.match(uri)) {
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);

        case CATEGORIES:

            // Make sure that the fields are all set
            if (!values.containsKey(NoteCategory.NAME))
        	throw new NullPointerException(NoteCategory.NAME);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(CATEGORY_TABLE_NAME, NoteCategory.NAME, values);
            if (rowId > 0) {
        	Uri categoryUri = ContentUris.withAppendedId(
        		NoteCategory.CONTENT_URI, rowId);
        	getContext().getContentResolver().notifyChange(categoryUri, null);
        	return categoryUri;
            }
            break;

        case METADATA:

            if (!values.containsKey(NoteMetadata.NAME))
        	throw new NullPointerException(NoteMetadata.NAME);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(METADATA_TABLE_NAME, NoteMetadata.NAME, values);
            if (rowId > 0) {
        	Uri datUri = ContentUris.withAppendedId(
        		NoteMetadata.CONTENT_URI, rowId);
        	getContext().getContentResolver().notifyChange(datUri, null);
        	return datUri;
            }
            break;

        case NOTES:

            long now = System.currentTimeMillis();

            // Make sure that the non-null fields are all set
            if (!values.containsKey(NoteItem.CREATE_TIME))
        	values.put(NoteItem.CREATE_TIME, now);

            if (!values.containsKey(NoteItem.MOD_TIME))
        	values.put(NoteItem.MOD_TIME, now);

            if (!values.containsKey(NoteItem.PRIVATE))
        	values.put(NoteItem.PRIVATE, 0);

            if (!values.containsKey(NoteItem.CATEGORY_ID))
        	values.put(NoteItem.CATEGORY_ID, NoteCategory.UNFILED);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(NOTE_TABLE_NAME, NoteItem.NOTE, values);
            if (rowId > 0) {
        	Uri noteUri = ContentUris.withAppendedId(NoteItem.CONTENT_URI, rowId);
        	getContext().getContentResolver().notifyChange(noteUri, null);
        	return noteUri;
            }
            break;
        }

        throw new SQLException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(@NonNull Uri uri, String where, String[] whereArgs) {
	Log.d(TAG, getClass().getSimpleName() + ".delete(" + uri.toString() + ")");
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case CATEGORIES:
            // Make sure we don't delete the default category
            where = NoteCategory._ID + " != " + NoteCategory.UNFILED + (
        	    TextUtils.isEmpty(where) ? "" : (" AND (" + where + ")")); 
            count = db.delete(CATEGORY_TABLE_NAME, where, whereArgs);
            if (count > 0) {
        	// Change the category of all Note items to Unfiled
        	ContentValues categoryUpdate = new ContentValues();
        	categoryUpdate.put(NoteItem.CATEGORY_ID, NoteCategory.UNFILED);
        	update(NoteItem.CONTENT_URI, categoryUpdate, null, null);
            }
            break;

        case CATEGORY_ID:
            long categoryId = Long.parseLong(uri.getPathSegments().get(1));
            if (categoryId == NoteCategory.UNFILED)
        	// Don't delete the default category
        	return 0;
            db.beginTransaction();
            count = db.delete(CATEGORY_TABLE_NAME,
        	    NoteCategory._ID + " = " + categoryId
        	    + (TextUtils.isEmpty(where) ? "" : (" AND (" + where + ")")),
        	    whereArgs);
            if (count > 0) {
        	// Change the category of all Note items
        	// that were in this category to Unfiled
        	ContentValues categoryUpdate = new ContentValues();
        	categoryUpdate.put(NoteItem.CATEGORY_ID, NoteCategory.UNFILED);
        	update(NoteItem.CONTENT_URI, categoryUpdate,
        		NoteItem.CATEGORY_ID + "=" + categoryId, null);
            }
            db.setTransactionSuccessful();
            db.endTransaction();
            break;

        case METADATA:
            count = db.delete(METADATA_TABLE_NAME, where, whereArgs);
            break;

        case METADATUM_ID:
            long datId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.delete(METADATA_TABLE_NAME, NoteMetadata._ID + " = " + datId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case NOTES:
            count = db.delete(NOTE_TABLE_NAME, where, whereArgs);
            break;

        case NOTE_ID:
            long noteId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.delete(NOTE_TABLE_NAME, NoteItem._ID + " = " + noteId
                    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
                    whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String where,
	    String[] whereArgs) {
	Log.d(TAG, getClass().getSimpleName() + ".update(" + uri.toString()
		+ "," + values + ")");
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case CATEGORIES:
            throw new UnsupportedOperationException(
        	    "Cannot modify multiple categories");

        case CATEGORY_ID:
            long categoryId = Long.parseLong(uri.getPathSegments().get(1));
            // To do: prevent duplicate names
            count = db.update(CATEGORY_TABLE_NAME, values,
        	    NoteCategory._ID + " = " + categoryId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case METADATA:
            count = db.update(METADATA_TABLE_NAME, values, where, whereArgs);
            break;

        case METADATUM_ID:
            long datId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.update(METADATA_TABLE_NAME, values,
        	    NoteMetadata._ID + " = " + datId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case NOTES:
            count = db.update(NOTE_TABLE_NAME, values, where, whereArgs);
            break;

        case NOTE_ID:
            long noteId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.update(NOTE_TABLE_NAME, values,
        	    NoteItem._ID + " = " + noteId
                    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
                    whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(Note.AUTHORITY, "categories", CATEGORIES);
        sUriMatcher.addURI(Note.AUTHORITY, "categories/#", CATEGORY_ID);
        sUriMatcher.addURI(Note.AUTHORITY, "misc", METADATA);
        sUriMatcher.addURI(Note.AUTHORITY, "misc/#", METADATUM_ID);
        sUriMatcher.addURI(Note.AUTHORITY, "note", NOTES);
        sUriMatcher.addURI(Note.AUTHORITY, "note/#", NOTE_ID);

        categoryProjectionMap = new HashMap<>();
        categoryProjectionMap.put(NoteCategory._ID, NoteCategory._ID);
        categoryProjectionMap.put(NoteCategory.NAME, NoteCategory.NAME);
        metadataProjectionMap = new HashMap<>();
        metadataProjectionMap.put(NoteMetadata._ID, NoteMetadata._ID);
        metadataProjectionMap.put(NoteMetadata.NAME, NoteMetadata.NAME);
        metadataProjectionMap.put(NoteMetadata.VALUE, NoteMetadata.VALUE);
        itemProjectionMap = new HashMap<>();
        itemProjectionMap.put(NoteItem._ID,
        	NOTE_TABLE_NAME + "." + NoteItem._ID);
        itemProjectionMap.put(NoteItem.CREATE_TIME, NoteItem.CREATE_TIME);
        itemProjectionMap.put(NoteItem.MOD_TIME, NoteItem.MOD_TIME);
        itemProjectionMap.put(NoteItem.PRIVATE, NoteItem.PRIVATE);
        itemProjectionMap.put(NoteItem.CATEGORY_ID, NoteItem.CATEGORY_ID);
        itemProjectionMap.put(NoteItem.CATEGORY_NAME,
        	CATEGORY_TABLE_NAME + "." + NoteCategory.NAME
        	+ " AS " + NoteItem.CATEGORY_NAME);
        itemProjectionMap.put(NoteItem.NOTE, NoteItem.NOTE);
    }
}
