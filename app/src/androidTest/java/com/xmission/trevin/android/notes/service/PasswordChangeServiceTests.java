/*
 * Copyright © 2025 Trevin Beattie
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

import static com.xmission.trevin.android.notes.service.PasswordChangeService.*;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomNote;
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static org.junit.Assert.*;

import android.content.Intent;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class PasswordChangeServiceTests {

    private MockNoteRepository mockRepo = null;

    /**
     * Observer which updates a latch when the service reports either
     * completion or an error, and can be queried to wait for completion.
     */
    private static class PasswordTestObserver implements HandleIntentObserver {

        private final PasswordChangeService service;
        private final CountDownLatch latch;
        private boolean finished = false;
        private boolean success = false;
        private Exception e = null;
        private final List<String> toasts = new ArrayList<>();

        /**
         * Initialize an observer for the given service
         */
        PasswordTestObserver(PasswordChangeService service) {
            this.service = service;
            latch = new CountDownLatch(1);
            service.registerObserver(this);
        }

        @Override
        public void onComplete() {
            finished = true;
            success = true;
            latch.countDown();
        }

        @Override
        public void onRejected() {
            finished = true;
            latch.countDown();
        }

        @Override
        public void onError(Exception e) {
            finished = true;
            this.e = e;
            latch.countDown();
        }

        @Override
        public void onToast(String message) {
            toasts.add(message);
        }

        /**
         * Wait for the latch to show all changes complete.
         * Times out after a given number of seconds.
         *
         * @throws AssertionError if the latch times out
         * @throws InterruptedException if the wait was interrupted
         */
        public void await(int timeoutSeconds) throws InterruptedException {
            try {
                assertTrue("Timed out waiting for the service to finish",
                        latch.await(timeoutSeconds, TimeUnit.SECONDS));
            } finally {
                service.unregisterObserver(this);
            }
        }

        /** @return whether the service finished handling the intent */
        public boolean isFinished() {
            return finished;
        }

        /** @return whether the intent was handled successfully */
        public boolean isSuccess() {
            return success;
        }

        /** @return whether the intent threw an exception */
        public boolean isError() {
            return (e != null);
        }

        /** @return the exception thrown by the service */
        public Exception getException() {
            return e;
        }

        /**
         * @return the (first) Toast message shown by the service,
         * or {@code null} if it did not show a Toast.
         */
        public String getToastMessage() {
            return toasts.isEmpty() ? null : toasts.get(0);
        }

        /** @return the number of Toast messages that the service popped up */
        public int getNumberOfToasts() {
            return toasts.size();
        }

        /**
         * @param substring text to look for in Toast messages
         *
         * @return whether any of the Toast messages contain the given
         * string (ignoring case)
         */
        public boolean toastsContains(String substring) {
            String lowersub = substring.toLowerCase();
            for (String message : toasts)
                if (message.toLowerCase().contains(lowersub))
                    return true;
            return false;
        }

        /**
         * @return all of the Toast messages as a single string,
         * separated by newlines.  If there were no Toast messages,
         * returns an empty string.
         */
        public String getAllToastMessages() {
            if (toasts.isEmpty())
                return "";
            return StringUtils.join(toasts, "\n");
        }

    }

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Before
    public void initializeRepository() {
        if (mockRepo == null) {
            mockRepo = MockNoteRepository.getInstance();
            NoteRepositoryImpl.setInstance(mockRepo);
        }
        mockRepo.open(InstrumentationRegistry.getTargetContext());
        mockRepo.clear();
    }

    @After
    public void releaseRepository() {
        mockRepo.release(InstrumentationRegistry.getTargetContext());
    }

    /**
     * Set up the password change service, call it,
     * then wait for it to finish.
     *
     * @param oldPassword the old password, or {@code null} if setting a new one
     * @param newPassword the new password, or {@code null} if clearing it
     *
     * @return the PasswordTestObserver that was registered, which shows
     * the results of the password change call
     *
     * @throws AssertionError if the service did not finish changing the
     * password within 10 seconds
     * @throws InterruptedException if we were interrupted while waiting
     * for the service to finish
     * @throws TimeoutException if the service did not start
     * (Android system timeout)
     * @throws Exception (any other) if the observer reports an error
     * from the service&rsquo; {@code onHandleIntent} method
     */
    private PasswordTestObserver runPasswordChange(
            String oldPassword, String newPassword) throws Exception {
        // Set up the password change service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                PasswordChangeService.class);
        intent.setAction(ACTION_CHANGE_PASSWORD);
        if (oldPassword != null)
            intent.putExtra(EXTRA_OLD_PASSWORD, oldPassword.toCharArray());
        if (newPassword != null)
            intent.putExtra(EXTRA_NEW_PASSWORD, newPassword.toCharArray());
        // We need to register our observer before calling it.
        IBinder binder = serviceRule.bindService(intent);
        long timeoutLimit = System.currentTimeMillis() + 10000;
        while (binder == null) {
            // serviceRule.bindService() may return null if the service
            // is already started or for some other reason.  Try to start
            // it and wait for a bit, then try binding again.
            long timeLeft = timeoutLimit - System.currentTimeMillis();
            assertTrue("Timed out waiting to bind to XMLImporterService",
                    timeLeft > 0);
            Thread.sleep((timeLeft > 200) ? 200 : timeLeft);
            binder = serviceRule.bindService(intent);
        }
        assertNotNull("Failed to bind the importer service", binder);
        PasswordChangeService service = ((PasswordChangeService.PasswordBinder)
                binder).getService();
        PasswordTestObserver observer = new PasswordTestObserver(service);
        service.setRepository(mockRepo);
        serviceRule.startService(intent);

        // Wait for the service to change the password
        observer.await(10);

        if (observer.isError())
            throw observer.getException();
        assertTrue("Importer service did not finish",
                observer.isFinished());

        if (observer.getNumberOfToasts() > 0)
            // Log the toasts for analysis if needed
            Log.i("XMLImportServiceTests", "Toasts: "
                    + observer.getAllToastMessages());

        return observer;
    }

    /**
     * Test setting a new password when the database has private records.
     * All private records should become encrypted.
     */
    @Test
    public void testNewPassword() throws Exception {

        // Set up the test data
        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        privateNote.setPrivate(1);
        mockRepo.insertNote(privateNote);

        final String password = RandomStringUtils.randomAlphanumeric(8);

        // Call the password change service
        PasswordTestObserver observer = runPasswordChange(null, password);

        // Verify the results
        assertTrue("Password change service did not finish successfully",
                observer.isSuccess());
        NoteMetadata passwordHash =
                mockRepo.getMetadataByName(METADATA_PASSWORD_HASH);
        assertNotNull("Password hash was not saved", passwordHash);

        NoteItem savedNote = mockRepo.getNoteById(publicNote.getId());
        assertEquals("Public note", publicNote, savedNote);

        savedNote = mockRepo.getNoteById(privateNote.getId());
        assertNotNull("Private note not found", savedNote);
        assertTrue("Private note was not encrypted", savedNote.isEncrypted());

        // Check the encryption on the encrypted note
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        if (se.getPassword() == null) {
            se.setPassword(password.toCharArray());
            se.checkPassword(mockRepo);
        }
        String decrypted = se.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note does not match original",
                privateNote.getNote(), decrypted);

    }

    /**
     * Test clearing the password when the database has encrypted records.
     * All encrypted records should be unencrypted but private.
     */
    @Test
    public void testRemovePassword() throws Exception {

        // Set up the test data
        final String password = RandomStringUtils.randomAlphanumeric(8);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        se.setPassword(password.toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        String clearText = privateNote.getNote();
        privateNote.setPrivate(2);
        privateNote.setEncryptedNote(se.encrypt(clearText));
        mockRepo.insertNote(privateNote);

        se.forgetPassword();

        // Call the password change service
        PasswordTestObserver observer = runPasswordChange(password, null);

        // Verify the results
        assertTrue("Password change service did not finish successfully",
                observer.isSuccess());
        assertNull("Password was not removed",
                mockRepo.getMetadataByName(METADATA_PASSWORD_HASH));

        NoteItem savedNote = mockRepo.getNoteById(publicNote.getId());
        assertEquals("Public note", publicNote, savedNote);

        savedNote = mockRepo.getNoteById(privateNote.getId());
        assertNotNull("Private note not found", savedNote);
        assertFalse("Private note was not decrypted",
                savedNote.isEncrypted());
        assertTrue("Private note was made public", savedNote.isPrivate());
        assertEquals("Private note contents", clearText, savedNote.getNote());

    }

    /**
     * Test clearing the password when the proper old password has
     * not been given.  Should result in a bad password toast.
     */
    @Test
    public void testRemoveBadPassword() throws Exception {

        // Set up the test data
        String password = RandomStringUtils.randomAlphanumeric(12);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        se.setPassword(password.toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        se.forgetPassword();

        password = RandomStringUtils.randomAlphabetic(8);

        PasswordTestObserver observer = runPasswordChange(password, null);

        // Verify the results
        assertFalse("Password change service accepted the change",
                observer.isSuccess());
        assertTrue("Password change service did not report a bad password",
                observer.toastsContains("password"));

    }

    /**
     * Test clearing the password when there is no password set.
     * Should result in a bad password toast.
     */
    @Test
    public void testRemoveNoPassword() throws Exception {

        String password = RandomStringUtils.randomAlphabetic(8);

        PasswordTestObserver observer = runPasswordChange(password, null);

        // Verify the results
        assertFalse("Password change service accepted the change",
                observer.isSuccess());
        assertTrue("Password change service did not report a bad password",
                observer.toastsContains("password"));

    }

    /**
     * Test changing the password when the database has encrypted records.
     * All encrypted records should be re-encrypted with the new password,
     * and any previously private but unencrypted records should be
     * encrypted as well.
     */
    @Test
    public void testChangePassword() throws Exception {

        // Set up the test data
        final String oldPassword = RandomStringUtils.randomAlphanumeric(8);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        se.setPassword(oldPassword.toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        String clearText1 = privateNote.getNote();
        privateNote.setPrivate(1);
        mockRepo.insertNote(privateNote);
        NoteItem encryptedNote = randomNote();
        String clearText2 = encryptedNote.getNote();
        encryptedNote.setPrivate(2);
        encryptedNote.setEncryptedNote(se.encrypt(clearText2));
        mockRepo.insertNote(encryptedNote);

        final String newPassword = RandomStringUtils.randomAlphanumeric(12);
        se.forgetPassword();

        // Call the password change service
        PasswordTestObserver observer = runPasswordChange(
                oldPassword, newPassword);

        // Verify the results
        assertTrue("Password change service did not finish successfully",
                observer.isSuccess());
        NoteMetadata passwordHash =
                mockRepo.getMetadataByName(METADATA_PASSWORD_HASH);
        assertNotNull("Password hash was removed", passwordHash);

        NoteItem savedNote = mockRepo.getNoteById(publicNote.getId());
        assertEquals("Public note", publicNote, savedNote);

        savedNote = mockRepo.getNoteById(privateNote.getId());
        assertNotNull("Private note (#1) not found", savedNote);
        assertTrue("Private note was not encrypted",
                savedNote.isEncrypted());

        // Check the encryption on the first private note; don't rely on
        // which password was last set in the global encryption instance.
        se.forgetPassword();
        se.setPassword(newPassword.toCharArray());
        se.checkPassword(mockRepo);
        String decrypted = se.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note (#1) does not match original",
                clearText1, decrypted);

        savedNote = mockRepo.getNoteById(encryptedNote.getId());
        assertNotNull("Encrypted note (#2) not found", savedNote);
        assertTrue("Encrypted note was left unencrypted",
                savedNote.isEncrypted());
        decrypted = se.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note (#2) does not match original",
                clearText2, decrypted);

    }

    /**
     * Test setting a &ldquo;new&rdquo; password when the database already
     * has a password.  Should result in a bad password toast.
     */
    @Test
    public void testNewPasswordConflict() throws Exception {

        // Set up the test data
        String password = RandomStringUtils.randomAlphanumeric(12);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        se.setPassword(password.toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        se.forgetPassword();

        password = RandomStringUtils.randomAlphabetic(12);

        PasswordTestObserver observer = runPasswordChange(null, password);

        // Verify the results
        assertFalse("Password change service accepted the change",
                observer.isSuccess());
        assertTrue("Password change service did not report a bad password",
                observer.toastsContains("password"));

    }

}
