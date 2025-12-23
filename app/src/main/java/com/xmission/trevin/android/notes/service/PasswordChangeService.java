/*
 * Copyright © 2011 Trevin Beattie
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

import java.security.GeneralSecurityException;

import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.InvalidPasswordException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.app.IntentService;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;

/**
 * Encrypts and decrypts private entries in the database
 * when the user sets or changes the password.
 *
 * @author Trevin Beattie
 */
public class PasswordChangeService extends IntentService
	implements ProgressReportingService {

    private static final String TAG = "PasswordChangeService";

    /** The name of the Intent action for changing the password */
    public static final String ACTION_CHANGE_PASSWORD =
	"com.xmission.trevin.android.notes.ChangePassword";
    /**
     * The name of the Intent extra data that holds the old password.
     * This must be a char array!
     */
    public static final String EXTRA_OLD_PASSWORD =
	"com.xmission.trevin.android.notes.OldPassword";
    /**
     * The name of the Intent extra data that holds the new password.
     * This must be a char array!
     */
    public static final String EXTRA_NEW_PASSWORD =
	"com.xmission.trevin.android.notes.NewPassword";

    /** The Note Pad database */
    NoteRepository repository = null;

    /** Handler for making calls involving the UI */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** The mode of operation */
    public enum OpMode {
       DECRYPTING, ENCRYPTING, REENCRYPTING;
    }
    private OpMode currentMode = null;

    /** The current number of entries changed */
    private int numChanged = 0;

    /** The total number of entries to be changed */
    private int changeTarget = 1;

    private long[] privateNoteIds;

    public class PasswordBinder extends Binder {
	public PasswordChangeService getService() {
	    Log.d(TAG, "PasswordBinder.getService()");
	    return PasswordChangeService.this;
	}
    }

    private PasswordBinder binder = new PasswordBinder();

    /** Create the importer service with a named worker thread */
    public PasswordChangeService() {
	super(PasswordChangeService.class.getSimpleName());
	Log.d(TAG,"created");
	// If we die in the middle of a password change, restart the request.
	setIntentRedelivery(true);
    }

    /**
     * Set the repository to be used by this service.
     * This is meant for UI tests to override the repository with a mock;
     * if not called explicitly, the activity will use the regular
     * repository implementation.
     *
     * @param repository the repository to use for notes
     */
    public void setRepository(@NonNull NoteRepository repository) {
        if (this.repository != null) {
            if (this.repository == repository)
                return;
            throw new IllegalStateException(String.format(
                    "Attempted to set the repository to %s"
                    + " when it had previously been set to %s",
                    repository.getClass().getCanonicalName(),
                    this.repository.getClass().getCanonicalName()));
        }
        this.repository = repository;
    }

    /** @return the current mode of operation */
    public String getCurrentMode() {
        switch (currentMode) {
            case DECRYPTING:
                return getString(R.string.ProgressMessageDecrypting);
            case ENCRYPTING:
                return getString(R.string.ProgressMessageEncrypting);
            case REENCRYPTING:
                return getString(R.string.ProgressMessageReencrypting);
            default: return "";
        }
    }

    /** @return the total number of entries to be changed */
    public int getMaxCount() { return changeTarget; }

    /** @return the number of entries changed so far */
    public int getChangedCount() { return numChanged; }

    /** Called when an activity requests a password change */
    @Override
    protected synchronized void onHandleIntent(@NonNull Intent intent) {
	Log.d(TAG, ".onHandleIntent({action=" + intent.getAction()
		+ ", data=" + intent.getDataString() + ")}");
	// Get the old password, if there is one.
	char[] oldPassword = intent.getCharArrayExtra(EXTRA_OLD_PASSWORD);
	// Get the new password, if there is one.
	char[] newPassword = intent.getCharArrayExtra(EXTRA_NEW_PASSWORD);
        privateNoteIds = repository.getPrivateNoteIds();
        changeTarget = privateNoteIds.length;
        numChanged = 0;
        StringEncryption globalEncryption =
                StringEncryption.holdGlobalEncryption();
        try {
            if (oldPassword != null) {
                if (!globalEncryption.hasPassword(repository)) {
                    uiHandler.post(toastBadPassword);
                    return;
                }
                globalEncryption.setPassword(oldPassword);
                if (!globalEncryption.checkPassword(repository)) {
                    uiHandler.post(toastBadPassword);
                    return;
                }
                currentMode = (newPassword == null) ?
                        OpMode.DECRYPTING : OpMode.REENCRYPTING;
            } else {
                if (globalEncryption.hasPassword(repository)) {
                    uiHandler.post(toastBadPassword);
                    return;
                }
                currentMode = (newPassword == null) ?
                        null : OpMode.ENCRYPTING;
            }
            PasswordChangeTransactionRunner transaction =
                    new PasswordChangeTransactionRunner(oldPassword, newPassword);
            repository.runInTransaction(transaction);

            if (newPassword != null) {
                globalEncryption.setPassword(newPassword);
                globalEncryption.checkPassword(repository);
            } else {
                if (globalEncryption.hasKey())
                    globalEncryption.forgetPassword();
            }

        } catch (Exception e) {
            if (e.getCause() instanceof GeneralSecurityException)
                e = (Exception) e.getCause();
            final String message = e.getMessage();
            Log.e(TAG, "Error changing the password!", e);
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PasswordChangeService.this,
                            message, Toast.LENGTH_LONG).show();
                }
            });
	} finally {
	    StringEncryption.releaseGlobalEncryption();
	}
    }

    /**
     * Show a &ldquo;bad password&rdquo; toast.
     * This must be run on the UI thread.
     */
    private final Runnable toastBadPassword = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(PasswordChangeService.this,
                    R.string.ToastBadPassword, Toast.LENGTH_LONG).show();
        }
    };

    /**
     * Runnable task which does the database updates in a transaction.
     */
    private class PasswordChangeTransactionRunner implements Runnable {
        private final StringEncryption oldEncryption;
        private final StringEncryption newEncryption;

        /**
         * Initialize the runner.  Checks the {@code oldPassword} against
         * the password hash stored in the database, if there is one.
         * Sets up two temporary encryption objects: one for decrypting
         * encrypted records, one for encrypting private records.
         *
         * @param oldPassword the old password provided by the user, or
         *                    {@code null} if no password is expected.
         * @param newPassword the new password with which to encrypt
         *                    private records.
         *
         * @throws InvalidPasswordException if the old password is incorrect.
         * @throws GeneralSecurityException on any other error thrown
         * by the encryption objects.
         */
        PasswordChangeTransactionRunner(char[] oldPassword,
                                        char[] newPassword)
                throws GeneralSecurityException {
            if (oldPassword == null) {
                oldEncryption = null;
            } else {
                oldEncryption = new StringEncryption();
                oldEncryption.setPassword(oldPassword);
                if (!oldEncryption.checkPassword(repository))
                    throw new InvalidPasswordException();
            }
            if (newPassword == null) {
                newEncryption = null;
            } else {
                newEncryption = new StringEncryption();
                newEncryption.setPassword(newPassword);
                newEncryption.addSalt();
            }
        }

        /**
         * Process the password change for all private records.
         * For each private record, it decrypts it if it was encrypted,
         * then encrypts it if a new password has been set, and
         * changes the note&rsquo;s {@code private} flag accordingly.
         * Finally, it stores the new password&rsquo;s hash in the database.
         *
         * @throws RuntimeException wrapping a {@link GeneralSecurityException}
         * if we fail to decrypt or encrypt any note.
         */
        @Override
        public void run() {
            int countDecrypted = 0;
            int countEncrypted = 0;
            try {
                for (long id : privateNoteIds) {
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
                }
                Log.d(TAG, String.format("%d notes decrypted, %d encrypted",
                        countDecrypted, countEncrypted));
                if (newEncryption == null) {
                    if (oldEncryption != null)
                        oldEncryption.removePassword(repository);
                } else {
                    newEncryption.storePassword(repository);
                }
            } catch (GeneralSecurityException gsx) {
                throw new RuntimeException(gsx);
            }
        }
    }

    /**
     * Called when the service is created.
     */
    @Override
    public void onCreate() {
        Log.d(TAG, ".onCreate");
        super.onCreate();

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
        repository.open(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, ".onBind");
	return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, ".onDestroy");
        repository.release(this);
        super.onDestroy();
    }

}
