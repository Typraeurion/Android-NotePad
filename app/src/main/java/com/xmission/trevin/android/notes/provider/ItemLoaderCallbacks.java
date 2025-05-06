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

import android.annotation.TargetApi;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.ui.NoteCursorAdapter2;

/**
 * Callbacks to have the {@link LoaderManager} call to provide a
 * {@link NoteCursor} to the {@link NoteCursorAdapter2}.
 *
 * @author Trevin Beattie
 */
@TargetApi(11)
public class ItemLoaderCallbacks
        implements LoaderManager.LoaderCallbacks<NoteCursor> {

    private static final String TAG = "ItemLoaderCallbacks";

    private final Context context;

    private final NotePreferences notePrefs;

    /** Used to map {@link NoteItem}s from the database to {@link View}s */
    private final NoteCursorAdapter2 itemAdapter;

    /** The repository that provides our Note Pad items */
    private final NoteRepository noteRepository;

    /**
     * Initialize the callbacks
     *
     * @param context the context in which the loader is being used
     * @param preferences The Note Pad preferences
     * @param adapter The Adapter which will map {@link NoteItem}s
     *                from the database to {@link View}s
     * @param repository The repository that provides our Note Pad items
     */
    public ItemLoaderCallbacks(@NonNull Context context,
                        @NonNull NotePreferences preferences,
                        @NonNull NoteCursorAdapter2 adapter,
                        @NonNull NoteRepository repository) {
        this.context = context;
        notePrefs = preferences;
        itemAdapter = adapter;
        noteRepository = repository;
    }

    @NonNull
    @Override
    public Loader<NoteCursor> onCreateLoader(int id, @Nullable Bundle args) {
        Log.d(TAG, ".onCreateLoader");
        return new NoteCursorLoader(context, noteRepository, notePrefs);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<NoteCursor> loader,
                               NoteCursor cursor) {
        Log.d(TAG, ".onLoadFinished");
        itemAdapter.swapCursor(cursor);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<NoteCursor> loader) {
        Log.d(TAG, ".onLoaderReset");
        itemAdapter.swapCursor(null);
    }

}
