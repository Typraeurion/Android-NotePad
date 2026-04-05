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
import com.xmission.trevin.android.notes.util.PasswordRequiredException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * This class exports the Note Pad notes to a ZIP file on external storage.
 *
 * @author Trevin Beattie
 */
public class ZIPExportWorker extends Worker implements ProgressBarUpdater {

    public static final String TAG = "ZIPExportWorker";

    /**
     * The key of the input data that holds
     * the location of the notes.zip file
     */
    public static final String ZIP_DATA_FILENAME = "ZIPDataFileName";

    /**
     * The key of the input data that indicates whether to
     * export all categories or just a single category of notes.
     */
    public static final String EXPORT_CATEGORY = "ZIPExportCategory";

    /**
     * The key of the input data that indicates whether to
     * export private records.
     */
    public static final String EXPORT_PRIVATE = "ZIPExportPrivate";

    /**
     * The key of the optional input data that holds the type of
     * encryption to use in the ZIP file for private notes
     * (enum name).
     */
    public static final String ZIP_ENCRYPTION_TYPE = "ZIPEncryptionType";

    /**
     * The key of the optional input data that holds a password
     * for encrypting ZIP entries.
     */
    public static final String ZIP_PASSWORD = "ZIPPassword";

    /**
     * Notification ID to use when running this worker in the foreground
     * (Oreo or later).
     */
    private static final int FG_NOTIFICATION_ID = 1210327551;

    /**
     * Output stream where we be writing the ZIP file.
     * This is only used if we&rsquo;re using Android&rsquo;s
     * Storage Access Framework.
     * <p>
     * <b>Caution:</b> in order to properly use the Storage Access
     * Framework and check for access errors, the file is opened
     * in this class&rsquo; constructor; the actual write operation
     * does not occur until {@link #doWork()} is called, so the
     * file may remain open for an indeterminate amount of time.
     * </p>
     */
    private final OutputStream outStream;

    /**
     * Local ZIP file.  If using Android&rsquo;s Storage Access Framework,
     * this is a temporary file in our private storage directory that we
     * use to generate the ZIP file first, and then subsequently copy
     * its contents into the {@link #outStream}.
     */
    private final File localZipFile;

    /** Which category of notes to export */
    private long exportCategory = NotePreferences.ALL_CATEGORIES;

    /** Whether to export private records */
    private final boolean exportPrivate;

    private ZIPExporter.EncryptionType encryptionType;

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

    private final ZIPExporter exporter;

    /**
     * Initialize the ZIPExportWorker using the standard system services
     * and app class instances.
     *
     * @param context the application context
     * @param params Parameters to set up the internal state of this worker
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    public ZIPExportWorker(@NonNull Context context,
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
            throw new IllegalArgumentException("No ZIP output file provided");
        if (params.getInputData().hasKeyWithValueOfType(
                EXPORT_CATEGORY, Long.class))
            exportCategory = params.getInputData().getLong(
                    EXPORT_CATEGORY, NotePreferences.ALL_CATEGORIES);
        exportPrivate = params.getInputData().getBoolean(
                EXPORT_PRIVATE, false);

        if (exportPrivate && params.getInputData().hasKeyWithValueOfType(
                ZIP_ENCRYPTION_TYPE, String.class)) {
            String typeName = params.getInputData().getString(
                    ZIP_ENCRYPTION_TYPE);
            try {
                encryptionType = ZIPExporter.EncryptionType.valueOf(typeName);
            } catch (IllegalArgumentException iax) {
                throw new IllegalArgumentException(
                        "Invalid encryption type: " + typeName);
            }
        }
        if (exportPrivate && params.getInputData().hasKeyWithValueOfType(
                ZIP_PASSWORD, String.class))
            zipPassword = params.getInputData()
                    .getString(ZIP_PASSWORD).toCharArray();

        String fileLocation = params.getInputData().getString(ZIP_DATA_FILENAME);
        if (fileLocation.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(fileLocation);
                // We need a local file first for random access
                localZipFile = File.createTempFile("notes-", ".zip",
                        context.getCacheDir());
                localZipFile.deleteOnExit();
                outStream = context.getContentResolver()
                        .openOutputStream(contentUri, "wt");
            } catch (FileNotFoundException fe) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for writing", fileLocation), fe);
                showToast(context.getString(
                        R.string.ErrorExportCantMkdirs, fileLocation));
                throw fe;
            } catch (IOException ioe) {
                Log.e(TAG, String.format(Locale.US,
                        "Failed to open %s for writing", fileLocation), ioe);
                showToast(context.getString(
                        R.string.ErrorExportPermissionDenied, fileLocation));
                throw ioe;
            }
        }

        else {
            localZipFile = new File(fileLocation);
            if (localZipFile.exists()) {
                if (!localZipFile.canWrite()) {
                    Log.w(TAG, String.format(Locale.US,
                            "Cannot write to %s", fileLocation));
                    showToast(context.getString(
                            R.string.ErrorExportPermissionDenied,
                            fileLocation));
                    throw new IOException(String.format(Locale.US,
                            "Cannot write to %s",
                            localZipFile.getAbsolutePath()));
                }
            }
            outStream = null;
        }

        exporter = new ZIPExporter(preferences, repository, this);
        // Initialize string resources on the exporter for the progress bar
        exporter.setModeText(ZIPExporter.OpMode.START,
                context.getString(R.string.ProgressMessageStart));
        exporter.setModeText(ZIPExporter.OpMode.SETTINGS,
                context.getString(R.string.ProgressMessageExportSettings));
        exporter.setModeText(ZIPExporter.OpMode.CATEGORIES,
                context.getString(R.string.ProgressMessageExportCategories));
        exporter.setModeText(ZIPExporter.OpMode.ITEMS,
                context.getString(R.string.ProgressMessageExportItems));
        exporter.setModeText(ZIPExporter.OpMode.FINISH,
                context.getString(R.string.ProgressMessageFinish));
        exporter.setPrivateTitle(context.getString(
                R.string.ZIPFileNamePrivate));
        exporter.setEncryptedTitle(context.getString(
                R.string.ZIPFileNameEncrypted));
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
        StringEncryption decryptor = null;
        if (exportPrivate) try {
            StringEncryption globalEncryption =
                    StringEncryption.holdGlobalEncryption();
            if (globalEncryption.hasKey()) {
                // Copy the password from global encryption so the work
                // won't be impacted if the user re-locks encrypted notes.
                decryptor = new StringEncryption();
                decryptor.setPassword(globalEncryption.getPassword());
                decryptor.checkPassword(repository);
            } else if (((encryptionType == null) ||
                    (encryptionType != ZIPExporter.EncryptionType.BUNDLED_ENCRYPTION)) &&
                    globalEncryption.hasPassword(repository)) {
                showToast(context.getString(R.string.ToastPasswordProtected));
                return Result.failure(new Data.Builder()
                                .putString("Exception",
                                        PasswordRequiredException.class
                                                .getCanonicalName())
                                .putString("message", context.getString(
                                        R.string.ToastPasswordProtected))
                        .build());
            }
        } finally {
            StringEncryption.releaseGlobalEncryption(context);
        }
        updateProgress(context.getString(
                R.string.ProgressMessageStart), 0, 0, false);
        repository.open(context);
        try {
            exporter.export(localZipFile, exportCategory,
                    decryptor, encryptionType, zipPassword);
            if (outStream != null) {
                // Need to copy the local file to the stream
                try (FileInputStream in = new FileInputStream(localZipFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0)
                        outStream.write(buffer, 0, len);
                }
                outStream.close();
                // Now we can delete the temp file.
                localZipFile.delete();
            }
            return Result.success();
        } catch (Throwable e) {
            Log.e(TAG, "Error exporting data to ZIP!", e);
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
            if (decryptor != null)
                decryptor.forgetPassword();
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
