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

import android.util.Log;

import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.util.PasswordMismatchException;
import com.xmission.trevin.android.notes.util.PasswordRequiredException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Encrypts and decrypts private entries in the database
 * when the user sets or changes the password.
 *
 * @author Trevin Beattie
 */
public class PasswordChanger implements Runnable {

    private static final String TAG = "PasswordChangeer";

    /** Modes of operation */
    public enum OpMode {
        START,
        ENCRYPT,
        DECRYPT,
        REENCRYPT,
        FINISH
    }

    /**
     * Text to pass to the {@link ProgressBarUpdater} for each
     * mode of operation.  This may be overridden when the class
     * is initialized.
     */
    private static final Map<OpMode,String> modeText = new HashMap<>();
    static {
        modeText.put(OpMode.START, "Starting\u2026");
        modeText.put(OpMode.ENCRYPT, "Encrypting private records with the new password\u2026");
        modeText.put(OpMode.DECRYPT, "Decrypting private records with the old password\u2026");
        modeText.put(OpMode.REENCRYPT, "Re-encrypting private records with the new password\u2026");
        modeText.put(OpMode.FINISH, "Finishing\u2026");
    }

    /**
     * Change the text associated with a mode of operation.
     *
     * @param mode the mode whose text to change
     * @param text the new text to use
     */
    static void setModeText(OpMode mode, String text) {
        modeText.put(mode, text);
    }

    @NonNull
    private final NoteRepository repository;

    /**
     * Decryption object using the old password to decrypt encrypted records,
     * or {@code null} if there was no password previously set.
     */
    private final StringEncryption oldEncryption;

    /**
     * Encryption object using the new password (if any) to (re-)encrypt
     * private records, or {@code null} when clearing the password.
     */
    private final StringEncryption newEncryption;

    /** Progress updater passed to the {@link #changePassword} method */
    private final ProgressBarUpdater progressUpdater;

    /**
     * Change the password.  Decrypt any encrypted records in the database
     * using the old password, encrypt any private records using the new
     * password, and store the hash of the new password.
     *
     * @param repository The repository in which to store the password changes
     * @param oldPassword the old password (or {@code null} if there was no
     * password previously set).
     * @param newPassword the new password (or {@code null} if removing
     * the password).
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    public static void changePassword(NoteRepository repository,
                                      char[] oldPassword,
                                      char[] newPassword,
                                      ProgressBarUpdater progressUpdater) {
        StringEncryption oldEncryption = null;
        if (oldPassword != null) {
            oldEncryption = new StringEncryption();
            oldEncryption.setPassword(oldPassword);
        }
        StringEncryption newEncryption = null;
        if (newPassword != null) {
            newEncryption = new StringEncryption();
            newEncryption.setPassword(newPassword);
            newEncryption.addSalt();
        }
        PasswordChanger changer = new PasswordChanger(repository,
                oldEncryption, newEncryption, progressUpdater);
        try {
            repository.runInTransaction(changer);
        } finally {
            if (oldEncryption != null)
                oldEncryption.forgetPassword();
            if (newEncryption != null)
                newEncryption.forgetPassword();
        }
    }

    private PasswordChanger(NoteRepository repository,
                            StringEncryption oldEncryption,
                            StringEncryption newEncryption,
                            ProgressBarUpdater progressUpdater) {
        this.repository = repository;
        this.oldEncryption = oldEncryption;
        this.newEncryption = newEncryption;
        this.progressUpdater = progressUpdater;
    }

    @Override
    public void run() {
        String progressMode;
        if (oldEncryption != null) {
            if (!oldEncryption.checkPassword(repository))
                throw new PasswordMismatchException(
                        "The old password is incorrect");
            progressMode = (newEncryption == null)
                    ? modeText.get(OpMode.DECRYPT)
                    : modeText.get(OpMode.REENCRYPT);
        } else {
            if (newEncryption.hasPassword(repository))
                throw new PasswordRequiredException(
                        "The old password has not been provided");
            progressMode = modeText.get(OpMode.ENCRYPT);
        }

        long[] privateItemIds = repository.getPrivateNoteIds();
        // The total number of entries to be changed
        int changeTarget = privateItemIds.length;
        // The current number of entries changed
        int numChanged = 0;
        int countDecrypted = 0;
        int countEncrypted = 0;
        long startTime = System.nanoTime();
        for (long id : privateItemIds) {
            NoteItem note = repository.getNoteById(id);
            if (note == null) {
                Log.w(TAG, String.format(
                        "Note #%d disappeared while changing the password!",
                        id));
                continue;
            }
            if (note.isEncrypted()) {
                note.setNote(oldEncryption.decrypt(
                        note.getEncryptedNote()));
                note.setPrivate(1);
                countDecrypted++;
            }
            if (newEncryption != null) {
                note.setEncryptedNote(newEncryption.encrypt(
                        note.getNote()));
                note.setPrivate(2);
                countEncrypted++;
            }
            repository.updateNote(note);
            numChanged++;

            // Periodically update our progress
            progressUpdater.updateProgress(progressMode,
                    numChanged, changeTarget, true);
        }
        long now = System.nanoTime();
        Log.d(TAG, String.format(
                "%d items decrypted, %d encrypted in %.3fs",
                countDecrypted, countEncrypted,
                (now - startTime) / 1.0e+9));
        if (newEncryption == null) {
            if (oldEncryption != null)
                oldEncryption.removePassword(repository);
        } else {
            newEncryption.storePassword(repository);
        }
        progressUpdater.updateProgress(modeText.get(OpMode.FINISH),
                numChanged, changeTarget, false);
    }

}
