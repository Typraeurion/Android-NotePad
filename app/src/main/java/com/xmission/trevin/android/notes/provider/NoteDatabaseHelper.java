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

    private static final String TAG = "NoteProvider";

    private static final String DATABASE_NAME = "notes.db";

    /**
     * Resources
     */
    private Resources res;

    NoteDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, NoteProvider.DATABASE_VERSION);
        res = context.getResources();
        Log.d(TAG, "created");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, ".onCreate(" + db + ")");
        db.execSQL("CREATE TABLE " + NoteProvider.METADATA_TABLE_NAME + " ("
                + Note.NoteMetadata._ID + " INTEGER PRIMARY KEY,"
                + Note.NoteMetadata.NAME + " TEXT UNIQUE,"
                + Note.NoteMetadata.VALUE + " BLOB);");

        db.execSQL("CREATE TABLE " + NoteProvider.CATEGORY_TABLE_NAME + " ("
                + Note.NoteCategory._ID + " INTEGER PRIMARY KEY,"
                + Note.NoteCategory.NAME + " TEXT UNIQUE"
                + ");");
        ContentValues values = new ContentValues();
        values.put(Note.NoteCategory._ID, Note.NoteCategory.UNFILED);
        values.put(Note.NoteCategory.NAME,
                res.getString(R.string.Category_Unfiled));
        db.insert(NoteProvider.CATEGORY_TABLE_NAME, null, values);

        db.execSQL("CREATE TABLE " + NoteProvider.NOTE_TABLE_NAME + " ("
                + Note.NoteItem._ID + " INTEGER PRIMARY KEY,"
                + Note.NoteItem.CREATE_TIME + " INTEGER,"
                + Note.NoteItem.MOD_TIME + " INTEGER,"
                + Note.NoteItem.PRIVATE + " INTEGER,"
                + Note.NoteItem.CATEGORY_ID + " INTEGER,"
                + Note.NoteItem.NOTE + " TEXT"
                + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, String.format(".onUpgrade(%s,%d,%d)",
                db.getPath(), oldVersion, newVersion));
        // Not supported at this version
    }

}
