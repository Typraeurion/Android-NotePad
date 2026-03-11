/*
 * Copyright © 2026 Trevin Beattie
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
package com.xmission.trevin.android.notes.service;

//import android.app.Notification;
import android.content.Context;
//import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
//import androidx.core.app.NotificationCompat;
import androidx.work.Data;
//import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
//import androidx.work.impl.utils.futures.SettableFuture;

//import com.google.common.util.concurrent.ListenableFuture;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.service.XMLImporter.ImportType;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * This class imports the note list from an XML file on external storage.
 *
 * @author Trevin Beattie
 */
public class XMLImportWorker extends Worker implements ProgressBarUpdater {

    public static final String TAG = "XMLImportWorker";

    /**
     * The key of the input data that holds
     * the location of the notes.xml file
     */
    public static final String XML_DATA_FILENAME = "XMLDataFileName";

    /** The key of the input data that holds the import type (enum name) */
    public static final String XML_IMPORT_TYPE = "XMLImportType";

    /**
     * The key of the input data that indicates
     * whether to import private records (boolean)
     */
    public static final String IMPORT_PRIVATE = "XMLImportPrivate";

    /** The key of the input data that holds the password for the backup */
    public static final String XML_PASSWORD = "XMLImportPassword";

    /**
     * Notification ID to use when running this worker in the foreground
     * (Oreo or later).
     */
    private static final int FG_NOTIFICATION_ID = -1356076293;

    /** The name of the XML file being read */
    private final String importFileName;

    /**
     * Input stream from which we will be reading the XML document.
     * <p>
     * <b>Caution:</b> in order to properly use the Storage Access
     * Framework and check for access errors, the file is opened
     * in this class&rsquo; constructor; the actual read operation
     * does not occur until {@link #doWork()} is called, so the
     * file may remain open for an indeterminate amount of time.
     * </p>
     */
    private final InputStream xmlStream;

    /** How to merge items from the XML file with those in the database. */
    private final ImportType importType;

    /** Whether to import private records */
    private final boolean importPrivate;

    /** The password used to decrypt records in the XML file */
    private String xmlPassword = null;

    /**
     * The password used to encrypt private records in the database.
     * If {@code null}, private records will not be encrypted.
     */
    private String currentPassword = null;

    /** Internal time when we last updated the async progress */
    private long lastProgressTimeNano;

    private String lastProgressMessage = null;

    @NonNull
    private final Context context;

    @NonNull
    private final NotePreferences preferences;

    @NonNull
    private final NoteRepository repository;

    /** Handler for making calls involving the UI */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * Initialise the XMLImportWorker using the standard system services
     * and app class instances.
     *
     * @param context the application context
     * @param params Parameters to set up the internal state of this worker
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    public XMLImportWorker(@NonNull Context context,
                           @NonNull WorkerParameters params)
        throws IllegalArgumentException, IOException {
        super(context, params);
        Log.d(TAG, String.format(Locale.US, "Default initialization for %s",
                context.getClass().getName()));
        this.context = context;
        preferences = NotePreferences.getInstance(context);
        repository = NoteRepositoryImpl.getInstance();

        if (!params.getInputData().hasKeyWithValueOfType(
                XML_DATA_FILENAME, String.class))
            throw new IllegalArgumentException(
                    "No XML input file provided");
        if (!params.getInputData().hasKeyWithValueOfType(
                XML_IMPORT_TYPE, String.class))
            throw new IllegalArgumentException("Import type not specified");
        try {
            importType = ImportType.valueOf(params.getInputData()
                    .getString(XML_IMPORT_TYPE));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid import type", e);
        }
        importPrivate = params.getInputData().getBoolean(
                IMPORT_PRIVATE, false);

        /*
         * Note: We don't verify either password when initializing the
         * worker; we can't check the XML password until we are in
         * the middle of reading the file, and there could be a race
         * condition with the current password where it may change
         * between here and importing data.  These checks will be done
         * by the import method inside a database transaction.
         */
        if (params.getInputData().hasKeyWithValueOfType(
                XML_PASSWORD, String.class))
            xmlPassword = params.getInputData().getString(XML_PASSWORD);

        if (importPrivate) {
            StringEncryption se = StringEncryption.holdGlobalEncryption();
            if (se.hasKey())
                currentPassword = new String(se.getPassword());
        }

        importFileName = params.getInputData().getString(XML_DATA_FILENAME);
        if (importFileName.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(importFileName);
                xmlStream = context.getContentResolver()
                        .openInputStream(contentUri);
            } catch (FileNotFoundException fe) {
                Log.e(TAG, String.format(Locale.US,
                        "Content URI %s does not exist", importFileName), fe);
                showToast(context.getString(
                        R.string.ErrorImportNotFound, importFileName));
                throw fe;
            } catch (IOException e) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for reading", importFileName), e);
                showToast(context.getString(
                        R.string.ErrorImportCantRead, importFileName));
                throw e;
            }
        }

        else {
            File xmlFile = new File(importFileName);
            if (!xmlFile.exists()) {
                Log.e(TAG, String.format(Locale.US,
                        "File %s does not exist", importFileName));
                showToast(context.getString(
                        R.string.ErrorImportNotFound, importFileName));
                throw new FileNotFoundException(importFileName);
            }
            if (!xmlFile.canRead()) {
                Log.e(TAG, String.format(Locale.US,
                        "Cannot read %s", importFileName));
                showToast(context.getString(
                        R.string.ErrorImportPermissionDenied, importFileName));
                throw new IOException(String.format(Locale.US,
                        "Cannot read %s", xmlFile.getAbsolutePath()));
            }
            try {
                xmlStream = new FileInputStream(xmlFile);
            } catch (IOException e) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for reading", importFileName), e);
                showToast(context.getString(
                        R.string.ErrorImportCantRead, importFileName));
                throw e;
            }
        }

        // Initialize string resources on the importer for the progress bar
        XMLImporter.setModeText(XMLImporter.OpMode.START,
                context.getString(R.string.ProgressMessageStart));
        XMLImporter.setModeText(XMLImporter.OpMode.SETTINGS,
                context.getString(R.string.ProgressMessageImportSettings));
        XMLImporter.setModeText(XMLImporter.OpMode.CATEGORIES,
                context.getString(R.string.ProgressMessageImportCategories));
        XMLImporter.setModeText(XMLImporter.OpMode.ITEMS,
                context.getString(R.string.ProgressMessageImportItems));
        XMLImporter.setModeText(XMLImporter.OpMode.FINISH,
                context.getString(R.string.ProgressMessageFinish));
    }

    /**
     * Main entry point of the worker.
     */
    @Override
    @NonNull
    public Result doWork() {
        Log.d(TAG, ".doWork");
        long startTimeNano = System.nanoTime();
        lastProgressTimeNano = startTimeNano;
        updateProgress(context.getString(
                R.string.ProgressMessageStart), 0, 0, false);
        repository.open(context);
        try {
            XMLImporter.importData(preferences, repository,
                    importFileName, xmlStream, importType, importPrivate,
                    xmlPassword, currentPassword, this);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error importing data from XML!", e);
            showToast(e.getMessage());
            return Result.failure(new Data.Builder()
                    .putString("Exception", e.getClass().getCanonicalName())
                    .putString("message", e.getMessage())
                    .build());
        } finally {
            long now = System.nanoTime();
            repository.release(context);
            Log.d(TAG, String.format("Finished work in %.4f seconds",
                    (now - startTimeNano) / 1.0e+9));
        }
    }

    /**
     * Update the async progress indicator.
     *
     * @param modeString the current mode of operation (reading,
     *                   adding categories, adding items)
     * @param currentCount the number of items imported so far
     * @param totalCount the total number of items to be imported
     * @param throttle if {@code true}, skip updating the progress
     *                 if it&rsquo;s been less than 250 ms since
     *                 we last posted our progress.
     */
    @Override
    public void updateProgress(String modeString,
                               int currentCount, int totalCount,
                               boolean throttle) {
        if (throttle) {
            long now = System.nanoTime();
            if ((now - lastProgressTimeNano) < 250000000L)
                return;
            lastProgressTimeNano = now;
        }
        Data progressData = new Data.Builder()
                .putString(PROGRESS_CURRENT_MODE, modeString)
                .putInt(PROGRESS_MAX_COUNT, totalCount)
                .putInt(PROGRESS_CURRENT_COUNT, currentCount)
                .build();
        setProgressAsync(progressData);
    }

    /**
     * Show a toast message.  This must be done on the UI thread.
     *
     * @param message the message to toast
     */
    private void showToast(String message) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /* FIXME: Check whether we need this
//    /**
//     * Return a notification of this worker when it&rsquo;s run
//     * in the foreground.
//     */
//    @Override
//    @NonNull
//    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
//        Log.d(TAG, ".getForegroundInfoAsync");
//        Notification busyNotification = new NotificationCompat
//                .Builder(context, SILENT_CHANNEL_ID)
//                .setSmallIcon(R.drawable.stat_notes)
//                .setContentText(context.getString(R.string.app_name))
//                .setContentText((lastProgressMessage == null)
//                        ? context.getString(R.string.ImportFileDialogTitle)
//                        : lastProgressMessage)
//                .setOnlyAlertOnce(true)
//                .build();
//        ForegroundInfo info = new ForegroundInfo(
//                FG_NOTIFICATION_ID, busyNotification,
//                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
//        SettableFuture<ForegroundInfo> future = SettableFuture.create();
//        future.set(info);
//        return future;
//    }

}
