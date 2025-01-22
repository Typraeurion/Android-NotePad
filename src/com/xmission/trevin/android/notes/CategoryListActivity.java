/*
 * $Id: CategoryListActivity.java,v 1.3 2015/03/28 20:14:20 trevin Exp trevin $
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
 * $Log: CategoryListActivity.java,v $
 * Revision 1.3  2015/03/28 20:14:20  trevin
 * Changed the code to make it similar to that used by the ToDo app,
 *   and hopefully fix some of the bugs it had.
 *
 * Revision 1.2  2014/03/30 23:43:08  trevin
 * Added the copyright notice.
 * Replaced SQLiteException with the more general SQLException.
 *
 * Revision 1.1  2011/03/21 00:48:06  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import static com.xmission.trevin.android.notes.Note.NoteCategory.*;

import com.xmission.trevin.android.notes.Note.*;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;

import java.util.*;

/**
 * Displays a list of Note categories.  Will display categories from the
 * {@link Uri} provided in the intent if there is one, otherwise defaults
 * to displaying the contents of the {@link NoteProvider}
 */
public class CategoryListActivity extends ListActivity {

    private static final String TAG = "CategoryListActivity";

    /**
     * The columns we are interested in from the category table
     */
    private static final String[] CATEGORY_PROJECTION = new String[] {
	    _ID, // 0
	    NAME, // 1
    };

    public static final String ORIG_NAME = "original " + NAME;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
	super.onCreate(savedInstanceState);
	Log.d(TAG, ".onCreate");

	setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

	// If no data was given in the intent (because we were started
	// as a MAIN activity), then use our default content provider.
	Intent intent = getIntent();
	if (intent.getData() == null) {
	    intent.setData(CONTENT_URI);
	}

	/*
	 * Perform a managed query. The Activity will handle closing and
	 * requerying the cursor when needed, but for now we need to
	 * read in the current category list.  Changes will not be
	 * written until the activity is finished.
	 */
	Cursor categoryCursor = managedQuery(
		getIntent().getData(), CATEGORY_PROJECTION,
		_ID + " != " + UNFILED, null, DEFAULT_SORT_ORDER);
	final List<Map<String,Object>> categoryList =
		new ArrayList<Map<String,Object>>(categoryCursor.getCount());
	while (categoryCursor.moveToNext()) {
	    Map<String,Object> valueMap = new HashMap<String,Object>();
	    valueMap.put(NAME, categoryCursor.getString(
		    categoryCursor.getColumnIndex(NAME)));
	    valueMap.put(_ID, categoryCursor.getLong(
		    categoryCursor.getColumnIndex(_ID)));
	    valueMap.put(ORIG_NAME, valueMap.get(NAME));
	    categoryList.add(valueMap);
	}
	categoryCursor.deactivate();

	final CategoryEditorAdapter categoryAdapter =
		new CategoryEditorAdapter(this, categoryList);
	setListAdapter(categoryAdapter);

	setContentView(R.layout.category_list);

	// Add callbacks
	Button newButton = (Button) findViewById(R.id.CategoryListButtonNew);
	newButton.setOnClickListener(new View.OnClickListener() {
	    @Override
	    public void onClick(View v) {
		Log.d(TAG, "newButton.onClick: adding a new category to the list");
		// Add a new item to the list
		Map<String,Object> newEntry = new HashMap<String,Object>();
		newEntry.put(NoteCategory.NAME, "");
		categoryList.add(newEntry);
		// Tell the adapter to refresh the display
		categoryAdapter.notifyDataSetChanged();
	    }
	});

	Button okButton = (Button) findViewById(R.id.CategoryListButtonOK);
	okButton.setOnClickListener(new View.OnClickListener() {
	    @Override
	    public void onClick(View v) {
		Log.d(TAG, "okButton.onClick");
		// Collect and commit changes
		ContentResolver cr = getContentResolver();
		Uri categoryUri = getIntent().getData();
		// To do: start a transaction
		try {
		    for (Map<String,Object> entry : categoryList) {
			String newName = (String) entry.get(NoteCategory.NAME);
			if (entry.containsKey(NoteCategory._ID)) {
			    // Has this entry been modified?
			    if (!newName.equals(entry.get(ORIG_NAME))) {
				Uri itemUri = ContentUris.withAppendedId(
					categoryUri,
					(Long) entry.get(NoteCategory._ID));
				if (newName.length() == 0) {
				    cr.delete(itemUri, null, null);
				} else {
				    ContentValues values = new ContentValues();
				    values.put(NoteCategory.NAME, newName);
				    cr.update(itemUri, values, null, null);
				}
			    }
			} else {
			    if (newName.length() > 0) {
				ContentValues values = new ContentValues();
				values.put(NoteCategory.NAME, newName);
				cr.insert(categoryUri, values);
			    }
			}
		    }
		    // To do: commit the transaction
		} catch (SQLiteException sqx) {
		    // Throw up an alert box
		    AlertDialog.Builder builder =
			new AlertDialog.Builder(CategoryListActivity.this);
		    builder.setIcon(android.R.drawable.ic_dialog_alert);
		    builder.setTitle(sqx.getClass().getSimpleName());
		    builder.setMessage(sqx.getMessage());
		    builder.setNeutralButton(
			    CategoryListActivity.this.getResources().getString(
				    R.id.CategoryListButtonOK),
				    new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int id) {
				    dialog.cancel();
				}
			    });
		    builder.show();
		    // To do: roll back the transaction
		    return;
		}
		CategoryListActivity.this.finish();
	    }
	});

	Button cancelButton = (Button)
		findViewById(R.id.CategoryListButtonCancel);
	cancelButton.setOnClickListener(new View.OnClickListener() {
	    @Override
	    public void onClick(View v) {
		Log.d(TAG, "cancelButton.onClick");
		CategoryListActivity.this.finish();
	    }
	});
    }
}
