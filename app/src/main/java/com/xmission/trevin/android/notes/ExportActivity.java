/*
 * $Id: ExportActivity.java,v 1.1 2014/04/01 00:48:54 trevin Exp trevin $
 * Copyright © 2014 Trevin Beattie
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
 * $Log: ExportActivity.java,v $
 * Revision 1.1  2014/04/01 00:48:54  trevin
 * Initial revision
 *
 * Revision 1.1  2014/03/22 19:03:52  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import java.io.File;


import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;

/**
 * Displays options for exporting a backup of the To Do list,
 * prior to actually attempting the export.
 *
 * @author Trevin Beattie
 */
public class ExportActivity extends Activity {

    private static final String TAG = "ExportActivity";

    /** The file name */
    EditText exportFileName = null;

    /** Checkbox for including private records */
    CheckBox exportPrivateCheckBox = null;

    /** Export button */
    Button exportButton = null;

    /**
     * Cancel button; this should remain available
     * until any changes are made to the current database.
     */
    Button cancelButton = null;

    /** Progress bar */
    ProgressBar exportProgressBar = null;

    /** Progress message */
    TextView exportProgressMessage = null;

    /** Progress reporting service */
    ProgressReportingService progressService = null;

    /** Shared preferences */
    private SharedPreferences prefs;

    /** Label for the last exported file name */
    public static final String NPREF_EXPORT_FILE = "ExportFile";

    /** Label for the preferences option "Include Private" */
    public static final String NPREF_EXPORT_PRIVATE = "ExportPrivate";

    StringEncryption encryptor;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // Inflate our view so we can find our fields
	setContentView(R.layout.export_options);

	exportFileName = (EditText) findViewById(R.id.ExportEditTextFile);
	exportPrivateCheckBox = (CheckBox) findViewById(
		R.id.ExportCheckBoxIncludePrivate);
	exportButton = (Button) findViewById(R.id.ExportButtonOK);
	cancelButton = (Button) findViewById(R.id.ExportButtonCancel);
	exportProgressBar = (ProgressBar) findViewById(R.id.ExportProgressBar);
	exportProgressMessage = (TextView) findViewById(
		R.id.ExportTextProgressMessage);

	encryptor = StringEncryption.holdGlobalEncryption();
	prefs = getSharedPreferences(
		NoteListActivity.NOTE_PREFERENCES, MODE_PRIVATE);

	// Set default values
	String fileName = Environment.getExternalStorageDirectory()
		    + "/Android/Data/"
		    + NoteListActivity.class.getPackage().getName()
		    + "/notes.xml";
	fileName = prefs.getString(NPREF_EXPORT_FILE, fileName);
	exportFileName.setText(fileName);

	boolean exportPrivate = prefs.getBoolean(NPREF_EXPORT_PRIVATE, true);
	exportPrivateCheckBox.setChecked(exportPrivate);

	findViewById(R.id.TableRowPasswordNotSetWarning)
	.setVisibility((encryptor.getPassword() == null)
		? View.VISIBLE : View.GONE);

	// At least until we know how big the input file is...
	exportProgressBar.setIndeterminate(true);
	exportProgressBar.setVisibility(View.GONE);

	// Set callbacks
	exportFileName.addTextChangedListener(new TextWatcher () {
	    @Override
	    public void afterTextChanged(Editable s) {
		prefs.edit().putString(NPREF_EXPORT_FILE, s.toString()).apply();
	    }
	    @Override
	    public void beforeTextChanged(CharSequence s,
		    int start, int count, int after) {}
	    @Override
	    public void onTextChanged(CharSequence s,
		    int start, int before, int count) {}
	});

	exportPrivateCheckBox.setOnCheckedChangeListener(
		new CompoundButton.OnCheckedChangeListener() {
		    public void onCheckedChanged(
			    CompoundButton b, boolean checked) {
			prefs.edit().putBoolean(
				NPREF_EXPORT_PRIVATE, checked).apply();
		    }
		});

	exportButton.setOnClickListener(new ExportButtonOnClickListener());
	cancelButton.setOnClickListener(
		new View.OnClickListener() {
		    @Override
		    public void onClick(View v) {
			Log.d(TAG, "ExportButtonCancel.onClick");
			ExportActivity.this.finish();
		    }
		});
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
	StringEncryption.releaseGlobalEncryption(this);
	super.onDestroy();
    }

    /**
     * Override the back button to prevent it from happening
     * in the middle of an export.
     */
    @Override
    public void onBackPressed() {
	if (cancelButton.isEnabled())
	    super.onBackPressed();
    }

    /** Enable or disable the form items */
    private void xableFormElements(boolean enable) {
	exportFileName.setEnabled(enable);
	exportPrivateCheckBox.setEnabled(enable);
	exportButton.setEnabled(enable);
	cancelButton.setEnabled(enable);
	exportProgressBar.setVisibility(enable ? View.GONE : View.VISIBLE);
	exportProgressMessage.setVisibility(enable ? View.GONE : View.VISIBLE);
    }

    static final DialogInterface.OnClickListener dismissListener =
	new DialogInterface.OnClickListener() {
	    @Override
	    public void onClick(DialogInterface dialog, int item) {
		dialog.dismiss();
	    }
	};

    /** Called when the user clicks Export to start exporting the data */
    class ExportButtonOnClickListener implements View.OnClickListener {
	@Override
	public void onClick(View v) {
	    Log.d(TAG, "ExportButtonOK.onClick");
	    exportProgressMessage.setText("...");
	    xableFormElements(false);
	    File exportFile = new File(exportFileName.getText().toString());
	    // Check whether the file is in external storage,
	    if (exportFile.getParent().startsWith(
		    Environment.getExternalStorageDirectory().getPath())) {
		// and if so whether the external storage is available.
		String storageState = Environment.getExternalStorageState();
		if (!Environment.MEDIA_MOUNTED.equals(storageState) &&
			!Environment.MEDIA_MOUNTED_READ_ONLY.equals(storageState)) {
		    xableFormElements(true);
		    new AlertDialog.Builder(ExportActivity.this)
		    .setIcon(android.R.drawable.ic_dialog_alert)
		    .setTitle(getResources().getString(R.string.ErrorSDNotFound))
		    .setMessage(getResources().getString(
			    R.string.PromptMountStorage))
		    .setNeutralButton(getResources().getString(
			    R.string.ConfirmationButtonOK), dismissListener)
		    .create().show();
		    return;
		}
	    }
	    // Make sure the parent directory exists
	    if (!exportFile.getParentFile().exists()) {
		try {
		    if (!exportFile.getParentFile().mkdirs()) {
			new AlertDialog.Builder(ExportActivity.this)
			.setIcon(android.R.drawable.ic_dialog_alert)
			.setTitle(getResources().getString(
				R.string.ErrorExportFailed))
			.setMessage(String.format(getResources().getString(
				R.string.ErrorExportCantMkdirs),
				exportFile.getParent()))
			.setNeutralButton(getResources().getString(
				R.string.ConfirmationButtonOK), dismissListener)
			.create().show();
			return;
		    }
		} catch (SecurityException sx) {
		    new AlertDialog.Builder(ExportActivity.this)
		    .setIcon(android.R.drawable.ic_dialog_alert)
		    .setTitle(getResources().getString(
			    R.string.ErrorExportFailed))
		    .setMessage(sx.getMessage())
		    .setNeutralButton(getResources().getString(
			    R.string.ConfirmationButtonOK), dismissListener)
		    .create().show();
		    return;
		}
	    }

	    Intent intent = new Intent(ExportActivity.this,
		    XMLExporterService.class);
	    intent.putExtra(XMLExporterService.XML_DATA_FILENAME,
		    exportFile.getAbsolutePath());
	    intent.putExtra(XMLExporterService.EXPORT_PRIVATE,
		    exportPrivateCheckBox.isChecked());
	    ServiceConnection serviceConnection =
		new XMLExportServiceConnection();

	    // Set up a callback to update the progress bar
	    final Handler progressHandler = new Handler();
	    progressHandler.postDelayed(new Runnable() {
		int oldMax = 0;
		String oldMessage = "...";
		@Override
		public void run() {
		    if (progressService != null) {
			String newMessage = progressService.getCurrentMode();
			int newMax = progressService.getMaxCount();
			int newProgress = progressService.getChangedCount();
			Log.d(TAG, ".Runnable: Updating the progress dialog to "
				+ newMessage + " " + newProgress + "/" + newMax);
			if (!oldMessage.equals(newMessage)) {
			    exportProgressMessage.setText(newMessage);
			    oldMessage = newMessage;
			}
			if (newMax != oldMax) {
			    exportProgressBar.setIndeterminate(newMax == 0);
			    exportProgressBar.setMax(newMax);
			    oldMax = newMax;
			}
			exportProgressBar.setProgress(newProgress);
			// To do: also display the values (if max > 0)
			progressHandler.postDelayed(this, 100);
		    }
		}
	    }, 100);
	    startService(intent);
	    Log.d(TAG, "ExportButtonOK.onClick: binding to the export service");
	    bindService(intent, serviceConnection, 0);
	}
    }

    class XMLExportServiceConnection implements ServiceConnection {
	public void onServiceConnected(ComponentName name, IBinder service) {
	    try {
		Log.d(TAG, ".XMLExportServiceConnection.onServiceConnected("
			+ name.getShortClassName() + ","
			+ service.getInterfaceDescriptor() + ")");
	    } catch (RemoteException rx) {}
	    XMLExporterService.ExportBinder xbinder =
		(XMLExporterService.ExportBinder) service;
	    progressService = xbinder.getService();
	}

	/** Called when a connection to the service has been lost */
	public void onServiceDisconnected(ComponentName name) {
	    Log.d(TAG, ".onServiceDisconnected(" + name.getShortClassName() + ")");
	    xableFormElements(true);
	    progressService = null;
	    unbindService(this);
	    // To do: was the export successful?
	    ExportActivity.this.finish();
	}
    }
}
