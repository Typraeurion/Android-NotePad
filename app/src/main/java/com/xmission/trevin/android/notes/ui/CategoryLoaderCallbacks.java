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
package com.xmission.trevin.android.notes.ui;

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteCategoryColumns.DEFAULT_SORT_ORDER;
import static com.xmission.trevin.android.notes.ui.NoteListActivity.CATEGORY_PROJECTION;

import android.annotation.TargetApi;
import android.app.LoaderManager;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.xmission.trevin.android.notes.data.NotePreferences;

/**
 * As of Honeycomb (API 11), the application needs to re-initialize
 * the category cursor whenever the activity is restarted.
 * This class provides the callbacks for the category loader manager.
 * Used when NoteListActivity is created.
 *
 * @deprecated as of Pie (API 28)
 */
@TargetApi(11)
class CategoryLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final String TAG = "CategoryLoaderCallbacks";

    private final NoteListActivity listActivity;
    private final NotePreferences notePrefs;

    // Used to map note categories from the database to a filter list
    private final CategoryFilterCursorAdapter categoryAdapter;

    /** The URI by which we were started for the categories */
    private final Uri categoryUri;

    CategoryLoaderCallbacks(NoteListActivity activity,
                            NotePreferences prefs,
                            CategoryFilterCursorAdapter adapter,
                            Uri uri) {
        listActivity = activity;
        notePrefs = prefs;
        categoryAdapter = adapter;
        categoryUri = uri;
    }

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Log.d(TAG, ".onCreateLoader");
        CursorLoader loader = new CursorLoader(listActivity,
                categoryUri, CATEGORY_PROJECTION, null, null,
                DEFAULT_SORT_ORDER) {

            //private Cursor myCursor = null;

            @Override
            public Cursor loadInBackground() {
                Log.d(TAG, ".CursorLoader.loadInBackground");
                /*
                if (myCursor != null)
                    myCursor.close();
                myCursor = listActivity.getContentResolver().query(categoryUri,
                        CATEGORY_PROJECTION, null, null, DEFAULT_SORT_ORDER);
                // Ensure the cursor window is filled (from super class)
                myCursor.getCount();
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
        categoryAdapter.swapCursor(data);
        listActivity.setCategorySpinnerByID(notePrefs.getSelectedCategory());
    }

    public void onLoaderReset(Loader<Cursor> loader) {
        Log.d(TAG, ".onLoaderReset");
        categoryAdapter.swapCursor(null);
    }

}
