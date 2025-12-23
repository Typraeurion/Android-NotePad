/*
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
 */
package com.xmission.trevin.android.notes.provider;

import com.xmission.trevin.android.notes.provider.NoteSchema.NoteCategoryColumns;
import com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns;
import com.xmission.trevin.android.notes.provider.NoteSchema.NoteMetadataColumns;

import java.util.HashMap;

import android.content.*;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.*;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

/**
 * Provides access to a database of Note items and categories.
 *
 * @deprecated So long as we don&rsquo;t need to share data between
 * different applications, it should be easier for the application
 * to use a {@link NoteRepository} directly.
 */
public class NoteProvider extends ContentProvider {

    private static final String TAG = "NoteProvider";

    public static final String CATEGORY_TABLE_NAME = "category";
    public static final String METADATA_TABLE_NAME = "misc";
    public static final String NOTE_TABLE_NAME = "notes";

    /** Projection fields which are available in a category query */
    private static HashMap<String, String> categoryProjectionMap;

    /** Projection fields which are available in a metadata query */
    private static HashMap<String, String> metadataProjectionMap;

    /** Projection fields which are available in a note item query */
    private static HashMap<String, String> itemProjectionMap;

    // Provider URI matching codes
    private static final int CATEGORIES = 3;
    private static final int CATEGORY_ID = 4;
    private static final int METADATA = 5;
    private static final int METADATUM_ID = 6;
    private static final int NOTES = 1;
    private static final int NOTE_ID = 2;

    private static final UriMatcher sUriMatcher;

    private NoteDatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
	Log.d(TAG, getClass().getSimpleName() + ".onCreate");
        mOpenHelper = new NoteDatabaseHelper(getContext());
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
            orderBy = NoteCategoryColumns.DEFAULT_SORT_ORDER;
            break;

        case CATEGORY_ID:
            qb.setTables(CATEGORY_TABLE_NAME);
            qb.setProjectionMap(categoryProjectionMap);
            qb.appendWhere(NoteCategoryColumns._ID + " = "
        	    + uri.getPathSegments().get(1));
            orderBy = null;
            break;

        case METADATA:
            qb.setTables(METADATA_TABLE_NAME);
            qb.setProjectionMap(metadataProjectionMap);
            orderBy = NoteMetadataColumns.NAME;
            break;

        case METADATUM_ID:
            qb.setTables(METADATA_TABLE_NAME);
            qb.setProjectionMap(metadataProjectionMap);
            qb.appendWhere(NoteMetadataColumns._ID + " = "
        	    + uri.getPathSegments().get(1));
            orderBy = null;
            break;

        case NOTES:
            qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
                    + " ON (" + NOTE_TABLE_NAME + "." + NoteItemColumns.CATEGORY_ID
                    + " = " + CATEGORY_TABLE_NAME + "." + NoteCategoryColumns._ID + ")");
            qb.setProjectionMap(itemProjectionMap);
            orderBy = NoteItemColumns.DEFAULT_SORT_ORDER;
            break;

        case NOTE_ID:
            qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
                    + " ON (" + NOTE_TABLE_NAME + "." + NoteItemColumns.CATEGORY_ID
                    + " = " + CATEGORY_TABLE_NAME + "." + NoteCategoryColumns._ID + ")");
            qb.setProjectionMap(itemProjectionMap);
            qb.appendWhere(NOTE_TABLE_NAME + "." + NoteItemColumns._ID
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
            return NoteCategoryColumns.CONTENT_TYPE;

        case CATEGORY_ID:
            return NoteCategoryColumns.CONTENT_ITEM_TYPE;

        case METADATA:
            return NoteMetadataColumns.CONTENT_TYPE;

        case METADATUM_ID:
            return NoteMetadataColumns.CONTENT_ITEM_TYPE;

        case NOTES:
            return NoteItemColumns.CONTENT_TYPE;

        case NOTE_ID:
            return NoteItemColumns.CONTENT_ITEM_TYPE;

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
            if (!values.containsKey(NoteCategoryColumns.NAME))
        	throw new NullPointerException(NoteCategoryColumns.NAME);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(CATEGORY_TABLE_NAME, NoteCategoryColumns.NAME, values);
            if (rowId > 0) {
        	Uri categoryUri = ContentUris.withAppendedId(
        		NoteCategoryColumns.CONTENT_URI, rowId);
        	getContext().getContentResolver().notifyChange(categoryUri, null);
        	return categoryUri;
            }
            break;

        case METADATA:

            if (!values.containsKey(NoteMetadataColumns.NAME))
        	throw new NullPointerException(NoteMetadataColumns.NAME);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(METADATA_TABLE_NAME, NoteMetadataColumns.NAME, values);
            if (rowId > 0) {
        	Uri datUri = ContentUris.withAppendedId(
        		NoteMetadataColumns.CONTENT_URI, rowId);
        	getContext().getContentResolver().notifyChange(datUri, null);
        	return datUri;
            }
            break;

        case NOTES:

            long now = System.currentTimeMillis();

            // Make sure that the non-null fields are all set
            if (!values.containsKey(NoteItemColumns.CREATE_TIME))
        	values.put(NoteItemColumns.CREATE_TIME, now);

            if (!values.containsKey(NoteItemColumns.MOD_TIME))
        	values.put(NoteItemColumns.MOD_TIME, now);

            if (!values.containsKey(NoteItemColumns.PRIVATE))
        	values.put(NoteItemColumns.PRIVATE, 0);

            if (!values.containsKey(NoteItemColumns.CATEGORY_ID))
        	values.put(NoteItemColumns.CATEGORY_ID, NoteCategoryColumns.UNFILED);

            db = mOpenHelper.getWritableDatabase();
            rowId = db.insert(NOTE_TABLE_NAME, NoteItemColumns.NOTE, values);
            if (rowId > 0) {
        	Uri noteUri = ContentUris.withAppendedId(NoteItemColumns.CONTENT_URI, rowId);
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
            where = NoteCategoryColumns._ID + " != " + NoteCategoryColumns.UNFILED + (
        	    TextUtils.isEmpty(where) ? "" : (" AND (" + where + ")")); 
            count = db.delete(CATEGORY_TABLE_NAME, where, whereArgs);
            if (count > 0) {
        	// Change the category of all Note items to Unfiled
        	ContentValues categoryUpdate = new ContentValues();
        	categoryUpdate.put(NoteItemColumns.CATEGORY_ID, NoteCategoryColumns.UNFILED);
        	update(NoteItemColumns.CONTENT_URI, categoryUpdate, null, null);
            }
            break;

        case CATEGORY_ID:
            long categoryId = Long.parseLong(uri.getPathSegments().get(1));
            if (categoryId == NoteCategoryColumns.UNFILED)
        	// Don't delete the default category
        	return 0;
            db.beginTransaction();
            count = db.delete(CATEGORY_TABLE_NAME,
        	    NoteCategoryColumns._ID + " = " + categoryId
        	    + (TextUtils.isEmpty(where) ? "" : (" AND (" + where + ")")),
        	    whereArgs);
            if (count > 0) {
        	// Change the category of all Note items
        	// that were in this category to Unfiled
        	ContentValues categoryUpdate = new ContentValues();
        	categoryUpdate.put(NoteItemColumns.CATEGORY_ID, NoteCategoryColumns.UNFILED);
        	update(NoteItemColumns.CONTENT_URI, categoryUpdate,
        		NoteItemColumns.CATEGORY_ID + "=" + categoryId, null);
            }
            db.setTransactionSuccessful();
            db.endTransaction();
            break;

        case METADATA:
            count = db.delete(METADATA_TABLE_NAME, where, whereArgs);
            break;

        case METADATUM_ID:
            long datId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.delete(METADATA_TABLE_NAME, NoteMetadataColumns._ID + " = " + datId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case NOTES:
            count = db.delete(NOTE_TABLE_NAME, where, whereArgs);
            break;

        case NOTE_ID:
            long noteId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.delete(NOTE_TABLE_NAME, NoteItemColumns._ID + " = " + noteId
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
        	    NoteCategoryColumns._ID + " = " + categoryId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case METADATA:
            count = db.update(METADATA_TABLE_NAME, values, where, whereArgs);
            break;

        case METADATUM_ID:
            long datId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.update(METADATA_TABLE_NAME, values,
        	    NoteMetadataColumns._ID + " = " + datId
        	    + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
        	    whereArgs);
            break;

        case NOTES:
            count = db.update(NOTE_TABLE_NAME, values, where, whereArgs);
            break;

        case NOTE_ID:
            long noteId = Long.parseLong(uri.getPathSegments().get(1));
            count = db.update(NOTE_TABLE_NAME, values,
        	    NoteItemColumns._ID + " = " + noteId
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
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "categories", CATEGORIES);
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "categories/#", CATEGORY_ID);
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "misc", METADATA);
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "misc/#", METADATUM_ID);
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "note", NOTES);
        sUriMatcher.addURI(NoteSchema.AUTHORITY, "note/#", NOTE_ID);

        categoryProjectionMap = new HashMap<>();
        categoryProjectionMap.put(NoteCategoryColumns._ID, NoteCategoryColumns._ID);
        categoryProjectionMap.put(NoteCategoryColumns.NAME, NoteCategoryColumns.NAME);
        metadataProjectionMap = new HashMap<>();
        metadataProjectionMap.put(NoteMetadataColumns._ID, NoteMetadataColumns._ID);
        metadataProjectionMap.put(NoteMetadataColumns.NAME, NoteMetadataColumns.NAME);
        metadataProjectionMap.put(NoteMetadataColumns.VALUE, NoteMetadataColumns.VALUE);
        itemProjectionMap = new HashMap<>();
        itemProjectionMap.put(NoteItemColumns._ID,
        	NOTE_TABLE_NAME + "." + NoteItemColumns._ID);
        itemProjectionMap.put(NoteItemColumns.CREATE_TIME, NoteItemColumns.CREATE_TIME);
        itemProjectionMap.put(NoteItemColumns.MOD_TIME, NoteItemColumns.MOD_TIME);
        itemProjectionMap.put(NoteItemColumns.PRIVATE, NoteItemColumns.PRIVATE);
        itemProjectionMap.put(NoteItemColumns.CATEGORY_ID, NoteItemColumns.CATEGORY_ID);
        itemProjectionMap.put(NoteItemColumns.CATEGORY_NAME,
        	CATEGORY_TABLE_NAME + "." + NoteCategoryColumns.NAME
        	+ " AS " + NoteItemColumns.CATEGORY_NAME);
        itemProjectionMap.put(NoteItemColumns.NOTE, NoteItemColumns.NOTE);
    }
}
