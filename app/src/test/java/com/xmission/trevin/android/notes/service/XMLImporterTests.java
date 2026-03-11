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

import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.*;

import static org.junit.Assert.*;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema;
import com.xmission.trevin.android.notes.service.XMLImporter.ImportType;
import com.xmission.trevin.android.notes.util.*;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit tests for importing notes from an XML file.
 *
 * @author Trevin Beattie
 */
public class XMLImporterTests {

    public static final Random RAND = new Random();
    public static final RandomStringUtils SRAND = RandomStringUtils.insecure();

    private static MockSharedPreferences underlyingPrefs = null;
    private static NotePreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;
    private StringEncryption globalEncryption = null;
    private static final String unfiledName = "Unfiled";

    @BeforeClass
    public static void initializeMocks() {
        if (mockPrefs == null) {
            underlyingPrefs = MockSharedPreferences.getInstance();
            NotePreferences.setSharedPreferences(underlyingPrefs);
            mockPrefs = NotePreferences.getInstance(null);
        }
        if (mockRepo == null) {
            mockRepo = MockNoteRepository.getInstance();
        }
    }

    @Before
    public void resetMocks() {
        underlyingPrefs.resetMock();
        mockRepo.clear();
        globalEncryption = StringEncryption.holdGlobalEncryption();
    }

    @After
    public void cleanUp() {
        globalEncryption.forgetPassword();
        StringEncryption.releaseGlobalEncryption();
    }

    /**
     * Run the importer for a given test file.  The operation is expected
     * to run successfully.
     *
     * @param inFileName the name of the XML file to import (relative to
     * the &ldquo;resources/&rdquo; directory).
     * @param importType the type of import to perform.
     * @param importPrivate whether to include private records.
     * @param xmlPassword the password with which the XML file was exported,
     * or {@code null}
     *
     * @return the progress indicator at the end of the import
     *
     * @throws IOException if there was an error reading the import file.
     * @throws RuntimeException if the import failed.
     */
    private MockProgressBar runImporter(
            String inFileName, ImportType importType,
            boolean importPrivate, String xmlPassword)
        throws IOException {
        String currentPassword = null;
        if (globalEncryption.hasKey())
            currentPassword = new String(globalEncryption.getPassword());
        MockProgressBar progress = new MockProgressBar();
        if (!inFileName.startsWith("/"))
            inFileName = "/" + inFileName;
        InputStream inStream = getClass().getResourceAsStream(inFileName);
        assertNotNull(String.format("Import test file %s not found",
                inFileName), inStream);
        try {
            XMLImporter.importData(mockPrefs, mockRepo,
                    inFileName, inStream, importType,
                    importPrivate, xmlPassword, currentPassword,
                    progress);
            progress.setEndTime();
        } finally {
            inStream.close();
        }
        return progress;
    }

    /**
     * <b>TEMPORARY TEST &mdash;</b> test reading a backup of Note Pad
     * data from an actual phone running an older version of the app
     * (version 1 XML data).  This backup file <i>must not</i>
     * be committed with the source files.  For security, the password
     * must be given as an environment variable.
     */
    //Test
    public void testImportPhoneV1() throws IOException {
        String xmlPassword = System.getenv("NOTEPAD_PASSWORD1");
        runImporter("notes-phone-1.xml", ImportType.TEST, true, xmlPassword);
    }

    /**
     * <b>TEMPORARY TEST &mdash;</b> test reading a backup of Note Pad
     * data from an actual tablet running an older version of the app
     * (version 1 XML data).  This backup file <i>must not</i>
     * be committed with the source files.  For security, the password
     * must be given as an environment variable.
     */
    //Test
    public void testImportTabletV1() throws IOException {
        String xmlPassword = System.getenv("NOTEPAD_PASSWORD2");
        runImporter("notes-tablet-1.xml", ImportType.TEST, true, xmlPassword);
    }

    /**
     * Test reading a version 2 XML file with no notes.
     */
    @Test
    public void testImportEmpty2() throws IOException {
        MockProgressBar progress = runImporter("notes-empty-2.xml",
                ImportType.TEST, false, null);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        assertEquals("Total records in file", 0, endProgress.total);
        assertEquals("Number of records processed", 0, endProgress.current);
    }

    /**
     * Test reading preferences.
     */
    @Test
    public void testImportPreferences() throws IOException {
        /*
         * The import type doesn't matter for this section,
         * as long as it's not TEST.  Since the mock starts out empty,
         * we should have a value for every item in the input file.
         */
        MockProgressBar progress = runImporter("notes-preferences.xml",
                ImportType.CLEAN, false, null);

        assertEquals(NPREF_SORT_ORDER, 853, mockPrefs.getSortOrder());
        // The importer should not have changed the "Show Private" flag.
        assertEquals(NPREF_SHOW_PRIVATE, null,
                underlyingPrefs.getPreference(NPREF_SHOW_PRIVATE));
        // The importer MUST NOT have changed the "Show Encrypted" flag.
        assertEquals(NPREF_SHOW_ENCRYPTED, null,
                underlyingPrefs.getPreference(NPREF_SHOW_ENCRYPTED));
        assertEquals(NPREF_SHOW_CATEGORY, true, mockPrefs.showCategory());
        assertEquals(NPREF_SELECTED_CATEGORY, 23465L, mockPrefs.getSelectedCategory());
        // We do not expect the importer to change any of the
        // Export / Import preferences.
        assertEquals(NPREF_EXPORT_FILE, null,
                underlyingPrefs.getPreference(NPREF_EXPORT_FILE));
        assertEquals(NPREF_EXPORT_PRIVATE, null,
                underlyingPrefs.getPreference(NPREF_EXPORT_PRIVATE));
        assertEquals(NPREF_IMPORT_FILE, null,
                underlyingPrefs.getPreference(NPREF_IMPORT_FILE));
        assertEquals(NPREF_IMPORT_TYPE, null,
                underlyingPrefs.getPreference(NPREF_IMPORT_TYPE));
        assertEquals(NPREF_IMPORT_PRIVATE, null,
                underlyingPrefs.getPreference(NPREF_IMPORT_PRIVATE));

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        assertEquals("Total records in file", 11, endProgress.total);
        assertEquals("Number of records processed", 11, endProgress.current);
    }

    /**
     * Test reading preferences in {@link ImportType#TEST TEST} mode.
     * This should not set any preferences.
     */
    @Test
    public void testImportPreferencesTest() throws IOException {
        runImporter("notes-preferences.xml", ImportType.TEST, false, null);

        assertTrue("Preferences were changed",
                underlyingPrefs.getAll().isEmpty());
    }

    /**
     * Test reading public metadata.
     * This does not include checking the password.
     */
    @Test
    public void testImportPublicMetadata() throws IOException {
        /*
         * The import type doesn't matter for this section;
         * the app doesn't do anything with any metadata
         *  other than the password hash.
         */
        runImporter("notes-metadata.xml", ImportType.TEST, false, null);
    }

    /**
     * Test checking the password against the metadata stored
     * in the XML file.  This relies on a pre-determined password
     * whose hash was computed when the file was created.
     */
    @Test
    public void testCheckXMLPasswordMatch() throws IOException {
        /*
         * The import type doesn't matter for this section;
         * the app doesn't change any existing password
         * whether the export file was encrypted or not.
         */
        runImporter("notes-metadata.xml", ImportType.TEST, true, "EsdnuQeLz");
    }

    /**
     * Test checking an invalid password hash against the metadata
     * stored in the XML file.
     */
    @Test
    public void testCheckXMLPasswordMismatch() throws IOException {
        try {
            runImporter("notes-metadata.xml", ImportType.TEST, true, "12345");
            fail("Import completed successfully");
        } catch (PasswordMismatchException e) {
            String lmsg = e.getMessage().toLowerCase(Locale.US);
            assertTrue("Exception thrown but does not mention password mismatch",
                    lmsg.contains("password") && lmsg.contains("match"));
        }
    }

    /**
     * Test checking an import which is password-protected but
     * no password is provided.
     */
    @Test
    public void testImportPrivateNoPassword() throws IOException {
        try {
            runImporter("notes-metadata.xml", ImportType.TEST, true, null);
            fail("Import completed successfully");
        } catch (PasswordRequiredException e) {
            String lmsg = e.getMessage().toLowerCase(Locale.US);
            assertTrue("Exception thrown but does not mention password",
                    lmsg.contains("password"));
        }
    }

    /**
     * Categories expected from a clean import of the
     * &ldquo;notes-categories.xml&rdquo; file.
     */
    public static final List<NoteCategory> TEST_CATEGORIES_1;
    static {
        List<NoteCategory> list = new ArrayList<>(10);

        NoteCategory category = new NoteCategory();
        category.setId(1);
        category.setName("Alpha");
        list.add(category);

        category = new NoteCategory();
        category.setId(2);
        category.setName("Beta");
        list.add(category);

        category = new NoteCategory();
        category.setId(3);
        category.setName("Gamma");
        list.add(category);

        category = new NoteCategory();
        category.setId(4);
        category.setName("Delta");
        list.add(category);

        category = new NoteCategory();
        category.setId(5);
        category.setName("Epsilon");
        list.add(category);

        category = new NoteCategory();
        category.setId(6);
        category.setName("Zeta");
        list.add(category);

        category = new NoteCategory();
        category.setId(7);
        category.setName("Eta");
        list.add(category);

        category = new NoteCategory();
        category.setId(8);
        category.setName("Theta");
        list.add(category);

        category = new NoteCategory();
        category.setId(9);
        category.setName("Iota");
        list.add(category);

        category = new NoteCategory();
        category.setId(10);
        category.setName("Kappa");
        list.add(category);

        TEST_CATEGORIES_1 = Collections.unmodifiableList(list);
    }

    /**
     * Add some random categories to the mock repository.
     * This uses some of the same ID&rsquo;s and names as those
     * in {@link #TEST_CATEGORIES_1}, but not necessarily
     * the same mapping in order to check potential replacements
     * or reassignments.
     *
     * @return a new {@link Map} of the categories that were added
     */
    private SortedMap<Long,String> addRandomCategories() {
        SortedMap<Long,String> testCategories = new TreeMap<>();
        for (int i = 8 + RAND.nextInt(5); i >= 0; --i) {
            NoteCategory cat = new NoteCategory();
            do {
                cat.setId(RAND.nextInt(20) + 1);
                if (RAND.nextBoolean())
                    cat.setName(TEST_CATEGORIES_1.get(
                            RAND.nextInt(TEST_CATEGORIES_1.size()))
                            .getName());
                else
                    cat.setName(randomWord() + " " + randomWord());
            }
            // Verify that there are no duplicates within this set
            while (testCategories.containsKey(cat.getId()) ||
                    testCategories.containsValue(cat.getName()));
            mockRepo.insertCategory(cat);
            testCategories.put(cat.getId(), cat.getName());
        }
        return testCategories;
    }

    /**
     * Return the key corresponding to a given value from a {@link Map}
     *
     * @param map the map to search
     * @param value the value to look for
     *
     * @return the key, or {@code null} if {@code value} was not found
     * in {@code map}.
     */
    public static <K,V> K getKey(Map<K,V> map, V value) {
        for (Map.Entry<K,V> entry : map.entrySet())
            if (entry.getValue().equals(value))
                return entry.getKey();
        return null;
    }

    /**
     * Read all categories from the mock repository into a {@link Map}
     *
     * @return the category map
     */
    private SortedMap<Long,String> readCategories() {
        SortedMap<Long,String> categoryMap = new TreeMap<>();
        List<NoteCategory> categories = mockRepo.getCategories();
        for (NoteCategory cat : categories)
            categoryMap.put(cat.getId(), cat.getName());
        return categoryMap;
    }

    /**
     * Verify the imported categories.
     *
     * @param expectedCategories the categories we expect to be in the
     * database.  This method will add the default &ldquo;Unfiled&rdquo;
     * category.
     *
     * @throws AssertionError if the actual categories do not match
     */
    private void assertCategoriesEquals(Map<Long,String> expectedCategories)
            throws AssertionError {
        SortedMap<Long,String> actualCategories = readCategories();
        assertCategoriesEquals(expectedCategories, actualCategories);
    }

    private void assertCategoriesEquals(
            Map<Long,String> expectedCategories,
            Map<Long,String> actualCategories) throws AssertionError {
        expectedCategories.put((long) NoteCategory.UNFILED, unfiledName);
        assertEquals("Imported categories",
                expectedCategories, actualCategories);
    }

    /**
     * Test a clean import of categories.
     * All existing categories must be replaced.
     */
    @Test
    public void testImportCategoriesClean() throws IOException {

        addRandomCategories();

        MockProgressBar progress = runImporter("notes-categories.xml",
                ImportType.CLEAN, false, null);

        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1)
            expectedCategories.put(cat.getId(), cat.getName());

        assertCategoriesEquals(expectedCategories);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        assertEquals("Total records in file", 11, endProgress.total);
        assertEquals("Number of records processed", 11, endProgress.current);

    }

    /**
     * Test importing categories in {@link ImportType#REVERT REVERT}
     * mode.  If a name conflicts with an existing entry in the database
     * all To Do items using the current category ID will be moved to
     * &ldquo;Unfiled&rdquo;.  If an ID conflicts with an existing entry
     * in the database, the category&rsquo;s name will be replaced.
     */
    @Test
    public void testImportCategoriesOverwrite() throws IOException {

        SortedMap<Long,String> expectedCategories = addRandomCategories();

        // Add one To Do record using a category name that will be replaced
        NoteCategory dupCatName = null;
        List<Map.Entry<Long,String>> candidates =
                new ArrayList<>(expectedCategories.entrySet());
        while ((dupCatName == null) && !candidates.isEmpty()) {
            Map.Entry<Long,String> entry = candidates.get(
                    RAND.nextInt(candidates.size()));
            for (NoteCategory cat : TEST_CATEGORIES_1) {
                if (entry.getValue().equals(cat.getName()) &&
                        !entry.getKey().equals(cat.getId())) {
                    dupCatName = new NoteCategory();
                    dupCatName.setId(entry.getKey());
                    dupCatName.setName(entry.getValue());
                    break;
                }
            }
            if (dupCatName == null)
                candidates.remove(entry);
        }
        if (dupCatName == null) {
            // No usable category was created randomly; force one
            dupCatName = new NoteCategory();
            dupCatName.setId(RAND.nextInt(10) + 21);
            do {
                dupCatName.setName(TEST_CATEGORIES_1.get(
                        RAND.nextInt(TEST_CATEGORIES_1.size())).getName());
            } while (expectedCategories.containsValue(dupCatName.getName()));
            mockRepo.insertCategory(dupCatName);
            expectedCategories.put(dupCatName.getId(), dupCatName.getName());
        }

        NoteItem noteDupCatName = randomNote();
        noteDupCatName.setCategoryId(dupCatName.getId());
        noteDupCatName.setCategoryName(dupCatName.getName());
        mockRepo.insertNote(noteDupCatName);

        // Update our expectation for the item's revision.
        // (The mock repo contains a clone of the item, not our original.)
        noteDupCatName.setCategoryId(NoteCategory.UNFILED);
        noteDupCatName.setCategoryName(unfiledName);

        // Add one To Do record using a category ID whose name will be changed
        NoteCategory dupCatId = null;
        candidates.clear();
        candidates.addAll(expectedCategories.entrySet());
        while ((dupCatId == null) && !candidates.isEmpty()) {
            Map.Entry<Long,String> entry = candidates.get(
                    RAND.nextInt(candidates.size()));
            for (NoteCategory cat : TEST_CATEGORIES_1) {
                if (entry.getKey().equals(cat.getId()) &&
                        !entry.getValue().equals(cat.getName())) {
                    // No imported categories are valid candidates
                    boolean newName = false;
                    for (NoteCategory cat2 : TEST_CATEGORIES_1) {
                        if (entry.getValue().equals(cat2.getName())) {
                            newName = true;
                            break;
                        }
                    }
                    if (newName) {
                        candidates.remove(entry);
                        continue;
                    }
                    dupCatId = new NoteCategory();
                    dupCatId.setId(entry.getKey());
                    dupCatId.setName(entry.getValue());
                    break;
                }
            }
        }
        if (dupCatId == null) {
            // No usable category was created randomly; force one
            dupCatId = new NoteCategory();
            do {
                dupCatId.setId(RAND.nextInt(10) + 1);
                dupCatId.setName(randomWord() + " " + randomWord());
            } while (expectedCategories.containsKey(dupCatId.getId()) ||
                    expectedCategories.containsValue(dupCatId.getName()));
            mockRepo.insertCategory(dupCatId);
            expectedCategories.put(dupCatId.getId(), dupCatId.getName());
        }

        NoteItem noteDupCatId = randomNote();
        noteDupCatId.setCategoryId(dupCatId.getId());
        noteDupCatId.setCategoryName(dupCatId.getName());
        mockRepo.insertNote(noteDupCatId);

        for (NoteCategory cat : TEST_CATEGORIES_1) {
            if (cat.getId().equals(dupCatId.getId())) {
                noteDupCatId.setCategoryName(cat.getName());
                break;
            }
        }

        MockProgressBar progress = runImporter("notes-categories.xml",
                ImportType.REVERT, false, null);

        // Set our category expectations
        for (NoteCategory cat : TEST_CATEGORIES_1) {
            if (expectedCategories.containsValue(cat.getName())) {
                Long id = getKey(expectedCategories, cat.getName());
                expectedCategories.remove(id);
            }
            expectedCategories.put(cat.getId(), cat.getName());
        }

        assertCategoriesEquals(expectedCategories);

        // Verify the category of the first To Do item
        // was changed to Unfiled
        NoteItem actualItem = mockRepo.getNoteById(noteDupCatName.getId());
        List<String> diffs = compareNoteRecords(
                "To Do item formerly in category " + dupCatName.getName(),
                noteDupCatName, actualItem);
        if (!diffs.isEmpty())
            fail(StringUtils.join("\n", diffs));

        // Verify the category of the second To Do item
        // just had its name changed
        actualItem = mockRepo.getNoteById(noteDupCatId.getId());
        diffs = compareNoteRecords(
                "To Do item with category #" + dupCatId.getId(),
                noteDupCatId, actualItem);
        if (!diffs.isEmpty())
            fail(StringUtils.join("\n", diffs));

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        assertEquals("Total records in file", 11, endProgress.total);
        assertEquals("Number of records processed", 11, endProgress.current);

    }

    /**
     * Test importing categories in {@link ImportType#UPDATE UPDATE} or
     * {@link ImportType#ADD} mode; the effect of all of these are the same.
     * If we already have a category of the same name but a different ID,
     * the importer should use the existing ID.  If we have another category
     * with the same ID as one being imported, the imported category will
     * get a new ID.  Otherwise we&rsquo;ll try to add the category with
     * its given ID.
     */
    private void testImportCategoriesUA(ImportType importType)
        throws IOException {

        SortedMap<Long,String> expectedCategories = addRandomCategories();

        // Add one To Do record using a category name that has a different ID
        NoteCategory dupCatName = null;
        List<Map.Entry<Long,String>> candidates =
                new ArrayList<>(expectedCategories.entrySet());
        while ((dupCatName == null) && !candidates.isEmpty()) {
            Map.Entry<Long,String> entry = candidates.get(
                    RAND.nextInt(candidates.size()));
            for (NoteCategory cat : TEST_CATEGORIES_1) {
                if (entry.getValue().equals(cat.getName()) &&
                        !entry.getKey().equals(cat.getId())) {
                    dupCatName = new NoteCategory();
                    dupCatName.setId(entry.getKey());
                    dupCatName.setName(entry.getValue());
                    break;
                }
            }
            if (dupCatName == null)
                candidates.remove(entry);
        }
        if (dupCatName == null) {
            // No usable category was created randomly; force one
            dupCatName = new NoteCategory();
            dupCatName.setId(RAND.nextInt(10) + 21);
            do {
                dupCatName.setName(TEST_CATEGORIES_1.get(
                        RAND.nextInt(TEST_CATEGORIES_1.size())).getName());
            } while (expectedCategories.containsValue(dupCatName.getName()));
            mockRepo.insertCategory(dupCatName);
            expectedCategories.put(dupCatName.getId(), dupCatName.getName());
        }

        NoteItem noteDupCatName = randomNote();
        noteDupCatName.setCategoryId(dupCatName.getId());
        noteDupCatName.setCategoryName(dupCatName.getName());
        mockRepo.insertNote(noteDupCatName);

        // Add one To Do record using a category ID that conflicts
        NoteCategory dupCatId = null;
        candidates.clear();
        candidates.addAll(expectedCategories.entrySet());
        while ((dupCatId == null) && !candidates.isEmpty()) {
            Map.Entry<Long,String> entry = candidates.get(
                    RAND.nextInt(candidates.size()));
            for (NoteCategory cat : TEST_CATEGORIES_1) {
                if (entry.getKey().equals(cat.getId()) &&
                        !entry.getValue().equals(cat.getName())) {
                    dupCatId = new NoteCategory();
                    dupCatId.setId(entry.getKey());
                    dupCatId.setName(entry.getValue());
                    break;
                }
            }
        }
        if (dupCatId == null) {
            // No usable category was created randomly; force one
            dupCatId = new NoteCategory();
            do {
                dupCatId.setId(RAND.nextInt(10) + 1);
                dupCatId.setName(randomWord() + " " + randomWord());
            } while (expectedCategories.containsKey(dupCatId.getId()) ||
                    expectedCategories.containsValue(dupCatId.getName()));
            mockRepo.insertCategory(dupCatId);
            expectedCategories.put(dupCatId.getId(), dupCatId.getName());
        }

        NoteItem noteDupCatId = randomNote();
        noteDupCatId.setCategoryId(dupCatId.getId());
        noteDupCatId.setCategoryName(dupCatId.getName());
        mockRepo.insertNote(noteDupCatId);

        // We won't know the ID's to expect for conflicting category
        // ID's until after the import.  Save these for later.
        Map<Long,String> expectedMoves = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1) {
            if (expectedCategories.containsValue(cat.getName()))
                continue;  // This should not change any existing categories
            if (expectedCategories.containsKey(cat.getId())) {
                expectedMoves.put(cat.getId(), cat.getName());
                continue;
            }
            expectedCategories.put(cat.getId(), cat.getName());
        }

        MockProgressBar progress = runImporter("notes-categories.xml",
                importType, false, null);

        // Find the new ID's of categories which should have been moved
        SortedMap<Long,String> actualCategories = readCategories();
        for (Long id : expectedMoves.keySet()) {
            String catName = expectedMoves.get(id);
            assertTrue(String.format("Category \"%s\" was not imported"
                    + " (conflicting with category #%d)", catName, id),
                    actualCategories.containsValue(catName));
            // Add this to our full map of expected categories
            expectedCategories.put(
                    getKey(actualCategories, catName), catName);
        }

        // Now we can compare the full category maps
        assertCategoriesEquals(expectedCategories, actualCategories);

        // The To Do items should not have been changed
        NoteItem actualItem = mockRepo.getNoteById(noteDupCatName.getId());
        List<String> diffs = compareNoteRecords(String.format(
                        "To Do item in category #%d \"%s\"",
                        dupCatName.getId(), dupCatName.getName()),
                noteDupCatName, actualItem);
        if (!diffs.isEmpty())
            fail(StringUtils.join("\n", diffs));

        actualItem = mockRepo.getNoteById(noteDupCatId.getId());
        diffs = compareNoteRecords(String.format(
                        "To Do item in category #%d \"%s\"",
                        dupCatId.getId(), dupCatId.getName()),
                noteDupCatId, actualItem);
        if (!diffs.isEmpty())
            fail(StringUtils.join("\n", diffs));

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        assertEquals("Total records in file", 11, endProgress.total);
        assertEquals("Number of records processed", 11, endProgress.current);

    }

    @Test
    public void testImportCategoriesUpdate() throws IOException {
        testImportCategoriesUA(ImportType.UPDATE);
    }

    @Test
    public void testImportCategoriesAdd() throws IOException {
        testImportCategoriesUA(ImportType.ADD);
    }

    /**
     * Categories used by the miscellaneous notes XML import files.
     */
    public static final List<NoteCategory> TEST_CATEGORIES_2;
    static {
        List<NoteCategory> list = new ArrayList<>(3);

        NoteCategory category = new NoteCategory();
        category.setId(3);
        category.setName("Mauris");
        list.add(category);

        category = new NoteCategory();
        category.setId(4);
        category.setName("Posuere");
        list.add(category);

        category = new NoteCategory();
        category.setId(5);
        category.setName("Eget");
        list.add(category);

        category = new NoteCategory();
        category.setId(18);
        category.setName("Aliquam");
        list.add(category);

        TEST_CATEGORIES_2 = Collections.unmodifiableList(list);
    }

    /**
     * Notes expected from a clean import of the
     * &ldquo;notes-misc-v1.xml&rdquo; file.
     * This file is not encrypted.
     */
    public static final List<NoteItem> TEST_NOTES_1;
    static {
        List<NoteItem> list = new ArrayList<>();

        NoteItem note = new NoteItem();
        note.setId(1);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName(unfiledName);
        note.setCreateTime(Instant.ofEpochMilli(1773161585792L));
        note.setModTime(Instant.ofEpochMilli(1773161607272L));
        note.setNote("Lorem ipsum dolor sit amet, consectetur adipiscing elit."
                + "  Integer sollicitudin malesuada orci."
                + "  Praesent lacus augue, euismod a volutpat ut,"
                + " consectetur nec tellus."
                + "  Pellentesque ornare diam sed dignissim viverra."
                + "  Sed urna arcu, elementum faucibus"
                + " faucibus faucibus, aliquet id neque."
                + "  Proin ultricies, turpis vitae feugiat mollis,"
                + " leo elit mollis metus, lobortis rhoncus elit orci ac leo."
                + "  Fusce eget tempus metus."
                + "  Maecenas mollis mollis felis,"
                + " eget rhoncus elit pharetra sed."
                + "  Etiam tempor lacus ut est elementum dapibus.");
        list.add(note);

        note = new NoteItem();
        note.setId(5);
        note.setCategoryId(TEST_CATEGORIES_2.get(0).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(0).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773161698147L));
        note.setModTime(Instant.ofEpochMilli(1773161709876L));
        note.setNote("Pellentesque rhoncus urna nec porttitor luctus."
                + "  Ut id metus sapien."
                + "  Morbi eu tristique diam, suscipit eleifend diam."
                + "  Cras vel tempor nisi."
                + "  Mauris mi ex, condimentum vitae risus sed,"
                + " vulputate hendrerit lectus."
                + "  Donec dictum leo nunc, a finibus augue gravida nec."
                + "  Donec eget ipsum odio."
                + "  Sed sit amet convallis lacus."
                + "  Aenean interdum tincidunt mi,"
                + " sit amet viverra leo efficitur non."
                + "  Nullam ac aliquam sem."
                + "  Phasellus faucibus ipsum sed sollicitudin commodo."
                + "  Sed at ligula ac sapien dignissim porta ut non ligula."
                + "  Etiam suscipit non mauris eu blandit.");
        list.add(note);

        note = new NoteItem();
        note.setId(7);
        note.setCategoryId(TEST_CATEGORIES_2.get(1).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(1).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773161775937L));
        note.setModTime(Instant.ofEpochMilli(1773161786048L));
        note.setNote("Integer non blandit leo."
                + "  Pellentesque habitant morbi tristique senectus"
                + " et netus et malesuada fames ac turpis egestas."
                + "  Nam cursus interdum ex, et eleifend metus varius eu."
                + "  Duis feugiat pulvinar nunc ut auctor."
                + "  Morbi molestie eros vitae posuere dapibus."
                + "  Aenean suscipit sapien mauris,"
                + " vel aliquet erat efficitur a."
                + "  Aenean volutpat aliquet suscipit."
                + "  Morbi velit mi, aliquam sed auctor non,"
                + " sollicitudin vitae urna."
                + "  Maecenas molestie magna nisi,"
                + " sed tempor nibh feugiat iaculis."
                + "  Integer placerat nisl eget nisi sagittis fermentum.");
        list.add(note);

        note = new NoteItem();
        note.setId(22);
        note.setCategoryId(TEST_CATEGORIES_2.get(2).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(2).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773161847272L));
        note.setModTime(Instant.ofEpochMilli(1773161853333L));
        note.setNote("Duis et felis at sem porttitor ultrices."
                + "  Duis gravida ex a porta interdum."
                + "  Nunc sed pretium nisl, eu consequat urna."
                + "  Donec gravida ipsum quam, et molestie tellus lacinia sed."
                + "  Vivamus pretium massa et quam vulputate,"
                + " et viverra purus ultricies."
                + "  Curabitur iaculis fringilla egestas."
                + "  Praesent placerat pretium tincidunt."
                + "  Aliquam consequat scelerisque sapien."
                + "  Duis at quam in mi imperdiet auctor vel sit amet neque."
                + "  Sed imperdiet feugiat sapien vitae feugiat."
                + "  Orci varius natoque penatibus et magnis dis"
                + " parturient montes, nascetur ridiculus mus.\n"
                + "\n"
                + "In tempor sapien sit amet nibh semper"
                + ", sed pellentesque nisl hendrerit."
                + "  Duis id tortor nisl."
                + "  Vestibulum tincidunt laoreet pellentesque."
                + "  Quisque malesuada varius tellus sit amet dapibus."
                + "  Nullam congue dignissim dui, in elementum metus posuere id."
                + "  Suspendisse eu lacus in dolor malesuada imperdiet."
                + "  Donec vestibulum libero et tortor"
                + " vulputate porta eu ac neque."
                + "  Curabitur fringilla molestie quam,"
                + " ultricies iaculis augue mollis et."
                + "  Sed vestibulum gravida suscipit.");
        list.add(note);

        note = new NoteItem();
        note.setId(29);
        note.setCategoryId(TEST_CATEGORIES_2.get(3).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(3).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773161918383L));
        note.setModTime(Instant.ofEpochMilli(1773161925678L));
        note.setNote("Aenean mattis eget eros a suscipit."
                + "  Donec aliquam, dui non consectetur congue,"
                + " justo ante imperdiet nulla,"
                + " et vulputate tellus orci eget dolor."
                + "  Phasellus posuere sem sed posuere egestas."
                + "  Nam ante neque, interdum non fermentum at,"
                + " placerat in lorem."
                + "  Etiam at sagittis elit."
                + "  Suspendisse potenti."
                + "  Donec rhoncus feugiat augue,"
                + " sit amet vestibulum leo semper ut."
                + "  Donec varius finibus massa sed vehicula.\n"
                + "\n"
                + "Integer eu sapien diam."
                + "  Donec eu consequat libero, vel vehicula mi."
                + "  Integer ultrices luctus dolor."
                + "  Pellentesque sodales tellus nec quam aliquet consectetur."
                + "  In ac ligula quam."
                + "  Morbi eget tincidunt augue."
                + "  Mauris quis felis vel tellus sagittis"
                + " vulputate sit amet blandit libero."
                + "  Curabitur commodo pretium sem,"
                + " at pulvinar elit laoreet non."
                + "  Sed consequat accumsan vulputate.\n"
                + "\n"
                + "Sed viverra quam nec quam lacinia,"
                + " sed sollicitudin turpis venenatis."
                + "  Quisque nec blandit ipsum."
                + "  Praesent nibh tortor, malesuada viverra urna id,"
                + " gravida bibendum nibh."
                + "  Nulla maximus libero nec purus interdum,"
                + " et interdum justo dictum."
                + "  Sed sodales molestie nisi sit amet tincidunt."
                + "  Fusce sit amet egestas risus."
                + "  Phasellus lectus nulla, sodales ut rutrum et,"
                + " tempus id ipsum."
                + "  Proin eleifend rutrum enim a hendrerit."
                + "  Pellentesque vel nibh eget ante placerat blandit a id dui."
                + "  Ut nec magna ligula."
                + "  Aenean orci nibh, faucibus et viverra quis,"
                + " efficitur et enim."
                + "  Mauris tincidunt tortor et leo tristique,"
                + " vitae pretium odio efficitur."
                + "  Suspendisse sodales tincidunt nibh,"
                + " sed vehicula velit placerat sed."
                + "  Nullam fermentum justo sit amet pretium egestas."
                + "  Sed rhoncus id nisl ut tempus."
                + "  Cras ut placerat nunc.\n"
                + "\n"
                + "Phasellus a tempus libero."
                + "  Quisque porttitor mi vel elit pharetra,"
                + " sit amet feugiat ligula cursus."
                + "  Donec et dapibus est."
                + "  In varius mollis nibh."
                + "  Proin sit amet leo tincidunt,"
                + " tempor ligula vel, ullamcorper enim."
                + "  Sed eu purus turpis."
                + "  Duis venenatis ultrices orci eget feugiat."
                + "  Donec in mattis ex, eget fermentum eros."
                + "  Nam pellentesque ligula eget metus auctor fringilla."
                + "  Donec sagittis risus vel sapien cursus,"
                + " sit amet lobortis metus semper."
                + "  Cras eu libero sed mauris ullamcorper sollicitudin.\n"
                + "\n"
                + "Aliquam tempus ornare rhoncus."
                + "  Duis sed ex massa."
                + "  Ut gravida mollis nunc vel pretium."
                + "  Integer non lorem posuere, egestas sem at, lacinia lorem."
                + "  Quisque in sollicitudin enim."
                + "  Ut vitae elementum diam."
                + "  Pellentesque dictum pharetra odio,"
                + " sit amet semper odio venenatis sit amet."
                + "  Aenean commodo sapien nec est tincidunt,"
                + " quis tristique mi laoreet."
                + "  Donec faucibus quam arcu, vel lacinia libero ornare ac."
                + "  Integer rutrum commodo velit,"
                + " quis scelerisque urna pellentesque et."
                + "  Integer eu pretium odio."
                + "  Vivamus scelerisque orci non purus interdum elementum."
                + "  Nam ut nibh mauris.\n"
                + "\n"
                + "Nam lobortis lectus urna."
                + "  Suspendisse vel urna purus."
                + "  Suspendisse eget ligula vitae lorem euismod tempor."
                + "  Interdum et malesuada fames ac"
                + " ante ipsum primis in faucibus."
                + "  Curabitur congue purus ex, at volutpat felis laoreet non."
                + "  Duis at placerat est."
                + "  Morbi nec maximus tortor, vitae blandit lectus."
                + "  Quisque at ipsum elit."
                + "  Integer placerat dui eu sodales vulputate."
                + "  Nullam pharetra non mauris quis blandit."
                + "  Aenean varius a urna ut accumsan.\n"
                + "\n"
                + "Suspendisse eu justo nulla."
                + "  Nullam non augue sed enim vestibulum molestie vel ut sem."
                + "  Donec laoreet massa vel magna ultricies cursus."
                + "  Vivamus auctor lacus sagittis hendrerit tristique."
                + "  Duis felis nulla, mattis sagittis fringilla ut,"
                + " porta ut diam."
                + "  Integer odio augue, egestas tempor tellus convallis,"
                + " laoreet porta urna."
                + "  Integer et nisl sed mauris sollicitudin molestie."
                + "  Donec a ultricies eros."
                + "  Suspendisse tincidunt dapibus justo molestie aliquam."
                + "  Vivamus eget aliquam felis."
                + "  Suspendisse ut elit non orci venenatis molestie."
                + "  Nunc id eros a lacus facilisis molestie ac nec lectus."
                + "  Aenean posuere faucibus sapien vel gravida."
                + "  Nulla auctor ac massa sit amet cursus."
                + "  Quisque non tempus elit."
                + "  Nulla metus velit, condimentum consectetur libero vel,"
                + " vehicula sollicitudin est.\n"
                + "\n"
                + "Praesent posuere, dolor et consectetur mattis,"
                + " felis augue tincidunt nisi,"
                + " et tincidunt justo turpis at risus."
                + "  Praesent in ornare urna."
                + "  Proin hendrerit mi id convallis sagittis."
                + "  Duis quis tortor ut massa porta"
                + " aliquam fringilla non lorem."
                + "  Praesent aliquet sit amet nisl nec maximus."
                + "  Integer sit amet neque blandit,"
                + " tincidunt magna ac, vestibulum ligula."
                + "  Orci varius natoque penatibus et magnis dis"
                + " parturient montes, nascetur ridiculus mus.\n"
                + "\n"
                + "Nullam finibus lacinia libero, et accumsan magna placerat et."
                + "  Vivamus imperdiet, ipsum scelerisque cursus sollicitudin,"
                + " mauris nulla pulvinar turpis,"
                + " ut accumsan dolor quam sit amet tellus."
                + "  Aliquam finibus felis ac tellus fringilla,"
                + " ut lacinia magna volutpat."
                + "  Suspendisse accumsan dolor eu purus porta faucibus."
                + "  Praesent facilisis vel arcu nec tincidunt."
                + "  Curabitur laoreet, quam eget commodo malesuada,"
                + " ipsum augue maximus diam, vitae tempor ex lectus vitae mi."
                + "  Donec gravida risus a tellus tempor mollis."
                + "  Integer dapibus, mi hendrerit condimentum consectetur,"
                + " risus nibh tristique leo,"
                + " nec molestie elit magna vehicula dui."
                + "  Proin cursus interdum quam."
                + "  Cras in pulvinar purus."
                + "  Vivamus id purus sapien."
                + "  Suspendisse venenatis, velit et ornare commodo,"
                + " velit neque semper neque,"
                + " nec viverra sapien magna vitae urna."
                + "  Ut eu arcu quis justo pellentesque"
                + " consectetur a eget sapien."
                + "  Duis a purus id leo egestas mattis eget vel nibh.\n"
                + "\n"
                + "Etiam accumsan dui quis erat elementum,"
                + " a efficitur sem cursus."
                + "  Nunc accumsan posuere tristique."
                + "  Curabitur eget lectus euismod,"
                + " mattis quam a, convallis justo."
                + "  Nullam vehicula sem in nisi egestas lobortis."
                + "  Nullam sed ultricies enim."
                + "  Sed quis viverra nibh."
                + "  Nullam a elit accumsan, scelerisque"
                + " magna nec, fermentum tellus."
                + "  Nam placerat enim sed mi posuere dictum."
                + "  Aenean a enim orci."
                + "  Ut lectus enim, luctus in ipsum in, euismod sagittis urna.");
        note.setPrivate(StringEncryption.NO_ENCRYPTION);
        list.add(note);

        TEST_NOTES_1 = Collections.unmodifiableList(list);
    }

    /**
     * Notes expected from a clean import of the
     * &ldquo;notes-misc-v2.xml&rdquo; file.
     * This file is not encrypted.
     */
    public static final List<NoteItem> TEST_NOTES_2;
    static {
        List<NoteItem> list = new ArrayList<>();

        NoteItem note = new NoteItem();
        note.setId(11);
        note.setCategoryId(NoteCategory.UNFILED);
        note.setCategoryName(unfiledName);
        note.setCreateTime(Instant.ofEpochMilli(1773164918383L));
        note.setModTime(Instant.ofEpochMilli(1773164930000L));
        note.setNote("Lorem ipsum dolor sit amet,"
                + " consectetur adipiscing elit."
                + "  Mauris quis volutpat elit."
                + "  Nam in mauris volutpat, venenatis leo sed, iaculis turpis."
                + "  Donec imperdiet posuere nunc eget rutrum."
                + "  Proin mollis ipsum lorem,"
                + " ullamcorper finibus tortor gravida non."
                + "  Aenean dapibus sagittis nibh in imperdiet."
                + "  In vulputate lobortis dolor vitae iaculis."
                + "  Morbi at sapien ut purus tempus"
                + " vulputate quis tempor nulla."
                + "  Proin sed nisi magna."
                + "  Nulla sodales condimentum lacinia."
                + "  Pellentesque ornare turpis at bibendum fringilla."
                + "  Duis ut dui aliquam ipsum imperdiet tempus."
                + "  Sed ornare, ligula quis ullamcorper semper,"
                + " metus est pulvinar enim,"
                + " sit amet sollicitudin neque ante et nulla."
                + "  Nunc justo ipsum, porttitor ut arcu scelerisque,"
                + " congue sagittis lorem."
                + "  Vivamus malesuada mauris id tempus vestibulum.");
        list.add(note);

        note = new NoteItem();
        note.setId(28);
        note.setCategoryId(TEST_CATEGORIES_2.get(0).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(0).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773165042086L));
        note.setModTime(Instant.ofEpochMilli(1773165049494L));
        note.setNote("Quisque porta egestas turpis,"
                + " eget tincidunt metus luctus quis."
                + "  Curabitur nec orci ac elit consectetur"
                + " tincidunt pellentesque in mauris."
                + "  Pellentesque molestie rhoncus tortor sit amet sodales."
                + "  Proin a posuere ex."
                + "  Nam id iaculis tellus."
                + "  Donec iaculis nibh vel feugiat mattis."
                + "  Ut dictum quam dolor, ut mattis elit pharetra et."
                + "  Praesent efficitur leo non arcu aliquet laoreet."
                + "  Aliquam malesuada, risus vel mollis gravida,"
                + " nibh dui tristique massa, nec hendrerit lorem magna ut mi.");
        list.add(note);

        note = new NoteItem();
        note.setId(49);
        note.setCategoryId(TEST_CATEGORIES_2.get(1).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(1).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773165176789L));
        note.setModTime(Instant.ofEpochMilli(1773165182864L));
        note.setNote("In tincidunt nisi metus, vel iaculis lorem molestie a."
                + "  Sed id feugiat leo."
                + "  Vestibulum diam leo, vulputate id augue et,"
                + " commodo cursus sapien."
                + "  Phasellus tempor odio vel libero blandit pharetra."
                + "  Nulla viverra tortor a magna"
                + " bibendum dignissim in a ligula."
                + "  Etiam sed consectetur sapien."
                + "  Quisque aliquam velit sit amet est mollis efficitur."
                + "  Aenean sed sapien viverra,"
                + " dignissim magna sit amet, dignissim sapien."
                + "  Aenean malesuada posuere massa a euismod."
                + "  Suspendisse ultricies pulvinar feugiat."
                + "  Maecenas iaculis scelerisque ipsum at elementum."
                + "  Mauris quis metus in libero tempor convallis."
                + "  Sed viverra maximus nibh vel viverra."
                + "  Suspendisse nibh nibh, sagittis quis elementum vel,"
                + " sollicitudin eu diam.");
        list.add(note);

        note = new NoteItem();
        note.setId(57);
        note.setCategoryId(TEST_CATEGORIES_2.get(2).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(2).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773165191111L));
        note.setModTime(Instant.ofEpochMilli(1773165198529L));
        note.setNote("Integer pellentesque turpis ornare dui congue vulputate."
                + "  Nulla a ultrices turpis, sit amet tincidunt felis."
                + "  Aenean dignissim luctus rutrum."
                + "  Vestibulum purus libero, varius vitae faucibus at,"
                + " ultricies a mauris."
                + "  Sed magna tortor, ultrices nec pellentesque quis,"
                + " vestibulum sit amet elit."
                + "  Aenean magna orci, elementum facilisis risus vitae,"
                + " ornare imperdiet elit."
                + "  Nunc quis venenatis nulla."
                + "  Nam neque libero, lacinia vel mollis efficitur,"
                + " tempus sit amet nunc."
                + "  Etiam faucibus nulla a justo rhoncus,"
                + " ac maximus nibh consectetur."
                + "  Proin efficitur sapien eget sodales sagittis."
                + "  Donec pharetra risus mauris, ut porta tortor commodo vel."
                + "  Maecenas eu tempor metus, vel porttitor erat."
                + "  Vestibulum aliquam auctor convallis."
                + "  Cras sed ullamcorper urna.\n"
                + "\n"
                + "Sed convallis turpis vitae auctor cursus."
                + "  Curabitur non pretium nulla."
                + "  Integer interdum orci elit,"
                + " ac mattis nunc luctus sit amet."
                + "  Donec laoreet, leo vitae pellentesque finibus,"
                + " tortor tellus faucibus sapien,"
                + " non eleifend odio neque vel tellus."
                + "  Nunc aliquet commodo dui."
                + "  Nulla facilisi."
                + "  Donec faucibus ornare nisi"
                + ", quis elementum neque cursus sit amet."
                + "  In sit amet turpis ullamcorper,"
                + " volutpat enim id, posuere dui."
                + "  Morbi tincidunt vestibulum tortor,"
                + " vitae vehicula diam pharetra vel."
                + "  Nullam bibendum nibh et metus vulputate aliquet."
                + "  Maecenas interdum non tellus sit amet placerat."
                + "  Donec posuere dolor in viverra tincidunt."
                + "  Nullam a lorem a metus pharetra egestas.");
        list.add(note);

        note = new NoteItem();
        note.setId(62);
        note.setCategoryId(TEST_CATEGORIES_2.get(3).getId());
        note.setCategoryName(TEST_CATEGORIES_2.get(3).getName());
        note.setCreateTime(Instant.ofEpochMilli(1773165210741L));
        note.setModTime(Instant.ofEpochMilli(1773165218383L));
        note.setNote("Integer mollis, dui quis pretium faucibus,"
                + " risus tortor tempus odio,"
                + " vel egestas ipsum est sit amet lorem."
                + "  Vestibulum iaculis pulvinar mauris,"
                + " in cursus leo tincidunt eget."
                + "  Morbi ut velit non augue rhoncus fermentum."
                + "  Nunc mattis, eros in faucibus viverra,"
                + " dui libero vulputate nisl,"
                + " eget convallis est neque nec magna."
                + "  Maecenas sit amet eleifend arcu."
                + "  Phasellus nunc lacus, vulputate eu tortor et,"
                + " scelerisque consectetur mi."
                + "  Aenean a orci sit amet mauris congue feugiat.\n"
                + "\n"
                + "Praesent ut felis nibh."
                + "  Suspendisse sollicitudin libero metus,"
                + " ut rhoncus libero ultricies id."
                + "  Vivamus interdum leo nec vehicula accumsan."
                + "  Cras feugiat varius tristique."
                + "  Fusce in justo mi."
                + "  Vivamus eget dictum sapien, eget tristique tortor."
                + "  Vestibulum nec tortor non odio"
                + " porttitor ornare ac quis eros."
                + "  Mauris gravida diam lacus,"
                + " vitae efficitur purus feugiat sed."
                + "  Quisque feugiat, nisl suscipit lobortis dictum,"
                + " odio ligula malesuada nulla,"
                + " vel consectetur sapien tortor et enim."
                + "  Maecenas nec ultricies erat."
                + "  Proin semper imperdiet pharetra."
                + "  Sed finibus auctor enim in sodales."
                + "  Ut vitae est rhoncus quam pulvinar sollicitudin."
                + "  Pellentesque semper, leo in tincidunt pellentesque,"
                + " felis sem sagittis sapien, at dapibus arcu metus in lacus.\n"
                + "\n"
                + "Nunc leo metus, lobortis eget leo eu, venenatis iaculis arcu."
                + "  Pellentesque luctus, nulla non porta convallis,"
                + " velit tortor lacinia nisl,"
                + " in dapibus risus tellus eget mauris."
                + "  Vestibulum fermentum maximus mollis."
                + "  Sed id neque sapien."
                + "  Ut dui neque, congue quis venenatis nec, porta quis justo."
                + "  Integer vitae orci sapien."
                + "  Suspendisse suscipit orci eu hendrerit tempor."
                + "  In hac habitasse platea dictumst."
                + "  Morbi maximus tincidunt nisl,"
                + " a dapibus odio luctus sit amet."
                + "  Praesent lobortis, erat ac finibus auctor,"
                + " eros turpis vehicula nunc,"
                + " sit amet vehicula erat lorem ut nunc."
                + "  Aliquam dapibus, sem ac lacinia mollis,"
                + " libero risus pellentesque turpis,"
                + " in maximus ipsum ex vel dolor."
                + "  Donec fringilla mi id arcu vulputate,"
                + " et tempor risus elementum."
                + "  Ut tincidunt urna eget odio gravida,"
                + " ac pharetra diam sollicitudin."
                + "  Nam et lectus nulla."
                + "  Sed quam leo, pellentesque sit amet accumsan eget,"
                + " maximus sed nunc."
                + "  Integer cursus elit vel orci placerat,"
                + " a blandit augue scelerisque.\n"
                + "\n"
                + "Donec rutrum libero vestibulum,"
                + " tempus dolor ut, commodo diam."
                + "  Suspendisse aliquet a nibh vitae lacinia."
                + "  Praesent quis condimentum risus."
                + "  Curabitur sagittis, risus eget malesuada accumsan,"
                + " nibh ex mattis magna, dapibus iaculis arcu est ut est."
                + "  Cras elit ligula, condimentum quis magna eu,"
                + " porta posuere enim."
                + "  Aliquam sit amet dictum ante."
                + "  Duis vehicula congue justo vel sollicitudin."
                + "  Pellentesque habitant morbi tristique senectus"
                + " et netus et malesuada fames ac turpis egestas."
                + "  Suspendisse ac augue id libero dapibus aliquet."
                + "  Interdum et malesuada fames ac"
                + " ante ipsum primis in faucibus.\n"
                + "\n"
                + "Curabitur sollicitudin interdum purus in vestibulum."
                + "  Donec vulputate, tellus sodales consequat tincidunt,"
                + " urna tellus varius ligula, at interdum dui eros non ipsum."
                + "  Cras a ultrices elit."
                + "  Cras at tortor velit."
                + "  Pellentesque at nunc at erat"
                + " fermentum tincidunt quis et velit."
                + "  Fusce et auctor quam."
                + "  Pellentesque posuere sem ut leo euismod,"
                + " id scelerisque mi laoreet."
                + "  Nulla aliquam enim in imperdiet tincidunt."
                + "  Morbi sed nisl ut quam semper semper."
                + "  Duis non dui nec dui rhoncus tristique."
                + "  Phasellus odio risus, convallis eget mi non,"
                + " imperdiet vestibulum est."
                + "  Pellentesque egestas porta commodo.\n"
                + "\n"
                + "Integer suscipit mauris vel felis ultrices faucibus."
                + "  Suspendisse potenti."
                + "  Aliquam vestibulum ante nec fringilla imperdiet."
                + "  Aliquam at volutpat lectus."
                + "  Mauris tincidunt vel eros eget tempor."
                + "  In fermentum quis sem at mollis."
                + "  Duis mollis congue eros."
                + "  Suspendisse at gravida nunc."
                + "  Aenean scelerisque vehicula turpis, sed dapibus magna."
                + "  Aenean convallis magna ex,"
                + " sit amet rhoncus sapien lobortis sed.\n"
                + "\n"
                + "Nunc et augue quis massa viverra consequat."
                + "  Cras nec erat nec libero gravida auctor."
                + "  Phasellus eu nunc posuere,"
                + " auctor lectus in, interdum nisi."
                + "  Ut sit amet justo at ipsum dapibus"
                + " condimentum eget id tortor."
                + "  Donec nec ligula sagittis,"
                + " viverra turpis vitae, iaculis est."
                + "  Suspendisse potenti."
                + "  Sed tincidunt ultrices tortor placerat tincidunt."
                + "  Nullam aliquam faucibus velit,"
                + " ut elementum eros sagittis a."
                + "  Curabitur mollis justo sed erat pulvinar,"
                + " id dapibus leo laoreet."
                + "  Fusce vel pretium urna."
                + "  Curabitur facilisis erat orci,"
                + " sed vestibulum erat fermentum feugiat."
                + "  Suspendisse fringilla dignissim varius."
                + "  Quisque mattis fermentum eleifend."
                + "  Suspendisse laoreet lectus lectus,"
                + " interdum sodales arcu ullamcorper vitae."
                + "  Nulla eleifend rutrum orci,"
                + " a hendrerit tortor molestie nec."
                + "  Cras nisi magna, ultrices fermentum mauris et,"
                + " ornare cursus mauris.");
        note.setPrivate(StringEncryption.NO_ENCRYPTION);
        list.add(note);

        TEST_NOTES_2 = Collections.unmodifiableList(list);
    }

    /**
     * Add all of the categories from {@link #TEST_CATEGORIES_1} to the
     * mock repository and an assortment of random notes, some of
     * which will have conflicting ID&rsquo;s with records in
     * {@link #TEST_NOTES_1} and {@link #TEST_NOTES_2} and some will
     * have categories whose ID&rsquo;s conflict with records in
     * {@link #TEST_CATEGORIES_2}.
     *
     * @return a {@link List} of the notes that were added
     */
    private List<NoteItem> addRandomNotes() {
        for (NoteCategory cat : TEST_CATEGORIES_1)
            mockRepo.insertCategory(cat);

        List<NoteItem> newNotes = new ArrayList<>();
        Set<Long> usedIds = new HashSet<>();
        for (NoteItem note : TEST_NOTES_1)
            usedIds.add(note.getId());
        for (NoteItem note : TEST_NOTES_2)
            usedIds.add(note.getId());
        NoteItem note;
        NoteCategory cat;

        // Start with some notes which don't conflict with anything
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i) {
            note = randomNote();
            cat = TEST_CATEGORIES_1.get(
                    RAND.nextInt(TEST_CATEGORIES_1.size()));
            note.setCategoryId(cat.getId());
            note.setCategoryName(cat.getName());
            do {
                note.setId(RAND.nextInt(1024) + 1);
            } while (usedIds.contains(note.getId()));
            mockRepo.insertNote(note);
            newNotes.add(note);
            usedIds.add(note.getId());
        }

        // Add some notes whose category ID's conflict with
        // TEST_CATEGORIES_2 (3, 4, or 5)
        for (NoteCategory inCat : TEST_CATEGORIES_2) {
            if (inCat.getId() - 1 >= TEST_CATEGORIES_1.size())
                continue;
            cat = TEST_CATEGORIES_1.get(inCat.getId().intValue() - 1);
            note = randomNote();
            note.setCategoryId(cat.getId());
            note.setCategoryName(cat.getName());
            do {
                note.setId(RAND.nextInt(1024) + 1);
            } while (usedIds.contains(note.getId()));
            mockRepo.insertNote(note);
            newNotes.add(note);
            usedIds.add(note.getId());
        }

        // Add one note from each of the V1 and V2 import files with
        // a conflicting ID
        note = randomNote();
        cat = TEST_CATEGORIES_1.get(
                RAND.nextInt(TEST_CATEGORIES_1.size()));
        note.setCategoryId(cat.getId());
        note.setCategoryName(cat.getName());
        NoteItem conflict = TEST_NOTES_1.get(RAND.nextInt(TEST_NOTES_1.size()));
        note.setId(conflict.getId());
        note.setCreateTime(conflict.getCreateTime());
        mockRepo.insertNote(note);
        newNotes.add(note);

        note = randomNote();
        cat = TEST_CATEGORIES_1.get(
                RAND.nextInt(TEST_CATEGORIES_1.size()));
        note.setCategoryId(cat.getId());
        note.setCategoryName(cat.getName());
        conflict = TEST_NOTES_2.get(RAND.nextInt(TEST_NOTES_2.size()));
        note.setId(conflict.getId());
        note.setCreateTime(conflict.getCreateTime());
        mockRepo.insertNote(note);
        newNotes.add(note);

        return newNotes;
    }

    /**
     * Read the current set of notes from the mock repository.
     * They should be sorted by ID.
     *
     * @return a {@link List} of notes.
     */
    private List<NoteItem> readNotes() {
        List<NoteItem> list = new ArrayList<>(TEST_NOTES_1.size());
        try (NoteCursor cursor = mockRepo.getNotes(
                ALL_CATEGORIES, true, true,
                NoteRepositoryImpl.NOTE_TABLE_NAME + "."
                        + NoteSchema.NoteItemColumns._ID)) {
            while (cursor.moveToNext())
                list.add(cursor.getNote());
        }
        return list;
    }

    /**
     * Arrange a {@link List} of {@link NoteItem}s into a {@link SortedMap}.
     *
     * @param message the assertion message to show if any duplicate
     * item ID&rsquo;s are found
     * @param list the list of records
     *
     * @return the map
     */
    private static SortedMap<Long,NoteItem> toMap(
            String message, List<NoteItem> list) {
        SortedMap<Long,NoteItem> map = new TreeMap<>();
        for (NoteItem note : list) {
            assertFalse(message, map.containsKey(note.getId()));
            map.put(note.getId(), note);
        }
        return map;
    }

    /**
     * Assert two lists of notes are exact.  For all fields,
     * they&rsquo;re compared for equality but reported individually
     * if they don&rsquo;t match.
     *
     * @param message the message to show in front of the mismatch errors
     * if there are any notes that don&rsquo;t match
     * @param expected the list of expected notes
     * @param actual the list of actual notes
     *
     * @throws AssertionError if any notes don&rsquo;t match
     */
    public static void assertNoteEquals(
            String message, List<NoteItem> expected, List<NoteItem> actual)
        throws AssertionError {
        Map<Long,NoteItem> expectedMap = toMap(
                message + " - expected notes", expected);
        Map<Long,NoteItem> actualMap = toMap(
                message + " - actual notes", actual);
        assertNoteEquals(message, expectedMap, actualMap);
    }

    /**
     * Get the first line or so of a note&rsquo;s text, with a
     * minimum of 80 characters.
     *
     * @param note the note whose heading to get
     *
     * @return the first 80 characters of the note content plus any
     * additional characters up to the next punctuation mark
     * (inclusive) or newline (exclusive).
     */
    public static String getNoteHeading(NoteItem note) {
        final Pattern pat = Pattern.compile("^(.{80}[^!.,?]*[!,.?]?)",
                Pattern.DOTALL + Pattern.MULTILINE);
        Matcher m = pat.matcher(note.getNote());
        if (m.find())
            return m.group(1);
        return note.getNote();
    }

    /**
     * Assert two maps of notes  are exact.  For all fields,
     * they&rsquo;re compared for equality but reported individually
     * if they don&rsquo;t match.
     *
     * @param message the message to show in front of the mismatch errors
     * if there are any notes that don&rsquo;t match
     * @param expected the list of expected notes
     * @param actual the list of actual notes
     *
     * @throws AssertionError if any notes don&rsquo;t match
     */
    public static void assertNoteEquals(
            String message, Map<Long,NoteItem> expected,
            Map<Long,NoteItem> actual)
        throws AssertionError {
        SortedSet<Long> allIds = new TreeSet<>(expected.keySet());
        allIds.addAll(actual.keySet());
        // For notes where we don't know the ultimate ID in advance,
        // get a map of note content to actual note ID's.
        Map<String,Long> contentMap = new HashMap<>();
        for (NoteItem note : actual.values()) {
            if (!expected.containsKey(note.getId()))
                contentMap.put(note.getNote(), note.getId());
        }
        Set<Long> unknownIds = new HashSet<>(allIds.headSet(0L));
        for (long id : unknownIds) {
            String content = expected.get(id).getNote();
            if (contentMap.containsKey(content)) {
                NoteItem note = expected.remove(id);
                expected.put(contentMap.get(content), note);
                allIds.remove(id);
            }
        }
        List<String> errors = new ArrayList<>();
        for (long id : allIds) {
            NoteItem expectedNote = expected.get(id);
            NoteItem actualNote = (id >= 0) ? actual.get(id)
                    : contentMap.containsKey(expectedNote.getNote())
                    ? actual.get(contentMap.get(expectedNote.getNote()))
                    : null;
            if (expectedNote == null) {
                errors.add(String.format("Note #%d expected:<null> but was:%s",
                        id, actualNote));
                continue;
            }
            if (actualNote == null) {
                errors.add(String.format("Note #%d expected:%s but was:<null>",
                        id, expectedNote));
                continue;
            }
            errors.addAll(compareNoteRecords(String.format("Note #%d", id),
                    expectedNote, actualNote));
        }
        if (errors.size() > 1)
            fail(message + ": Multiple errors:\n"
                    + StringUtils.join(errors, "\n"));
        else if (errors.size() == 1)
            fail(message + ": " + errors.get(0));
    }

    /**
     * Compare two notes for equality.
     *
     * @param message the string to prepend to any error message
     * @param expected the expected record
     * @param actual the actual record
     *
     * @return a list of any validation errors found
     * (empty if the notes are identical).
     */
    private static List<String> compareNoteRecords(
            String message, NoteItem expected, NoteItem actual) {
        List<String> errors = new ArrayList<>();

        if ((expected.getId() >= 0) &&
                !expected.getId().equals(actual.getId()))
            errors.add(String.format("%s ID expected:%d but was:%d",
                    message, expected.getId(), actual.getId()));
        if (!StringUtils.equals(expected.getNote(), actual.getNote()))
            errors.add(String.format("%s note expected:\"%s\" but was:\"%s\"",
                    message, getNoteHeading(expected), getNoteHeading(actual)));
        if (!Arrays.equals(expected.getEncryptedNote(),
                actual.getEncryptedNote())) {
            if (expected.getEncryptedNote() == null)
                errors.add(String.format("%s encrypted note"
                                + " expected:null but was:<%d bytes>",
                        message, actual.getEncryptedNote().length));
            else if (actual.getEncryptedNote() == null)
                errors.add(String.format("%s encrypted note"
                                + " expected:<%d bytes> but was:null",
                        message, expected.getEncryptedNote().length));
            else
                errors.add(String.format("%s encrypted notes differ", message));
        }
        if (!expected.getCreateTime().equals(actual.getCreateTime()))
            errors.add(String.format("%s created time expected:%s but was:%s",
                    message, expected.getCreateTime(), actual.getCreateTime()));
        if (!expected.getModTime().equals(actual.getModTime()))
            errors.add(String.format("%s modified time expected:%s but was:%s",
                    message, expected.getModTime(), actual.getModTime()));
        if (expected.getPrivate() != actual.getPrivate())
            errors.add(String.format("%s privacy level expected:%d but was:%d",
                    message, expected.getPrivate(), actual.getPrivate()));
        if (expected.getCategoryId() != actual.getCategoryId())
            errors.add(String.format("%s category ID expected:%d but was:%d",
                    message, expected.getCategoryId(), actual.getCategoryId()));
        if (expected.getCategoryName() != null &&
                !expected.getCategoryName().equals(actual.getCategoryName()))
            errors.add(String.format("%s category name expected:\"%s\" but was:\"%s\"",
                    message, expected.getCategoryName(), actual.getCategoryName()));

        return errors;
    }

    /**
     * Test a clean import of public notes from a version 1 import file.
     * All existing records must be replaced.
     */
    @Test
    public void testImportNotesClean1Public() throws IOException {

        addRandomNotes();

        MockProgressBar progress = runImporter("notes-misc-v1.xml",
                ImportType.CLEAN, false, null);

        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(cat.getId(), cat.getName());
        assertCategoriesEquals(expectedCategories);

        List<NoteItem> expectedNotes = new ArrayList<>(TEST_NOTES_1.size());
        for (NoteItem note : TEST_NOTES_1) {
            if (!note.isPrivate())
                expectedNotes.add(note);
        }
        // Ensure the notes are sorted by ID
        Collections.sort(expectedNotes, MockNoteRepository.NOTE_ID_COMPARATOR);
        List<NoteItem> actualNotes = readNotes();

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        // The "Unfiled" category is not in the TEST_CATEGORIES lists
        int expectedRecordCount = 1 + TEST_CATEGORIES_2.size()
                + TEST_NOTES_1.size();
        assertEquals("Total records in file", -1, endProgress.total);
        assertEquals("Number of records processed",
                expectedRecordCount, endProgress.current);
    }

    /**
     * Test a clean import of private (not password-protected) notes
     * from a version 2 import file.  All existing records must be replaced.
     */
    @Test
    public void testImportNotesClean2Private() throws IOException {

        addRandomNotes();

        MockProgressBar progress = runImporter("notes-misc-v2.xml",
                ImportType.CLEAN, true, null);

        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(cat.getId(), cat.getName());
        assertCategoriesEquals(expectedCategories);

        List<NoteItem> expectedNotes = new ArrayList<>(TEST_NOTES_2);
        // Ensure the notes are sorted by ID
        Collections.sort(expectedNotes, MockNoteRepository.NOTE_ID_COMPARATOR);
        List<NoteItem> actualNotes = readNotes();

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Progress meter after import", endProgress);
        // The "Unfiled" category is not in the TEST_CATEGORIES lists
        int expectedRecordCount = 1 + TEST_CATEGORIES_2.size()
                + TEST_NOTES_2.size();
        assertEquals("Total records in file",
                expectedRecordCount, endProgress.total);
        assertEquals("Number of records processed",
                expectedRecordCount, endProgress.current);
    }

    /**
     * Test importing private (not password-protected) notes in
     * {@link ImportType#REVERT REVERT} mode.  Any existing notes with
     * the same ID as one from the XML import file should be overwritten.
     * This one uses the version 1 import file.
     */
    @Test
    public void testImportNotesRevert1Private() throws IOException {

        List<NoteItem> oldNotes = addRandomNotes();
        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1)
            expectedCategories.put(cat.getId(), cat.getName());

        runImporter("notes-misc-v1.xml",
                ImportType.REVERT, true, null);

        // For this operation we don't have any conflicting category names;
        // all categories between the two groups have unique names.
        // Categories with conflicting ID's will be replaced without
        // affecting the notes using those categories.
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(cat.getId(), cat.getName());
        assertCategoriesEquals(expectedCategories);

        // Update the expectations for notes.
        Set<Long> newIds = new HashSet<>();
        List<NoteItem> expectedNotes = new ArrayList<>(
                oldNotes.size() + TEST_NOTES_1.size());
        for (NoteItem note : TEST_NOTES_1) {
            expectedNotes.add(note);
            newIds.add(note.getId());
        }
        for (NoteItem note : oldNotes) {
            if (newIds.contains(note.getId()))
                // This note should have been replaced; leave it out.
                continue;
            // Ensure the remaining old items are using the new category names
            note.setCategoryName(expectedCategories
                    .get(note.getCategoryId()));
            expectedNotes.add(note);
        }
        // Ensure the notes are sorted by ID
        Collections.sort(expectedNotes, MockNoteRepository.NOTE_ID_COMPARATOR);

        List<NoteItem> actualNotes = readNotes();

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);
    }

    /**
     * Test importing public notes in {@link ImportType#REVERT REVERT} mode.
     * Any existing notes with the same ID as one from the XML import
     * file should be overwritten.  This one uses the version 2 import file.
     */
    @Test
    public void testImportNotesRevert2Public() throws IOException {

        List<NoteItem> oldNotes = addRandomNotes();
        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1)
            expectedCategories.put(cat.getId(), cat.getName());

        runImporter("notes-misc-v2.xml",
                ImportType.REVERT, false, null);

        // For this operation we don't have any conflicting category names;
        // all categories between the two groups have unique names.
        // Categories with conflicting ID's will be replaced without
        // affecting the notes using those categories.
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(cat.getId(), cat.getName());
        assertCategoriesEquals(expectedCategories);

        // Update the expectations for notes.
        Set<Long> newIds = new HashSet<>();
        List<NoteItem> expectedNotes = new ArrayList<>(
                oldNotes.size() + TEST_NOTES_2.size());
        for (NoteItem note : TEST_NOTES_2) {
            if (note.isPrivate())
                continue;
            expectedNotes.add(note);
            newIds.add(note.getId());
        }
        for (NoteItem note : oldNotes) {
            if (newIds.contains(note.getId()))
                // This note should have been replaced; leave it out.
                continue;
            // Ensure the remaining old items are using the new category names
            note.setCategoryName(expectedCategories
                    .get(note.getCategoryId()));
            expectedNotes.add(note);
        }
        // Ensure the notes are sorted by ID
        Collections.sort(expectedNotes, MockNoteRepository.NOTE_ID_COMPARATOR);

        List<NoteItem> actualNotes = readNotes();

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);
    }

    /**
     * Test importing public notes in {@link ImportType#ADD ADD} mode.
     * Any imported note with the same ID as an existing note will be
     * assigned a new ID regardless of its contents.  This test uses
     * the version 1 import file.
     */
    @Test
    public void testImportNotesAdd1Public() throws IOException {

        List<NoteItem> oldNotes = addRandomNotes();
        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1)
            expectedCategories.put(cat.getId(), cat.getName());

        // For this test we need a public note which exists in the import file.
        NoteItem alreadyImportedNote = null;
        while (alreadyImportedNote == null) {
            alreadyImportedNote = TEST_NOTES_1.get(RAND.nextInt(TEST_NOTES_1.size()));
            if ((alreadyImportedNote.getCategoryId() == NoteCategory.UNFILED) ||
                    alreadyImportedNote.isPrivate()) {
                alreadyImportedNote = null;
                continue;
            }
            for (NoteItem note : oldNotes) {
                if (note.getId().equals(alreadyImportedNote.getId())) {
                    alreadyImportedNote = null;
                    break;
                }
            }
        }
        NoteCategory alreadyImportedCategory = mockRepo.insertCategory(
                alreadyImportedNote.getCategoryName());
        expectedCategories.put(alreadyImportedCategory.getId(),
                alreadyImportedCategory.getName());
        alreadyImportedNote = alreadyImportedNote.clone();
        alreadyImportedNote.setCategoryId(alreadyImportedCategory.getId());
        alreadyImportedNote.setCreateTime(Instant.now().minusSeconds(
                RAND.nextInt(86400) + 86400));
        alreadyImportedNote.setModTime(alreadyImportedNote.getCreateTime());
        mockRepo.insertNote(alreadyImportedNote);
        oldNotes.add(alreadyImportedNote);

        runImporter("notes-misc-v1.xml", ImportType.ADD, false, null);

        // The ID's of imported categories that conflict with existing
        // entries should change; we won't know what they are in advance.
        Map<String,Long> catNameMap = new HashMap<>();
        for (NoteCategory cat : mockRepo.getCategories())
            catNameMap.put(cat.getName(), cat.getId());
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(catNameMap.get(cat.getName()), cat.getName());
        assertCategoriesEquals(expectedCategories);

        // Notes with a conflicting ID should be assigned a new ID,
        // which we can't determine until we've read the actual data.
        // Start with the non-conflicting notes.
        SortedMap<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (NoteItem note : oldNotes)
            expectedNotes.put(note.getId(), note);
        Map<Long,NoteItem> conflictingNotes = new HashMap<>();
        for (NoteItem note : TEST_NOTES_1) {
            if (note.isPrivate())
                continue;
            if (expectedNotes.containsKey(note.getId())) {
                conflictingNotes.put(note.getId(), note);
                continue;
            }
            NoteItem newExpectation = note.clone();
            newExpectation.setCategoryId(
                    catNameMap.get(note.getCategoryName()));
            expectedNotes.put(note.getId(), newExpectation);
        }

        SortedMap<Long,NoteItem> actualNotes = new TreeMap<>();
        for (NoteItem note : readNotes()) {
            actualNotes.put(note.getId(), note);
            if (conflictingNotes.containsKey(note.getId()) &&
                    !expectedNotes.containsKey(note.getId())) {
                NoteItem newExpectation = conflictingNotes.get(
                        note.getId()).clone();
                newExpectation.setId(note.getId());
                newExpectation.setCategoryId(
                        catNameMap.get(note.getCategoryName()));
                expectedNotes.put(note.getId(), newExpectation);
                conflictingNotes.remove(note.getId());
            }
        }
        // If we have any remaining conflicts, ignore their ID's
        long nextAvailableId = -1;
        for (NoteItem note : conflictingNotes.values()) {
            NoteItem newExpectation = note.clone();
            newExpectation.setId(--nextAvailableId);
            newExpectation.setCategoryId(
                    catNameMap.get(note.getCategoryName()));
            expectedNotes.put(--nextAvailableId, newExpectation);
        }

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);
    }

    /**
     * Test importing private (but not encrypted) notes in
     * {@link ImportType#ADD ADD} mode.  Any imported note with the same
     * ID as an existing note will be assigned a new ID regardless of its
     * contents.  This test uses the version 2 import file.
     */
    @Test
    public void testImportNotesAdd2Private() throws IOException {

        List<NoteItem> oldNotes = addRandomNotes();
        SortedMap<Long,String> expectedCategories = new TreeMap<>();
        for (NoteCategory cat : TEST_CATEGORIES_1)
            expectedCategories.put(cat.getId(), cat.getName());

        // For this test we need a note which exists in the import file.
        NoteItem alreadyImportedNote = null;
        while (alreadyImportedNote == null) {
            alreadyImportedNote = TEST_NOTES_2.get(RAND.nextInt(TEST_NOTES_2.size()));
            if ((alreadyImportedNote.getCategoryId() == NoteCategory.UNFILED)) {
                alreadyImportedNote = null;
                continue;
            }
            for (NoteItem note : oldNotes) {
                if (note.getId().equals(alreadyImportedNote.getId())) {
                    alreadyImportedNote = null;
                    break;
                }
            }
        }
        NoteCategory alreadyImportedCategory = mockRepo.insertCategory(
                alreadyImportedNote.getCategoryName());
        expectedCategories.put(alreadyImportedCategory.getId(),
                alreadyImportedCategory.getName());
        alreadyImportedNote = alreadyImportedNote.clone();
        alreadyImportedNote.setCategoryId(alreadyImportedCategory.getId());
        alreadyImportedNote.setCreateTime(Instant.now().minusSeconds(
                RAND.nextInt(86400) + 86400));
        alreadyImportedNote.setModTime(alreadyImportedNote.getCreateTime());
        mockRepo.insertNote(alreadyImportedNote);
        oldNotes.add(alreadyImportedNote);

        runImporter("notes-misc-v2.xml", ImportType.ADD, true, null);

        // The ID's of imported categories that conflict with existing
        // entries should change; we won't know what they are in advance.
        Map<String,Long> catNameMap = new HashMap<>();
        for (NoteCategory cat : mockRepo.getCategories())
            catNameMap.put(cat.getName(), cat.getId());
        for (NoteCategory cat : TEST_CATEGORIES_2)
            expectedCategories.put(catNameMap.get(cat.getName()), cat.getName());
        assertCategoriesEquals(expectedCategories);

        // Notes with a conflicting ID should be assigned a new ID,
        // which we can't determine until we've read the actual data.
        // Start with the non-conflicting notes.
        SortedMap<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (NoteItem note : oldNotes)
            expectedNotes.put(note.getId(), note);
        Map<Long,NoteItem> conflictingNotes = new HashMap<>();
        for (NoteItem note : TEST_NOTES_2) {
            if (expectedNotes.containsKey(note.getId())) {
                conflictingNotes.put(note.getId(), note);
                continue;
            }
            NoteItem newExpectation = note.clone();
            newExpectation.setCategoryId(
                    catNameMap.get(note.getCategoryName()));
            expectedNotes.put(note.getId(), newExpectation);
        }

        SortedMap<Long,NoteItem> actualNotes = new TreeMap<>();
        for (NoteItem note : readNotes()) {
            actualNotes.put(note.getId(), note);
            if (conflictingNotes.containsKey(note.getId()) &&
                    !expectedNotes.containsKey(note.getId())) {
                NoteItem newExpectation = conflictingNotes.get(
                        note.getId()).clone();
                newExpectation.setId(note.getId());
                newExpectation.setCategoryId(
                        catNameMap.get(note.getCategoryName()));
                expectedNotes.put(note.getId(), newExpectation);
                conflictingNotes.remove(note.getId());
            }
        }
        // If we have any remaining conflicts, ignore their ID's
        long nextAvailableId = -1;
        for (NoteItem note : conflictingNotes.values()) {
            NoteItem newExpectation = note.clone();
            newExpectation.setId(--nextAvailableId);
            newExpectation.setCategoryId(
                    catNameMap.get(note.getCategoryName()));
            expectedNotes.put(--nextAvailableId, newExpectation);
        }

        assertNoteEquals("Imported notes", expectedNotes, actualNotes);
    }

    /**
     * Test importing a private, encrypted note in version 1 format.
     */
    @Test
    public void testImportEncrypted1() throws IOException {

        NoteItem expectedNote = new NoteItem();
        expectedNote.setId(14);
        expectedNote.setCategoryId(NoteCategory.UNFILED);
        expectedNote.setCategoryName(unfiledName);
        expectedNote.setCreateTime(Instant.ofEpochMilli(1773178820503L));
        expectedNote.setModTime(Instant.ofEpochMilli(1773178961068L));
        expectedNote.setPrivate(StringEncryption.encryptionType());

        final String newPassword = SRAND.nextAlphanumeric(12, 16);
        globalEncryption.setPassword(newPassword.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);
        assertTrue("Current password was not set", globalEncryption.hasKey());

        expectedNote.setEncryptedNote(
                globalEncryption.encrypt("Sed nec mauris libero."
                        + "  Mauris egestas, odio eget bibendum tincidunt,"
                        + " erat ipsum porta nunc,"
                        + " et fringilla tortor lectus ac mauris."
                        + "  Duis lectus leo, cursus et lobortis sit amet,"
                        + " accumsan vitae lorem."
                        + "  Vestibulum sit amet dui in purus"
                        + " tempus feugiat nec sed enim."
                        + "  Nulla ligula nulla, egestas vitae volutpat a,"
                        + " aliquet id eros."
                        + "  Nam ut maximus massa, id porttitor ante."
                        + "  Aliquam accumsan venenatis molestie."
                        + "  Donec id dolor leo."
                        + "  Aliquam laoreet molestie quam,"
                        + " vel maximus elit varius ut."
                        + "  Ut eu elementum felis."
                        + "  Aenean nec finibus velit, in sagittis arcu."));

        runImporter("notes-encrypted-v1.xml", ImportType.CLEAN,
                true, "EsdnuQeLz");

        assertNoteEquals("Imported note",
                Collections.singletonList(expectedNote), readNotes());

    }

    /**
     * Test importing a private, encrypted note in version 2 format.
     */
    @Test
    public void testImportEncrypted2() throws IOException {

        NoteItem expectedNote = new NoteItem();
        expectedNote.setId(2);
        expectedNote.setCategoryId(NoteCategory.UNFILED);
        expectedNote.setCategoryName(unfiledName);
        expectedNote.setCreateTime(Instant.ofEpochMilli(1773179153219L));
        expectedNote.setModTime(Instant.ofEpochMilli(1773179195235L));
        expectedNote.setPrivate(StringEncryption.encryptionType());

        final String newPassword = SRAND.nextAlphanumeric(12, 16);
        globalEncryption.setPassword(newPassword.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);
        assertTrue("Current password was not set", globalEncryption.hasKey());

        expectedNote.setEncryptedNote(
                globalEncryption.encrypt("Sed sagittis diam ut mi finibus,"
                        + " id gravida eros fringilla."
                        + "  Phasellus ac eros sed ipsum viverra cursus."
                        + "  Sed est felis, auctor sit amet purus vel,"
                        + " aliquam varius risus."
                        + "  Etiam luctus maximus mauris,"
                        + " sit amet pellentesque dui dapibus in."
                        + "  Aliquam ac nulla eu tortor"
                        + " eleifend fermentum eget id elit."
                        + "  Nullam et elementum velit, a viverra tortor."
                        + "  Sed placerat mauris ac urna egestas,"
                        + " id placerat massa egestas."
                        + "  Praesent tristique gravida semper."
                        + "  Sed a aliquet metus, ac molestie ex."
                        + "  Praesent luctus ligula eu tristique sagittis."
                        + "  Nunc suscipit iaculis lacus at tempor."
                        + "  Phasellus ut ex elit."
                        + "  Vivamus rutrum nisl at odio blandit commodo."));

        runImporter("notes-encrypted-v2.xml", ImportType.CLEAN,
                true, "EsdnuQeLz");

        assertNoteEquals("Imported note",
                Collections.singletonList(expectedNote), readNotes());

    }

}
