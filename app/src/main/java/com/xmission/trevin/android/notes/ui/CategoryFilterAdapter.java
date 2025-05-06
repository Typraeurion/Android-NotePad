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

import android.content.Context;
import android.content.Intent;
import android.database.DataSetObserver;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An adapter for note categories which adds two static entries to the list:
 * &ldquo;All&rdquo; at the top of the list which turns filtering off, and
 * &ldquo;Edit Categories&hellip;&rdquo; at the end of the list which pops
 * up the category list activity.
 *
 * @author Trevin Beattie
 */
public class CategoryFilterAdapter extends BaseAdapter {

    public static final String TAG = "CategoryFilterAdapter";

    private final Context context;

    private final LayoutInflater inflater;

    private boolean isOpen = false;

    private final NoteRepository repository;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Runnable READ_RUNNER = new ReadCategoriesRunner();

    private final List<DataSetObserver> observers = new ArrayList<>();

    /** View type for the All item */
    private static final int ALL_VIEW_TYPE = 1;
    /** View type for the Edit item */
    private static final int EDIT_VIEW_TYPE = 2;
    /** View type for the category items */
    private static final int DATA_VIEW_TYPE = 0;

    /** A pseudo-category for &ldquo;All&rdquo; at the head of the list */
    private final NoteCategory ALL_CATEGORY;

    /**
     * A pseudo-category for &ldquo;Edit Categories&hellip;&rdquo;
     * at the end of the list
     */
    private final NoteCategory EDIT_CATEGORY;

    /** A copy of the actual categories from the repository. */
    private List<NoteCategory> categories = null;

    /**
     * Weak map of views that have been used and the view type
     * they should be associated with.  We use this to determine
     * whether these views may be re-used or must be replaced.
     */
    private final WeakHashMap<View,Integer> spinnerViews =
            new WeakHashMap<>();

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
     * Create the category filter adapter with the given repository.
     * This will usually be the SQLite implementation but may be given
     * a mock repository instead for testing.
     *
     * @param context the context in which the adapter is being used
     * @param repository the repository to use
     */
    public CategoryFilterAdapter(@NonNull Context context,
                                 @NonNull NoteRepository repository) {
        this.context = context;
        this.repository = repository;
        inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        ALL_CATEGORY = new NoteCategory();
        ALL_CATEGORY.setId(NotePreferences.ALL_CATEGORIES);
        ALL_CATEGORY.setName(context.getString(R.string.Category_All));

        EDIT_CATEGORY = new NoteCategory();
        EDIT_CATEGORY.setId(NotePreferences.ALL_CATEGORIES - 1);
        EDIT_CATEGORY.setName(context.getString(R.string.Category_Edit));

        // The repository should be opened, but not on the UI thread.
        // However, AsyncTask is only available at API level 11 (Honeycomb).
        Runnable openRepo = new OpenRepositoryRunner();
        if ((Looper.getMainLooper().getThread() != Thread.currentThread()) ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)) {
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
            synchronized (CategoryFilterAdapter.this) {
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
            synchronized (CategoryFilterAdapter.this) {
                categories = repository.getCategories();
                CategoryFilterAdapter.this.notify();
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
            Log.e(TAG, "Did not open the repository within 5 seconds");
            return false;
        }
        if (categories == null) {
            if ((Looper.getMainLooper().getThread() != Thread.currentThread()) ||
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)) {
                try {
                    READ_RUNNER.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to read the category list", e);
                }
            } else {
                try {
                    executor.submit(READ_RUNNER);
                    wait(5000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Did not read the category list within 5 seconds");
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
     * @return the number of categories in the database plus 2
     */
    @Override
    public synchronized int getCount() {
        if (readCategories())
            return categories.size() + 2;
        return 2;
    }

    /**
     * Get the category filter associated with
     * the specified position in the data.
     * The first position, {@code 0}, is always &ldquo;All&rdquo;.
     * The following positions are a 1-based index into the sorted
     * categories.  The last position (number of categories + 1)
     * is the &ldquo;Edit Categories&hellip;&rdquo; pseudo-category.
     *
     * @param position the position in the list
     *
     * @return the category for that position
     */
    @Override
    public NoteCategory getItem(int position) {
        if (position < 0) {
            Log.w(TAG, String.format(".getItem(%d) - Invalid position",
                    position));
            return null;
        }
        if (position == 0)
            return ALL_CATEGORY;
        if (!readCategories() || (position == categories.size() + 1))
            return EDIT_CATEGORY;
        if (position > getCount() + 1) {
            Log.w(TAG, String.format(
                    ".getItem(%d) - Invalid position (max %d)",
                    position, categories.size() + 1));
            return null;
        }
        return categories.get(position - 1);
    }

    /**
     * Get the &lqduo;row ID&rdquo; associated with the specified position
     * in the list.  The &ldquo;All&rdquo; filter has a constant ID
     * of {@value NotePreferences#ALL_CATEGORIES}.  The
     * &ldquo;Edit Categories&hellip;&rdquo; pseudo-item has a value
     * one less than that.  All other categories use their database ID.
     *
     * @param position the position in the list
     *
     * @return the database ID corresponding to the category
     * or pseudo-category
     */
    @Override
    public long getItemId(int position) {
        if (position <= 0)
            return NotePreferences.ALL_CATEGORIES;
        if (!readCategories() || (position >= categories.size() + 1))
            return EDIT_CATEGORY.getId();
        return categories.get(position - 1).getId();
    }

    /**
     * @return the type of {@link View} that will be created by
     * {@link #getView(int, View, ViewGroup)} for the specified item.
     * We have three types: one for "All", which is not bound to
     * the category list; one for each item bound to the category list;
     * and one for "Edit categories...", which is neither bound
     * nor selectable (it's an action button.)
     * <p>
     * As of API 19 (Lollipop), Spinner only supports a single view type (0).
     * Therefore this method is only used internally; we do <i>not</i>
     * override the corresponding interface method.
     * </p>
     *
     * @see android.widget.BaseAdapter#getItemViewType(int)
     */
    private int internalItemViewType(int position) {
	if (position == 0)
	    // The first item is not bound to any data
	    return ALL_VIEW_TYPE;
	else if (position == getCount() - 1)
	    // The last item is an action button
	    // and must not be mixed with spinner selection items.
	    return EDIT_VIEW_TYPE;
	else
	    // All other items have the same type
	    return DATA_VIEW_TYPE;
    }

    /**
     * Get a View that displays the category filter at the
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
        Log.d(TAG, String.format(".getView(%d,%s,%s)",
                position, cvDesc, parent));
        NoteCategory category = getItem(position);
        TextView tv;
        int viewType = internalItemViewType(position);
        if (convertView != null) {
            if (!spinnerViews.containsKey(convertView) ||
                    (viewType != spinnerViews.get(convertView))) {
                Log.d(TAG, "Old view does not match target view type; ignoring it");
                convertView = null;
            }
        }
        if (convertView == null) {
            Log.d(TAG, "Creating a new spinner item view");
            tv = (TextView) inflater.inflate(android.R.layout.simple_spinner_item,
		    parent, false);
        } else {
            tv = (TextView) convertView;
        }
        if (category != null) {
            tv.setText(category.getName());
            spinnerViews.put(tv, viewType);
        }
        return tv;
    }

    /**
     * Handler for the &ldquo;Edit categories&hellip;&rdquo; item
     * in the category filter.
     */
    private static final View.OnClickListener EDIT_CATEGORIES_LISTENER =
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "[Edit categories...].onClick");
                    Intent intent = new Intent(v.getContext(),
                            CategoryListActivity.class);
                    v.getContext().startActivity(intent);
                }
            };

    /**
     * Get a View that displays the category filter in the drop-down
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
        Log.d(TAG, String.format(".getDropDownView(%d,%s,%s)",
                position, cvDesc, parent));
        NoteCategory category = getItem(position);
        TextView tv;
        int viewType = internalItemViewType(position);
        if (convertView != null) {
            if (!spinnerViews.containsKey(convertView) ||
                    (viewType != spinnerViews.get(convertView))) {
                Log.d(TAG, "Old view does not match target view type; ignoring it");
                convertView = null;
            }
        }
        if (convertView == null) {
            int layoutId = android.R.layout.simple_spinner_dropdown_item;
            if (category == ALL_CATEGORY) {
                Log.d(TAG, "Creating a new simple_spinner_dropdown_item for the \"All\" filter");
            }
            else if (category == EDIT_CATEGORY) {
                Log.d(TAG, "Creating a new simple_list_item_1 for \"Edit categories\"");
                layoutId = android.R.layout.simple_list_item_1;
            }
            else {
                Log.d(TAG, "Creating a new simple_spinner_item for the \""
                        + category.getName() + "\" category");
            }
            tv = (TextView) inflater.inflate(layoutId, parent, false);
        } else {
            tv = (TextView) convertView;
        }
        if (category != null) {
            tv.setText(category.getName());
            spinnerViews.put(tv, viewType);
            /*
             * If this is the "Edit categories..." item, we need to
             * install our own handler.
             */
            if (category == EDIT_CATEGORY) {
                tv.setOnClickListener(EDIT_CATEGORIES_LISTENER);
            }
        }
        return tv;
    }

    /**
     * Item ID&rsquo;s are <i>not</i> stable across changes to the
     * underlying data; categories may be added or removed,
     * and that will change the ID of the
     * &ldquo;Edit Categories&hellip;&rdquo; item.
     *
     * @return {@code false}
     */
    @Override
    public boolean hasStableIds() {
        return false;
    }

    /**
     * The category filter is never empty.
     *
     * @return {@code false}
     */
    @Override
    public boolean isEmpty() {
        return false;
    }

    /**
     * All items for this filter are always enabled.
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
        Log.d(TAG, ".notifyDataSetChanged");
        if (!isOpen) {
            Log.w(TAG, "The repository has been released;"
                    + " not notifying observers");
            return;
        }
        for (DataSetObserver observer : observers) try {
            observer.onChanged();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to notify observer "
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
        Log.i(TAG, ".notifyDataSetInvalidated");
        for (DataSetObserver observer : observers) try {
            observer.onInvalidated();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to notify observer "
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
