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

import java.security.GeneralSecurityException;

import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.Note.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * The preferences activity manages the user options dialog.
 */
public class PreferencesActivity extends Activity {

    public static final String LOG_TAG = "PreferencesActivity";

    private NotePreferences prefs;

    CheckBox privateCheckBox = null;
    EditText passwordEditText = null;

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
		if (position >= NoteItem.USER_SORT_ORDERS.length) {
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
	final TableRow passwordRow =
	    (TableRow) findViewById(R.id.TableRowPassword);
	passwordRow.setVisibility(encryptor.hasPassword(getContentResolver())
		&& privateCheckBox.isChecked() ? View.VISIBLE : View.GONE);
	passwordEditText =
	    (EditText) findViewById(R.id.PrefsEditTextPassword);
	if (encryptor.hasKey())
	    passwordEditText.setText(encryptor.getPassword(), 0,
		    encryptor.getPassword().length);
	else
	    passwordEditText.setText("");
	privateCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
	    @Override
	    public void onCheckedChanged(CompoundButton button, boolean isChecked) {
		Log.d(LOG_TAG, "prefsCheckBoxShowPrivate.onCheckedChanged("
			+ isChecked + ")");
		passwordRow.setVisibility((isChecked &&
			encryptor.hasPassword(getContentResolver()))
			? View.VISIBLE : View.GONE);
                prefs.setShowPrivate(isChecked);
	    }
	});

	checkBox = (CheckBox) findViewById(R.id.PrefsCheckBoxShowPassword);
	passwordEditText.setInputType(InputType.TYPE_CLASS_TEXT
		+ (checkBox.isChecked()
			? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			: InputType.TYPE_TEXT_VARIATION_PASSWORD));
	checkBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
	    @Override
	    public void onCheckedChanged(CompoundButton button, boolean isChecked) {
		Log.d(LOG_TAG, "prefsCheckBoxShowPassword.onCheckedChanged("
			+ isChecked + ")");
		passwordEditText.setInputType(InputType.TYPE_CLASS_TEXT
			+ (isChecked
				? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
				: InputType.TYPE_TEXT_VARIATION_PASSWORD));
	    }
	});
    }

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
	Log.d(LOG_TAG, ".onBackPressed()");
	if (privateCheckBox.isChecked() &&
		(passwordEditText.length() > 0)) {
	    if (!encryptor.hasPassword(getContentResolver())) {
		// To do: the password field should have been disabled
		Toast.makeText(PreferencesActivity.this,
			R.string.ToastBadPassword, Toast.LENGTH_LONG).show();
	    } else {
		char[] newPassword = new char[passwordEditText.length()];
		passwordEditText.getText().getChars(0, newPassword.length, newPassword, 0);
		encryptor.setPassword(newPassword);
		try {
		    if (encryptor.checkPassword(getContentResolver())) {
                        prefs.setShowEncrypted(true);
			super.onBackPressed();
			return;
		    } else {
			Toast.makeText(PreferencesActivity.this,
				R.string.ToastBadPassword,
				Toast.LENGTH_LONG).show();
		    }
		} catch (GeneralSecurityException gsx) {
		    Toast.makeText(PreferencesActivity.this,
			    gsx.getMessage(), Toast.LENGTH_LONG).show();
		}
	    }
	}
	encryptor.forgetPassword();
        prefs.setShowEncrypted(false);
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
