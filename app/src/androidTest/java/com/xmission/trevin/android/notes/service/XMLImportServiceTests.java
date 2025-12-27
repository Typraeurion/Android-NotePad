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

import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static com.xmission.trevin.android.notes.provider.NoteRepositoryImpl.NOTE_TABLE_NAME;
import static com.xmission.trevin.android.notes.service.XMLExporterService.*;
import static com.xmission.trevin.android.notes.service.XMLImporterService.*;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomNote;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomSentence;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomWord;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.NoteItemColumns;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class XMLImportServiceTests {

    private static final Random RAND = new Random();

    private MockNoteRepository mockRepo = null;

    /**
     * Cache our import test files, since a single file may be used
     * in many import tests.
     */
    private Map<String,File> installedFiles = new HashMap<>();

    private Map<String,String> textAssets = new HashMap<>();

    /**
     * Observer which updates a latch when the service reports either
     * completion or an error, and can be queried to wait for completion.
     */
    private static class ImportTestObserver implements HandleIntentObserver {

        private final XMLImporterService service;
        private final CountDownLatch latch;
        private boolean finished = false;
        private boolean success = false;
        private Exception e = null;
        private final List<String> toasts = new ArrayList<>();

        /**
         * Initialize an observer for the given service
         */
        ImportTestObserver(XMLImporterService service) {
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

    String unfiledCategoryName;

    @Before
    public void initializeRepository() {
        if (mockRepo == null) {
            mockRepo = MockNoteRepository.getInstance();
            NoteRepositoryImpl.setInstance(mockRepo);
            unfiledCategoryName = InstrumentationRegistry.getTargetContext()
                    .getString(R.string.Category_Unfiled);
        }
        mockRepo.open(InstrumentationRegistry.getTargetContext());
        mockRepo.clear();
    }

    @After
    public void releaseRepository() {
        mockRepo.release(InstrumentationRegistry.getTargetContext());
    }

    /**
     * Copy an XML test file from our test assets directory into
     * the app&rsquo;s private file storage.  The file will be marked
     * for deletion at the end of the test.
     *
     * @param sourceFileName the name of the test file to copy
     *
     * @return a {@link File} object referencing the copy that the
     * app can use
     *
     * @throws IOException if there is any error reading the source file
     * or copying it to the destination file
     */
    public File copyTestFile(String sourceFileName) throws IOException {
        if (installedFiles.containsKey(sourceFileName))
            return installedFiles.get(sourceFileName);

        Context testContext = InstrumentationRegistry.getContext();
        Context targetContext = InstrumentationRegistry.getTargetContext();
        // Generate a random name for the copied file to ensure it
        // doesn't interfere with any prior test or regular app files
        String destFileName = sourceFileName.replaceFirst("\\.xml$", "-")
                + RandomStringUtils.randomAlphanumeric(8) + ".xml";
        File destFile = new File(targetContext.getFilesDir(), destFileName);
        destFile.deleteOnExit();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                testContext.getAssets().open(sourceFileName)));
        try {
            BufferedWriter writer = new BufferedWriter(
                    new FileWriter(destFile));
            try {
                String line = reader.readLine();
                while (line != null) {
                    writer.write(line);
                    writer.newLine();
                    line = reader.readLine();
                }
                writer.flush();
            } finally {
                writer.close();
            }
        } finally {
            reader.close();
        }
        installedFiles.put(sourceFileName, destFile);
        return destFile;
    }

    /**
     * Read the contents of a text file into a String.  Leading or trailing
     * whitespace will be trimmed.  This is used by the tests for encrypted
     * notes so we can store the password and plain text version of encrypted
     * notes in the assets instead of in code.
     */
    public String readTextFile(String sourceFileName) throws IOException {
        if (textAssets.containsKey(sourceFileName))
            return textAssets.get(sourceFileName);

        Context testContext = InstrumentationRegistry.getContext();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                testContext.getAssets().open(sourceFileName)));
        try {
            String line = reader.readLine();
            if (line == null) {
                Log.w("XMLImportServiceTests",
                        String.format("File \"%s\" is empty", sourceFileName));
                textAssets.put(sourceFileName, "");
                return "";
            }
            String nextLine = reader.readLine();
            while (nextLine != null) {
                line = line + "\n" + nextLine;
                nextLine = reader.readLine();
            }
            line = line.trim();
            textAssets.put(sourceFileName, line);
            return line;
        } finally {
            reader.close();
        }
    }

    /**
     * Set up the import service, call it, then wait for it to finish.
     *
     * @param importFile the file to import; caller should have copied this
     * from the test assets with {@link #copyTestFile(String)}.
     * @param importType the type of import to perform
     * @param importPrivate whether to import private records
     *
     * @return the HandleIntentObserver that was registered, which shows
     * the results of the import call
     *
     * @throws AssertionError if the service did not finish importing the
     * file within 10 seconds
     * @throws InterruptedException if we were interrupted while waiting
     * for the service to finish
     * @throws TimeoutException if the service did not start
     * (Android system timeout)
     * @throws Exception (any other) if the observer reports an error
     * from the service&rsquo; {@code onHandleIntent} method
     */
    private ImportTestObserver runImporter(
            File importFile, ImportType importType, boolean importPrivate)
            throws Exception {
        return runImporter(importFile, importType, importPrivate, null);
    }

    /**
     * Set up the import service, call it, then wait for it to finish.
     *
     * @param importFile the file to import; caller should have copied this
     * from the test assets with {@link #copyTestFile(String)}.
     * @param importType the type of import to perform
     * @param importPrivate whether to import private records
     * @param filePassword the password that should be used to decrypt the
     * file if we are importing encrypted records, or {@code null} to
     * skip encrypted records.
     *
     * @return the ImportTestObserver that was registered, which shows
     * the results of the import call
     *
     * @throws AssertionError if the service did not finish importing the
     * file within 10 seconds
     * @throws InterruptedException if we were interrupted while waiting
     * for the service to finish
     * @throws TimeoutException if the service did not start
     * (Android system timeout)
     * @throws Exception (any other) if the observer reports an error
     * from the service&rsquo; {@code onHandleIntent} method
     */
    private ImportTestObserver runImporter(
            File importFile, ImportType importType,
            boolean importPrivate, String filePassword)
            throws Exception {
        // Set up the import service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                XMLImporterService.class);
        intent.putExtra(XML_DATA_FILENAME, importFile.getAbsolutePath());
        intent.putExtra(XML_IMPORT_TYPE, importType);
        intent.putExtra(IMPORT_PRIVATE, importPrivate);
        if (filePassword != null)
            intent.putExtra(OLD_PASSWORD, filePassword.toCharArray());
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
        XMLImporterService service = ((XMLImporterService.ImportBinder)
                binder).getService();
        ImportTestObserver observer = new ImportTestObserver(service);
        service.setRepository(mockRepo);
        serviceRule.startService(intent);

        // Wait for the service to import the file.
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
     * Test importing preferences.  We only expect preferences to change
     * on {@link ImportType#CLEAN CLEAN}, {@link ImportType#REVERT REVERT},
     * or {@link ImportType#UPDATE UPDATE}.
     */
    private void testImportPreferences(ImportType importType) throws Exception {
        File importFile = copyTestFile("notePreferences.xml");
        // Randomize the preferences under test.
        // The import does NOT import all preferences, only
        // the sort order, whether to show categories, and
        // the currently selected category.
        NotePreferences prefs = NotePreferences.getInstance(
                InstrumentationRegistry.getTargetContext());
        long oldCategory = RAND.nextInt(100) + 1;
        int oldOrder = RAND.nextInt(10) + 1;
        prefs.edit()
                .setSelectedCategory(oldCategory)
                .setShowCategory(false)
                .setSortOrder(oldOrder)
                .finish();

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, importType, false);

        // Verify the results; this depends on the import type
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        switch (importType) {
            case CLEAN:
            case REVERT:
            case UPDATE:
                assertEquals("Selected category",
                        NoteCategory.UNFILED, prefs.getSelectedCategory());
                assertTrue("Show categories", prefs.showCategory());
                assertEquals("Sort order (index)",
                        0, prefs.getSortOrder());
                break;

            default:
                assertEquals("Selected category",
                        oldCategory, prefs.getSelectedCategory());
                assertFalse("Show categories", prefs.showCategory());
                assertEquals("Sort order (index)",
                        oldOrder, prefs.getSortOrder());
        }

    }

    @Test
    public void testImportPreferencesClean() throws Exception {
        testImportPreferences(ImportType.CLEAN);
    }

    @Test
    public void testImportPreferencesRevert() throws Exception {
        testImportPreferences(ImportType.REVERT);
    }

    @Test
    public void testImportPreferencesUpdate() throws Exception {
        testImportPreferences(ImportType.UPDATE);
    }

    @Test
    public void testImportPreferencesAdd() throws Exception {
        testImportPreferences(ImportType.ADD);
    }

    @Test
    public void testImportPreferencesTest() throws Exception {
        testImportPreferences(ImportType.TEST);
    }

    /**
     * A copy of the categories found in the file
     * &ldquo;noteCategories.xml&rdquo; for comparison after imports.
     * Depending on the type of import, the test may expect all
     * or a subset of these categories to match.
     */
    public static final Map<Long,String> FILE_CATEGORIES;
    static {
        Map<Long,String> m = new TreeMap<>();
        m.put((long) NoteCategory.UNFILED, "Unfiled");
        m.put(1L, "Alpha");
        m.put(2L, "Beta");
        m.put(3L, "Gamma");
        m.put(4L, "Delta");
        m.put(5L, "Epsilon");
        m.put(6L, "Zeta");
        m.put(7L, "Eta");
        m.put(8L, "Theta");
        m.put(9L, "Iota");
        m.put(10L, "Kappa");
        FILE_CATEGORIES = Collections.unmodifiableMap(m);
    }

    /**
     * Test importing categories in {@link ImportType#TEST TEST} mode.
     * This must result in no changes.
     */
    @Test
    public void testImportCategoriesTest() throws Exception {
        File importFile = copyTestFile("noteCategories.xml");
        // Set up the initial set of categories
        Map<Long,String> expectedCategories = new TreeMap<>();
        expectedCategories.put((long) NoteCategory.UNFILED, unfiledCategoryName);
        for (int i = 0; i < 10; i++) {
            NoteCategory category = mockRepo.insertCategory(
                    (char) ('A' + RAND.nextInt(26)) + randomWord());
            assertNotNull("Failed to add a test category", category);
            expectedCategories.put(category.getId(), category.getName());
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.TEST, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        Map<Long,String> actualCategories = new TreeMap<>();
        for (NoteCategory category : mockRepo.getCategories())
            actualCategories.put(category.getId(), category.getName());
        assertEquals("Categories after import",
                expectedCategories, actualCategories);
    }

    /**
     * Test importing categories in {@link ImportType#CLEAN CLEAN} mode.
     * This must result in all categories being replaced.
     */
    @Test
    public void testImportCategoriesClean() throws Exception {
        File importFile = copyTestFile("noteCategories.xml");
        // Set up the set of categories that should be wiped out
        for (int i = 0; i < 15; i++) {
            NoteCategory category = mockRepo.insertCategory(
                    (char) ('A' + RAND.nextInt(26)) + randomWord());
            assertNotNull("Failed to add a test category", category);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.CLEAN, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        Map<Long,String> actualCategories = new TreeMap<>();
        for (NoteCategory category : mockRepo.getCategories())
            actualCategories.put(category.getId(), category.getName());
        assertEquals("Categories after import",
                FILE_CATEGORIES, actualCategories);
    }

    /**
     * Test importing categories in {@link ImportType#REVERT REVERT}
     * (a.k.a. Overwrite} mode.  If a name conflicts with an existing
     * entry in the database (same name but different ID), all notes
     * using the current category ID will be changed to Unfiled.
     * If an ID conflicts with an existing entry in the database,
     * that category&rsquo;s name will be replaced with the imported name.
     * Otherwise existing categories will be left alone.
     */
    @Test
    public void testImportCategoriesOverwrite() throws Exception {
        File importFile = copyTestFile("noteCategories.xml");
        // Set up the current set of categories.  We need more
        // categories than will be imported (10), and at least one
        // of these must have the same name with a different ID.
        long conflictedName1 = RAND.nextInt(10) + 1;
        long conflictedName2 = RAND.nextInt(5) + 11;
        Map<Long,String> expectedCategories = new TreeMap<>();
        Map<Long,NoteItem> testNotes = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            NoteCategory category = mockRepo.insertCategory(
                    (char) ('A' + RAND.nextInt(26)) + randomWord());
            assertNotNull("Failed to add a test category", category);
            expectedCategories.put(category.getId(), category.getName());
            // Create a note using this category
            NoteItem note = randomNote();
            note.setCategoryId(category.getId());
            note.setPrivate(0);
            note = mockRepo.insertNote(note);
            assertNotNull("Failed to add a test note", note);
            testNotes.put(note.getCategoryId(), note);
        }
        for (int i = 11; i <= 15; i++) {
            NoteCategory category = mockRepo.insertCategory(
                    (char) ('A' + RAND.nextInt(26)) + randomWord());
            assertNotNull("Failed to add a test category", category);
            if (category.getId() == conflictedName2) {
                mockRepo.updateCategory(conflictedName2,
                        FILE_CATEGORIES.get(conflictedName1));
                // We expect this category to be deleted, so
                // exclude it from our expected categories map.
            } else {
                expectedCategories.put(category.getId(), category.getName());
            }
        }
        for (long categoryId : FILE_CATEGORIES.keySet())
            expectedCategories.put(categoryId, FILE_CATEGORIES.get(categoryId));

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.REVERT, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        Map<Long,String> actualCategories = new TreeMap<>();
        for (NoteCategory category : mockRepo.getCategories())
            actualCategories.put(category.getId(), category.getName());
        assertEquals("Categories after import",
                expectedCategories, actualCategories);

        for (NoteItem note : testNotes.values()) {
            NoteItem updatedNote = mockRepo.getNoteById(note.getId());
            assertNotNull(String.format(
                    "Note %d disappeared after the import!",
                    note.getId()), updatedNote);
            if (note.getCategoryId() == conflictedName2)
                assertEquals("Category ID of the note that was"
                        + " using a conflicting category name",
                        Long.valueOf(NoteCategory.UNFILED),
                        updatedNote.getCategoryId());
            else
                assertEquals("Category ID of note " + note.getId(),
                        note.getCategoryId(), updatedNote.getCategoryId());
        }

    }

    /**
     * Test importing categories in {@link ImportType#UPDATE UPDATE} or
     * {@link ImportType#ADD ADD} mode; the effect of both of these are
     * the same.  If we already have a category of the same name but a
     * different ID, the importer should use the existing ID.  If we have
     * another category with the same ID as one being imported, the imported
     * category will get a new ID.  Otherwise we&rsquo;ll try to add the
     * category with its given ID.
     */
    // To Do
    private void testImportCategoriesMerge(ImportType importType)
            throws Exception {
        File importFile = copyTestFile("noteCategories.xml");
        // Set up the current set of categories.  We need fewer
        // categories than well be imported (10), and at least one
        // of these must have the same name with a different ID.
        long conflictedId1 = RAND.nextInt(5) + 1;
        long conflictedName1 = RAND.nextInt(5) + 6;
        Map<Long,String> originalCategories = new TreeMap<>();
        Set<String> expectedCategories = new TreeSet<>();
        Map<Long,NoteItem> testNotes = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            String catName = (i == conflictedName1)
                    ? FILE_CATEGORIES.get(conflictedName1)
                    : (char) ('A' + RAND.nextInt(26)) + randomWord();
            NoteCategory category = mockRepo.insertCategory(catName);
            assertNotNull(String.format("Failed to add test category \"%s\"",
                    catName), category);
            originalCategories.put(category.getId(), category.getName());
            expectedCategories.add(catName);
            // Create a note using this category
            NoteItem note = randomNote();
            note.setCategoryId(category.getId());
            note.setPrivate(0);
            note = mockRepo.insertNote(note);
            assertNotNull("Failed to add a test note", note);
            testNotes.put(note.getCategoryId(), note);
        }
        expectedCategories.addAll(FILE_CATEGORIES.values());

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, importType, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        // We don't care about the ID's of imported categories,
        // just that the ID's of the original categories haven't changed.
        Map<Long,String> checkCategories = new TreeMap<>();
        Set<String> actualCategories = new TreeSet<>();
        for (NoteCategory category : mockRepo.getCategories()) {
            if (originalCategories.containsKey(category.getId()))
                checkCategories.put(category.getId(), category.getName());
            actualCategories.add(category.getName());
        }

        assertEquals("Original categories",
                originalCategories, checkCategories);
        assertEquals("Set of new categories",
                expectedCategories, actualCategories);

        for (NoteItem note : testNotes.values()) {
            NoteItem updatedNote = mockRepo.getNoteById(note.getId());
            assertNotNull(String.format(
                    "Note %d disappeared after the import!",
                    note.getId()), updatedNote);
            assertEquals("Category ID of note " + note.getId(),
                    note.getCategoryId(), updatedNote.getCategoryId());
        }

    }

    @Test
    public void testImportCategoriesUpdate() throws Exception {
        testImportCategoriesMerge(ImportType.UPDATE);
    }

    @Test
    public void testImportCategoriesAdd() throws Exception {
        testImportCategoriesMerge(ImportType.ADD);
    }

    /**
     * A copy of notes found in the file &ldquo;notesPublic1.xml&rdquo;
     * for comparison after imports.  Depending on the type of import,
     * the test may expect all or a subset of these notes to match.
     */
    public static final Map<Long,NoteItem> FILE1_NOTES;
    static {
        Map<Long,NoteItem> m = new TreeMap<>();

        NoteItem note = new NoteItem();
        note.setId(1);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1766704912000L);
        note.setModTime(1766704913000L);
        note.setPrivate(0);
        note.setNote("Hello, world.");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(2);
        note.setCategoryId(1);
        note.setCategoryName("Alpha Squadron");
        note.setCreateTime(1766765819000L);
        note.setModTime(1766766046000L);
        note.setPrivate(0);
        note.setNote("The Flying Dutchman\n\n"
                + "July 11th. At 4 a.m. the Flying Dutchman crossed our bows."
                + " A strange red light as of a phantom ship all aglow,"
                + " in the midst of which light the masts, spars and sails"
                + " of a brig 200 yards distant stood out in strong relief"
                + " as she came up on the port bow, where also the officer"
                + " of the watch from the bridge clearly saw her, as did the"
                + " quarterdeck midshipman, who was sent forward at once to"
                + " the forecastle; but on arriving there was no vestige nor"
                + " any sign whatever of any material ship was to be seen"
                + " either near or right away to the horizon, the night being"
                + " clear and the sea calm. Thirteen persons altogether saw"
                + " her ... At 10.45 a.m. the ordinary seaman who had this"
                + " morning reported the Flying Dutchman fell from the"
                + " foretopmast crosstrees on to the topgallant forecastle"
                + " and was smashed to atoms.");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(3);
        note.setCategoryId(2);
        note.setCategoryName("Grey 17");
        note.setCreateTime(1766766321000L);
        note.setModTime(1766766401000L);
        note.setPrivate(1);
        note.setNote("What I am about to show you has not been seen"
                + " by anyone outside the Grey Council.");
        m.put(note.getId(), note);

        FILE1_NOTES = Collections.unmodifiableMap(m);
    }

    /**
     * <i>Partial</i> copy of notes found in the file
     * &ldquo;notesEncrypted2.xml&rdquo; for comparison after imports.
     * This does not include the encrypted text, since that&rsquo;s
     * dependent on the file password (which is stored as an external
     * asset) and is typically changed when the file is imported
     * according to the current password in the mock database.
     */
    public static Map<Long,NoteItem> FILE2_NOTES;
    static {
        Map<Long,NoteItem> m = new TreeMap<>();

        NoteItem note = new NoteItem();
        note.setId(4);
        note.setCategoryId(3);
        note.setCategoryName("Purple Files");
        note.setCreateTime(1766770036000L);
        note.setModTime(1766770038000L);
        note.setPrivate(2);
        m.put(note.getId(), note);

        FILE2_NOTES = Collections.unmodifiableMap(m);
    }

    /**
     * A copy of notes found in the file &ldquo;notesPublic3.xml&rdquo;
     * for comparison after imports.  Depending on the type of import,
     * the test may expect all or a subset of these notes to match.
     */
    public static final Map<Long,NoteItem> FILE3_NOTES;
    static {
        Map<Long,NoteItem> m = new TreeMap<>();

        NoteItem note = new NoteItem();
        note.setId(11);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1766620800000L);
        note.setModTime(1766707199000L);
        note.setPrivate(0);
        note.setNote("Merry Christmas!");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(12);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1767225600000L);
        note.setModTime(1767311999000L);
        note.setPrivate(0);
        note.setNote("Happy New Year!");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(13);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1835395200000L);
        note.setModTime(1835481599000L);
        note.setPrivate(0);
        note.setNote("Leap Day");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(14);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(2147483646000L);
        note.setModTime(2147483648000L);
        note.setPrivate(0);
        note.setNote("Y2038");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(15);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(325987200000L);
        note.setModTime(326073599000L);
        note.setPrivate(1);
        note.setNote("Mayday!");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(16);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(32529949261000L);
        note.setModTime(32530759810000L);
        note.setPrivate(1);
        note.setNote("Robomadan");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(17);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1771286400000L);
        note.setModTime(1772582399000L);
        note.setPrivate(1);
        note.setNote("\u6625\u7bc0");
        m.put(note.getId(), note);

        note = new NoteItem();
        note.setId(18);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName("Unfiled");
        note.setCreateTime(1321578000000L);
        note.setModTime(1765243271000L);
        note.setPrivate(1);
        note.setNote("Craft Mine");
        m.put(note.getId(), note);

        FILE3_NOTES = Collections.unmodifiableMap(m);
    }

    /**
     * Read all current notes from the mock repository.
     * This is used to check the results in several test cases.
     */
    private Map<Long,NoteItem> getAllNotes() {
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        NoteCursor c = mockRepo.getNotes(ALL_CATEGORIES, true, true,
                NOTE_TABLE_NAME + "." + NoteItemColumns._ID);
        try {
            while (c.moveToNext()) {
                NoteItem note = c.getNote();
                actualNotes.put(note.getId(), note);
            }
        } finally {
            c.close();
        }
        return actualNotes;
    }

    /**
     * Test importing notes in {@link ImportType#TEST TEST} mode.
     * This must not import any notes.
     */
    @Test
    public void testImportNotesTest() throws Exception {
        File importFile = copyTestFile("notesPublic1.xml");
        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (int i = 0; i < 5; i++) {
            NoteItem note = randomNote();
            note.setCategoryId(NoteCategory.UNFILED);
            note.setPrivate(0);
            note = mockRepo.insertNote(note);
            assertNotNull("Failed to add a test note", note);
            // The category name should be included when we retrieve this later
            note.setCategoryName(unfiledCategoryName);
            expectedNotes.put(note.getId(), note);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.TEST, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());
        Map<Long,NoteItem> actualNotes = getAllNotes();
        assertEquals("Notes after import", expectedNotes, actualNotes);
    }

    /**
     * Test importing notes in {@link ImportType#CLEAN CLEAN} mode,
     * excluding private notes.  This must result in all notes
     * being replaced.
     */
    @Test
    public void testImportNotesCleanPublic() throws Exception {
        File importFile = copyTestFile("notesPublic1.xml");
        for (int i = 0; i < 5; i++) {
            NoteItem note = randomNote();
            note.setCategoryId(NoteCategory.UNFILED);
            note.setPrivate(0);
            note = mockRepo.insertNote(note);
            assertNotNull("Failed to add a test note", note);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.CLEAN, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (NoteItem note : FILE1_NOTES.values()) {
            if (!note.isPrivate())
                expectedNotes.put(note.getId(), note);
        }
        Map<Long,NoteItem> actualNotes = getAllNotes();
        assertEquals("Notes after import", expectedNotes, actualNotes);
    }

    /**
     * Test importing notes in {@link ImportType#CLEAN CLEAN} mode,
     * including private notes but not encrypted (no password given).
     * This must result in all notes being replaced.
     */
    @Test
    public void testImportNotesCleanPrivate() throws Exception {
        File importFile = copyTestFile("notesPublic1.xml");
        for (int i = 0; i < 5; i++) {
            NoteItem note = randomNote();
            note.setCategoryId(NoteCategory.UNFILED);
            note.setPrivate(0);
            note = mockRepo.insertNote(note);
            assertNotNull("Failed to add a test note", note);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.CLEAN, true);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (NoteItem note : FILE1_NOTES.values()) {
            if (!note.isEncrypted())
                expectedNotes.put(note.getId(), note);
        }
        Map<Long,NoteItem> actualNotes = getAllNotes();
        assertEquals("Notes after import", expectedNotes, actualNotes);
    }

    /**
     * Test importing notes in {@link ImportType#CLEAN CLEAN} mode,
     * including encrypted notes.  This must result in all notes
     * being replaced.  Additionally, all encrypted notes should be
     * re-encrypted using the current password.
     */
    @Test
    public void testImportNotesCleanEncrypted() throws Exception {
        File importFile = copyTestFile("notesEncrypted2.xml");
        NoteItem victim = randomNote();
        victim.setCategoryId(NoteCategory.UNFILED);
        victim.setPrivate(0);
        victim = mockRepo.insertNote(victim);
        assertNotNull("Failed to add a test note", victim);

        String filePassword = readTextFile("password.txt");
        String expectedText = readTextFile("note4clear.txt");
        String dbPassword = RandomStringUtils.randomAlphanumeric(8);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(dbPassword.toCharArray());
            se.storePassword(mockRepo);

            // Run the test
            ImportTestObserver observer = runImporter(
                    importFile, ImportType.CLEAN, true, filePassword);

            // Verify the results
            assertTrue("Importer did not finish successfully",
                    observer.isSuccess());

            NoteItem expectedNote = FILE2_NOTES.get(4L);
            NoteItem actualNote = mockRepo.getNoteById(4);
            assertNotNull("Note 4 was not imported", actualNote);
            // Compare everything in the note except its content
            NoteItem noteContext = actualNote.clone();
            noteContext.setEncryptedNote(null);
            noteContext.setNote(null);
            assertEquals("Note 4 context (all but content)",
                    expectedNote, noteContext);
            // Attempt to decrypt the note and compare its clear text
            try {
                String actualText = se.decrypt(actualNote.getEncryptedNote());
                assertEquals("Note 4 clear text", expectedText, actualText);
            } catch (GeneralSecurityException gsx) {
                fail("Note was not re-encrypted with current password");
            }

            assertNull("Original note was not cleared",
                    mockRepo.getNoteById(victim.getId()));
        } finally {
            se.forgetPassword();
            StringEncryption.releaseGlobalEncryption();
        }
    }

    /**
     * Test importing encrypted notes in any mode if the user has not
     * provided a password or the password is incorrect.  This must not
     * change any existing notes in the repository.
     */
    private void testImportEncryptedNotesBadPassword(
            ImportType importType, String filePassword) throws Exception {
        File importFile = copyTestFile("notesEncrypted2.xml");
        List<NoteItem> expectedNotes = new ArrayList<>();
        String dbPassword = RandomStringUtils.randomAlphanumeric(8);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(dbPassword.toCharArray());
            se.storePassword(mockRepo);

            for (int i = 0; i < 5; i++) {
                NoteItem note = randomNote();
                note.setPrivate(RAND.nextInt(3));
                if (note.isEncrypted()) {
                    note.setEncryptedNote(se.encrypt(note.getNote()));
                    note.setNote(null);
                }
                note = mockRepo.insertNote(note);
                assertNotNull("Failed to add a test note", note);
                expectedNotes.add(note);
            }

            // Run the test
            ImportTestObserver observer = runImporter(
                    importFile, importType, true, filePassword);

            // Verify the results
            assertFalse("Importer finished successfully",
                    observer.isSuccess());
            assertTrue("No toast messages were shown",
                    observer.getNumberOfToasts() > 0);
            assertTrue("Toast message is not related to the password",
                    observer.toastsContains("password"));

            for (NoteItem expectedNote : expectedNotes) {
                NoteItem actualNote = mockRepo.getNoteById(expectedNote.getId());
                assertEquals(String.format("Note %d after failed import",
                        expectedNote.getId()), expectedNote, actualNote);
            }
        } finally {
            se.forgetPassword();
            StringEncryption.releaseGlobalEncryption();
        }
    }

    @Test
    public void testImportEncryptedNotesTestNoPassword() throws Exception {
        testImportEncryptedNotesBadPassword(ImportType.TEST, null);
    }

    @Test
    public void testImportEncryptedNotesTestWrongPassword() throws Exception {
        String badPassword = RandomStringUtils.randomAlphanumeric(8);
        testImportEncryptedNotesBadPassword(ImportType.TEST, badPassword);
    }

    @Test
    public void testImportEncryptedNotesCleanNoPassword() throws Exception {
        testImportEncryptedNotesBadPassword(ImportType.CLEAN, null);
    }

    @Test
    public void testImportEncryptedNotesCleanWrongPassword() throws Exception {
        String badPassword = RandomStringUtils.randomAlphanumeric(8);
        testImportEncryptedNotesBadPassword(ImportType.CLEAN, badPassword);
    }

    @Test
    public void testImportEncryptedNotesOverwriteNoPassword() throws Exception {
        testImportEncryptedNotesBadPassword(ImportType.REVERT, null);
    }

    @Test
    public void testImportEncryptedNotesOverwriteWrongPassword() throws Exception {
        String badPassword = RandomStringUtils.randomAlphanumeric(8);
        testImportEncryptedNotesBadPassword(ImportType.REVERT, badPassword);
    }

    @Test
    public void testImportEncryptedNotesUpdateNoPassword() throws Exception {
        testImportEncryptedNotesBadPassword(ImportType.UPDATE, null);
    }

    @Test
    public void testImportEncryptedNotesUpdateWrongPassword() throws Exception {
        String badPassword = RandomStringUtils.randomAlphanumeric(8);
        testImportEncryptedNotesBadPassword(ImportType.UPDATE, badPassword);
    }

    @Test
    public void testImportEncryptedNotesAddNoPassword() throws Exception {
        testImportEncryptedNotesBadPassword(ImportType.ADD, null);
    }

    @Test
    public void testImportEncryptedNotesAddWrongPassword() throws Exception {
        String badPassword = RandomStringUtils.randomAlphanumeric(8);
        testImportEncryptedNotesBadPassword(ImportType.ADD, badPassword);
    }

    /**
     * Test importing notes in {@link ImportType#REVERT REVERT} (Overwrite)
     * mode, excluding private notes.  This means if two notes have the same
     * {@code id} and {@code createTime}, the file&rsquo;s note overwrites
     * the current note.  If two notes have the same {@code id} but different
     * {@code createTime}s, the current note is left alone and the note
     * from the file is added with a new {@code id}.
     */
    @Test
    public void testImportNotesOverwritePublic() throws Exception {
        File importFile = copyTestFile("notesPublic1.xml");
        // First note will be the case of the DB having
        // an updated version of the note
        NoteItem oldNote1 = FILE1_NOTES.get(1L).clone();
        oldNote1.setNote(randomSentence());
        oldNote1.setModTimeNow();
        assertNotNull("Failed to add test note 1",
                mockRepo.insertNote(oldNote1));
        // Second note will be the case of different create times
        NoteItem oldNote2 = randomNote();
        oldNote2.setId(2);
        oldNote2.setPrivate(0);
        assertNotNull("Failed to add test note 2",
                mockRepo.insertNote(oldNote2));
        // Third note is private; it should not be modified
        // despite having a match
        NoteCategory privateCategory = new NoteCategory();
        NoteItem oldNote3 = FILE1_NOTES.get(3L).clone();
        privateCategory.setId(oldNote3.getCategoryId());
        privateCategory.setName(oldNote3.getCategoryName());
        oldNote3.setNote(randomSentence());
        oldNote3.setModTimeNow();
        assertNotNull("Failed to add category for test note 3",
                mockRepo.insertCategory(privateCategory));
        assertNotNull("Failed to add test note 3",
                mockRepo.insertNote(oldNote3));

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.REVERT, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();

        assertEquals("Note 1 after import",
                FILE1_NOTES.get(1L), actualNotes.get(1L));
        assertEquals("Note 2 after import",
                oldNote2, actualNotes.get(2L));
        assertEquals("Note 3 after import",
                oldNote3, actualNotes.get(3L));

        // Note 2 from the file should have been imported with a new ID;
        // we won't rely on any specific ID.
        boolean found = false;
        for (NoteItem note : actualNotes.values()) {
            if (note.getNote().equals(FILE1_NOTES.get(2L).getNote())) {
                found = true;
                break;
            }
        }
        assertTrue("Note 2 from the file was not imported", found);

        // Finally, make sure the private note from the import file
        // was NOT imported.
        for (NoteItem note : actualNotes.values()) {
            assertNotEquals("Private note from the file was imported",
                    note.getNote(), FILE1_NOTES.get(3L).getNote());
        }
    }

    /**
     * Test importing notes in {@link ImportType#REVERT REVERT} (Overwrite)
     * mode, including private notes.  See
     * {@link #testImportNotesOverwritePublic()} for details on merge logic.
     */
    @Test
    public void testImportNotesOverwritePrivate() throws Exception {
        File importFile = copyTestFile("notesPublic1.xml");
        // First note will be the case of different create times
        NoteItem oldNote1 = randomNote();
        oldNote1.setId(1);
        oldNote1.setPrivate(0);
        assertNotNull("Failed to add test note 1",
                mockRepo.insertNote(oldNote1));
        // For the second note, we're going to use a category from
        // the import file that's in a different position.
        // This should be changed to Unfiled.
        NoteCategory revisedCategory = new NoteCategory();
        NoteItem oldNote2 = randomNote();
        oldNote2.setId(2);
        oldNote2.setPrivate(0);
        revisedCategory.setId(1);
        revisedCategory.setName("Purple Files");
        assertNotNull("Failed to add category for test note 2",
                mockRepo.insertCategory(revisedCategory));
        assertNotNull("Failed to add test note 2",
                mockRepo.insertNote(oldNote2));
        // Third note will be an updated version of note 3 from the file
        NoteCategory privateCategory = new NoteCategory();
        NoteItem oldNote3 = FILE1_NOTES.get(3L).clone();
        privateCategory.setId(oldNote3.getCategoryId());
        privateCategory.setName(oldNote3.getCategoryName());
        oldNote3.setNote(randomSentence());
        oldNote3.setModTimeNow();
        assertNotNull("Failed to add category for test note 3",
                mockRepo.insertCategory(privateCategory));
        assertNotNull("Failed to add test note 3",
                mockRepo.insertNote(oldNote3));

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.REVERT, true);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();
        // We'll also need the new ID's of categories for reference
        Map<String,Long> categoryIdMap = new HashMap<>();
        for (NoteCategory category : mockRepo.getCategories())
            categoryIdMap.put(category.getName(), category.getId());

        assertEquals("Note 1 after import",
                oldNote1, actualNotes.get(1L));
        oldNote2.setCategoryId(NoteCategory.UNFILED);
        oldNote2.setCategoryName(unfiledCategoryName);
        assertEquals("Note 2 after import",
                oldNote2, actualNotes.get(2L));
        NoteItem expectedNote = FILE1_NOTES.get(3L).clone();
        expectedNote.setCategoryId(categoryIdMap.get(oldNote3.getCategoryName()));
        assertEquals("Note 3 after import",
                expectedNote, actualNotes.get(3L));

        // Note 1 from the file should have been imported with a new ID;
        // we won't rely on any specific ID.
        boolean found = false;
        for (NoteItem note : actualNotes.values()) {
            if (note.getNote().equals(FILE1_NOTES.get(1L).getNote())) {
                found = true;
                break;
            }
        }
        assertTrue("Note 1 from the file was not imported", found);
    }

    /**
     * Test importing notes in {@link ImportType#UPDATE UPDATE} mode,
     * excluding private notes.  This means if two notes have the same
     * {@code id} and {@code createTime}, the one with the newer
     * {@code modTime} takes precedence.  If two notes have the same
     * {@code id} but different {@code createTime}s, the current note
     * is left alone and the note from the file is added with a new
     * {@code id}.
     */
    @Test
    public void testImportNotesUpdatePublic() throws Exception {
        File importFile = copyTestFile("notesPublic3.xml");
        // First note will be the case that the file's note is newer
        NoteItem oldNote11 = FILE3_NOTES.get(11L).clone();
        oldNote11.setModTime(oldNote11.getModTime() - 3600000L);
        oldNote11.setNote(randomSentence());
        assertNotNull("Failed to add test note 11",
                mockRepo.insertNote(oldNote11));
        // Second note will be the case that the DB note is newer
        NoteItem oldNote12 = FILE3_NOTES.get(12L).clone();
        oldNote12.setModTime(oldNote12.getModTime() + 3600000L);
        oldNote12.setNote(randomSentence());
        assertNotNull("Failed to add test note 12",
                mockRepo.insertNote(oldNote12));
        // Third note will be the case that the create times differ
        NoteItem oldNote13 = randomNote();
        oldNote13.setId(13);
        oldNote13.setPrivate(0);
        assertNotNull("Failed to add test note 13",
                mockRepo.insertNote(oldNote13));

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.UPDATE, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();

        assertEquals("Note 11 after import",
                FILE3_NOTES.get(11L), actualNotes.get(11L));
        assertEquals("Note 12 after import",
                oldNote12, actualNotes.get(12L));
        assertEquals("Note 13 after import",
                oldNote13, actualNotes.get(13L));

        // Note 13 from the file should have been imported with a new ID;
        // this may have impacted the ID assigned to note 14.
        boolean found13 = false;
        boolean found14 = false;
        for (NoteItem note : actualNotes.values()) {
            if (note.getNote().equals(FILE3_NOTES.get(13L).getNote()))
                found13 = true;
            if (note.getNote().equals(FILE3_NOTES.get(14L).getNote()))
                found14 = true;
        }
        assertTrue("Note 13 from the file was not imported", found13);
        assertTrue("Note 14 from the file was not imported", found14);

        // Make sure no private notes were imported
        for (NoteItem note : actualNotes.values())
            assertFalse("Private note from the file was imported",
                note.isPrivate());
    }

    /**
     * Test importing notes in {@link ImportType#UPDATE UPDATE} mode,
     * including private notes.  See
     * {@link #testImportNotesUpdatePublic()} for details on merge logic.
     */
    @Test
    public void testImportNotesUpdatePrivate() throws Exception {
        File importFile = copyTestFile("notesPublic3.xml");
        // First each of public and private notes
        // will be the case that the file's note is newer
        NoteItem oldNote11 = FILE3_NOTES.get(11L).clone();
        oldNote11.setModTime(oldNote11.getModTime() - 3600000L);
        oldNote11.setNote(randomSentence());
        assertNotNull("Failed to add test note 11",
                mockRepo.insertNote(oldNote11));
        NoteItem oldNote15 = FILE3_NOTES.get(15L).clone();
        oldNote15.setModTime(oldNote15.getModTime() - 3600000L);
        oldNote15.setNote(randomSentence());
        assertNotNull("Failed to add test note 15",
                mockRepo.insertNote(oldNote15));
        // Second will be the case that the DB note is newer
        NoteItem oldNote12 = FILE3_NOTES.get(12L).clone();
        oldNote12.setModTime(oldNote12.getModTime() + 3600000L);
        oldNote12.setNote(randomSentence());
        assertNotNull("Failed to add test note 12",
                mockRepo.insertNote(oldNote12));
        NoteItem oldNote16 = FILE3_NOTES.get(16L).clone();
        oldNote16.setModTime(oldNote16.getModTime() + 3600000L);
        oldNote16.setNote(randomSentence());
        assertNotNull("Failed to add test note 16",
                mockRepo.insertNote(oldNote16));
        // Third will be the case that the create times differ
        NoteItem oldNote13 = randomNote();
        oldNote13.setId(13);
        oldNote13.setPrivate(0);
        assertNotNull("Failed to add test note 13",
                mockRepo.insertNote(oldNote13));
        NoteItem oldNote17 = randomNote();
        oldNote17.setId(17);
        oldNote17.setPrivate(1);
        assertNotNull("Failed to add test note 17",
                mockRepo.insertNote(oldNote17));

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.UPDATE, true);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();

        assertEquals("Note 11 after import",
                FILE3_NOTES.get(11L), actualNotes.get(11L));
        assertEquals("Note 12 after import",
                oldNote12, actualNotes.get(12L));
        assertEquals("Note 13 after import",
                oldNote13, actualNotes.get(13L));
        assertEquals("Note 15 after import",
                FILE3_NOTES.get(15L), actualNotes.get(15L));
        assertEquals("Note 16 after import",
                oldNote16, actualNotes.get(16L));
        assertEquals("Note 17 after import",
                oldNote17, actualNotes.get(17L));

        // Note 13 from the file should have been imported with a new ID;
        // this may have impacted the ID assigned to note 14, which would
        // then cascade to notes 17 and 18.
        boolean found13 = false;
        boolean found14 = false;
        boolean found17 = false;
        boolean found18 = false;
        for (NoteItem note : actualNotes.values()) {
            if (note.getNote().equals(FILE3_NOTES.get(13L).getNote()))
                found13 = true;
            if (note.getNote().equals(FILE3_NOTES.get(14L).getNote()))
                found14 = true;
            if (note.getNote().equals(FILE3_NOTES.get(17L).getNote()))
                found17 = true;
            if (note.getNote().equals(FILE3_NOTES.get(18L).getNote()))
                found18 = true;
        }
        assertTrue("Note 13 from the file was not imported", found13);
        assertTrue("Note 14 from the file was not imported", found14);
        assertTrue("Note 17 from the file was not imported", found17);
        assertTrue("Note 18 from the file was not imported", found18);
    }

    /**
     * Test importing notes in {@link ImportType#ADD ADD} mode, excluding
     * private notes.  All non-private notes from the file are added with
     * new ID&rsquo;s disregarding any existing notes in the repository.
     */
    @Test
    public void testImportNotesAddPublic() throws Exception {
        File importFile = copyTestFile("notesPublic3.xml");
        Map<Long,NoteItem> expectedOriginalNotes = new TreeMap<>();
        for (NoteItem note : FILE3_NOTES.values()) {
            NoteItem noteClone = note.clone();
            noteClone.setNote(randomSentence());
            noteClone = mockRepo.insertNote(noteClone);
            assertNotNull("Failed to add test note "
                    + note.getId(), noteClone);
            expectedOriginalNotes.put(noteClone.getId(), noteClone);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.ADD, false);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();
        for (NoteItem note : expectedOriginalNotes.values())
            assertEquals("Note " + note.getId() + " after import",
                    note, actualNotes.get(note.getId()));

        // Remove all the original notes and check that the remaining
        // notes contain all non-private notes from the import file
        for (long id : expectedOriginalNotes.keySet())
            actualNotes.remove(id);
        Map<Long,NoteItem> expectedNewNotes = new TreeMap<>();
        for (NoteItem note : FILE3_NOTES.values())
            if (!note.isPrivate()) {
                boolean found = false;
                for (NoteItem note2 : actualNotes.values())
                    if (note.getNote().equals(note2.getNote())) {
                        NoteItem noteClone = note.clone();
                        noteClone.setId(note2.getId());
                        expectedNewNotes.put(noteClone.getId(), noteClone);
                        found = true;
                        break;
                    }
                if (!found)
                    expectedNewNotes.put(note.getId() + 999000, note);
            }
        assertEquals("New notes imported", expectedNewNotes, actualNotes);
    }

    /**
     * Test importing notes in {@link ImportType#ADD ADD} mode, including
     * private notes.  All non-private notes from the file are added with
     * new ID&rsquo;s disregarding any existing notes in the repository.
     */
    @Test
    public void testImportNotesAddPrivate() throws Exception {
        File importFile = copyTestFile("notesPublic3.xml");
        Map<Long,NoteItem> expectedOriginalNotes = new TreeMap<>();
        for (NoteItem note : FILE3_NOTES.values()) {
            NoteItem noteClone = note.clone();
            noteClone.setNote(randomSentence());
            noteClone = mockRepo.insertNote(noteClone);
            assertNotNull("Failed to add test note "
                    + note.getId(), noteClone);
            expectedOriginalNotes.put(noteClone.getId(), noteClone);
        }

        // Run the test
        ImportTestObserver observer = runImporter(
                importFile, ImportType.ADD, true);

        // Verify the results
        assertTrue("Importer did not finish successfully",
                observer.isSuccess());

        Map<Long,NoteItem> actualNotes = getAllNotes();
        for (NoteItem note : expectedOriginalNotes.values())
            assertEquals("Note " + note.getId() + " after import",
                    note, actualNotes.get(note.getId()));

        // Remove all the original notes and check that the remaining
        // notes contain all notes from the import file, including private ones
        for (long id : expectedOriginalNotes.keySet())
            actualNotes.remove(id);
        Map<Long,NoteItem> expectedNewNotes = new TreeMap<>();
        for (NoteItem note : FILE3_NOTES.values()) {
            boolean found = false;
            for (NoteItem note2 : actualNotes.values())
                if (note.getNote().equals(note2.getNote())) {
                    NoteItem noteClone = note.clone();
                    noteClone.setId(note2.getId());
                    expectedNewNotes.put(noteClone.getId(), noteClone);
                    found = true;
                    break;
                }
            if (!found)
                expectedNewNotes.put(note.getId() + 999000, note);
        }
        assertEquals("New notes imported", expectedNewNotes, actualNotes);
    }

}
