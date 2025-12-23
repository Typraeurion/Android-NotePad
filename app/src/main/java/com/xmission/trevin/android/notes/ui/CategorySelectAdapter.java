/*
 * Copyright © 2011-2025 Trevin Beattie
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

import android.content.Context;
import android.database.DataSetObserver;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.provider.NoteRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CategorySelectAdapter extends BaseAdapter {

    private static final String LOG_TAG = "CategorySelectAdapter";

    private final Context context;

    private final LayoutInflater inflater;

    private boolean isOpen = false;

    private final NoteRepository repository;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Runnable READ_RUNNER = new ReadCategoriesRunner();

    private final List<DataSetObserver> observers = new ArrayList<>();

    /** A copy of the actual categories from the repository. */
    private List<NoteCategory> categories = null;

    /**
     * An observer we register with the database to let us know of
     * changes to the data, so we can in turn notify any observers
     * of this adapter.
     */
    private class PassthroughObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            notifyDataSetChanged();
        }
        @Override
        public void onInvalidated() {
            notifyDataSetInvalidated();
        }
    }

    private PassthroughObserver observer = null;

    /**
     * Create the category selection adapter with the given repository.
     * This will usually be the SQLite implementation but may be given
     * a mock repository instead for testing.
     *
     * @param context the context in which the adapter is being used
     * @param repository the repository to use
     */
    public CategorySelectAdapter(@NonNull Context context,
                                 @NonNull NoteRepository repository) {
        Log.d(LOG_TAG, "created");
        this.context = context;
        this.repository = repository;
        inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        // The repository should be opened, but not on the UI thread.
        // However, AsyncTask is only available at API level 11 (Honeycomb).
        Runnable openRepo = new OpenRepositoryRunner();
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            openRepo.run();
        } else {
            executor.submit(openRepo);
        }
    }

    /**
     * A runner for opening the repository on a non-UI thread
     * (if on Honeycomb or later)
     */
    private class OpenRepositoryRunner implements Runnable {
        @Override
        public void run() {
            synchronized (CategorySelectAdapter.this) {
                repository.open(context);
                isOpen = true;
                // Initially populate the category list
                READ_RUNNER.run();
                observer = new PassthroughObserver();
                repository.registerDataSetObserver(observer);
            }
        }
    }

    /**
     * A runner for reading the categories from the repository
     * on a non-UI thread (if on Honeycomb or later)
     */
    private class ReadCategoriesRunner implements Runnable {
        @Override
        public void run() {
            synchronized (CategorySelectAdapter.this) {
                categories = repository.getCategories();
                CategorySelectAdapter.this.notify();
            }
        }
    }

    /**
     * Read in the category list if we don&rsquo;t already have it.
     *
     * @return true if the category list is available, false otherwise
     */
    private synchronized boolean readCategories() {
        if (categories != null)
            return true;
        if (!isOpen) try {
            wait(5000);
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Did not open the repository within 5 seconds");
            return false;
        }
        if (categories == null) {
            if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
                try {
                    READ_RUNNER.run();
                } catch (RuntimeException e) {
                    Log.e(LOG_TAG, "Failed to read the category list", e);
                }
            } else {
                try {
                    executor.submit(READ_RUNNER);
                    wait(5000);
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, "Did not read the category list within 5 seconds");
                }
            }
        }
        return (categories != null);
    }

    /** Indicate that all items in this adapter are enabled */
    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    /**
     * Get the number of items in the data set represented by this adapter
     *
     * @return the number of categories in the database
     */
    @Override
    public synchronized int getCount() {
        if (readCategories())
            return categories.size();
        return 0;
    }

    /**
     * Get the category filter associated with the specified position
     * in the data.  The positions are a 0-based index into the sorted
     * categories.
     *
     * @param position the position in the list
     *
     * @return the category for that position
     */
    @Override
    public NoteCategory getItem(int position) {
        if (position < 0) {
            Log.w(LOG_TAG, String.format(".getItem(%d) - Invalid position",
                    position));
            return null;
        }
        if (!readCategories() || (position >= categories.size())) {
            Log.w(LOG_TAG, String.format(
                    ".getItem(%d) - Invalid position (max %d)",
                    position, categories.size() - 1));
            return null;
        }
        return categories.get(position);
    }

    /**
     * Get the &lqduo;row ID&rdquo; associated with the specified position
     * in the list.  All categories use their database ID.
     *
     * @param position the position in the list
     *
     * @return the database ID corresponding to the category
     */
    @Override
    public long getItemId(int position) {
        if ((position < 0) || !readCategories() ||
                (position >= categories.size()))
            return NoteCategory.UNFILED;
        return categories.get(position).getId();
    }

    /**
     * Get a View that displays the category at the
     * specified position in the data set.
     *
     * @param position the position of the category filter in the list
     * @param convertView the old view to use, if possible
     * @param parent the parent that this view will be attached to
     *
     * @return a View of the category at this position
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // For debug logging
        String cvDesc = (convertView == null) ? "null"
                : convertView.getClass().getSimpleName();
        if (convertView instanceof TextView)
            cvDesc = String.format("%s@%s(\"%s\")", cvDesc,
                    Integer.toHexString(System.identityHashCode(convertView)),
                    ((TextView) convertView).getText().toString());
        Log.d(LOG_TAG, String.format(".getView(%d,%s,%s)",
                position, cvDesc, parent));
        NoteCategory category = getItem(position);
        TextView tv;
        if (convertView instanceof TextView) {
            tv = (TextView) convertView;
        } else {
            Log.d(LOG_TAG, "Creating a new spinner item view");
            tv = (TextView) inflater.inflate(android.R.layout.simple_spinner_item,
		    parent, false);
        }
        if (category != null) {
            tv.setText(category.getName());
        }
        return tv;
    }

    /**
     * Get a View that displays the category in the drop-down
     * popup at the specified position in the data set.
     *
     * @param position the position of the category filter in the list
     * @param convertView the old view to use, if possible
     * @param parent the parent that this view will be attached to
     *
     * @return a View of the category at this position
     */
    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        // For debug logging
        String cvDesc = (convertView == null) ? "null"
                : convertView.getClass().getSimpleName();
        if (convertView instanceof TextView)
            cvDesc = String.format("%s@%s(\"%s\")", cvDesc,
                    Integer.toHexString(System.identityHashCode(convertView)),
                    ((TextView) convertView).getText().toString());
        Log.d(LOG_TAG, String.format(".getDropDownView(%d,%s,%s)",
                position, cvDesc, parent));
        NoteCategory category = getItem(position);
        TextView tv;
        if (convertView instanceof TextView) {
            tv = (TextView) convertView;
        } else {
            Log.d(LOG_TAG, "Creating a new simple_spinner_item for the \""
                    + category.getName() + "\" category");
            tv = (TextView) inflater.inflate(
                    android.R.layout.simple_spinner_dropdown_item,
                    parent, false);
        }
        if (category != null) {
            tv.setText(category.getName());
        }
        return tv;
    }

    /**
     * All items for this adapter are always enabled.
     *
     * @param position (ignored)
     *
     * @return {@code true}
     */
    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    /**
     * Notify any attached observers that the underlying categories
     * have been changed and any View reflecting the data set
     * should refresh itself.
     */
    @Override
    public void notifyDataSetChanged() {
        Log.d(LOG_TAG, ".notifyDataSetChanged");
        if (!isOpen) {
            Log.w(LOG_TAG, "The repository has been released;"
                    + " not notifying observers");
            return;
        }
        for (DataSetObserver observer : observers) try {
            observer.onChanged();
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Failed to notify observer "
                    + observer.getClass().getCanonicalName(), e);
        }
    }

    /**
     * Notify any attached observers that the underlying category data
     * is no longer valid or available.  Once invoked this adapter is
     * no longer valid and should not report further data set changes.
     */
    @Override
    public synchronized void notifyDataSetInvalidated() {
        Log.i(LOG_TAG, ".notifyDataSetInvalidated");
        for (DataSetObserver observer : observers) try {
            observer.onInvalidated();
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Failed to notify observer "
                    + observer.getClass().getCanonicalName(), e);
        }
        if (observer != null) {
            repository.unregisterDataSetObserver(observer);
            observer = null;
        }
        repository.release(context);
        isOpen = false;
    }

    /**
     * Register an observer that is called when changes happen
     * to the category list.
     *
     * @param observer the observer to notify when changes happen
     */
    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        observers.add(observer);
    }

    /**
     * Unregister an observer that has previously been registered with
     * {@link #registerDataSetObserver}
     *
     * @param observer the observer to unregister
     */
    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        observers.remove(observer);
    }

}
