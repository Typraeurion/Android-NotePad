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
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static org.junit.Assert.*;

import android.content.Intent;
import android.database.DataSetObserver;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;

import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class PasswordChangeServiceTests {

    private static final Random RAND = new Random();

    private MockNoteRepository mockRepo = null;

    /**
     * Observer which updates a latch when the repository reports any change,
     * and can be queried to wait for all changes to complete.
     */
    private class DataChangeObserver extends DataSetObserver {
        private final CountDownLatch latch;

        /**
         * Initialize an observer which expects a single data update
         */
        DataChangeObserver() {
            latch = new CountDownLatch(1);
            mockRepo.registerDataSetObserver(this);
        }

        /**
         * Initialize an observer which expects a given number of data updates
         */
        DataChangeObserver(int numUpdates) {
            latch = new CountDownLatch(numUpdates);
            mockRepo.registerDataSetObserver(this);
        }

        @Override
        public void onChanged() {
            latch.countDown();
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
                assertTrue(String.format(
                                "Timed out waiting for %s data change(s)",
                                latch.getCount()),
                        latch.await(timeoutSeconds, TimeUnit.SECONDS));
            } finally {
                mockRepo.unregisterDataSetObserver(this);
            }
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

    /** Random word generator.  Just a string of lower-case letters. */
    public static String randomWord() {
        int targetLen = RAND.nextInt(7) + 1;
        return RandomStringUtils.randomAlphabetic(targetLen).toLowerCase();
    }

    /**
     * Random sentence generator.  Strings random words with the first
     * word capitalized, joined by spaces, ending in a period.
     */
    public static String randomSentence() {
        int numWords = RAND.nextInt(10) + 3;
        List<String> words = new ArrayList<>();
        words.add(('A' + RAND.nextInt(26)) + randomWord());
        while (words.size() < numWords - 1)
            words.add(randomWord());
        words.add(randomWord() + ".");
        return StringUtils.join(words, ' ');
    }

    /**
     * Random paragraph generator.  Strings random sentences together
     * joined by two spaces each.  Ends the whole thing with a newline.
     */
    public static String randomParagraph() {
        int numSentences = RAND.nextInt(5) + 1;
        List<String> sentences = new ArrayList<>();
        while (sentences.size() < numSentences)
            sentences.add(randomSentence());
        return StringUtils.join(sentences, "  ") + "\n";
    }

    /**
     * Generate a random note.
     */
    public static NoteItem randomNote() {
        NoteItem note = new NoteItem();
        note.setCreateTimeNow();
        note.setModTime(note.getCreateTime());
        note.setCategoryId(NoteCategory.UNFILED);
        note.setNote(randomParagraph());
        return note;
    }

    /**
     * Test setting a new password when the database has private records.
     * All private records should become encrypted.
     */
    @Test
    public void testNewPassword()
            throws GeneralSecurityException,
            InterruptedException,
            TimeoutException {

        // Set up the test data
        NoteItem publicNote = randomNote();
        publicNote.setPrivate(0);
        mockRepo.insertNote(publicNote);
        NoteItem privateNote = randomNote();
        privateNote.setPrivate(1);
        mockRepo.insertNote(privateNote);

        final String password = RandomStringUtils.randomAlphanumeric(8);

        DataChangeObserver observer = new DataChangeObserver();

        // Call the password change service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                PasswordChangeService.class);
        intent.setAction(ACTION_CHANGE_PASSWORD);
        intent.putExtra(EXTRA_NEW_PASSWORD, password.toCharArray());
        serviceRule.startService(intent);

        // Wait for the service to commit something to the database.
        // (But not too long.)
        observer.await(5);

        // Verify the results
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
    public void testRemovePassword()
            throws GeneralSecurityException,
            InterruptedException,
            TimeoutException {

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

        DataChangeObserver observer = new DataChangeObserver();

        // Call the password change service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                PasswordChangeService.class);
        intent.setAction(ACTION_CHANGE_PASSWORD);
        intent.putExtra(EXTRA_OLD_PASSWORD, password.toCharArray());
        serviceRule.startService(intent);

        // Wait for the service to commit something to the database.
        // (But not too long.)
        observer.await(5);

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

    }

    /**
     * Test changing the password when the database has encrypted records.
     * All encrypted records should be re-encrypted with the new password,
     * and any previously private but unencrypted records should be
     * encrypted as well.
     */
    @Test
    public void testChangePassword()
            throws GeneralSecurityException,
            InterruptedException,
            TimeoutException {

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

        DataChangeObserver observer = new DataChangeObserver();

        // Call the password change service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                PasswordChangeService.class);
        intent.setAction(ACTION_CHANGE_PASSWORD);
        intent.putExtra(EXTRA_OLD_PASSWORD, oldPassword.toCharArray());
        intent.putExtra(EXTRA_NEW_PASSWORD, newPassword.toCharArray());
        serviceRule.startService(intent);

        // Wait for the service to commit something to the database.
        // (But not too long.)
        observer.await(5);

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

}
