package com.xmission.trevin.android.notes;

import static com.xmission.trevin.android.notes.Note.NoteCategory.DEFAULT_SORT_ORDER;
import static com.xmission.trevin.android.notes.Note.NoteItem.USER_SORT_ORDERS;
import static com.xmission.trevin.android.notes.NoteListActivity.ITEM_PROJECTION;
import static com.xmission.trevin.android.notes.NoteListActivity.NPREF_SORT_ORDER;

import android.annotation.TargetApi;
import android.app.LoaderManager;
import android.content.CursorLoader;
import android.content.Loader;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * As of Honeycomb (API 11), the application needs to re-initialize
 * the item cursor whenever the activity is restarted.
 * This class provides the callbacks for the item loader manager.
 * Used when NoteListActivity is created.
 *
 * @deprecated as of Pie (API 28)
 */
@TargetApi(11)
class ItemLoaderCallbacks
        implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final String TAG = "ItemLoaderCallbacks";

    private final NoteListActivity listActivity;
    private final SharedPreferences sharedPrefs;

    // Used to map Note entries from the database to views
    private final NoteCursorAdapter itemAdapter;

    /** The URI by which we were started for the To-Do items */
    private final Uri noteUri;

    ItemLoaderCallbacks(NoteListActivity activity,
                        SharedPreferences prefs,
                        NoteCursorAdapter adapter,
                        Uri uri) {
        listActivity = activity;
        sharedPrefs = prefs;
        itemAdapter = adapter;
        noteUri = uri;
    }

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Log.d(TAG, ".onCreateLoader");
        CursorLoader loader = new CursorLoader(listActivity,
                // The selection and sort order parameters
                // are overridden in loadInBackground
                noteUri, ITEM_PROJECTION, null, null, DEFAULT_SORT_ORDER) {

            //private Cursor myCursor = null;

            @Override
            public Cursor loadInBackground() {
                Log.d(TAG, ".CursorLoader.loadInBackground");
                /*
                if (myCursor != null)
                    myCursor.close();
                */
                int selectedSortOrder = sharedPrefs.getInt(NPREF_SORT_ORDER, 0);
                if ((selectedSortOrder < 0) ||
                        (selectedSortOrder >= USER_SORT_ORDERS.length)) {
                    sharedPrefs.edit().putInt(NPREF_SORT_ORDER, 0).apply();
                    selectedSortOrder = 0;
                }
                setSelection(listActivity.generateWhereClause());
                setSortOrder(USER_SORT_ORDERS[selectedSortOrder]);
                /*
                myCursor = listActivity.getContentResolver().query(noteUri,
                        ITEM_PROJECTION, listActivity.generateWhereClause(), null,
                        USER_SORT_ORDERS[selectedSortOrder]);
                return myCursor;
                */
                return super.loadInBackground();
            }

            @Override
            public void cancelLoadInBackground() {
                Log.d(TAG, ".CursorLoader.cancelLoadInBackground");
                super.cancelLoadInBackground();
            }

            @Override
            public void deliverResult(Cursor cursor) {
                Log.d(TAG, String.format(".CursorLoader.deliverResult(%s)",
                        (cursor == null) ? "null"
                                : cursor.isClosed() ? "closed cursor"
                                : "open cursor"));
                super.deliverResult(cursor);
            }

            @Override
            protected void onStartLoading() {
                Log.d(TAG, ".CursorLoader.onStartLoading");
                super.onStartLoading();
            }

            @Override
            public void onCanceled(Cursor cursor) {
                Log.d(TAG, String.format(".CursorLoader.onCanceled(%s)",
                        (cursor == null) ? "null"
                                : cursor.isClosed() ? "closed cursor"
                                : "open cursor"));
                super.onCanceled(cursor);
            }

            @Override
            protected void onStopLoading() {
                Log.d(TAG, ".CursorLoader.onStopLoading");
                super.onStopLoading();
                /*
                if (myCursor != null)
                    myCursor.close();
                myCursor = null;
                */
            }

        };
        return loader;
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        Log.d(TAG, ".onLoadFinished");
        itemAdapter.swapCursor(data);
    }

    public void onLoaderReset(Loader<Cursor> loader) {
        Log.d(TAG, ".onLoaderReset");
        itemAdapter.swapCursor(null);
    }

}
