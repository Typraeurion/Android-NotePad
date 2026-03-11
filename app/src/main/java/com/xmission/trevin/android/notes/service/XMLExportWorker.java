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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * This class exports the Note Pad notes to an XML file on external storage.
 *
 * @author Trevin Beattie
 */
public class XMLExportWorker extends Worker implements ProgressBarUpdater {

    public static final String TAG = "XMLExportWorker";

    /**
     * The key of the input data that holds
     * the location of the notes.xml file
     */
    public static final String XML_DATA_FILENAME = "XMLDataFileName";

    /**
     * The key of the input data that indicates whether to
     * export private records.
     */
    public static final String EXPORT_PRIVATE = "XMLExportPrivate";

    /**
     * Notification ID to use when running this worker in the foreground
     * (Oreo or later).
     */
    private static final int FG_NOTIFICATION_ID = -2060275898;
    /**
     * Output stream where we be writing the XML document.
     * <p>
     * <b>Caution:</b> in order to properly use the Storage Access
     * Framework and check for access errors, the file is opened
     * in this class&rsquo; constructor; the actual write operation
     * does not occur until {@link #doWork()} is called, so the
     * file may remain open for an indeterminate amount of time.
     * </p>
     */
    private OutputStream xmlStream;

    /** Whether to export private records */
    private boolean exportPrivate;

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
     * Initialize the XMLExportWorker using the standard system services
     * and app class instances.
     *
     * @param context the application context
     * @param params Parameters to set up the internal state of this worker
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    public XMLExportWorker(@NonNull Context context,
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
                    "No XML output file provided");
        exportPrivate = params.getInputData().getBoolean(
                EXPORT_PRIVATE, false);

        String fileLocation = params.getInputData().getString(XML_DATA_FILENAME);
        if (fileLocation.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(fileLocation);
                xmlStream = context.getContentResolver().openOutputStream(
                        contentUri, "wt");
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
            File xmlFile = new File(fileLocation);
            if (xmlFile.exists()) {
                if (!xmlFile.canWrite()) {
                    Log.w(TAG, String.format(Locale.US,
                            "Cannot write to %s", fileLocation));
                    showToast(context.getString(
                            R.string.ErrorExportPermissionDenied,
                            fileLocation));
                    throw new IOException(String.format(Locale.US,
                            "Cannot write to %s", xmlFile.getAbsolutePath()));
                }
            } else try {
                Files.createFile(xmlFile.toPath());
                xmlStream = new FileOutputStream(xmlFile, false);
            } catch (IOException ioe) {
                Log.e(TAG, String.format("Failed to open %s for writing",
                        fileLocation), ioe);
                if (!xmlFile.getParentFile().exists()) {
                    showToast(context.getString(
                            R.string.ErrorExportCantMkdirs,
                            xmlFile.getAbsoluteFile().getParent()));
                    throw new FileNotFoundException(String.format(
                            "Parent directory %s does not exist",
                            xmlFile.getAbsoluteFile().getParent()));
                }
                showToast(context.getString(
                        R.string.ErrorExportPermissionDenied, fileLocation));
                throw ioe;
            }
        }

        // Initialize string resources on the exporter for the progress bar
        XMLExporter.setModeText(XMLExporter.OpMode.START,
                context.getString(R.string.ProgressMessageStart));
        XMLExporter.setModeText(XMLExporter.OpMode.SETTINGS,
                context.getString(R.string.ProgressMessageExportSettings));
        XMLExporter.setModeText(XMLExporter.OpMode.CATEGORIES,
                context.getString(R.string.ProgressMessageExportCategories));
        XMLExporter.setModeText(XMLExporter.OpMode.ITEMS,
                context.getString(R.string.ProgressMessageExportItems));
        XMLExporter.setModeText(XMLExporter.OpMode.FINISH,
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
            XMLExporter.export(preferences, repository,
                    xmlStream, exportPrivate, this);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error exporting data to XML!", e);
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

    /* FIXME: Check whether we need this
     * Return a notification of this worker when it&rsquo;s run
     * in the foreground.
     */
//    @Override
//    @NonNull
//    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
//        Log.d(TAG, ".getForegroundInfoAsync");
//        Notification busyNotification = new NotificationCompat
//                .Builder(context, SILENT_CHANNEL_ID)
//                .setSmallIcon(R.drawable.stat_note)
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
