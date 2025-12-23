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

import java.util.*;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.R;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;

/**
 * An adapter to map category names to the cat_list_item layout.
 * The list is initialized from the database, but is modifiable
 * until the user finishes the CategoryListActivity.
 *
 * @see android.widget.SimpleAdapter
 */
public class CategoryEditorAdapter extends BaseAdapter {

    private static final String LOG_TAG = "CategoryEditorAdapter";

    /** Key we use to store the position of the view in the list */
    private static final int POSITION_KEY = 592685895;

    private final LayoutInflater inflater;

    /** The list of categories being edited. */
    private final List<NoteCategory> categories;

    /**
     * Create the category editor adapter with the given category list.
     * This will ordinarily be read from the database when CategoryListActivity
     * is created but may be given mock data instead for testing.
     * 
     * @param context The context where the View associated
     *               with this adapter is running
     * @param data the initial list of categories to show
     */
    public CategoryEditorAdapter(@NonNull Context context,
                                 @NonNull List<NoteCategory> data) {
	Log.d(LOG_TAG, "created");
	inflater = (LayoutInflater)
		context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        categories = data;
    }

    /**
     * Get the number of items in the data set represented by this adapter
     *
     * @return the number of categories in the database
     */
    @Override
    public int getCount() {
        return categories.size();
    }

    /**
     * Get the category associated with the specified position in the data.
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
        if (position > categories.size() - 1) {
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
     * @return the database ID of the category at the specified position
     * in the list.  Returns -1 if the position represents an item which
     * is not yet in the database.
     */
    @Override
    public long getItemId(int position) {
        if ((position < 0) || (position >= categories.size()))
            return -1;
        NoteCategory category = categories.get(position);
        if ((category != null) && (category.getId() != null))
            return category.getId();
	return -1;
    }

    /**
     * A listener which is called when a view (of editable text)
     * gains or loses focus.  When it loses focus, we pull the the
     * current category name from the view to store in our in-memory
     * copy for a later update.
     */
    private class EditCategoryFocusChangeListener
            implements View.OnFocusChangeListener {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            EditText et = (EditText) v;
            Log.d(LOG_TAG, String.format("onFocusChange(EditText(\"%s\"),%s)",
                    et.getText().toString(), hasFocus));
            if (!hasFocus) {
                Integer position = (Integer) et.getTag(POSITION_KEY);
                if (position == null) {
                    Log.e(LOG_TAG, "Position has not been set on "
                            + v);
                    return;
                }
                if ((position < 0) || (position >= categories.size())) {
                    Log.e(LOG_TAG, String.format(
                            "Position for %s (%d) is out of range (0\u2013%d)",
                            v, position, categories.size() - 1));
                    return;
                }
                String newText = ((EditText) v).getText().toString();
                categories.get(position).setName(newText);
            }
        }
    }

    private final EditCategoryFocusChangeListener FOCUS_CHANGE_LISTENER =
            new EditCategoryFocusChangeListener();

    /**
     * Get a {@link View} that displays the category for
     * the specified position in the category list.
     *
     * @param position the position of the category in the list
     * @param convertView the old view to use, if possible
     * @param parent the parent that this view will be attached to
     *
     * @return a View of the category at this position
     */
    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        // For debug logging
        String cvDesc = (convertView == null) ? "null"
                : convertView.getClass().getSimpleName();
        View outerView = convertView;
        EditText et = null;
        try {
            if (convertView != null)
                et = convertView.findViewById(R.id.CategoryListItemID);
            if (et != null)
                cvDesc = String.format("%s@%s(\"%s\")", cvDesc,
                        Integer.toHexString(System.identityHashCode(convertView)),
                        ((EditText) convertView).getText().toString());
            Log.d(LOG_TAG, String.format("getView(%d,%s,%s)",
                    position, cvDesc, parent));
            NoteCategory category = getItem(position);
            if (et == null) {
                Log.d(LOG_TAG, "Creating a new list item view");
                outerView = inflater.inflate(R.layout.cat_list_item,
                        parent, false);
                et = outerView.findViewById(R.id.CategoryListItemID);
            }

            if (et == null) {
                Log.e(LOG_TAG, "getView: no EditText found in category list item view!");
                return null;
            }

            et.setText((category.getName() == null) ? "" : category.getName());
            et.setTag(POSITION_KEY, position);
            et.setOnFocusChangeListener(FOCUS_CHANGE_LISTENER);

            // If this is a new entry, request focus
            if (TextUtils.isEmpty(category.getName()) ||
                    (category.getId() == null))
                et.requestFocus();

            return outerView;
        }
        catch (ClassCastException cx) {
            Log.e(LOG_TAG, "getView: CategoryListItemID is not an EditText component!", cx);
            return null;
        }
    }

}
