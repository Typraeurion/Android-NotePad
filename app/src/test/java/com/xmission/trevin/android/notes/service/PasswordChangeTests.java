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

import static com.xmission.trevin.android.notes.util.RandomNoteUtils.*;
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static org.junit.Assert.*;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.*;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.*;

import java.util.*;

/**
 * Unit tests for setting, changing, and removing a password.
 *
 * @author Trevin Beattie
 */
public class PasswordChangeTests {

    private static final RandomStringUtils SRAND = RandomStringUtils.insecure();

    private static MockNoteRepository mockRepo = null;
    private StringEncryption globalEncryption = null;

    @BeforeClass
    public static void createMocks() {
        if (mockRepo == null) {
            mockRepo = MockNoteRepository.getInstance();
            NoteRepositoryImpl.setInstance(mockRepo);
        }
    }

    @Before
    public void initializeRepository() {
        mockRepo.clear();
        globalEncryption = StringEncryption.holdGlobalEncryption();
    }

    @After
    public void releaseRepository() {
        StringEncryption.releaseGlobalEncryption();
    }

    /**
     * Set up the password change worker, call it,
     * then wait for it to finish.
     *
     * @param oldPassword the old password,
     * or {@code null} if setting a new one.
     * @param newPassword the new password,
     * or {@code null} if clearing the old one.
     * @param expectedException the class of the expected exception,
     * if any; {@code null} otherwise.
     *
     * @return the progress of the password change call.
     *
     * @throws AssertionError if the result is not the expected type.
     * @throws AuthenticationException if there is an unexpected
     * error with the password change
     */
    private MockProgressBar runPasswordChangeWorker(
            String oldPassword, String newPassword,
            Class<? extends AuthenticationException> expectedException)
            throws AssertionError, AuthenticationException {
        MockProgressBar progress = new MockProgressBar();
        try {
            PasswordChanger.changePassword(mockRepo,
                    (oldPassword == null) ? null : oldPassword.toCharArray(),
                    (newPassword == null) ? null : newPassword.toCharArray(),
                    progress);
            assertEquals("Exception thrown ", expectedException, null);
        } catch (AuthenticationException ae) {
            if (ae.getClass() != expectedException)
                throw ae;
        } finally {
            progress.setEndTime();
        }
        return progress;
    }

    /**
     * Test setting a new password when the database has private records.
     * All private records should become encrypted.
     */
    @Test
    public void testNewPassword() {

        // Set up the test data
        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        publicNote.setCategoryName(mockRepo.getCategoryById(
                NoteCategory.UNFILED).getName());
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        privateNote.setPrivate(1);
        privateNote.setCategoryName(mockRepo.getCategoryById(
                NoteCategory.UNFILED).getName());
        mockRepo.insertNote(privateNote);

        final String password = SRAND.nextAlphanumeric(8);

        // Call the password change service
        MockProgressBar progressBar = runPasswordChangeWorker(
                null, password, null);

        // Verify the results
        NoteMetadata passwordHash =
                mockRepo.getMetadataByName(METADATA_PASSWORD_HASH);
        assertNotNull("Password hash was not saved", passwordHash);

        NoteItem savedNote = mockRepo.getNoteById(publicNote.getId());
        assertEquals("Public note", publicNote, savedNote);

        savedNote = mockRepo.getNoteById(privateNote.getId());
        assertNotNull("Private note not found", savedNote);
        assertTrue("Private note was not encrypted",
                savedNote.isEncrypted());

        // Check the encryption on the encrypted note
        globalEncryption.forgetPassword();
        globalEncryption.setPassword(password.toCharArray());
        assertTrue("New password hash was not stored in the repository",
                globalEncryption.checkPassword(mockRepo));
        String decrypted = (savedNote.getEncryptedNote() == null)
                ? null : globalEncryption.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note does not match original",
                privateNote.getNote(), decrypted);

        MockProgressBar.Progress lastProgress = progressBar.getEndProgress();
        assertNotNull("Progress meter was not updated", lastProgress);
        assertEquals("Size of the progress meter", 1, lastProgress.total);
        assertEquals("Number of records encrypted", 1, lastProgress.current);
    }

    /**
     * Test clearing the password when the database has encrypted records.
     * All encrypted records should be unencrypted but private.
     */
    @Test
    public void testRemovePassword() throws Exception {

        // Set up the test data
        final String password = SRAND.nextAlphanumeric(8);
        globalEncryption.setPassword(password.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);

        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        String clearText = privateNote.getNote();
        privateNote.setPrivate(2);
        privateNote.setEncryptedNote(globalEncryption.encrypt(clearText));
        mockRepo.insertNote(privateNote);

        globalEncryption.forgetPassword();

        // Call the password change service
        MockProgressBar progressBar = runPasswordChangeWorker(
                password, null, null);

        // Verify the results
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

        MockProgressBar.Progress lastProgress = progressBar.getEndProgress();
        assertNotNull("Progress meter was not updated", lastProgress);
        assertEquals("Size of the progress meter", 1, lastProgress.total);
        assertEquals("Number of records decrypted", 1, lastProgress.current);
    }

    /**
     * Test clearing the password when the proper old password has
     * not been given.  Should result in a bad password toast.
     */
    @Test
    public void testRemoveBadPassword() throws Exception {

        // Set up the test data
        String password = SRAND.nextAlphanumeric(12);
        globalEncryption.setPassword(password.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);

        globalEncryption.forgetPassword();

        password = SRAND.nextAlphabetic(8);

        runPasswordChangeWorker(password, null,
                PasswordMismatchException.class);
    }

    /**
     * Test clearing the password when there is no password set.
     * Should result in a bad password toast.
     */
    @Test
    public void testRemoveNoPassword() throws Exception {

        String password = SRAND.nextAlphabetic(8);

        runPasswordChangeWorker(password, null,
                PasswordMismatchException.class);
    }

    /**
     * Test setting a &ldquo;new&rdquo; password when the database already
     * has a password.  Should result in a bad password exception.
     */
    @Test
    public void testNewPasswordConflict() {

        // Set up the test data
        String password = SRAND.nextAlphanumeric(12);
        globalEncryption.setPassword(password.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);

        globalEncryption.forgetPassword();

        password = SRAND.nextAlphabetic(12);

        // Call the password change worker
        runPasswordChangeWorker(null, password,
                PasswordRequiredException.class);
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
        final String oldPassword = SRAND.nextAlphanumeric(8);
        globalEncryption.setPassword(oldPassword.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);

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
        encryptedNote.setEncryptedNote(globalEncryption.encrypt(clearText2));
        mockRepo.insertNote(encryptedNote);

        final String newPassword = SRAND.nextAlphanumeric(12);
        globalEncryption.forgetPassword();

        // Call the password change service
        MockProgressBar progressBar = runPasswordChangeWorker(
                oldPassword, newPassword, null);

        // Verify the results
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
        globalEncryption.forgetPassword();
        globalEncryption.setPassword(newPassword.toCharArray());
        globalEncryption.checkPassword(mockRepo);
        String decrypted = globalEncryption.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note (#1) does not match original",
                clearText1, decrypted);

        savedNote = mockRepo.getNoteById(encryptedNote.getId());
        assertNotNull("Encrypted note (#2) not found", savedNote);
        assertTrue("Encrypted note was left unencrypted",
                savedNote.isEncrypted());
        decrypted = globalEncryption.decrypt(savedNote.getEncryptedNote());
        assertEquals("Decrypted note (#2) does not match original",
                clearText2, decrypted);

        MockProgressBar.Progress lastProgress = progressBar.getEndProgress();
        assertNotNull("Progress meter was not updated", lastProgress);
        assertEquals("Size of the progress meter", 2, lastProgress.total);
        assertEquals("Number of records re-encrypted", 2, lastProgress.current);
    }

}
