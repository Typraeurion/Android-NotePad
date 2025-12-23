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

import static com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns.USER_SORT_ORDERS;

import android.annotation.TargetApi;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.DataSetObserver;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.ui.NoteCursorAdapter;

/**
 * An asynchronous loader which provides a {@link NoteCursor}
 * for use by {@link NoteCursorAdapter}.
 */
@TargetApi(11)
public class NoteCursorLoader extends AsyncTaskLoader<NoteCursor> {

    private static final String TAG = "NoteCursorLoader";

    private final NoteRepository repository;

    private final NotePreferences prefs;

    private final DataSetObserver onDataChangeListener = new DataSetObserver() {
        /**
         * When the underlying note data changes, reload the data.
         */
        @Override
        public void onChanged() {
            Log.d(TAG, "DataSetObserver.onChanged");
            if (isStarted())
                forceLoad();
            else
                onContentChanged();
        }
    };

    public NoteCursorLoader(@NonNull Context context,
                            @NonNull NoteRepository repository,
                            @NonNull NotePreferences preferences) {
        super(context);
        this.repository = repository;
        prefs = preferences;
    }

    /**
     * Called on a worker thread to perform the actual load and to return
     * the result of the load operation.
     * <p>
     * Implementations should not deliver the result directly, but should return them
     * from this method, which will eventually end up calling {@link #deliverResult} on
     * the UI thread.  If implementations need to process the results on the UI thread
     * they may override {@link #deliverResult} and do so there.
     *
     * @return The result of the load operation.
     */
    @Nullable
    @Override
    public NoteCursor loadInBackground() {
        Log.d(TAG, ".loadInBackground");

        int selectedSortOrder = prefs.getSortOrder();
        if ((selectedSortOrder < 0) ||
                (selectedSortOrder >= USER_SORT_ORDERS.length)) {
            prefs.setSortOrder(0);
            selectedSortOrder = 0;
        }

        return repository.getNotes(prefs.getSelectedCategory(),
                prefs.showPrivate(), prefs.showEncrypted(),
                USER_SORT_ORDERS[selectedSortOrder]);

    }

    @Override
    public void deliverResult(@Nullable NoteCursor cursor) {
        Log.d(TAG, String.format(".deliverResult(%s)", cursor));
        super.deliverResult(cursor);
    }

    @Override
    public void cancelLoadInBackground() {
        Log.d(TAG, ".cancelLoadInBackground");
        super.cancelLoadInBackground();
    }

    @Override
    public void onCanceled(@Nullable NoteCursor cursor) {
        Log.d(TAG, String.format(".onCanceled(%s)", cursor));
        super.onCanceled(cursor);
    }

    @Override
    public void setUpdateThrottle(long delayMS) {
        Log.d(TAG, String.format(".setUpdateThrottle(%d)", delayMS));
        super.setUpdateThrottle(delayMS);
    }

    @Override
    protected boolean onCancelLoad() {
        Log.d(TAG, ".onCancelLoad");
        return super.onCancelLoad();
    }

    @Override
    protected void onForceLoad() {
        Log.d(TAG, ".onForceLoad");
        super.onForceLoad();
    }

    @Override
    protected @Nullable NoteCursor onLoadInBackground() {
        Log.d(TAG, ".onLoadInBackground");
        return super.onLoadInBackground();
    }

    @Override
    protected void onStartLoading() {
        Log.d(TAG, ".onStartLoading");
        super.onStartLoading();
        repository.registerDataSetObserver(onDataChangeListener);
        // For debugging
        //super.forceLoad();
        // Kick off the initial load
        onDataChangeListener.onChanged();
    }

    @Override
    protected void onReset() {
        Log.d(TAG, ".onReset");
        super.onReset();
        repository.unregisterDataSetObserver(onDataChangeListener);
    }
}
