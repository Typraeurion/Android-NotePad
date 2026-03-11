/*
 * Copyright © 2014–2026 Trevin Beattie
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
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import android.Manifest;
import android.annotation.TargetApi;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.service.ProgressBarUpdater;
import com.xmission.trevin.android.notes.service.XMLExportWorker;
import com.xmission.trevin.android.notes.util.FileUtils;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * Displays options for exporting a backup of the Note Pad,
 * prior to actually attempting the export.
 *
 * @author Trevin Beattie
 */
public class ExportActivity extends Activity {

    private static final String TAG = "ExportActivity";

    /**
     * Arbitrary request code for selecting a directory in which to save an
     * XML file from Android&rqsuo;s Open Document intent (Kit Kat or higher)
     */
    private static final int SAF_PICK_XML_DIRECTORY = 4;

    /** Radio group for selecting the storage location */
    RadioGroup exportRadioGroup;
    /** Radio button for selected app private storage */
    RadioButton exportRadioPrivate;
    /** Radio button for selecting shared storage */
    RadioButton exportRadioShared;
    /** The last selected radio option in case we need to revert */
    int exportRadioState;

    /**
     * The layout row for the import directory;
     * this may be hidden or revealed according to context
     */
    TableRow exportDirectoryRow = null;

    /** The directory where the import file is found */
    EditText exportDirectoryName = null;

    /** The file name */
    EditText exportFileName = null;

    /**
     * The URI of the export file, if it was selected from
     * Android&rsquo;s Storage Access Framework (Kit Kat or higher only)
     */
    Uri exportDocUri = null;

    /** Checkbox for including private records */
    CheckBox exportPrivateCheckBox = null;

    /**
     * Whether the database has a password set.  We check this
     * in a repository runner on a non-UI thread.
     */
    boolean hasPassword = false;

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

    /** Live data for the progress dialog */
    LiveData<WorkInfo> progressLiveData = null;

    /** Progress observer */
    ExportProgressObserver progressObserver = null;

    /** Shared preferences */
    private NotePreferences prefs;

    StringEncryption encryptor;

    /** The error dialog, if we need to show one */
    AlertDialog errorDialog;

    private WorkManager workManager;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // Inflate our view so we can find our fields
        setContentView(R.layout.export_options);

        exportRadioGroup =findViewById(R.id.ExportFolderRadioGroup);
        exportRadioPrivate = findViewById(R.id.ExportFolderRadioButtonPrivate);
        exportRadioShared = findViewById(R.id.ExportFolderRadioButtonShared);
        exportDirectoryRow = findViewById(R.id.ExportTableRowFileDirectory);
        exportDirectoryName = findViewById(R.id.ExportEditTextDirectory);
        exportFileName = findViewById(R.id.ExportEditTextFile);
        exportPrivateCheckBox = findViewById(
                R.id.ExportCheckBoxIncludePrivate);
        exportButton = findViewById(R.id.ExportButtonOK);
        cancelButton = findViewById(R.id.ExportButtonCancel);
        exportProgressBar = findViewById(R.id.ExportProgressBar);
        exportProgressMessage = findViewById(R.id.ExportTextProgressMessage);

        encryptor = StringEncryption.holdGlobalEncryption();
        prefs = NotePreferences.getInstance(this);

        workManager = WorkManager.getInstance(this);

        // Set default values
        String directoryName = FileUtils.getDefaultStorageDirectory(this);
        String fullPath = prefs.getExportFile(directoryName
                + File.separator + "notes.xml");
        String fileName;
        if (fullPath.startsWith(directoryName + File.separator)) {
            exportRadioPrivate.setChecked(true);
            exportDirectoryName.setEnabled(false);
            exportFileName.setEnabled(true);
            fileName = fullPath.substring(directoryName.length()
                    + File.separator.length());
        } else {
            exportRadioShared.setChecked(true);
            exportDocUri = null;
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) &&
                    fullPath.startsWith("content://")) {
                try {
                    exportDocUri = Uri.parse(fullPath);
                    // Test whether we still have access to this file;
                    // use append mode in case the file already exists so we
                    // don't overwrite it until the user initiates the export.
                    OutputStream testStream = getContentResolver()
                            .openOutputStream(exportDocUri, "wa");
                    testStream.close();
                    fullPath = FileUtils.getFileNameFromUri(this, exportDocUri);
                    exportDirectoryName.setEnabled(false);
                    exportFileName.setEnabled(false);
                } catch (Exception e) {
                    // If we can't write the file, revert to private storage.
                    exportRadioPrivate.setChecked(true);
                    fullPath = directoryName + File.separator + "notes.xml";
                    exportDirectoryName.setEnabled(false);
                    exportFileName.setEnabled(true);
                    prefs.setExportFile(fullPath);
                }
            } else { // Jelly Bean or earlier doesn't support Storage Access Framework
                exportDirectoryName.setEnabled(true);
                exportFileName.setEnabled(true);
            }
            final Pattern DIR_FILE_PATTERN = Pattern.compile("(.+:)?((.*)"
                    + File.separator + ")?(.+)");
            Matcher m = DIR_FILE_PATTERN.matcher(fullPath);
            if (m.matches()) {
                directoryName = m.group(3);
                if (directoryName == null)
                    directoryName = "";
                fileName = m.group(4);
            } else {
                directoryName = "";
                fileName = fullPath;
            }
            if (directoryName.equals("") && !exportDirectoryName.isEnabled())
                exportDirectoryRow.setVisibility(View.GONE);
            else
                exportDirectoryRow.setVisibility(View.VISIBLE);
        }
        exportDirectoryName.setText(directoryName);
        exportFileName.setText(fileName);
        exportRadioState = exportRadioGroup.getCheckedRadioButtonId();

        boolean exportPrivate = prefs.exportPrivate();
        exportPrivateCheckBox.setChecked(exportPrivate);

        // Check for a password in the database.  If there isn't one,
        // show a warning if the "Include Private" option is checked.
        final NoteRepository repository = NoteRepositoryImpl.getInstance();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    repository.open(ExportActivity.this);
                    hasPassword = encryptor.hasPassword(repository);
                    repository.release(ExportActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            findViewById(R.id.TableRowPasswordNotSetWarning)
                                    .setVisibility((prefs.exportPrivate() && !hasPassword)
                                            ? View.VISIBLE : View.GONE);
                        }
                    });
                }
            });
        }

        // At least until we know how big the input file is...
        exportProgressBar.setIndeterminate(true);
        exportProgressBar.setVisibility(View.GONE);

        // Set callbacks
        exportRadioPrivate.setOnCheckedChangeListener(
                new PrivateStorageCheckedChangeListener());

        exportRadioShared.setOnClickListener(
                new SharedStorageCheckedChangeListener());

        exportFileName.addTextChangedListener(new FileNameChangedListener());

        exportPrivateCheckBox.setOnCheckedChangeListener(
                new IncludePrivateCheckedChangeListener());

        exportButton.setOnClickListener(new ExportButtonOnClickListener());
        cancelButton.setOnClickListener(new CancelClickListener());
    }

    /**
     * Called when the user selects an import file through
     * the Storage Access Framework (KitKat and above)
     */
    @Override
    @TargetApi(19)
    public void onActivityResult(
            int requestCode, int resultCode, Intent resultData) {
        Log.d(TAG, String.format(Locale.US, ".onActivityResult(%d,%d,%s)",
                requestCode, resultCode, (resultData == null) ?
                        null : resultData.getData()));
        if (requestCode != SAF_PICK_XML_DIRECTORY)
            // Request code not recognized; ignore it
            return;
        if (resultCode == Activity.RESULT_CANCELED) {
            // Revert back to the previous state
            exportRadioGroup.check(exportRadioState);
            return;
        }
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Ignoring unexpected result code!");
            return;
        }
        if ((resultData == null) || (resultData.getData() == null)) {
            Log.w(TAG, String.format(Locale.US,
                    "No data returned from result!  Reverting to %s.",
                    (exportRadioState == R.id.ExportFolderRadioButtonPrivate)
                            ? "private storage" : (exportDocUri == null)
                            ? "shared storage" : exportDocUri.toString()));
            exportRadioGroup.check(exportRadioState);
            return;
        }
        Uri oldUri = exportDocUri;
        exportDocUri = resultData.getData();
        getContentResolver().takePersistableUriPermission(exportDocUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        // The path may include the protocol, e.g. "raw:"
        final Pattern DIR_FILE_PATTERN = Pattern.compile("(.+:)?((.*)"
                + File.separator + ")?(.+)");
        Matcher m = DIR_FILE_PATTERN.matcher(
                FileUtils.getFileNameFromUri(this, exportDocUri));
        if (!m.matches()) {
            Log.e(TAG, String.format(Locale.US,
                    "Failed to parse directory and file from Uri: %s",
                    exportDocUri.toString()));
            exportRadioGroup.check(exportRadioState);
            exportDocUri = oldUri;
            return;
        }
        String directoryName = m.group(3);
        if (directoryName == null)
            directoryName = "";
        String fileName = m.group(4);
        exportDirectoryName.setEnabled(false);
        exportFileName.setEnabled(false);
        exportDirectoryName.setText(directoryName);
        exportFileName.setText(fileName);
        exportDirectoryRow.setVisibility(directoryName.equals("")
                ? View.GONE : View.VISIBLE);
        prefs.setExportFile(exportDocUri.toString());
        exportRadioState = R.id.ExportFolderRadioButtonShared;
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
        if (exportDocUri == null) {
            if (exportRadioShared.isChecked())
                exportDirectoryName.setEnabled(enable);
            exportFileName.setEnabled(enable);
        }
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

    /** Called when the user changes the file location to private storage */
    private class PrivateStorageCheckedChangeListener
            implements RadioButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                @NonNull CompoundButton button, boolean selected) {
            Log.d(TAG, String.format(Locale.US,
                    "PrivateStorageCheckedChangeListener.onCheckedChanged(%s)",
                    selected));
            if (!selected)
                return; // The other radio button will take care of it
            exportDocUri = null;
            String directoryName = FileUtils
                    .getDefaultStorageDirectory(ExportActivity.this);
            String fileName = exportFileName.getText().toString();
            if (!fileName.endsWith(".xml")) {
                // The Storage Access Framework may replace the
                // actual file name with a temporary substitute;
                // revert to the default file name.
                fileName = "notes.xml";
                exportFileName.setText(fileName);
            }
            exportDirectoryName.setText(directoryName);
            exportDirectoryName.setEnabled(false);
            exportFileName.setEnabled(true);
            exportDirectoryRow.setVisibility(View.VISIBLE);
            prefs.setExportFile(directoryName + File.separator + fileName);
            exportRadioState = button.getId();
        }
    }

    /** Called when the user changes the file location to shared storage */
    private class SharedStorageCheckedChangeListener
            implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Log.d(TAG, "SharedStorageCheckedChangeListener.onClick()");
            // Default to local shared storage
            String directoryName = FileUtils.getSharedStorageDirectory();
            // Although SAF is supposedly supported on KitKat,
            // it doesn't work in practice -- import files uploaded
            // into the Downloads folder don't show up in the UI
            // until sometime > Marshmallow and <= Oreo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent createFileActivity =
                        new Intent(Intent.ACTION_CREATE_DOCUMENT);
                createFileActivity.addCategory(
                        Intent.CATEGORY_OPENABLE);
                createFileActivity.setFlags(
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                createFileActivity.setType("text/xml");
                startActivityForResult(Intent.createChooser(
                                createFileActivity,
                                getString(R.string.ExportFileDialogTitle)),
                        SAF_PICK_XML_DIRECTORY);
            } else {
                String fileName = exportFileName.getText().toString();
                exportDirectoryName.setText(directoryName);
                exportDirectoryName.setEnabled(true);
                exportFileName.setEnabled(true);
                exportDirectoryRow.setVisibility(View.VISIBLE);
                prefs.setExportFile(directoryName + File.separator + fileName);
                exportRadioState = view.getId();
            }
        }
    }

    /** Called when the export file name is changed */
    private class FileNameChangedListener implements TextWatcher {
        @Override
        public void afterTextChanged(Editable s) {
            String directoryName = exportDirectoryName.getText().toString();
            String fileName = s.toString();
            prefs.setExportFile(
                directoryName + File.separator + fileName);
        }
        @Override
        public void beforeTextChanged(CharSequence s,
                int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s,
                int start, int before, int count) {}
    }

    /**
     * Called when the user toggles the &ldquo;Include private&rdquo; checkbox
     */
    private class IncludePrivateCheckedChangeListener
            implements CompoundButton.OnCheckedChangeListener {
        public void onCheckedChanged(
                CompoundButton b, boolean checked) {
            prefs.setExportPrivate(checked);
            findViewById(R.id.TableRowPasswordNotSetWarning)
                    .setVisibility((checked && !hasPassword)
                            ? View.VISIBLE : View.GONE);
        }
    }

    /** Called when the user clicks Export to start exporting the data */
    class ExportButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "ExportButtonOK.onClick");
            exportProgressMessage.setText("...");
            xableFormElements(false);
            String fullName = exportDirectoryName.getText().toString()
                    + File.separator + exportFileName.getText().toString();
            if (exportDocUri == null) {
                File exportFile = new File(fullName);
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
                            + exportFile.getPath(), iox);
                    xableFormElements(true);
                    showAlertDialog(R.string.ErrorExportFailed, iox.getMessage());
                    return;
                }
                // Make sure the parent directory exists
                if (!exportFile.getParentFile().exists()) {
                    try {
                        FileUtils.ensureParentDirectoryExists(exportFile);
                    } catch (SecurityException sx) {
                        Log.e(TAG, "Failed to create directory for export file", sx);
                        xableFormElements(true);
                        showAlertDialog(R.string.ErrorExportFailed,
                                sx.getMessage());
                        return;
                    }
                }
                fullName = exportFile.getAbsolutePath();
            }

            else { // Using Uri from Storage Access Framework
                fullName = exportDocUri.toString();
            }

            WorkRequest exportRequest = new OneTimeWorkRequest
                    .Builder(XMLExportWorker.class)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(new Data.Builder()
                            .putString(XMLExportWorker.XML_DATA_FILENAME, fullName)
                            .putBoolean(XMLExportWorker.EXPORT_PRIVATE,
                                    exportPrivateCheckBox.isChecked())
                            .build())
                    .build();
            workManager.enqueue(exportRequest);

            // Sanity checks
            if ((progressLiveData != null) && (progressObserver != null))
                progressLiveData.removeObserver(progressObserver);

            progressObserver = new ExportProgressObserver();
            progressLiveData = workManager.getWorkInfoByIdLiveData(
                    exportRequest.getId());
            progressLiveData.observeForever(progressObserver);
        }
    }

    /** Called when the user cancels the export */
    private class CancelClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "ExportButtonCancel.onClick");
            ExportActivity.this.finish();
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
                else if (results[i] == PackageManager.PERMISSION_DENIED) {
                    Log.i(TAG, "Write external storage permission denied!");
                }
            } else {
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

    /** Observer of an export worker&rsquo;s progress. */
    private class ExportProgressObserver implements Observer<WorkInfo> {
        @Override
        public void onChanged(@NonNull WorkInfo workInfo) {
            if (exportProgressBar == null)
                return;

            if (workInfo.getState().isFinished()) {
                Log.d("ExportProgressObserver", String.format(Locale.US,
                        "Export %s", workInfo.getState()));
                xableFormElements(true);
                progressLiveData.removeObserver(progressObserver);
                progressObserver = null;
                progressLiveData = null;
                if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                    ExportActivity.this.finish();
                } else {
                    String message = workInfo.getOutputData()
                            .getString("message");
                    showAlertDialog(R.string.ErrorExportFailed, message);
                }
                return;
            }

            Data progress = workInfo.getProgress();
            int max = progress.getInt(
                    ProgressBarUpdater.PROGRESS_MAX_COUNT, -1);
            if (max <= 0) {
                exportProgressBar.setIndeterminate(true);
            } else {
                exportProgressBar.setIndeterminate(false);
                exportProgressBar.setMax(max);
                exportProgressBar.setProgress(progress.getInt(
                        ProgressBarUpdater.PROGRESS_CURRENT_COUNT, 0));
            }
            exportProgressMessage.setText(progress.getString(
                    ProgressBarUpdater.PROGRESS_CURRENT_MODE));
        }
    }

}
