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

import static com.xmission.trevin.android.notes.NoteApplication.SILENT_CHANNEL_ID;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.service.ZIPImporter.ImportType;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * This class imports Note Pad notes from a ZIP file on external storage.
 *
 * @author Trevin Beattie
 */
public class ZIPImportWorker extends Worker implements ProgressBarUpdater {

    public static final String TAG = "ZIPImportWorker";

    /**
     * The key of the input data that holds
     * the location of the notes.zip file
     */
    public static final String ZIP_DATA_FILENAME = "ZIPDataFileName";

    /**
     * The key of the input data that indicates how to merge
     * imported records.
     */
    public static final String IMPORT_TYPE = "ZIPImportType";

    /**
     * The key of the input data that indicates whether to
     * import private records.
     */
    public static final String IMPORT_PRIVATE = "ZIPImportPrivate";

    /**
     * The key of the optional input data that holds a password
     * for decrypting ZIP entries.
     */
    public static final String ZIP_PASSWORD = "ZIPPassword";

    /**
     * Notification ID to use when running this worker in the foreground
     * (Oreo or later).
     */
    private static final int FG_NOTIFICATION_ID = 527500164;

    /** The name of the ZIP file being read */
    private final String importFileName;

    /**
     * Input stream from which we will be reading the ZIP file.
     * This is only used if we&rsquo;re using Android&rsquo;s
     * Storage Access Framework.
     * <p>
     * <b>Caution:</b> in order to properly use the Storage Access
     * Framework and check for access errors, the file is opened
     * in this class&rsquo; constructor; the actual read operation
     * does not occur until {@link #doWork()} is called, so the
     * file may remain open for an indeterminate amount of time.
     * </p>
     */
    private InputStream inStream;

    /**
     * Local ZIP file.  If using Android&rsquo;s Storage Access Framework,
     * this is a temporary file in our private storage directory that we
     * copy the ZIP file to first from the {@link #inStream}, and then
     * subsequently use for random access reading.
     */
    private final File localZipFile;

    private final ImportType importType;

    /** Whether to import private records */
    private final boolean importPrivate;

    private char[] zipPassword = null;

    /** Internal time when we last updated the async progress */
    private long lastProgressTimeNano;

    private String lastProgressMessage = null;

    @NonNull
    private final Context context;

    @NonNull
    private final NoteRepository repository;

    /** Handler for making calls involving the UI */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ZIPImporter importer;

    /**
     * Initialize the ZIPImportWorker using the standard system services
     * and app class instances.
     *
     * @param context the application context
     * @param params Parameters to set up the internal state of this worker
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    public ZIPImportWorker(@NonNull Context context,
                           @NonNull WorkerParameters params)
        throws IllegalArgumentException, IOException {
        super(context, params);
        Log.d(TAG, String.format(Locale.US, "Initialization for %s",
                context.getClass().getName()));
        this.context = context;
        NotePreferences preferences = NotePreferences.getInstance(context);
        repository = NoteRepositoryImpl.getInstance();

        if (!params.getInputData().hasKeyWithValueOfType(
                ZIP_DATA_FILENAME, String.class))
            throw new IllegalArgumentException("No ZIP input file provided");
        if (!params.getInputData().hasKeyWithValueOfType(
                IMPORT_TYPE, String.class))
            throw new IllegalArgumentException("No import type provided");
        try {
            importType = ImportType.valueOf(
                    params.getInputData().getString(IMPORT_TYPE));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid import type", e);
        }
        importPrivate = params.getInputData().getBoolean(
                IMPORT_PRIVATE, false);

        if (importPrivate && params.getInputData().hasKeyWithValueOfType(
                ZIP_PASSWORD, String.class))
            zipPassword = params.getInputData()
                    .getString(ZIP_PASSWORD).toCharArray();

        importFileName = params.getInputData().getString(ZIP_DATA_FILENAME);
        if (importFileName.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(importFileName);
                // We will need to copy this a local file first for random access
                localZipFile = File.createTempFile("notes-", ".zip",
                        context.getCacheDir());
                localZipFile.deleteOnExit();
                inStream = context.getContentResolver()
                        .openInputStream(contentUri);
            } catch (FileNotFoundException fe) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for reading", importFileName), fe);
                showToast(context.getString(
                        R.string.ErrorImportNotFound, importFileName));
                throw fe;
            } catch (IOException ioe) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for reading", importFileName), ioe);
                showToast(context.getString(
                        R.string.ErrorImportPermissionDenied, importFileName));
                throw ioe;
            }
        }

        else {
            localZipFile = new File(importFileName);
            if (localZipFile.exists()) {
                if (!localZipFile.canRead()) {
                    Log.w(TAG, String.format(Locale.US,
                            "Cannot read %s", importFileName));
                    showToast(context.getString(
                            R.string.ErrorImportPermissionDenied, importFileName));
                    throw new IOException(String.format(Locale.US,
                            "Cannot read %s", importFileName));
                }
            } else {
                showToast(context.getString(
                        R.string.ErrorImportNotFound, importFileName));
                throw new FileNotFoundException(String.format(Locale.US,
                        "No such file: %s", importFileName));
            }
        }

        importer = new ZIPImporter(preferences, repository, this);
        // Initialize string resources on the importer for the progress bar
        importer.setModeText(ZIPImporter.OpMode.START,
                context.getString(R.string.ProgressMessageStart));
        importer.setModeText(ZIPImporter.OpMode.SETTINGS,
                context.getString(R.string.ProgressMessageImportSettings));
        importer.setModeText(ZIPImporter.OpMode.CATEGORIES,
                context.getString(R.string.ProgressMessageImportCategories));
        importer.setModeText(ZIPImporter.OpMode.ITEMS,
                context.getString(R.string.ProgressMessageImportItems));
        importer.setModeText(ZIPImporter.OpMode.FINISH,
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
        char[] currentPassword = null;
        if (importPrivate) try {
            StringEncryption globalEncryption =
                    StringEncryption.holdGlobalEncryption();
            if (globalEncryption.hasKey()) {
                currentPassword = Arrays.copyOf(globalEncryption.getPassword(),
                        globalEncryption.getPassword().length);
            }
        } finally {
            StringEncryption.releaseGlobalEncryption(context);
        }
        updateProgress(context.getString(
                R.string.ProgressMessageStart), 0, 0, false);
        repository.open(context);
        try {
            if (inStream != null) {
                // Need to copy the stream to the local file
                try (FileOutputStream out = new FileOutputStream(localZipFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = inStream.read(buffer)) > 0)
                        out.write(buffer, 0, len);
                }
                inStream.close();
            }
            importer.importData(importFileName, localZipFile, importType,
                    importPrivate, zipPassword, currentPassword);
            if (inStream != null) {
                // Now we can delete the temp file.
                localZipFile.delete();
            }
            return Result.success();
        } catch (Throwable e) {
            Log.e(TAG, "Error importing data from ZIP!", e);
            showToast(e.getMessage());
            return Result.failure(new Data.Builder()
                    .putString("Exception", e.getClass().getCanonicalName())
                    .putString("message", e.getMessage())
                    .build());
        } finally {
            long now = System.nanoTime();
            repository.release(context);
            if (zipPassword != null)
                Arrays.fill(zipPassword, (char) 0);
            Log.d(TAG, String.format("Finished work in %.4f seconds",
                    (now - startTimeNano) / 1.0e+9));
        }
    }

    /**
     * Update the async progress indicator.
     *
     * @param modeString the current mode of operation (reading,
     *                   adding categories, adding items)
     * @param currentCount the number of items exported so far
     * @param totalCount the total number of items to be exported
     * @param throttle if {@code true}, skip updating the progress
     *                 if it&rsquo;s been less than 250 ms since
     *                 we last posted our progress.
     */
    @Override
    public void updateProgress(String modeString,
                               int currentCount, int totalCount,
                               boolean throttle) {
        lastProgressMessage = modeString;
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
                Toast.makeText(context, message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Return a notification of this worker when it&rsquo;s run
     * in the foreground.
     */
    @Override
    @NonNull
    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
        Log.d(TAG, ".getForegroundInfoAsync");
        Notification busyNotification = new NotificationCompat
                .Builder(context, SILENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.stat_note)
                .setContentText(context.getString(R.string.app_name))
                .setContentText((lastProgressMessage == null)
                        ? context.getString(R.string.ImportFileDialogTitle)
                        : lastProgressMessage)
                .setOnlyAlertOnce(true)
                .build();
        // ForegroundInfo handles backward compatibility for the service type on pre-API-29 devices.
        @SuppressLint("InlinedApi")
        final ForegroundInfo info = new ForegroundInfo(
                FG_NOTIFICATION_ID, busyNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return CallbackToFutureAdapter.getFuture(new CallbackToFutureAdapter
                .Resolver<ForegroundInfo>() {
            @Override
            public String attachCompleter(@NonNull CallbackToFutureAdapter
                    .Completer<ForegroundInfo> completer) {
                completer.set(info);
                return TAG + " foreground info";
            }
        });
    }

}
