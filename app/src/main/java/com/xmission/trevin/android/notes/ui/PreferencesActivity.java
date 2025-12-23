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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.*;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CompoundButton.OnCheckedChangeListener;
import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
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
    TableRow passwordRow = null;
    EditText passwordEditText = null;

    /** The Note Pad database */
    NoteRepository repository = null;

    /**
     * Local copy of whether a password has been set.
     * This needs to be updated whenever the password hash
     * metadata is added or removed.
     */
    boolean hasPassword = false;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    /** The global encryption object */
    StringEncryption encryptor;

    /**
     * Set the repository to be used by this activity.
     * This is meant for UI tests to override the repository with a mock;
     * if not called explicitly, the activity will use the regular
     * repository implementation.
     *
     * @param repository the repository to use for notes
     */
    public void setRepository(@NonNull NoteRepository repository) {
        if (this.repository != null) {
            if (this.repository == repository)
                return;
            throw new IllegalStateException(String.format(
                    "Attempted to set the repository to %s"
                    + " when it had previously been set to %s",
                    repository.getClass().getCanonicalName(),
                    this.repository.getClass().getCanonicalName()));
        }
    }

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

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
        repository.registerDataSetObserver(passwordChangeObserver);

        Runnable openRepo = new OpenRepositoryRunner();
        executor.submit(openRepo);

	encryptor = StringEncryption.holdGlobalEncryption();
	privateCheckBox = (CheckBox) findViewById(R.id.PrefsCheckBoxShowPrivate);
	privateCheckBox.setChecked(prefs.showPrivate());
	passwordRow = (TableRow) findViewById(R.id.TableRowPassword);
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
		passwordRow.setVisibility((isChecked && hasPassword)
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

    /**
     * A runner which updates the visibility of the password row
     * to be called whenever we see a change in whether a password
     * has been set.
     */
    private final Runnable updatePasswordVisibility = new Runnable() {
        @Override
        public void run() {
            passwordRow.setVisibility(
                    hasPassword && privateCheckBox.isChecked()
                            ? View.VISIBLE : View.GONE);
        }
    };

    /**
     * A runner which checks the repository for a password hash,
     * updating our local {@code hasPassword} field accordingly.
     * If the value has changed since last checked, update the
     * visibility of the password row on the UI thread.
     */
    private final Runnable checkForPassword = new Runnable() {
        @Override
        public void run() {
            boolean oldHasPassword = hasPassword;
            hasPassword = encryptor.hasPassword(repository);
            if (hasPassword != oldHasPassword)
                runOnUiThread(updatePasswordVisibility);
        }
    };

    private class OpenRepositoryRunner implements Runnable {
        @Override
        public void run() {
            repository.open(PreferencesActivity.this);
            // Final UI initialization
            checkForPassword.run();
        }
    }

    /** Observer which checks for changes to the stored password hash */
    private final DataSetObserver passwordChangeObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            executor.submit(checkForPassword);
        }
    };

    /**
     * Check the password that the user entered in the {@code encryptor}
     * against the database.  This must be done on the non-UI thread.
     */
    private final Runnable checkPassword = new Runnable() {
        @Override
        public void run() {
            try {
                if (encryptor.checkPassword(repository)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            prefs.setShowEncrypted(true);
                            PreferencesActivity.super.onBackPressed();
                        }
                    });
                } else {
                    runOnUiThread(toastBadPassword);
                }
            } catch (GeneralSecurityException gsx) {
                runOnUiThread(new Runnable () {
                    @Override
                    public void run() {
                        Toast.makeText(PreferencesActivity.this,
                                gsx.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };

    /**
     * Pop up a &ldquo;bad password&rdquo; toast.
     * (This must be run on the UI thread)
     */
    private final Runnable toastBadPassword = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(PreferencesActivity.this,
                    R.string.ToastBadPassword,
                    Toast.LENGTH_LONG).show();
        }
    };

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
	Log.d(LOG_TAG, ".onBackPressed()");
	if (privateCheckBox.isChecked() &&
		(passwordEditText.length() > 0)) {
	    if (!hasPassword) {
		// To do: the password field should have been disabled
		Toast.makeText(PreferencesActivity.this,
			R.string.ToastBadPassword, Toast.LENGTH_LONG).show();
	    } else {
		char[] newPassword = new char[passwordEditText.length()];
		passwordEditText.getText().getChars(0, newPassword.length, newPassword, 0);
		encryptor.setPassword(newPassword);
                executor.submit(checkPassword);
                return;
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
        repository.unregisterDataSetObserver(passwordChangeObserver);
        repository.release(this);
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
