/*
 * $Id: CategoryFilterCursorAdapter.java,v 1.3 2015/03/28 20:06:02 trevin Exp trevin $
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
 * $Log: CategoryFilterCursorAdapter.java,v $
 * Revision 1.3  2015/03/28 20:06:02  trevin
 * Added a no-Cursor constructor for API 11 (Honeycomb)
 *
 * Revision 1.2  2014/04/06 20:56:25  trevin
 * Keep track of the views we create ourself.
 * If this is the "Edit categories..." item, we need to
 *   remove default action and install our own handler.
 *
 * Revision 1.1  2011/03/23 05:39:40  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import java.util.WeakHashMap;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;
import android.view.*;
import android.widget.*;

import com.xmission.trevin.android.notes.Note.*;

/**
 * An extension of the @{link SimpleCursorAdapter} which adds two
 * static entries to the list: "All" at the top of the list which
 * turns filtering off, and "Edit Categories..." at the end of the
 * list which pops up the category list activity.
 */
public class CategoryFilterCursorAdapter extends SimpleCursorAdapter {

    public static final String TAG = "CategryFiltrCursrAdaptr";

    private final Context context;

    /** Keeps track of the views we create ourself. */
    private final WeakHashMap<View,Integer> ourViews =
	new WeakHashMap<>();

    /** View type for the All item */
    private static final int ALL_VIEW_TYPE = 1;
    /** View type for the Edit item */
    private static final int EDIT_VIEW_TYPE = 2;
    /** View type for the category items */
    private static final int DATA_VIEW_TYPE = 0;

    private LayoutInflater inflater;

    public CategoryFilterCursorAdapter(Context context) {
	this(context,  null);
    }

    @TargetApi(11)
    public CategoryFilterCursorAdapter(Context context, int flags) {
       this(context, null, flags);
    }

    @SuppressWarnings("deprecation")
    public CategoryFilterCursorAdapter(Context context, Cursor c) {
	/*
	 * This constructor was deprecated in API 11,
	 * but its replacement is not available before then.
	 */
	super(context, android.R.layout.simple_spinner_item,
		c, new String[] { NoteCategory.NAME },
        	new int[] { android.R.id.text1 });
	this.context = context;
	inflater = (LayoutInflater)
		context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	Log.d(TAG, "created");
    }

    @TargetApi(11)
    public CategoryFilterCursorAdapter(Context context, Cursor c, int flags) {
       super(context, android.R.layout.simple_spinner_item,
               c, new String[] { NoteCategory.NAME },
               new int[] { android.R.id.text1 }, flags);
       this.context = context;
       inflater = (LayoutInflater)
               context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
       Log.d(TAG, "created");
    }

    /**
     * @return how many items are in the data set represented by this Adapter.
     * We always have 2 items in addition to the underlying data.
     *
     * @see android.widget.Adapter#getCount()
     */
    @Override
    public int getCount() {
	return super.getCount() + 2;
    }

    /**
     * @return the data item associated with the specified position
     * in the data set.
     *
     * @see android.widget.Adapter#getItem(int)
     */
    @Override
    public Object getItem(int position) {
	if ((position == 0) || (position == super.getCount() + 1))
	    return null;
	return super.getItem(position - 1);
    }

    /**
     * @return the row id associated with the specified position in the list.
     * <b>Caution:</b> the row ID is meaningless for the first and last
     * items in this adapter, as they do not come from the data set.
     *
     * @see android.widget.Adapter#getItemId(int)
     */
    @Override
    public long getItemId(int position) {
	if (position == 0)
	    return -1;
	return super.getItemId(position - 1);
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
	else if (position == super.getCount() + 1)
	    // The last item is an action button
	    // and must not be mixed with spinner selection items.
	    return EDIT_VIEW_TYPE;
	else
	    // All other items have the same type
	    return DATA_VIEW_TYPE;
    }

    /*
     * @return the number of types of {@link View}s that will be created by
     * {@link #getView(int, View, ViewGroup)}.
     * <p>
     * As of API 19 (Lollipop), Spinner *requires* a view type count of 1!
     * </p>
     *
     * @see android.widget.BaseAdapter#getViewTypeCount()
     */
    /*
    @Override
    public int getViewTypeCount() {
        return 3;
    }
    */

    /**
     * Get a {@link View) that displays the data in the spinner
     * when the specified position in the data set is selected.
     *
     * @see android.widget.Adapter#getView(int, View, ViewGroup)
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
	Log.d(TAG, ".getView(" + position + "," +
		((convertView == null) ? "null" : "oldView") + "," + parent + ")");
	int viewType = internalItemViewType(position);
	if (viewType == DATA_VIEW_TYPE) {
	    if (ourViews.containsKey(convertView))
		// Not convertible!
		convertView = null;
	    return super.getView(position - 1, convertView, parent);
	}
	if (!ourViews.containsKey(convertView) ||
		(ourViews.get(convertView) != viewType))
	    // Not convertible!
	    convertView = null;

	View v;
	if (convertView == null) {
	    Log.d(TAG, ": Creating a new spinner item view");
	    v = inflater.inflate(android.R.layout.simple_spinner_item,
		    parent, false);
	    ourViews.put(v, viewType);
	} else {
	    v = convertView;
	}

	// Bind the View
	TextView textView = (TextView) v;
	textView.setText(context.getString((position == 0)
		? R.string.Category_All : R.string.Category_Edit));

	return v;
    }

    /**
     * Get a {@link View) that displays the data
     * for the specified position in the data set.
     *
     * @see android.widget.Adapter#getView(int, View, ViewGroup)
     */
    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
	Log.d(TAG, ".getDropDownView(" + position + "," +
		((convertView == null) ? "null" : "oldView") + "," + parent + ")");
	int viewType = internalItemViewType(position);
	if (viewType == DATA_VIEW_TYPE) {
	    if (ourViews.containsKey(convertView))
		// Not convertible!
		convertView = null;
	    return super.getDropDownView(position - 1, convertView, parent);
	}
	if (!ourViews.containsKey(convertView) ||
		(ourViews.get(convertView) != viewType))
	    // Not convertible!
	    convertView = null;

	View v;
	if (convertView == null) {
	    Log.d(TAG, ": Creating a new drop-down view");
	    v = inflater.inflate((viewType == ALL_VIEW_TYPE)
		    ? R.layout.simple_spinner_dropdown_item
		    : R.layout.simple_dropdown_item_1line,
		    parent, false);
	    ourViews.put(v, viewType);

	    /*
	     * If this is the "Edit categories..." item, we need to
	     * remove default action and install our own handler.
	     */
	    if (viewType == EDIT_VIEW_TYPE) {
		TextView tv = (TextView) v;
		tv.setOnClickListener(new View.OnClickListener() {
		    @Override
		    public void onClick(View v) {
			Log.d(TAG, "[Edit categories...].onClick");
			Intent intent = new Intent(v.getContext(),
				CategoryListActivity.class);
			v.getContext().startActivity(intent);
		    }
		});
	    }
	} else {
	    v = convertView;
	}

	// Bind the View
	TextView textView = (TextView) v;
	textView.setText(context.getString((position == 0)
		? R.string.Category_All : R.string.Category_Edit));

	return v;
    }
}
