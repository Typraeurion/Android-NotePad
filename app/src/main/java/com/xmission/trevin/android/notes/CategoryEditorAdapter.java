/*
 * $Id: CategoryEditorAdapter.java,v 1.3 2015/03/28 20:03:25 trevin Exp trevin $
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
 * $Log: CategoryEditorAdapter.java,v $
 * Revision 1.3  2015/03/28 20:03:25  trevin
 * Changed the code to make it similar to that used by the ToDo app,
 *   and hopefully fix some of the bugs it had.
 *
 * Revision 1.2  2014/03/30 19:30:53  trevin
 * Added the copyright notice
 *
 * Revision 1.1  2011/03/21 00:18:57  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import java.util.*;

import com.xmission.trevin.android.notes.Note.NoteCategory;

import android.content.Context;
import android.util.Log;
import android.view.*;
import android.widget.*;

/**
 * An adapter to map category names to the cat_list_item layout.
 * The list is initialized from the database, but is modifiable
 * until the user finishes the CategoryListActivity.
 *
 * @see android.widget.SimpleAdapter
 */
public class CategoryEditorAdapter extends BaseAdapter {

    private static final String LOG_TAG = "CategoryEditorAdapter";

    private List<Map<String,Object>> data;

    private LayoutInflater inflater;

    /**
     * Constructor
     * 
     * @param context The context where the View associated with this SimpleAdapter is running
     * @param data A List of Maps. Each entry in the List corresponds to one row in the list. The
     *        Maps contain the data for each row, and should include all the entries specified in
     *        "from"
     */
    public CategoryEditorAdapter(Context context, List<Map<String,Object>> data) {
	Log.d(LOG_TAG, "created");
	this.data = data;
	inflater = (LayoutInflater)
		context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * @return how many items are in the data set represented by this Adapter.
     *
     * @see android.widget.Adapter#getCount()
     */
    @Override
    public int getCount() {
	return data.size();
    }

    /**
     * @return the data item associated with the specified position
     * in the data set.
     *
     * @see android.widget.Adapter#getItem(int)
     */
    @Override
    public Object getItem(int position) {
	return data.get(position);
    }

    /**
     * @return the row id associated with the specified position in the list.
     * Returns -1 if the position represents an item which is not yet in
     * the database.
     *
     * @see android.widget.Adapter#getItemId(int)
     */
    @Override
    public long getItemId(int position) {
	Map<String,Object> entry = data.get(position);
	if (entry != null) {
	    if (entry.containsKey(NoteCategory._ID))
		return (Long) entry.get(NoteCategory._ID);
	}
	return -1;
    }

    /**
     * Get a {@link View) that displays the data
     * when the specified position in the data set is selected.
     *
     * @see android.widget.Adapter#getView(int, View, ViewGroup)
     */
    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
	Log.d(LOG_TAG, "getView(" + position + "," +
		(convertView == null ? "null" : "convertView") + ",parent)");
	if (convertView == null)
	    convertView = inflater.inflate(R.layout.cat_list_item,
		    parent, false);

	final EditText text = (EditText) convertView;
	final Map<String,Object> entry = data.get(position);
	text.setText((String) entry.get(NoteCategory.NAME));
	text.setOnFocusChangeListener(new View.OnFocusChangeListener() {
	    @Override
	    public void onFocusChange(View v, boolean hasFocus) {
		Log.d(LOG_TAG, "onFocusChange(v," + hasFocus + ") ["
			+ position + "]");
		if (!hasFocus) {
		    String newText = ((EditText) v).getText().toString();
		    entry.put(NoteCategory.NAME, newText);
		}
	    }
	});

	// If this is a new entry, request focus
	if ((((String) entry.get(NoteCategory.NAME)).length() == 0) &&
		!entry.containsKey(NoteCategory._ID))
	    text.requestFocus();

	return convertView;
    }

}
