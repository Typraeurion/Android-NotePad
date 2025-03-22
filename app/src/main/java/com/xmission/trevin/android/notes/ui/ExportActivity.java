/*
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
 */
package com.xmission.trevin.android.notes.ui;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;


import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import androidx.annotation.NonNull;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.service.ProgressReportingService;
import com.xmission.trevin.android.notes.service.XMLExporterService;
import com.xmission.trevin.android.notes.util.FileUtils;
import com.xmission.trevin.android.notes.util.StringEncryption;

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
    private NotePreferences prefs;

    StringEncryption encryptor;

    /** The error dialog, if we need to show one */
    AlertDialog errorDialog;

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
	prefs = NotePreferences.getInstance(this);

	// Set default values
	String fileName = FileUtils.getDefaultStorageDirectory(this)
		    + "/notes.xml";
	fileName = prefs.getExportFile(fileName);
	exportFileName.setText(fileName);

	boolean exportPrivate = prefs.exportPrivate();
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
                prefs.setExportFile(s.toString());
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
                        prefs.setExportPrivate(checked);
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

    private final DialogInterface.OnClickListener dismissListener =
	new DialogInterface.OnClickListener() {
	    @Override
	    public void onClick(DialogInterface dialog, int item) {
		dialog.dismiss();
		errorDialog = null;
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
	    try {
                // Check whether the file is in external storage,
                // and if so whether the external storage is available.
                if (!FileUtils.isStorageAvailable(exportFile, true)) {
		    xableFormElements(true);
		    showAlertDialog(R.string.ErrorSDNotFound,
                            getString(R.string.PromptMountStorage));
		    return;
		}
		// Check whether we have permission to write to the directory
                if (!FileUtils.checkPermissionForExternalStorage(
                        ExportActivity.this, exportFile, true)) {
                    xableFormElements(true);
                    showAlertDialog(R.string.ErrorExportFailed,
                            getString(R.string.ErrorExportPermissionDenied,
                                    exportFile.getParent()));
                    // If we're running on Marshmallow or later, request permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        requestPermissions(new String[] {
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE },
                                R.id.ExportEditTextFile);
                    return;
                }
	    } catch (IOException iox) {
	        Log.e(TAG, "Failed to verify storage location "
                        + exportFile.getPath());
	        xableFormElements(true);
	        showAlertDialog(R.string.ErrorExportFailed, iox.getMessage());
	        return;
            }
	    // Make sure the parent directory exists
	    if (!exportFile.getParentFile().exists()) {
		try {
		    FileUtils.ensureParentDirectoryExists(exportFile);
		    /*
		    if (!exportFile.getParentFile().mkdirs()) {
		        showAlertDialog(R.string.ErrorExportFailed,
                                getString(R.string.ErrorExportCantMkdirs,
                                        exportFile.getParent()));
			return;
		    }
		    */
		} catch (SecurityException sx) {
		    Log.e(TAG, "Failed to create directory for export file", sx);
		    xableFormElements(true);
		    showAlertDialog(R.string.ErrorExportFailed, sx.getMessage());
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

    /** Called when the user grants or denies permission */
    @Override
    public void onRequestPermissionsResult(
            int code, @NonNull String[] permissions, int[] results) {

        // This part is all just for debug logging.
        String[] resultNames = new String[results.length];
        for (int i = 0; i < results.length; i++) {
            switch (results[i]) {
                case PackageManager.PERMISSION_DENIED:
                    resultNames[i] = "Denied";
                    break;
                case PackageManager.PERMISSION_GRANTED:
                    resultNames[i] = "Granted";
                    break;
                default:
                    resultNames[i] = Integer.toString(results[i]);
            }
        }
        Log.d(TAG, String.format(".onRequestPermissionsResult(%d, %s, %s)",
                code, Arrays.toString(permissions),
                Arrays.toString(resultNames)));

        if (code != R.id.ExportEditTextFile) {
            Log.e(TAG, "Unexpected code from request permissions; ignoring!");
            return;
        }

        if (permissions.length != results.length) {
            Log.e(TAG, String.format("Number of request permissions (%d"
                    + ") does not match number of results (%d); ignoring!",
                    permissions.length, results.length));
            return;
        }

        for (int i = 0; i < results.length; i++) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                if (results[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Write external storage permission granted");
                    if (errorDialog != null) {
                        errorDialog.dismiss();
                        errorDialog = null;
                        // Retry the export
                        exportButton.performClick();
                    }
                }
            }
            else if (results[i] == PackageManager.PERMISSION_DENIED) {
                Log.i(TAG, "Write external storage permission denied!");
            }
            else {
                Log.w(TAG, "Ignoring unknown permission " + permissions[i]);
            }
        }

    }

    /**
     * Show an error dialog.
     *
     * @param titleId ID of the string resource providing
     *                the title of the dialog
     * @param message the error message
     */
    private void showAlertDialog(int titleId, String message) {
        errorDialog = new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleId)
                .setMessage(message)
                .setNeutralButton(R.string.ConfirmationButtonOK, dismissListener)
                .create();
        errorDialog.show();
    }

    class XMLExportServiceConnection implements ServiceConnection {
	public void onServiceConnected(ComponentName name, IBinder service) {
	    String interfaceDescriptor;
	    try {
	        interfaceDescriptor = service.getInterfaceDescriptor();
	    } catch (RemoteException rx) {
	        interfaceDescriptor = rx.getMessage();
            }
            Log.d(TAG, String.format(".XMLExportServiceConnection.onServiceConnected(%s, %s)",
                    name.getShortClassName(), interfaceDescriptor));
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
