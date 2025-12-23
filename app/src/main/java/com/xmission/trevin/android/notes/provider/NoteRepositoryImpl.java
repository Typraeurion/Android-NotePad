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

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import android.support.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;

/**
 * Run-time implementation of the Note Pad repository.
 *
 * @author Trevin Beattie
 */
public class NoteRepositoryImpl implements NoteRepository {

    private static final String TAG = "NoteRepositoryImpl";

    public static final int DATABASE_VERSION = 1;
    public static final String CATEGORY_TABLE_NAME = "category";
    public static final String METADATA_TABLE_NAME = "misc";
    public static final String NOTE_TABLE_NAME = "notes";

    private static final String[] CATEGORY_FIELDS = new String[] {
            NoteCategoryColumns._ID,
            NoteCategoryColumns.NAME
    };

    private static final String[] METADATA_FIELDS = new String[] {
            NoteMetadataColumns._ID,
            NoteMetadataColumns.NAME,
            NoteMetadataColumns.VALUE
    };

    private static final String[] ITEM_FIELDS = new String[] {
            NoteItemColumns._ID,
            NoteItemColumns.CREATE_TIME,
            NoteItemColumns.MOD_TIME,
            NoteItemColumns.PRIVATE,
            NoteItemColumns.CATEGORY_ID,
            NoteItemColumns.CATEGORY_NAME,
            NoteItemColumns.NOTE
    };
    /**
     * Projection fields which are available in a note item query.
     * This must be used in note queries to disambiguate columns
     * that are joined with the category table.
     */
    private static final Map<String, String> ITEM_PROJECTION_MAP;

    static {
        Map<String,String> m = new HashMap<>();
        for (String field : ITEM_FIELDS)
            m.put(field, field);
        // Overrides
        m.put(NoteItemColumns._ID,
        	NOTE_TABLE_NAME + "." + NoteItemColumns._ID);
        m.put(NoteItemColumns.CATEGORY_NAME,
        	CATEGORY_TABLE_NAME + "." + NoteCategoryColumns.NAME
        	+ " AS " + NoteItemColumns.CATEGORY_NAME);
        ITEM_PROJECTION_MAP = Collections.unmodifiableMap(m);
    }

    /** Singleton instance of this repository */
    private static NoteRepositoryImpl instance = null;

    SQLiteDatabase db = null;

    private String unfiledCategoryName = null;

    private final LinkedHashMap<Context,Integer> openContexts =
            new LinkedHashMap<>();

    /** Observers to call when any Note Pad data changes */
    private final ArrayList<DataSetObserver> registeredObservers =
            new ArrayList<>();

    /**
     * Instantiate the Note Pad repository.  This should be a singleton.
     */
    private NoteRepositoryImpl() {}

    /** @return the singleton instance of the Note Pad repository */
    public static NoteRepository getInstance() {
        if (instance == null) {
            instance = new NoteRepositoryImpl();
        }
        return instance;
    }

    /**
     * Check that the database connection is open.  If not,
     * re-establish the connection.
     *
     * @return the database
     *
     * @throws SQLException if we fail to connect to the database
     */
    private synchronized SQLiteDatabase getDb() throws SQLException {
        if ((db != null) && !db.isOpen())
            db = null;
        if (db == null) {
            if (openContexts.isEmpty())
                throw new SQLException("Attempted to use the repository"
                        + " without opening it from a context");
            Context lastContext = null;
            for (Context context : openContexts.keySet())
                lastContext = context;
            NoteDatabaseHelper openHelper =
                    new NoteDatabaseHelper(lastContext);
            try {
                db = openHelper.getWritableDatabase();
            } catch (SQLException e) {
                Log.e(TAG, "Failed to connect to the database", e);
                throw e;
            }
        }
        return db;
    }

    /**
     * Call the registered observers when any Note Pad data changes.
     * The calls <b>must</b> be done on the main UI thread.
     */
    private Runnable observerNotificationRunner = new Runnable() {
        @Override
        public void run() {
            for (DataSetObserver observer : registeredObservers) try {
                observer.onChanged();
            } catch (Exception e) {
                Log.w(TAG, "Caught exception when notifying observer "
                        + observer.getClass().getCanonicalName(), e);
            }
        }
    };

    private  void notifyObservers() {
        for (Context context : openContexts.keySet()) {
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(
                        observerNotificationRunner);
                return;
            }
        }
        new Handler(Looper.getMainLooper()).post(
                observerNotificationRunner);
    }

    @Override
    public void open(@NonNull Context context) {
        Log.d(TAG, ".open");
        if (db == null) {
            Log.d(TAG, "Connecting to the database");
            NoteDatabaseHelper mOpenHelper = new NoteDatabaseHelper(context);
            try {
                db = mOpenHelper.getWritableDatabase();
            } catch (SQLException e) {
                Log.e(TAG, "Failed to connect to the database", e);
                throw e;
            }
        }
        if (unfiledCategoryName == null)
            unfiledCategoryName = context.getString(R.string.Category_Unfiled);
        if (openContexts.containsKey(context)) {
            openContexts.put(context, openContexts.get(context) + 1);
            Log.d(TAG, String.format(
                    "Context has opened the repository %d times",
                    openContexts.get(context)));
        } else {
            openContexts.put(context, 1);
        }
    }

    @Override
    public void release(@NonNull Context context) {
        if (!openContexts.containsKey(context)) {
            Log.e(TAG, ".release called from context"
                    + " which did not open the repository!");
            return;
        }
        Log.d(TAG, ".release");
        int openCount = openContexts.get(context) - 1;
        if (openCount > 0) {
            openContexts.put(context, openCount);
            Log.d(TAG, String.format(
                    "Context has %d remaining connections to the repository",
                    openCount));
        } else {
            openContexts.remove(context);
            if (openContexts.isEmpty() && (db != null)) {
                Log.d(TAG, "The last context has released the repository;"
                        + " closing the database");
                db.close();
                db = null;
            }
        }
    }

    /**
     * Get the index of a column in Cursor results.
     * This also checks that the column exists, which is expected.
     *
     * @param cursor the Cursor from which to get the column
     * @param columnName the name of the column
     *
     * @return the index of the column
     *
     * @throws SQLException if the column does not exist in the Cursor
     */
    private int getColumnIndex(Cursor cursor, String columnName)
        throws SQLException {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0)
            throw new SQLException(String.format(
                    "Missing %s column in the query results", columnName));
        return index;
    }

    @Override
    public int countCategories() {
        Log.d(TAG, ".countCategories");
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM "
                + CATEGORY_TABLE_NAME, null);
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 1;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of categories!", e);
            return 1;
        } finally {
            c.close();
        }
    }

    @Override
    public List<NoteCategory> getCategories() {
        Log.d(TAG, ".getCategories");
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(CATEGORY_TABLE_NAME);
        Cursor c = qb.query(getDb(), CATEGORY_FIELDS, null, null,
                null, null, NoteCategoryColumns.DEFAULT_SORT_ORDER);
        try {
            List<NoteCategory> categoryList = new ArrayList<>(c.getCount());
            int idColumn = getColumnIndex(c, NoteCategoryColumns._ID);
            int nameColumn = getColumnIndex(c, NoteCategoryColumns.NAME);
            while (c.moveToNext()) {
                NoteCategory nCat = new NoteCategory();
                nCat.setId(c.getLong(idColumn));
                nCat.setName(c.getString(nameColumn));
                categoryList.add(nCat);
            }
            return categoryList;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to read the category table!", e);
            NoteCategory unfiled = new NoteCategory();
            unfiled.setId(0);
            unfiled.setName(unfiledCategoryName);
            return Collections.singletonList(unfiled);
        } finally {
            c.close();
        }
    }

    @Override
    public NoteCategory getCategoryById(long categoryId) {
        Log.d(TAG, String.format(".getCategoryById(%d)", categoryId));
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(CATEGORY_TABLE_NAME);
        Cursor c = qb.query(getDb(), CATEGORY_FIELDS,
                NoteCategoryColumns._ID + " = ?",
                new String[] { Long.toString(categoryId) },
                null, null, null, "1");
        try {
            int nameColumn = getColumnIndex(c, NoteCategoryColumns.NAME);
            if (c.moveToFirst()) {
                NoteCategory cat = new NoteCategory();
                cat.setId(categoryId);
                cat.setName(c.getString(nameColumn));
                return cat;
            }
            return null;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to read category #" + categoryId, e);
            return null;
        } finally {
                c.close();
        }
    }

    @Override
    public NoteCategory insertCategory(@NonNull String categoryName)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".insertCategory(\"%s\")", categoryName));
        if (TextUtils.isEmpty(categoryName))
            throw new IllegalArgumentException("Category name cannot by empty");
        ContentValues values = new ContentValues();
        values.put(NoteCategoryColumns.NAME, categoryName);
        try {
            long rowId = getDb().insertOrThrow(
                    CATEGORY_TABLE_NAME, null, values);
            if (rowId < 0) {
                Log.e(TAG, String.format("Failed to add the category \"%s\"",
                        categoryName));
                throw new SQLException("Failed to insert category name");
            }
            if (!getDb().inTransaction())
                notifyObservers();
            NoteCategory newCat = new NoteCategory();
            newCat.setId(rowId);
            newCat.setName(categoryName);
            return newCat;
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to add the category \"%s\"",
                    categoryName), e);
            throw e;
        }
    }

    @Override
    public NoteCategory insertCategory(@NonNull NoteCategory category)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".insertCategory(%s)", category));
        if (TextUtils.isEmpty(category.getName()))
            throw new IllegalArgumentException("Category name cannot by empty");
        if (category.getId() == null)
            return insertCategory(category.getName());
        ContentValues values = new ContentValues();
        values.put(NoteCategoryColumns._ID, category.getId());
        values.put(NoteCategoryColumns.NAME, category.getName());
        try {
            long rowId = getDb().insertOrThrow(
                    CATEGORY_TABLE_NAME, null, values);
            if (rowId < 0) {
                Log.e(TAG, String.format("Failed to add %s", category));
                throw new SQLException("Failed to insert category (with ID)");
            }
            if (!getDb().inTransaction())
                notifyObservers();
            if (rowId != category.getId()) {
                Log.w(TAG, String.format("Category \"%s\" ID was changed from %d to %d",
                        category.getName(), category.getId(), rowId));
                category.setId(rowId);
            }
            return category;
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to add %s", category), e);
            throw e;
        }
    }

    @Override
    public NoteCategory updateCategory(long categoryId, @NonNull String newName)
        throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".updateCategory(%d, \"%s\")",
                categoryId, newName));
        if (TextUtils.isEmpty(newName))
            throw new IllegalArgumentException("Category name cannot be empty");
        if ((categoryId == NoteCategory.UNFILED) &&
                !newName.equals(unfiledCategoryName)) {
            Log.w(TAG, String.format("Unfiled category cannot be changed;"
                    + " using \"%s\" instead", unfiledCategoryName));
            newName = unfiledCategoryName;
        }
        ContentValues values = new ContentValues();
        values.put(NoteCategoryColumns._ID, categoryId);
        values.put(NoteCategoryColumns.NAME, newName);
        try {
            int count = getDb().update(CATEGORY_TABLE_NAME, values,
                    NoteCategoryColumns._ID + " = ?",
                    new String[] { Long.toString(categoryId) });
            if (count > 0) {
                if (!getDb().inTransaction())
                    notifyObservers();
                NoteCategory cat = new NoteCategory();
                cat.setId(categoryId);
                cat.setName(newName);
                return cat;
            } else {
                throw new SQLException("No rows matched category " + categoryId);
            }
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to change category %d to \"%s\"",
                    categoryId, newName));
            throw e;
        }
    }

    @Override
    public synchronized boolean deleteCategory(long categoryId)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".deleteCategory(%d)", categoryId));
        if (categoryId == NoteCategory.UNFILED)
            throw new IllegalArgumentException("Will not delete the Unfiled category");
        String[] whereArgs = new String[] { Long.toString(categoryId) };
        SQLiteDatabase db = getDb();
        boolean inTransaction = db.inTransaction();
        try {
            db.beginTransaction();
            int count = db.delete(CATEGORY_TABLE_NAME,
                    NoteCategoryColumns._ID + " = ?", whereArgs);
            if (count <= 0)
                return false;
            ContentValues update = new ContentValues();
            update.put(NoteItemColumns.CATEGORY_ID, NoteCategory.UNFILED);
            db.update(NOTE_TABLE_NAME, update,
                    NoteItemColumns.CATEGORY_ID + " = ?", whereArgs);
            db.setTransactionSuccessful();
            if (!inTransaction)
                notifyObservers();
            return true;
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to delete category %d"
                    + " or update notes", categoryId), e);
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public synchronized boolean deleteAllCategories() throws SQLException {
        Log.d(TAG, ".deleteAllCategories");
        String[] whereArgs = new String[] {
                Long.toString(NoteCategory.UNFILED)
        };
        SQLiteDatabase db = getDb();
        boolean inTransaction = db.inTransaction();
        try {
            db.beginTransaction();
            int count = db.delete(CATEGORY_TABLE_NAME,
            NoteCategoryColumns._ID + " != ?", whereArgs);
            if (count <= 0)
                return false;
            ContentValues update = new ContentValues();
            update.put(NoteItemColumns.CATEGORY_ID, NoteCategory.UNFILED);
            db.update(NOTE_TABLE_NAME, update,
                    NoteItemColumns.CATEGORY_ID + " != ?", whereArgs);
            db.setTransactionSuccessful();
            if (!inTransaction)
                notifyObservers();
            return true;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to delete all categories or update notes", e);
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public int countMetadata() {
        Log.d(TAG, ".countMetadata");
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM "
                + METADATA_TABLE_NAME, null);
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 1;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of metadata!", e);
            return 1;
        } finally {
            c.close();
        }
    }

    @Override
    public List<NoteMetadata> getMetadata() {
        Log.d(TAG, ".getMetadata");
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(METADATA_TABLE_NAME);
        Cursor c = qb.query(getDb(), METADATA_FIELDS, null, null,
                null, null, NoteMetadataColumns.NAME);
        try {
            List<NoteMetadata> metadataList = new ArrayList<>(c.getCount());
            int idColumn = getColumnIndex(c, NoteMetadataColumns._ID);
            int nameColumn = getColumnIndex(c, NoteMetadataColumns.NAME);
            int valueColumn = getColumnIndex(c, NoteMetadataColumns.VALUE);
            while (c.moveToNext()) {
                NoteMetadata nMeta = new NoteMetadata();
                nMeta.setId(c.getLong(idColumn));
                nMeta.setName(c.getString(nameColumn));
                nMeta.setValue(c.getBlob(valueColumn));
                metadataList.add(nMeta);
            }
            return metadataList;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to read the metadata table!", e);
            return Collections.emptyList();
        } finally {
            c.close();
        }
    }

    @Override
    public NoteMetadata getMetadataByName(@NonNull String key) {
        Log.d(TAG, String.format(".getMetadataByName(\"%s\")", key));
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(METADATA_TABLE_NAME);
        Cursor c = qb.query(getDb(), METADATA_FIELDS,
                NoteMetadataColumns.NAME + " = ?",
                new String[] { key }, null, null, null, "1");
        try {
            int idColumn = getColumnIndex(c, NoteMetadataColumns._ID);
            int nameColumn = getColumnIndex(c, NoteMetadataColumns.NAME);
            int valueColumn = getColumnIndex(c, NoteMetadataColumns.VALUE);
            if (c.moveToFirst()) {
                NoteMetadata metadata = new NoteMetadata();
                metadata.setId(c.getLong(idColumn));
                metadata.setName(c.getString(nameColumn));
                metadata.setValue(c.getBlob(valueColumn));
                return metadata;
            }
            return null;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to look up metadata " + key, e);
            return null;
        } finally {
            c.close();
        }
    }

    @Override
    public NoteMetadata getMetadataById(long id) {
        Log.d(TAG, String.format(".getMetadataById(%d)", id));
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(METADATA_TABLE_NAME);
        Cursor c = qb.query(getDb(), METADATA_FIELDS,
                NoteMetadataColumns._ID + " = ?",
                new String[] { Long.toString(id) },
                null, null, null, "1");
        try {
            int idColumn = getColumnIndex(c, NoteMetadataColumns._ID);
            int nameColumn = getColumnIndex(c, NoteMetadataColumns.NAME);
            int valueColumn = getColumnIndex(c, NoteMetadataColumns.VALUE);
            if (c.moveToFirst()) {
                NoteMetadata metadata = new NoteMetadata();
                metadata.setId(c.getLong(idColumn));
                metadata.setName(c.getString(nameColumn));
                metadata.setValue(c.getBlob(valueColumn));
                return metadata;
            }
            return null;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to look up metadata #" + id, e);
            return null;
        } finally {
            c.close();
        }
    }

    @Override
    public synchronized NoteMetadata upsertMetadata(
            @NonNull String name, @NonNull byte[] value)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".upsertMetadata(\"%s\", (%d bytes))",
                name, value.length));
        if (TextUtils.isEmpty(name))
            throw new IllegalArgumentException("Metadata name cannot be empty");
        ContentValues upsertValues = new ContentValues();
        upsertValues.put(NoteMetadataColumns.NAME, name);
        upsertValues.put(NoteMetadataColumns.VALUE, value);
        SQLiteDatabase db = getDb();
        boolean inTransaction = db.inTransaction();
        db.beginTransaction();
        try {
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables(METADATA_TABLE_NAME);
            Cursor c = qb.query(getDb(), METADATA_FIELDS,
                    NoteMetadataColumns.NAME + " = ?",
                    new String[] { name }, null, null, null, "1");
            int idColumn = getColumnIndex(c, NoteMetadataColumns._ID);
            long rowId;
            if (c.moveToFirst()) {
                rowId = c.getLong(idColumn);
                upsertValues.put(NoteMetadataColumns._ID, rowId);
                int count =  db.update(METADATA_TABLE_NAME, upsertValues,
                    NoteMetadataColumns._ID + " = ?",
                        new String[] { Long.toString(rowId) });
                if (count < 1)
                    throw new SQLException(
                            "Existing metadata was not updated");
            } else {
                rowId = getDb().insert(METADATA_TABLE_NAME,
                        null, upsertValues);
            }
            if (!inTransaction)
                notifyObservers();
            NoteMetadata metadata = new NoteMetadata();
            metadata.setName(name);
            metadata.setValue(value);
            metadata.setId(rowId);
            db.setTransactionSuccessful();
            return metadata;
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to set metadata \"%s\"",
                    name), e);
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public boolean deleteMetadata(@NonNull String name)
        throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".deleteMetadata(\"%s\")", name));
        try {
            int count = getDb().delete(METADATA_TABLE_NAME,
                    NoteMetadataColumns.NAME + " = ?",
                    new String[] { name });
            if ((count > 0) && !getDb().inTransaction())
                notifyObservers();
            return (count > 0);
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to delete metadata \"%s\"",
                    name), e);
            throw e;
        }
    }

    @Override
    public boolean deleteMetadataById(long id)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".deleteMetadataById(%d)", id));
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            int count = db.delete(METADATA_TABLE_NAME,
                    NoteMetadataColumns._ID + " = ?",
                    new String[] { Long.toString(id) });
            if ((count > 0) && !inTransaction)
                notifyObservers();
            return (count > 0);
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to delete metadata #%d",
                    id), e);
            throw e;
        }
    }

    @Override
    public boolean deleteAllMetadata() throws SQLException {
        Log.d(TAG, ".deleteAllMetadata");
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            int count = db.delete(METADATA_TABLE_NAME, null, null);
            if ((count > 0) && !inTransaction)
                notifyObservers();
            return (count > 0);
        } catch (SQLException e) {
            Log.e(TAG, "Failed to delete all metadata", e);
            throw e;
        }
    }

    @Override
    public int countNotes() {
        Log.d(TAG, ".countNotes");
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM "
                + NOTE_TABLE_NAME, null);
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 0;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of notes!", e);
            return 0;
        } finally {
            c.close();
        }
    }

    @Override
    public int countNotesInCategory(long categoryId) {
        Log.d(TAG, String.format(".countNotesInCategory(%d)", categoryId));
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM " + NOTE_TABLE_NAME
                + " WHERE " + NoteItemColumns.CATEGORY_ID + " = ?",
                new String[] { Long.toString(categoryId) });
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 0;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of notes!", e);
            return 0;
        } finally {
            c.close();
        }
    }

    @Override
    public int countPrivateNotes() {
        Log.d(TAG, ".countPrivateNotes()");
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM "
                + NOTE_TABLE_NAME + " WHERE "
                + NoteItemColumns.PRIVATE + " >= ?",
                new String[] { "1" });
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 0;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of private notes!", e);
            return 0;
        } finally {
            c.close();
        }
    }

    @Override
    public int countEncryptedNotes() {
        Log.d(TAG, ".countEncryptedNotes()");
        Cursor c = getDb().rawQuery("SELECT COUNT(1) FROM "
                + NOTE_TABLE_NAME + " WHERE "
                + NoteItemColumns.PRIVATE + " > ?",
                new String[] { "1" });
        try {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            Log.e(TAG, "Nothing returned from count query!");
            return 0;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to count the number of encrypted notes!", e);
            return 0;
        } finally {
            c.close();
        }
    }

    @Override
    public NoteCursor getNotes(long categoryId,
                               boolean includePrivate,
                               boolean includeEncrypted,
                               @NonNull String sortOrder) {
        Log.d(TAG, String.format(".getNotes(%d,%s,%s)",
                categoryId, includePrivate, includeEncrypted));
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
                + " ON (" + NOTE_TABLE_NAME + "." + NoteItemColumns.CATEGORY_ID
                + " = " + CATEGORY_TABLE_NAME + "." + NoteCategoryColumns._ID + ")");
        qb.setProjectionMap(ITEM_PROJECTION_MAP);
        List<String> selectors = new ArrayList<>(2);
        List<String> selectorArgs = new ArrayList<>(2);
        if (categoryId > NotePreferences.ALL_CATEGORIES) {
            selectors.add(NoteItemColumns.CATEGORY_ID + " = ?");
            selectorArgs.add(Long.toString(categoryId));
        }
        if (!includePrivate) {
            selectors.add(NoteItemColumns.PRIVATE + " <= ?");
            selectorArgs.add("0");
        } else if (!includeEncrypted) {
            selectors.add(NoteItemColumns.PRIVATE + " <= ?");
            selectorArgs.add("1");
        }
        String selection = null;
        if (!selectors.isEmpty())
            selection = TextUtils.join(" AND ", selectors);
        String[] selectionArgs = selectorArgs.isEmpty() ? null :
                selectorArgs.toArray(new String[selectorArgs.size()]);
        Cursor c = qb.query(getDb(), ITEM_FIELDS, selection, selectionArgs,
                null, null, sortOrder);
        return new NoteCursorImpl(c);
    }

    @Override
    public long[] getPrivateNoteIds() {
        Log.d(TAG, ".getPrivateNoteIds()");
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(NOTE_TABLE_NAME);
        String selection = NoteItemColumns.PRIVATE + " >= ?";
        String[] selectionArgs = new String[] { "1" };
        Cursor c = qb.query(getDb(), new String[] { NoteItemColumns._ID },
                selection, selectionArgs,
                null, null, NoteItemColumns._ID);
        try {
            long[] ids = new long[c.getCount()];
            for (int i = 0; i < ids.length; i++) {
                c.moveToNext();
                ids[i] = c.getLong(0);
            }
            return ids;
        }
        finally {
            c.close();
        }
    }

    @Override
    public NoteItem getNoteById(long noteId) {
        Log.d(TAG, String.format(".getNoteById(%d)", noteId));
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(NOTE_TABLE_NAME + " JOIN " + CATEGORY_TABLE_NAME
        + " ON (" + NOTE_TABLE_NAME + "." + NoteItemColumns.CATEGORY_ID
        + " = " + CATEGORY_TABLE_NAME + "." +  NoteCategoryColumns._ID + ")");
        qb.setProjectionMap(ITEM_PROJECTION_MAP);
        Cursor c = qb.query(getDb(), ITEM_FIELDS,
                NOTE_TABLE_NAME + "." + NoteItemColumns._ID + " = ?",
                new String[] { Long.toString(noteId) },
                null, null, null, "1");
        try {
            NoteCursor nc = new NoteCursorImpl(c);
            if (nc.moveToFirst()) {
                return nc.getNote();
            }
            return null;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to read note #" + noteId, e);
            return null;
        } finally {
            c.close();
        }
    }

    /**
     * Set up ContentValues based on the fields of a NoteItem.
     * This is used when inserting or updating a note.
     *
     * @param note the NoteItem to be inserted or updated
     *
     * @return the ContentValues
     *
     * @throws IllegalArgumentException if the {@code private} field
     * is not set or if either the {@code note} field is empty
     * (if {@code private} &le; 1) or {@code encryptedNote} is empty
     * (if {@code private} > 1)
     */
    private ContentValues noteToContentValues(NoteItem note) {
        if (note.getPrivate() == null)
            throw new IllegalArgumentException("Privacy level must be set");
        if (note.getPrivate() <= 1) {
            if (TextUtils.isEmpty(note.getNote()))
                throw new IllegalArgumentException("Note cannot be empty");
        } else {
            if ((note.getEncryptedNote() == null) ||
                    (note.getEncryptedNote().length == 0))
                throw new IllegalArgumentException("Note must be encrypted");
        }
        if (note.getCategoryId() == null)
            note.setCategoryId(NoteCategory.UNFILED);
        if (note.getCreateTime() == null)
            note.setCreateTimeNow();
        if (note.getModTime() == null)
            note.setModTimeNow();
        ContentValues values = new ContentValues();
        values.put(NoteItemColumns.CREATE_TIME, note.getCreateTime());
        values.put(NoteItemColumns.MOD_TIME, note.getModTime());
        values.put(NoteItemColumns.PRIVATE, note.getPrivate());
        values.put(NoteItemColumns.CATEGORY_ID, note.getCategoryId());
        if (note.getPrivate() <= 1)
            values.put(NoteItemColumns.NOTE, note.getNote());
        else
            values.put(NoteItemColumns.NOTE, note.getEncryptedNote());
        return values;
    }

    @Override
    public NoteItem insertNote(@NonNull NoteItem note)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".insertNote(%s)", note));
        ContentValues values = noteToContentValues(note);
        // Allow setting the ID for inserts, used when importing data.
        if (note.getId() != null)
            values.put(NoteItemColumns._ID, note.getId());
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            long rowId = db.insert(NOTE_TABLE_NAME, null, values);
            if (!inTransaction)
                notifyObservers();
            note.setId(rowId);
            return note;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to insert " + note, e);
            throw e;
        }
    }

    @Override
    public NoteItem updateNote(@NonNull NoteItem note)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".updateNote(%s)", note));
        if (note.getId() == null)
            throw new IllegalArgumentException("Missing note ID");
        ContentValues values = noteToContentValues(note);
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            int count = db.update(NOTE_TABLE_NAME, values,
                    NoteCategoryColumns._ID + " = ?",
                    new String[] { Long.toString(note.getId()) });
            if (count <= 0)
                throw new SQLException("No rows matched note " + note.getId());
            if (!inTransaction)
                notifyObservers();
            return note;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to update " + note, e);
            throw e;
        }
    }

    @Override
    public boolean deleteNote(long noteId) throws SQLException {
        Log.d(TAG, String.format(".deleteNote(%d)", noteId));
        String[] whereArgs = new String[] { Long.toString(noteId) };
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            int count = db.delete(NOTE_TABLE_NAME,
                    NoteItemColumns._ID + " = ?", whereArgs);
            if ((count > 0) && !inTransaction)
                notifyObservers();
            return (count > 0);
        } catch (SQLException e) {
            Log.e(TAG, String.format("Failed to delete note %d", noteId), e);
            throw e;
        }
    }

    @Override
    public boolean deleteAllNotes() throws SQLException {
        Log.d(TAG, ".deleteAllNotes");
        try {
            SQLiteDatabase db = getDb();
            boolean inTransaction = db.inTransaction();
            int count = db.delete(NOTE_TABLE_NAME, null, null);
            if ((count > 0) && !inTransaction)
                notifyObservers();
            return (count > 0);
        } catch (SQLException e) {
            Log.e(TAG, "Failed to delete all notes", e);
            throw e;
        }
    }

    @Override
    public synchronized void runInTransaction(@NonNull Runnable callback) {
        Log.d(TAG, String.format(".runInTransaction(%s)",
                callback.getClass().getCanonicalName()));
        SQLiteDatabase db = getDb();
        boolean nestedTransaction = db.inTransaction();
        db.beginTransaction();
        try {
            callback.run();
            db.setTransactionSuccessful();
            if (!nestedTransaction)
                notifyObservers();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void registerDataSetObserver(@NonNull DataSetObserver observer) {
        registeredObservers.add(observer);
    }

    @Override
    public void unregisterDataSetObserver(@NonNull DataSetObserver observer) {
        registeredObservers.remove(observer);
    }

}
