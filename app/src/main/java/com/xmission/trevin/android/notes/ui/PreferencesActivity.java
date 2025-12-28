/*
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
 */
package com.xmission.trevin.android.notes.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.*;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * The preferences activity manages the user options dialog.
 */
public class PreferencesActivity extends Activity {

    public static final String LOG_TAG = "PreferencesActivity";

    private NotePreferences prefs;

    CheckBox privateCheckBox = null;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    /** The global encryption object */
    StringEncryption encryptor;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
	super.onCreate(savedInstanceState);

	Log.d(LOG_TAG, ".onCreate");

	setContentView(R.layout.preferences);

	prefs = NotePreferences.getInstance(this);

	Spinner spinner = (Spinner) findViewById(R.id.PrefsSpinnerSortBy);
	setSpinnerByID(spinner, prefs.getSortOrder());
	spinner.setOnItemSelectedListener(new OnItemSelectedListener() {
	    @Override
	    public void onNothingSelected(AdapterView<?> parent) {
		// Do nothing
	    }
	    @Override
	    public void onItemSelected(AdapterView<?> parent, View child,
		    int position, long id) {
		Log.d(LOG_TAG, "spinnerSortBy.onItemSelected("
			+ position + "," + id + ")");
		if (position >= NoteItemColumns.USER_SORT_ORDERS.length) {
                    Log.e(LOG_TAG, "Unknown sort order selected");
                } else if (position >= 0) {
                    prefs.setSortOrder(position);
                }
	    }
	});

	CheckBox checkBox = (CheckBox) findViewById(R.id.PrefsCheckBoxShowCategory);
	checkBox.setChecked(prefs.showCategory());
	checkBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
	    @Override
	    public void onCheckedChanged(CompoundButton button, boolean isChecked) {
		Log.d(LOG_TAG, "prefsCheckBoxShowCategory.onCheckedChanged("
			+ isChecked + ")");
                prefs.setShowCategory(isChecked);
	    }
	});

	encryptor = StringEncryption.holdGlobalEncryption();
	privateCheckBox = (CheckBox) findViewById(R.id.PrefsCheckBoxShowPrivate);
	privateCheckBox.setChecked(prefs.showPrivate());
	privateCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
	    @Override
	    public void onCheckedChanged(CompoundButton button, boolean isChecked) {
		Log.d(LOG_TAG, "prefsCheckBoxShowPrivate.onCheckedChanged("
			+ isChecked + ")");
                prefs.setShowPrivate(isChecked);
	    }
	});
    }

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
	Log.d(LOG_TAG, ".onBackPressed()");
	super.onBackPressed();
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
	Log.d(LOG_TAG, ".onDestroy()");
	StringEncryption.releaseGlobalEncryption(this);
	super.onDestroy();
    }

    /** Look up the spinner item corresponding to a category ID and select it. */
    void setSpinnerByID(Spinner spinner, long id) {
	for (int position = 0; position < spinner.getCount(); position++) {
	    if (spinner.getItemIdAtPosition(position) == id) {
		spinner.setSelection(position);
		return;
	    }
	}
	Log.w(LOG_TAG, "No spinner item found for ID " + id);
	spinner.setSelection(0);
    }
}
