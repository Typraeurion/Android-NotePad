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
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
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
import com.xmission.trevin.android.notes.service.ZIPExportWorker;
import com.xmission.trevin.android.notes.service.ZIPExporter;
import com.xmission.trevin.android.notes.util.FileUtils;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * Displays options for exporting a backup of the Note Pad,
 * prior to actually attempting the export.
 *
 * @author Trevin Beattie
 */
public class ExportActivity extends AppCompatActivity {

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

    /**
     * Text-only row warning the user that private records
     * will not be encrypted in the export file.
     */
    TableRow passwordNotSetWarningRow = null;

    /**
     * Text-only row informing the user that they must unlock
     * encrypted records in order to export them to a ZIP file
     * using anything other than bundled encryption.
     */
    TableRow passwordReqWarningRow = null;

    /** The row containing the category filter, for ZIP exports only */
    TableRow zipCategoryRow = null;

    /** Category filter spinner; for ZIP exports only */
    Spinner zipCategoryList = null;

    // Used to map note categories from the database to a filter list
    CategoryFilterAdapter categoryAdapter = null;

    /**
     * The layout row for ZIP encryption type.  This is hidden
     * unless the selected file ends in &ldquo;.zip&rdquo; <i>and</i>
     * the &ldquo;Include Private&rdquo; box is checked.
     */
    TableRow exportZipEncryptionRow = null;

    /** The ZIP encryption type radio group */
    RadioGroup zipEncryptionRadioGroup = null;

    /** Radio button for no encryption on private notes */
    RadioButton encryptionButtonNone = null;

    /**
     * Radio button for copying encrypted notes directly into the ZIP
     * file without decrypting and re-encrypting using ZIP.
     */
    RadioButton encryptionButtonBundled = null;

    /** Radio button for weak (ZipCrypto) encryption on private notes */
    RadioButton encryptionButtonWeak = null;

    /**
     * Radio button for encrypting private notes with ZIP AES,
     * 128-bit key
     */
    RadioButton encryptionButtonAes128 = null;

    /**
     * Radio button for encrypting private notes with ZIP AES,
     * 256-bit key
     */
    RadioButton encryptionButtonAes256 = null;

    /**
     * The layout row for ZIP password.  This is hidden unless the
     * select file ends in &ldquo;.zip&rdquo;, the &ldquo;Include
     * Private&rdquo; box is checked, <i>and</i> the encryption type
     * is set to anything other than &ldquo;Keep internal storage
     * encryption&rdquo;.
     */
    TableRow exportZipPasswordRow = null;

    /** Entry box for the ZIP password. */
    EditText exportZipPassword = null;

    /**
     * Layout row for the &ldquo;Show Password&rdquo; checkbox.
     * This is only shown when the ZIP password row is visible.
     */
    TableRow exportShowZipPasswordRow = null;

    /** Checkbox for showing the ZIP password. */
    CheckBox exportShowZipPassword = null;

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

    /**
     * Launcher for starting Android&rsquo;s Open Document intent
     * for exporting notes.
     */
    private ActivityResultLauncher<Intent> openFileForExportLauncher = null;

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
        passwordNotSetWarningRow = findViewById(
                R.id.TableRowPasswordNotSetWarning);
        passwordReqWarningRow = findViewById(
                R.id.TableRowPasswordRequiredWarning);
        zipCategoryRow = findViewById(R.id.TableRowExportZipCategory);
        zipCategoryList = findViewById(R.id.ExportZipCategorySpinner);
        exportZipEncryptionRow = findViewById(
                R.id.TableRowExportZipEncryption);
        zipEncryptionRadioGroup = findViewById(
                R.id.ExportZipEncryptionRadioGroup);
        encryptionButtonNone = findViewById(
                R.id.ExportZipEncryptionRadioButtonNone);
        encryptionButtonBundled = findViewById(
                R.id.ExportZipEncryptionRadioButtonInternal);
        encryptionButtonWeak = findViewById(
                R.id.ExportZipEncryptionRadioButtonWeak);
        encryptionButtonAes128 = findViewById(
                R.id.ExportZipEncryptionRadioButtonAES128);
        encryptionButtonAes256 = findViewById(
                R.id.ExportZipEncryptionRadioButtonAES256);
        exportZipPasswordRow = findViewById(
                R.id.TableRowExportZipPassword);
        exportZipPassword = findViewById(R.id.ExportEditTextZipPassword);
        exportShowZipPasswordRow = findViewById(
                R.id.TableRowExportShowPassword);
        exportShowZipPassword = findViewById(R.id.ExportCheckBoxShowPassword);
        exportButton = findViewById(R.id.ExportButtonOK);
        cancelButton = findViewById(R.id.ExportButtonCancel);
        exportProgressBar = findViewById(R.id.ExportProgressBar);
        exportProgressMessage = findViewById(R.id.ExportTextProgressMessage);

        encryptor = StringEncryption.holdGlobalEncryption();
        prefs = NotePreferences.getInstance(this);

        workManager = WorkManager.getInstance(this);

        final NoteRepository repository = NoteRepositoryImpl.getInstance();
        categoryAdapter = new CategoryFilterAdapter(this, repository, false);
        zipCategoryList.setAdapter(categoryAdapter);
        categoryAdapter.registerDataSetObserver(new CategoryAdapterObserver());

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
            if (fullPath.startsWith("content://")) {
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

        switch (prefs.getExportZipEncryption()) {
            case BUNDLED_ENCRYPTION:
                encryptionButtonBundled.setChecked(true);
                break;
            case ZIP_CRYPTO:
                encryptionButtonWeak.setChecked(true);
                break;
            case AES_128:
                encryptionButtonAes128.setChecked(true);
                break;
            case AES_256:
                encryptionButtonAes256.setChecked(true);
                break;
            default:
                encryptionButtonNone.setChecked(true);
                break;
        }

        // Check for a password in the database.  If there isn't one,
        // show a warning if the "Include Private" option is checked.
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    repository.open(ExportActivity.this);
                    hasPassword = encryptor.hasPassword(repository);
                    repository.release(ExportActivity.this);
                    runOnUiThread(() -> updateZipVisibility());
                }
            });
        }

        updateZipVisibility();

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
        zipEncryptionRadioGroup.setOnCheckedChangeListener(
                new ZipEncryptionTypeChangedListener());
        exportZipPassword.addTextChangedListener(
                new ZipPasswordChangedListener());
        exportShowZipPassword.setOnCheckedChangeListener(
                new ShowZipPasswordCheckedChangeListener());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            openFileForExportLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    new ExportFileResultCallback());
        }

        exportButton.setOnClickListener(new ExportButtonOnClickListener());
        cancelButton.setOnClickListener(new CancelClickListener());

        getOnBackPressedDispatcher().addCallback(
                this, new ExportOnBackPressedCallback());
    }

    /**
     * Watch for a change from the category filter adapter.
     *  This is called when the adapter first loads its data.
     */
    private class CategoryAdapterObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            Log.d(TAG, ".CategoryAdapterObserver.onChanged");
            // Set the selected category to the same one selected for the note list.
            long selectedCategory = prefs.getSelectedCategory();
            int position = categoryAdapter
                    .getCategoryPosition(selectedCategory);
            if (categoryAdapter.getItemId(position) != selectedCategory) {
                Log.w(TAG, "No spinner item found for category ID "
                        + selectedCategory);
                return;
            }
            zipCategoryList.setSelection(position);
        }
    }

    /**
     * Called when the user selects an import file through
     * the Storage Access Framework
     */
    @RequiresApi(Build.VERSION_CODES.N)
    class ExportFileResultCallback
            implements ActivityResultCallback<ActivityResult> {
        @Override
        public void onActivityResult(ActivityResult result) {
            String resultCodeStr = (result.getResultCode() == RESULT_OK)
                    ? "OK" : (result.getResultCode() == RESULT_CANCELED)
                    ? "CANCELED" : Integer.toString(result.getResultCode());
            Log.d(TAG, String.format(Locale.US,
                    "ExportFileResultCallback.onActivityResult(%s / %s)",
                    resultCodeStr, result.getData()));
            if (result.getResultCode() == Activity.RESULT_CANCELED) {
                // Revert back to the previous state
                exportRadioGroup.check(exportRadioState);
                return;
            }
            if (result.getResultCode() != Activity.RESULT_OK) {
                Log.w(TAG, "Ignoring unexpected result code!");
                return;
            }
            if ((result.getData() == null) ||
                    (result.getData().getData() == null)) {
                Log.w(TAG, String.format(Locale.US,
                        "No data returned from result!  Reverting to %s.",
                        (exportRadioState == R.id.ExportFolderRadioButtonPrivate)
                                ? "private storage" : (exportDocUri == null)
                                ? "shared storage" : exportDocUri.toString()));
                exportRadioGroup.check(exportRadioState);
                return;
            }
            Uri oldUri = exportDocUri;
            exportDocUri = result.getData().getData();
            getContentResolver().takePersistableUriPermission(exportDocUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // The path may include the protocol, e.g. "raw:"
            final Pattern DIR_FILE_PATTERN = Pattern.compile("(.+:)?((.*)"
                    + File.separator + ")?(.+)");
            Matcher m = DIR_FILE_PATTERN.matcher(
                    FileUtils.getFileNameFromUri(ExportActivity.this, exportDocUri));
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
            updateZipVisibility();
        }
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
        StringEncryption.releaseGlobalEncryption(this);
        super.onDestroy();
    }

    /**
     * Intercept the back button to prevent it from happening
     * in the middle of an export.
     */
    private class ExportOnBackPressedCallback extends OnBackPressedCallback {
        ExportOnBackPressedCallback() {
            super(true);
        }

        @Override
        public void handleOnBackPressed() {
            if (cancelButton.isEnabled()) {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
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

    /**
     * SAF may add a parenthesized version if the user enters
     * an existing filename, so we have to strip that out.
     */
    static final Pattern XML_EXTENSION_PATTERN = Pattern.compile(
            "(?i).*\\.xml(\\s\\(\\d+\\))?$");

    /**
     * SAF may add a parenthesized version if the user enters
     * an existing filename, so we have to strip that out.
     */
    static final Pattern ZIP_EXTENSION_PATTERN = Pattern.compile(
            "(?i).*\\.zip(\\s\\(\\d+\\))?$");

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
            String directoryName = FileUtils
                    .getDefaultStorageDirectory(ExportActivity.this);
            String fileName = exportFileName.getText().toString();
            if (!(XML_EXTENSION_PATTERN.matcher(fileName).find()
                    || ZIP_EXTENSION_PATTERN.matcher(fileName).find())) {
                // The Storage Access Framework may replace the
                // actual file name with a temporary substitute;
                // revert to the default file name.
                fileName = "notes.xml";
                // If the actual file name ended with ".zip",
                // this needs to be "notes.zip".
                if (exportDocUri != null) {
                    if (ZIP_EXTENSION_PATTERN.matcher(FileUtils
                            .getFileNameFromUri(ExportActivity.this,
                                    exportDocUri)).find())
                        fileName = "notes.zip";
                }
                exportFileName.setText(fileName);
            }
            exportDocUri = null;
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
                createFileActivity.setType("*/*");
                createFileActivity.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] { "application/xml",
                                "application/zip", "text/xml" });
                openFileForExportLauncher.launch(Intent.createChooser(
                                createFileActivity,
                                getString(R.string.ExportFileDialogTitle)));
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
            updateZipVisibility();
        }
        @Override
        public void beforeTextChanged(CharSequence s,
                int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s,
                int start, int before, int count) {}
    }

    /**
     * Determine whether the currently selected file is a ZIP file
     * or not (presumably XML).  This is used both for updating the
     * visibility and state of ZIP-related UI elements and when the
     * export is finally started to determine which export worker
     * to run.
     *
     * @return {@code true} if the current export file has a MIME
     * type of &ldquo;application/zip&rdquo; or ends with the
     * extension &ldquo;.zip&rdquo;, {@code false} otherwise.
     */
    private boolean isZipExport() {
        // If we have a content URI, use that to determine the file type.
        if (exportDocUri != null) {
            String mime = FileUtils.getMimeTypeFromUri(this, exportDocUri);
            if ((mime != null) && !mime.endsWith("*"))
                return mime.endsWith("zip");
            // Fall back on the display name
            String realName = FileUtils.getFileNameFromUri(
                    this, exportDocUri);
            return ZIP_EXTENSION_PATTERN.matcher(realName).matches();
        }
        // Go by the name entered in the filename field
        String fileName = exportFileName.getText().toString();
        return ZIP_EXTENSION_PATTERN.matcher(fileName).matches();
    }

    /**
     * Set the visibility of the ZIP encryption elements according
     * to whether the export filename ends in &ldquo;.zip&rdquo;.
     */
    private void updateZipVisibility() {
        boolean showZipOptions = isZipExport();
        // For ZIP files, the category filter should be shown.
        zipCategoryRow.setVisibility(
                showZipOptions ? View.VISIBLE : View.GONE);

        // If we aren't exporting private records, all password
        // warnings and ZIP encryption fields are hidden.
        if (!exportPrivateCheckBox.isChecked()) {
            passwordNotSetWarningRow.setVisibility(View.GONE);
            passwordReqWarningRow.setVisibility(View.GONE);
            exportZipEncryptionRow.setVisibility(View.GONE);
            exportZipPasswordRow.setVisibility(View.GONE);
            exportShowZipPasswordRow.setVisibility(View.GONE);
            return;
        }

        boolean encryptedRecordsLocked = hasPassword && !encryptor.hasKey();
        // If this is not a ZIP file, all we need
        // to worry about is the "password has not been set" warning.
        if (!showZipOptions) {
            passwordNotSetWarningRow.setVisibility(
                    hasPassword ? View.GONE : View.VISIBLE);
            passwordReqWarningRow.setVisibility(View.GONE);
            exportZipEncryptionRow.setVisibility(View.GONE);
            exportZipPasswordRow.setVisibility(View.GONE);
            exportShowZipPasswordRow.setVisibility(View.GONE);
            return;
        }

        // For ZIP files, warn the user if encrypted records are locked
        passwordReqWarningRow.setVisibility(
                encryptedRecordsLocked ? View.VISIBLE : View.GONE);
        // The ZIP Encryption radio group should be shown,
        exportZipEncryptionRow.setVisibility(View.VISIBLE);
        // but not the Bundled Encryption button if we don't have a password.
        encryptionButtonBundled.setEnabled(hasPassword);
        encryptionButtonBundled.setVisibility(
                hasPassword ? View.VISIBLE : View.GONE);

        // If the user hasn't unlocked encrypted notes,
        // disable the "No encryption" and ZIP encryption options.
        encryptionButtonNone.setEnabled(!encryptedRecordsLocked);
        encryptionButtonWeak.setEnabled(!encryptedRecordsLocked);
        encryptionButtonAes128.setEnabled(!encryptedRecordsLocked);
        encryptionButtonAes256.setEnabled(!encryptedRecordsLocked);
        // If the current selection got disabled,
        // switch to an available button.
        if (!hasPassword && encryptionButtonBundled.isChecked())
            encryptionButtonNone.setChecked(true);
        if (encryptedRecordsLocked && !encryptionButtonBundled.isChecked())
            encryptionButtonBundled.setChecked(true);

        // The ZIP Password field should only be shown if the ZIP Encryption
        // buttons are enabled *and* either of them are selected.
        ZIPExporter.EncryptionType encType =
                prefs.getExportZipEncryption();
        boolean showZipPassword;
        switch (encType) {
            case NO_ENCRYPTION:
            case BUNDLED_ENCRYPTION:
                showZipPassword = false;
                break;
            default:
                showZipPassword = !encryptedRecordsLocked;
        }
        exportZipPasswordRow.setVisibility(
                showZipPassword ? View.VISIBLE : View.GONE);
        exportShowZipPasswordRow.setVisibility(
                showZipPassword ? View.VISIBLE : View.GONE);

        /*
         * The "No password has been set" warning has the most complex
         * conditions.  It is shown if: (there is no password in the DB
         *  -or- encrypted records are unlocked), *and either* (there is
         * no password in the DB *and either* (the encryption type is -not-
         * ZIP, -or- no password has been entered in the ZIP Password field),
         * -or- encrypted records are unlocked *and either* (the encryption
         * type is "No Encryption" -or- (the encryption type is ZIP *and*
         * no password has been entered in the ZIP Password field))).
         */
        int passwordWarningVisibility = View.GONE;
        boolean hasZipPassword = (exportZipPassword.getText().length() > 0);
        if (!encryptedRecordsLocked) {
            switch (encType) {
                case NO_ENCRYPTION:
                    passwordWarningVisibility = View.VISIBLE;
                    break;
                case BUNDLED_ENCRYPTION:
                    passwordWarningVisibility = hasPassword
                            ? View.GONE: View.VISIBLE;
                    break;
                default:
                    passwordWarningVisibility = hasZipPassword
                            ? View.GONE : View.VISIBLE;
            }
        }
        passwordNotSetWarningRow.setVisibility(passwordWarningVisibility);
    }

    /**
     * Called when the user toggles the &ldquo;Include private&rdquo; checkbox
     */
    private class IncludePrivateCheckedChangeListener
            implements CompoundButton.OnCheckedChangeListener {
        public void onCheckedChanged(
                @NonNull CompoundButton b, boolean checked) {
            prefs.setExportPrivate(checked);
            updateZipVisibility();
        }
    }

    /**
     * Called when the user changes the ZIP encryption type.
     */
    private class ZipEncryptionTypeChangedListener
    implements RadioGroup.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                @NonNull RadioGroup group, int which) {
            Log.d(TAG, String.format(Locale.US,
                    "ZipEncryptionTypeChangedListener.onCheckedChanged(%d)",
                    which));
            ZIPExporter.EncryptionType newType =
                    ZIPExporter.EncryptionType.NO_ENCRYPTION;
            if (which == R.id.ExportZipEncryptionRadioButtonInternal)
                newType = ZIPExporter.EncryptionType.BUNDLED_ENCRYPTION;
            else if (which == R.id.ExportZipEncryptionRadioButtonWeak)
                newType = ZIPExporter.EncryptionType.ZIP_CRYPTO;
            else if (which == R.id.ExportZipEncryptionRadioButtonAES128)
                newType = ZIPExporter.EncryptionType.AES_128;
            else if (which == R.id.ExportZipEncryptionRadioButtonAES256)
                newType = ZIPExporter.EncryptionType.AES_256;
            prefs.setExportZipEncryption(newType);
            updateZipVisibility();
        }
    }

    /**
     * Called when the user enters text in the ZIP password field.
     */
    private class ZipPasswordChangedListener
            implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s,
                int start, int count, int after) {}
        @Override
        public void onTextChanged(CharSequence s,
                int start, int before, int count) {}
        @Override
        public void afterTextChanged(Editable s) {
            updateZipVisibility();
        }
    }

    /**
     * Called when the user toggles the &ldquo;Show Password&rdquo;
     * checkbox for the ZIP password.
     */
    private class ShowZipPasswordCheckedChangeListener
            implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                @NonNull CompoundButton b, boolean checked) {
            int oldType = exportZipPassword.getInputType();
            if (checked)
                oldType &= ~InputType.TYPE_TEXT_VARIATION_PASSWORD;
            else
                oldType |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
            exportZipPassword.setInputType(oldType);
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

            WorkRequest exportRequest;
            if (isZipExport()) {
                ZIPExporter.EncryptionType encryptionType =
                        prefs.getExportZipEncryption();
                Data.Builder dataBuilder = new Data.Builder()
                        .putString(ZIPExportWorker.ZIP_DATA_FILENAME, fullName)
                        .putBoolean(ZIPExportWorker.EXPORT_PRIVATE,
                                exportPrivateCheckBox.isChecked())
                        .putLong(ZIPExportWorker.EXPORT_CATEGORY,
                                zipCategoryList.getSelectedItemId());
                switch (encryptionType) {
                    case NO_ENCRYPTION:
                    case BUNDLED_ENCRYPTION:
                        break;
                    default:
                        if (exportZipPassword.getText().length() > 0) {
                            dataBuilder.putString(ZIPExportWorker.ZIP_PASSWORD,
                                    exportZipPassword.getText().toString());
                        } else {
                            // Don't encrypt if no password was given
                            encryptionType = ZIPExporter.EncryptionType.NO_ENCRYPTION;
                        }
                }
                dataBuilder.putString(ZIPExportWorker.ZIP_ENCRYPTION_TYPE,
                        encryptionType.name());
                exportRequest = new OneTimeWorkRequest
                        .Builder(ZIPExportWorker.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(dataBuilder.build())
                        .build();
            } else {
                exportRequest = new OneTimeWorkRequest
                        .Builder(XMLExportWorker.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(new Data.Builder()
                                .putString(XMLExportWorker.XML_DATA_FILENAME, fullName)
                                .putBoolean(XMLExportWorker.EXPORT_PRIVATE,
                                        exportPrivateCheckBox.isChecked())
                                .build())
                        .build();
            }
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

        super.onRequestPermissionsResult(code, permissions, results);

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
