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

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Arrays;
import java.util.Locale;

/**
 * Encrypts and decrypts private entries in the database
 * when the user sets or changes the password.
 *
 * @author Trevin Beattie
 */
public class PasswordChangeWorker extends Worker implements ProgressBarUpdater {

    private static final String TAG = "PasswordChangeWorker";

    /** The key of the input data that holds the old password. */
    public static final String DATA_OLD_PASSWORD = "OldPassword";
    /** The key of the input data that holds the new password. */
    public static final String DATA_NEW_PASSWORD = "NewPassword";

    @NonNull
    private final Context context;

    @NonNull
    private final NoteRepository repository;

    /** Handler for making calls involving the UI */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** The old password used to decrypt encrypted records */
    private char[] oldPassword;

    /** The new password (if any) used to (re-)encrypt private records */
    private char[] newPassword;

    /** Internal time when we last updated the async progress */
    private long lastProgressTimeNano;

    /**
     * Initialize the PasswordChangeWorker using the standard system services
     * and app class instances.
     *
     * @param context the application context
     * @param params Parameters to set up the internal state of this worker
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    public PasswordChangeWorker(@NonNull Context context,
                                @NonNull WorkerParameters params)
            throws IllegalArgumentException  {
        super(context, params);
        Log.d(TAG, String.format("Default initialization for %s",
                context.getClass().getName()));
        this.context = context;
        repository = NoteRepositoryImpl.getInstance();
        getInputData(params);
    }

    /**
     * Extract our input data from the {@link WorkerParameters}.
     *
     * @throws IllegalArgumentException if the input data is invalid.
     */
    private void getInputData(@NonNull WorkerParameters params) {
        if (params.getInputData().hasKeyWithValueOfType(
                DATA_OLD_PASSWORD, String.class))
            oldPassword = params.getInputData().getString(
                    DATA_OLD_PASSWORD).toCharArray();
        else
            oldPassword = null;

        if (params.getInputData().hasKeyWithValueOfType(
                DATA_NEW_PASSWORD, String.class))
            newPassword = params.getInputData().getString(
                    DATA_NEW_PASSWORD).toCharArray();
        else
            newPassword = null;

        if ((oldPassword == null) && (newPassword == null))
            throw new IllegalArgumentException(
                    "Both the old and new passwords have not been provided"
                            + " or are the wrong type");
    }

    /**
     * Main entry point of the worker.  This checks the old password,
     * runs the main work loop in a database transaction, then updates
     * or clears the password in the global encryption object as needed.
     */
    @Override
    @NonNull
    public Result doWork() {
        Log.d(TAG, ".doWork");
        long startTimeNano = System.nanoTime();
        // Initialize the progress indicator
        updateProgress(context.getString(
                R.string.ProgressMessageStart), 0, -1, false);

        StringEncryption globalEncryption =
                StringEncryption.holdGlobalEncryption();
        final String badPasswordMessage =
                context.getString(R.string.ToastBadPassword);

        repository.open(context);
        try {
            PasswordChanger.changePassword(repository,
                    oldPassword, newPassword, this);

            if ((newPassword != null) && globalEncryption.hasKey()) {
                globalEncryption.setPassword(newPassword);
                globalEncryption.checkPassword(repository);
            } else {
                globalEncryption.forgetPassword();
            }
            return Result.success();
        }

        catch (AuthenticationException ae) {
            Log.e(TAG, "Authentication error", ae);
            showToast(badPasswordMessage);
            Data.Builder errBuilder = new Data.Builder();
            if (ae instanceof PasswordMismatchException)
                errBuilder.putString("Error",
                        "Old password does not match hash in the database");
            else if (ae instanceof PasswordRequiredException)
                errBuilder.putString("Error",
                        "Current password was not provided");
            else
                errBuilder.putString("Error",
                        ae.getMessage());
            errBuilder.putString("message", badPasswordMessage);
            return Result.failure(errBuilder.build());
        }

        catch (Exception e) {
            Log.e(TAG, "Error changing the password!", e);
            showToast(e.getMessage());
            return Result.failure(new Data.Builder()
                    .putString("Exception", e.getClass().getCanonicalName())
                    .putString("message", e.getMessage())
                    .build());
        }

        finally {
            long now = System.nanoTime();
            repository.release(context);
            StringEncryption.releaseGlobalEncryption(context);
            if (oldPassword != null)
                Arrays.fill(oldPassword, (char) 0);
            if (newPassword != null)
                Arrays.fill(newPassword, (char) 0);
            Log.d(TAG, String.format(Locale.US,
                    "Finished work in %.4f seconds",
                    (now - startTimeNano) / 1.0e+9));
        }
    }

    @Override
    public void updateProgress(String mode,
                               int currentCount, int totalCount,
                               boolean throttle) {
        if (throttle) {
            long now = System.nanoTime();
            if ((now - lastProgressTimeNano) < 250000000L)
                return;
            lastProgressTimeNano = now;
        }
        Data progressData = new Data.Builder()
                .putString(PROGRESS_CURRENT_MODE, mode)
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

}
