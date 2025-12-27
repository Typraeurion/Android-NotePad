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
package com.xmission.trevin.android.notes.provider;

import static com.xmission.trevin.android.notes.provider.MockNoteRepository.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.database.DataSetObserver;
import android.database.SQLException;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.UnsupportedEncodingException;
import java.util.*;

/**
 * Tests for the NoteRepository implementation
 *
 * @author Trevin Beattie
 */
@RunWith(AndroidJUnit4.class)
public class NoteRepositoryTests {

    static Context testContext = null;

    static NoteRepository repo = NoteRepositoryImpl.getInstance();

    static final Random RAND = new Random();

    public static class TestObserver extends DataSetObserver {
        private boolean changed = false;
        private boolean invalidated = false;
        @Override
        public void onChanged() {
            changed = true;
        }
        @Override
        public void onInvalidated() {
            invalidated = true;
        }
        public void reset() {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            changed = false;
            invalidated = false;
        }
        public void assertChanged() {
            assertChanged("Data observer onChanged was not called");
        }
        public void assertChanged(String message) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(message, changed);
        }
        public void assertNotChanged() {
            assertNotChanged("Data observer onChanged was called");
        }
        public void assertNotChanged(String message) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(message, changed);
        }
        public void assertInvalidated() {
            assertInvalidated("Data observer onInvalidated was not called");
        }
        public void assertInvalidated(String message) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(message, invalidated);
        }
        public void assertNotInvalidated() {
            assertNotInvalidated("Data observer onInvalidated was called");
        }
        public void assertNotInvalidated(String message) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(message, invalidated);
        }
    }

    @BeforeClass
    public static void openDatabase() {
        testContext = InstrumentationRegistry.getTargetContext();
        repo.open(testContext);
    }

    @AfterClass
    public static void closeDatabase() {
        repo.release(testContext);
    }

    /**
     * Test that the database is pre-populated with the
     * &ldquo;Unfiled&rdquo; category
     */
    @Test
    public void testUnfiledCategory() {
        String expectedName = testContext.getString(R.string.Category_Unfiled);
        NoteCategory category = repo.getCategoryById(NoteCategory.UNFILED);
        assertNotNull(String.format("Category %d not found in the repository",
                NoteCategory.UNFILED), category);
        assertEquals("Unfiled category name", expectedName, category.getName());
    }

    /**
     * Test counting the number of categories in the database.
     * Because the database may contain pre-test data, we don&rsquo;t
     * depend on a specific value; but it must be at least 1
     * (for the &ldquo;Unfiled&rdquo; category).
     */
    @Test
    public void testCountCategories() {
        int count = repo.countCategories();
        assertTrue(String.format("Expected at least 1 category, got %d",
                count), count > 0);
    }

    /**
     * Test inserting a new category, reading it,
     * updating it, and then deleting it.
     * <p>
     * There&rsquo;s a tiny chance this may result in a duplicate value error
     * since the category name is generated from random letters, but since the
     * minimum length of the name is 12 characters it shouldn&rsquo;t conflict
     * with &ldquo;Unfiled&rdquo; so would only be an issue if the database
     * already contains any additional categories with long names (no spaces),
     * and even then the odds of collision are less than one in 10<sup>20</sup>.
     * </p>
     */
    @Test
    public void testCategoryCRUD() {
        int targetLen = RAND.nextInt(20) + 12;
        String firstExpectedName = RandomStringUtils.randomAlphabetic(targetLen);
        targetLen = RAND.nextInt(20) + 12;
        String secondExpectedName = RandomStringUtils.randomAlphabetic(targetLen);
        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);
        try {
            NoteCategory newCategory = repo.insertCategory(firstExpectedName);
            assertNotNull("No NoteCategory returned from insert",
                    newCategory);
            assertNotNull("New category is missing its ID",
                    newCategory.getId());
            try {
                observer.assertChanged(
                        "Registered observer was not called after insert");
                NoteCategory readCategory = repo.getCategoryById(newCategory.getId());
                assertNotNull(String.format("New category %d not found",
                        newCategory.getId()), readCategory);
                assertEquals("Category name read back",
                        firstExpectedName, readCategory.getName());
                observer.reset();
                readCategory = repo.updateCategory(
                        newCategory.getId(), secondExpectedName);
                assertNotNull(String.format("Updated category %d not found",
                        newCategory.getId()), readCategory);
                assertEquals("Category name read back after update",
                        secondExpectedName, readCategory.getName());
                observer.assertChanged(
                        "Registered observer was not called after update");
            } finally {
                observer.reset();
                assertTrue("Repository did not indicate the category was deleted",
                        repo.deleteCategory(newCategory.getId()));
                assertNull(String.format("Category %d was not deleted",
                                newCategory.getId()),
                        repo.getCategoryById(newCategory.getId()));
                observer.assertChanged(
                        "Registered observer was not called after delete");
            }
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test inserting a NoteCategory object (used by the importer service).
     * We expect such an insert to fail if the provided category ID is
     * already in use.
     */
    @Test
    public void testCategoryObjectCRUD() {
        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);
        try {
            // First we'll need to find an unused row ID
            NoteCategory originalCategory = new NoteCategory();
            originalCategory.setId(99);
            while (repo.getCategoryById(originalCategory.getId()) != null)
                originalCategory.setId(originalCategory.getId() + 1);
            int targetLen = RAND.nextInt(20) + 12;
            originalCategory.setName(
                    RandomStringUtils.randomAlphabetic(targetLen));
            NoteCategory insertedCategory =
                    repo.insertCategory(originalCategory.clone());
            assertNotNull("No NoteCategory returned from insert",
                    insertedCategory);
            try {
                assertEquals("Inserted category",
                        originalCategory, insertedCategory);
                observer.assertChanged(
                        "Registered observer was not called after insert");
                observer.reset();
                NoteCategory conflictingCategory = new NoteCategory();
                conflictingCategory.setId(insertedCategory.getId());
                targetLen = RAND.nextInt(20) + 12;
                conflictingCategory.setName(
                        RandomStringUtils.randomAlphabetic(targetLen));
                try {
                    NoteCategory returnCategory =
                            repo.insertCategory(conflictingCategory);
                    if ((returnCategory != null) &&
                            (returnCategory.getId() != insertedCategory.getId()))
                        repo.deleteCategory(returnCategory.getId());
                    fail(String.format("Repository overwrote %s with %s",
                            insertedCategory, conflictingCategory));
                } catch (SQLException e) {
                    // Success
                }
                observer.assertNotChanged(
                        "Registered observer was called in spite of conflicting insert");

                // Now try again with a duplicate name, no specified ID.
                try {
                    NoteCategory returnCategory =
                            repo.insertCategory(originalCategory.getName());
                    if (returnCategory != null)
                        repo.deleteCategory(returnCategory.getId());
                    fail(String.format("Repository added duplicate category:"
                            + " original = %s, conflicting = %s",
                            insertedCategory, returnCategory));
                } catch (SQLException e) {
                    // Success
                }
            } finally {
                observer.reset();
                assertTrue("Repository did not indicate the category was deleted",
                        repo.deleteCategory(insertedCategory.getId()));
                assertNull(String.format("Category %d was not deleted",
                        insertedCategory.getId()),
                        repo.getCategoryById(insertedCategory.getId()));
                observer.assertChanged(
                        "Registered observer was not called after delete");
            }
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test reading all categories.  This entails inserting some new
     * categories in order to ensure we get back multiple values.
     * We delete the newly inserted categories afterward.
     * <p>
     * There&rsquo;s a tiny chance this may result in a duplicate value error
     * since the category name is generated from random letters, but since the
     * minimum length of the name is 12 characters it shouldn&rsquo;t conflict
     * with &ldquo;Unfiled&rdquo; so would only be an issue if the database
     * already contains any additional categories with long names (no spaces),
     * and even then the odds of collision are less than one in 10<sup>20</sup>.
     * </p>
     */
    @Test
    public void testGetCategories() {
        List<NoteCategory> testCategories = new ArrayList<>();
        Set<Long> expectedIds = new TreeSet<>();
        expectedIds.add((long) NoteCategory.UNFILED);
        int target = RAND.nextInt(3) + 3;
        try {
            for (int i = 0; i < target; i++) {
                int targetLen = RAND.nextInt(20) + 12;
                String name = RandomStringUtils.randomAlphabetic(targetLen);
                NoteCategory newCategory = repo.insertCategory(name);
                assertNotNull("Failed to insert new category " + name,
                        newCategory);
                assertNotNull(String.format(
                        "New category %s is missing its ID", name),
                        newCategory.getId());
                testCategories.add(newCategory);
                expectedIds.add(newCategory.getId());
            }

            List<NoteCategory> allCategories = repo.getCategories();
            assertNotNull("Repo did not return a list of categories",
                    allCategories);
            assertTrue(String.format("Expected at least %d categories"
                                    + " in the repository; got %d",
                            testCategories.size() + 1, allCategories.size()),
                    allCategories.size() > testCategories.size());
            Set<Long> actualIds = new TreeSet<>();
            for (NoteCategory category : allCategories) {
                assertNotNull(String.format("Category %s is missing its ID",
                        category.getName()), category.getId());
                actualIds.add(category.getId());
            }
            assertTrue(String.format("Expected categories %s, but got %s",
                    expectedIds, actualIds),
                    actualIds.containsAll(expectedIds));
        } finally {
            for (NoteCategory testCategory : testCategories) {
                repo.deleteCategory(testCategory.getId());
            }
        }
    }

    /**
     * Test deleting <i>all</i> categories.  <b>This test is destructive!</b>
     * We first insert a few new categories to ensure the table is not empty
     * (save for the &ldquo;Unfiled&rdquo; category), then delete all and
     * re-read the table to ensure that <i>only</i> the &ldquo;Unfiled&rdquo;
     * category remains.
     */
    @Test
    public void testDeleteAllCategories() {
        List<NoteCategory> testCategories = new ArrayList<>();
        int target = RAND.nextInt(3) + 3;
        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);
        try {
            while (testCategories.size() < target) {
                int targetLen = RAND.nextInt(20) + 12;
                String name = RandomStringUtils.randomAlphabetic(targetLen);
                NoteCategory newCategory = repo.insertCategory(name);
                assertNotNull("Failed to insert new category " + name,
                        newCategory);
                assertNotNull(String.format(
                                "New category %s is missing its ID", name),
                        newCategory.getId());
                testCategories.add(newCategory);
            }

            List<NoteCategory> allCategories = repo.getCategories();
            assertNotNull("Repo did not return a list of categories",
                    allCategories);
            assertTrue(String.format("Expected at least %d categories"
                                    + " in the repository; got %d",
                            testCategories.size() + 1, allCategories.size()),
                    allCategories.size() > testCategories.size());

            observer.reset();
            repo.deleteAllCategories();
            allCategories = repo.getCategories();
            assertNotNull("Repo did not return a list of categories",
                    allCategories);
            assertEquals("Number of categories remaining after deleting all",
                    1, allCategories.size());
            NoteCategory lastCategory = allCategories.get(0);
            assertEquals("Last remaining category ID",
                    Long.valueOf(NoteCategory.UNFILED), lastCategory.getId());
            observer.assertChanged(
                    "Observer not called after deleting all categories");
        } catch (RuntimeException e) {
            for (NoteCategory testCategory : testCategories) {
                repo.deleteCategory(testCategory.getId());
            }
            throw e;
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test inserting new metadata, reading it,
     * updating it, and then deleting it (by name).
     * <p>
     * There&rsquo;s a tiny chance this may result in a duplicate value error
     * since the metadata name is generated from random letters, but since the
     * minimum length of the name is 12 characters it would only be an issue
     * if the database already contains any additional metadata with long
     * names, and even then the odds of collision are less than one in
     * 10<sup>20</sup>.
     * </p>
     */
    @Test
    public void testMetadataCRUD() {
        int targetLen = RAND.nextInt(20) + 12;
        String name = RandomStringUtils.randomAlphabetic(targetLen);
        byte[] firstValue = new byte[10 + RAND.nextInt(20)];
        byte[] secondValue = new byte[10 + RAND.nextInt(20)];
        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);
        try {
            NoteMetadata newMetadata = repo.upsertMetadata(name, firstValue);
            assertNotNull("No NoteMetadata returned from insert", newMetadata);
            try {
                assertNotNull("New metadata is missing its ID",
                        newMetadata.getId());
                long metaId = newMetadata.getId();
                assertEquals("Metadata name returned from insert",
                        name, newMetadata.getName());
                assertArrayEquals("Metadata value returned from insert",
                        firstValue, newMetadata.getValue());
                observer.assertChanged("Observer not called after insert");
                NoteMetadata readMetadata = repo.getMetadataByName(name);
                assertNotNull(String.format(
                                "Newly inserted metadata \"%s\" not found", name),
                        readMetadata);
                assertEquals("ID read back from metadata",
                        (Long) metaId, readMetadata.getId());
                assertEquals("Name read back from metadata",
                        name, readMetadata.getName());
                assertArrayEquals("Value read back from metadata after insert",
                        firstValue, readMetadata.getValue());

                observer.reset();
                newMetadata = repo.upsertMetadata(name, secondValue);
                assertNotNull("No NoteMetadata returned from update", newMetadata);
                assertEquals("Updated metadata ID",
                        (Long) metaId, newMetadata.getId());
                assertEquals("Metadata name returned from update",
                        name, newMetadata.getName());
                assertArrayEquals("Metadata value returned from update",
                        secondValue, newMetadata.getValue());
                observer.assertChanged("Observer not called after update");
                readMetadata = repo.getMetadataById(metaId);
                assertNotNull(String.format("Updated metadata #%d not found",
                        metaId), readMetadata);
                assertEquals("ID read back from updated metadata",
                        (Long) metaId, readMetadata.getId());
                assertEquals("Name read back from updated metadata",
                        name, readMetadata.getName());
                assertArrayEquals("Value read back from metadata after update",
                        secondValue, readMetadata.getValue());

            } finally {
                observer.reset();
                assertTrue("Repository did not indicate the metadata was deleted",
                        repo.deleteMetadata(name));
                assertNull(String.format("Metadata \"%s\" was not deleted", name),
                        repo.getMetadataByName(name));
                observer.assertChanged("Observer not called after delete");
            }
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test reading and counting all metadata.  This entails inserting
     * some new metadata in order to ensure we get back multiple values.
     * We delete the newly inserted metadata afterward by their ID.
     * <p>
     * There&rsquo;s a tiny chance this may result in a duplicate value error
     * since the metadata name is generated from random letters, but since the
     * minimum length of the name is 12 characters it would only be an issue
     * if the database already contains any additional metadata with long
     * names, and even then the odds of collision are less than one in
     * 10<sup>20</sup>.
     * </p>
     */
    @Test
    public void testGetMetadata() throws UnsupportedEncodingException {
        int target = RAND.nextInt(3) + 3;
        Map<String,String> expectedData = new TreeMap<>();
        try {
            while (expectedData.size() < target) {
                int targetLen = RAND.nextInt(20) + 12;
                String name = RandomStringUtils.randomAlphabetic(targetLen);
                // For simplicity in logging any errors,
                // the values will be encoded strings.
                targetLen = RAND.nextInt(20) + 12;
                String sValue = RandomStringUtils.randomAscii(targetLen);
                NoteMetadata datum = repo.upsertMetadata(name,
                        sValue.getBytes("UTF-8"));
                assertNotNull("No NoteMetadata returned from insert", datum);
                assertNotNull("New metadata has no ID", datum.getId());
                expectedData.put(name, sValue);
            }

            int count = repo.countMetadata();
            assertTrue(String.format("Expected a metadata count of"
                    + " at least %d, but got %d", expectedData.size(), count),
                    count >= expectedData.size());

            List<NoteMetadata> allMetadata = repo.getMetadata();
            assertNotNull("No list returned from getMetadata", allMetadata);
            assertTrue(String.format("Expected at least %d metadata"
                                    + " in the repository, but got %d",
                            expectedData.size(), allMetadata.size()),
                    allMetadata.size() >= expectedData.size());
            Map<String,String> actualData = new TreeMap<>();
            for (NoteMetadata datum: allMetadata)
                actualData.put(datum.getName(),
                        new String(datum.getValue(), "UTF-8"));
            assertTrue(String.format("Expected metadata to include %s,"
                                    + " but got %s",
                            expectedData.keySet(), actualData.keySet()),
                    actualData.keySet().containsAll(expectedData.keySet()));
            if (actualData.size() > expectedData.size()) {
                // Discard any metadata we didn't create
                Iterator<Map.Entry<String,String>> it =
                        actualData.entrySet().iterator();
                while (it.hasNext()) {
                    if (expectedData.containsKey(it.next().getKey()))
                        continue;
                    it.remove();
                }
            }
            assertEquals("Metadata values", expectedData, actualData);
        } finally {
            for (String name : expectedData.keySet()) {
                repo.deleteMetadata(name);
            }
        }
    }

    /**
     * Test deleting <i>all</i> metadata.  <b>This test is destructive!</b>
     * We first insert a few new metadata to ensure the table is not empty,
     * then delete all and re-read the table to ensure that nothing remains.
     */
    @Test
    public void testDeleteAllMetadata() {
        int target = RAND.nextInt(3) + 3;
        Set<String> testNames = new TreeSet<>();
        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);
        try {
            while (testNames.size() < target) {
                int targetLen = RAND.nextInt(20) + 12;
                String name = RandomStringUtils.randomAlphabetic(targetLen);
                byte[] value = new byte[10 + RAND.nextInt(20)];
                NoteMetadata datum = repo.upsertMetadata(name, value);
                assertNotNull("No NoteMetadata returned from insert", datum);
                assertNotNull("New metadata has no ID", datum.getId());
                testNames.add(name);
            }

            List<NoteMetadata> allMetadata = repo.getMetadata();
            assertNotNull("No list returned from getMetadata", allMetadata);
            assertFalse("No metadata returned after inserts",
                    allMetadata.isEmpty());

            observer.reset();
            assertTrue("Response from deleteAllMetadata",
                    repo.deleteAllMetadata());
            observer.assertChanged(
                    "Observer not called after deleting all metadata");

            allMetadata = repo.getMetadata();
            assertNotNull("No list returned from getMetadata"
                    + " (after deleteAllMetadata)", allMetadata);
            assertEquals("Remaining metadata after deleteAllMetadata",
                    Collections.emptyList(), allMetadata);
        } finally {
            repo.unregisterDataSetObserver(observer);
            for (String name : testNames) {
                repo.deleteMetadata(name);
            }
        }
    }

    /**
     * Test inserting, reading, updating, and deleting a simple note.
     */
    @Test
    public void testNoteCRUD() {
        NoteItem expectedNote = new NoteItem();
        expectedNote.setCategoryId(NoteCategory.UNFILED);
        expectedNote.setPrivate(0);
        expectedNote.setCreateTimeNow();
        expectedNote.setModTime(expectedNote.getCreateTime());
        int targetLen = RAND.nextInt(30) + 12;
        expectedNote.setNote(RandomStringUtils.randomAscii(targetLen));

        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);

        try {
            NoteItem returnNote = repo.insertNote(expectedNote);
            assertNotNull("No note returned from insert", returnNote);
            assertNotNull("No ID returned with inserted note",
                    returnNote.getId());
            observer.assertChanged("Observer not called after insert");
            long noteId = returnNote.getId();

            try {
                returnNote = repo.getNoteById(noteId);
                assertNotNull("Failed to read back note " + noteId, returnNote);
                expectedNote.setId(noteId);
                expectedNote.setCategoryName(testContext
                        .getString(R.string.Category_Unfiled));
                assertEquals("Note read back from the repository after insert",
                        expectedNote, returnNote);

                observer.reset();
                targetLen = RAND.nextInt(30) + 12;
                expectedNote.setNote(RandomStringUtils.randomAscii(targetLen));
                expectedNote.setModTimeNow();
                returnNote = repo.updateNote(expectedNote);
                assertNotNull("No note returned from update", returnNote);
                observer.assertChanged("Observer not called after update");
                returnNote = repo.getNoteById(noteId);
                assertNotNull("Failed to read back note " + noteId, returnNote);
                assertEquals("Note read back from the repository after update",
                        expectedNote, returnNote);
            } finally {
                observer.reset();
                assertTrue("Repository did not indicate the note was deleted",
                        repo.deleteNote(noteId));
                assertNull("New note was not deleted", repo.getNoteById(noteId));
                observer.assertChanged("Observer not called after delete");
            }
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test inserting, reading, and deleting a note which has a
     * predetermined ID (e.g. from importing notes from a backup file)
     */
    @Test
    public void testInsertNoteWithId() {
        long expectedId = (RAND.nextInt() & 0xffff) + 1024L;
        while (repo.getNoteById(expectedId) != null)
            expectedId++;
        NoteItem expectedNote = new NoteItem();
        expectedNote.setId(expectedId);
        expectedNote.setCategoryId(NoteCategory.UNFILED);
        expectedNote.setPrivate(0);
        expectedNote.setCreateTimeNow();
        expectedNote.setModTimeNow();
        int targetLen = RAND.nextInt(30) + 12;
        expectedNote.setNote(RandomStringUtils.randomAscii(targetLen));

        TestObserver observer = new TestObserver();
        repo.registerDataSetObserver(observer);

        try {
            NoteItem returnNote = repo.insertNote(expectedNote);
            assertNotNull("No note returned from insert", returnNote);
            assertEquals("ID of inserted note", Long.valueOf(expectedId),
                    returnNote.getId());
            observer.assertChanged("Observer not called after insert");

            try {
                returnNote = repo.getNoteById(expectedId);
                assertNotNull("Failed to read back note " + expectedId,
                        returnNote);
                assertEquals("Note read back from the repository after insert",
                        expectedNote, returnNote);
            } finally {
                observer.reset();
                assertTrue("Repository did not indicate the note was deleted",
                        repo.deleteNote(expectedId));
                assertNull("New note was not deleted",
                        repo.getNoteById(expectedId));
                observer.assertChanged("Observer not called after delete");
            }
        } finally {
            repo.unregisterDataSetObserver(observer);
        }
    }

    /**
     * Test inserting, reading, updating, and deleting an encrypted note.
     */
    @Test
    public void testEncryptedNoteCRUD() {
        NoteItem expectedNote = new NoteItem();
        expectedNote.setCategoryId(NoteCategory.UNFILED);
        expectedNote.setPrivate(2);
        expectedNote.setCreateTimeNow();
        expectedNote.setModTime(expectedNote.getCreateTime());
        byte[] encryption = new byte[64 + 32 * RAND.nextInt(8)];
        RAND.nextBytes(encryption);
        expectedNote.setEncryptedNote(encryption);

        NoteItem returnNote = repo.insertNote(expectedNote);
        assertNotNull("No note returned from insert", returnNote);
        assertNotNull("No ID returned with inserted note",
                returnNote.getId());
        long noteId = returnNote.getId();

        try {
            returnNote = repo.getNoteById(noteId);
            assertNotNull("Failed to read back note " + noteId, returnNote);
            // assertEquals may not work with byte array fields,
            // so we'll compare that separately first.
            assertArrayEquals("Encrypted content read back after insert",
                    encryption, returnNote.getEncryptedNote());
            expectedNote.setId(noteId);
            expectedNote.setCategoryName(testContext
                    .getString(R.string.Category_Unfiled));
            expectedNote.setEncryptedNote(returnNote.getEncryptedNote());
            assertEquals("Note read back from the repository after insert",
                    expectedNote, returnNote);

            RAND.nextBytes(encryption);
            expectedNote.setEncryptedNote(encryption);
            expectedNote.setModTimeNow();
            returnNote = repo.updateNote(expectedNote);
            assertNotNull("No note returned from update", returnNote);
            returnNote = repo.getNoteById(noteId);
            assertNotNull("Failed to read back note " + noteId, returnNote);
            assertArrayEquals("Encrypted content read back after update",
                    encryption, returnNote.getEncryptedNote());
            expectedNote.setEncryptedNote(returnNote.getEncryptedNote());
            assertEquals("Note read back from the repository after update",
                    expectedNote, returnNote);
        } finally {
            assertTrue("Repository did not indicate the note was deleted",
                    repo.deleteNote(noteId));
            assertNull("New note was not deleted", repo.getNoteById(noteId));
        }
    }

    /**
     * Test that the repository refuses to insert a note with no
     * {@code note}.  We expect it to throw an
     * {@link IllegalArgumentException}.
     */
    @Test
    public void testInsertEmptyNote() {
        NoteItem emptyNote = new NoteItem();
        emptyNote.setCategoryId(NoteCategory.UNFILED);
        emptyNote.setPrivate(0);
        emptyNote.setCreateTimeNow();
        emptyNote.setModTime(emptyNote.getCreateTime());
        // Setting encryptedNote should have no bearing on a public note
        emptyNote.setEncryptedNote(new byte[] { 1, 2, 3, 4 });

        try {
            NoteItem returnNote = repo.insertNote(emptyNote);
            if ((returnNote != null) && (returnNote.getId() != null)) {
                repo.deleteNote(returnNote.getId());
                fail("Empty note was inserted");
            } else {
                fail("Repository did not throw an exception for an empty"
                        + " note, but didn't return a note ID either");
            }
        } catch (IllegalArgumentException e) {
            // Success
        }
    }

    /**
     * Test that the repository refuses to insert an
     * &ldquo;encrypted&rdquo; note with no {@code encryptedNote}.
     * We expect it to throw an {@link IllegalArgumentException}.
     */
    @Test
    public void testInsertEmptyEncryptedNote() {
        NoteItem emptyNote = new NoteItem();
        emptyNote.setCategoryId(NoteCategory.UNFILED);
        emptyNote.setPrivate(2);
        emptyNote.setCreateTimeNow();
        emptyNote.setModTime(emptyNote.getCreateTime());
        // Setting 'note' should have no bearing on an encrypted note
        emptyNote.setNote("Abcdefg");

        try {
            NoteItem returnNote = repo.insertNote(emptyNote);
            if ((returnNote != null) && (returnNote.getId() != null)) {
                repo.deleteNote(returnNote.getId());
                fail("Empty note was inserted");
            } else {
                fail("Repository did not throw an exception for an empty"
                        + " note, but didn't return a note ID either");
            }
        } catch (IllegalArgumentException e) {
            // Success
        }
    }

    /**
     * Test that when a note&rsquo;s category is deleted,
     * the note is reassigned to the &ldquo;Unfiled&rdquo; category.
     */
    @Test
    public void testDeleteNoteCategory() {
        int targetLen = RAND.nextInt(20) + 12;
        String testCategoryName = RandomStringUtils.randomAlphabetic(targetLen);
        NoteCategory testCategory = repo.insertCategory(testCategoryName);
        assertNotNull("Repository did not return the new category",
                testCategory);
        assertNotNull("Repository did not return the category ID",
                testCategory.getId());
        NoteItem testNote = new NoteItem();
        testNote.setCategoryId(testCategory.getId());
        testNote.setPrivate(0);
        testNote.setCreateTimeNow();
        testNote.setModTime(testNote.getCreateTime());
        targetLen = RAND.nextInt(30) + 12;
        testNote.setNote(RandomStringUtils.randomAscii(targetLen));
        NoteItem actualNote = null;
        try {
            actualNote = repo.insertNote(testNote);
            assertNotNull("Repository did not return the inserted note",
                    actualNote);
            assertNotNull("Repository did not return the note ID",
                    actualNote.getId());
            long noteId = actualNote.getId();
            actualNote = repo.getNoteById(noteId);
            assertNotNull(String.format("Newly created note %d was lost",
                    noteId), actualNote);
            assertEquals("Original note category returned by the repository",
                    testCategoryName, actualNote.getCategoryName());

            repo.deleteCategory(testCategory.getId());
            actualNote = repo.getNoteById(noteId);
            assertNotNull(String.format("Newly created note %d was lost",
                    noteId), actualNote);
            assertEquals("Note category ID after deleting"
                    + " its original category",
                    Long.valueOf(NoteCategory.UNFILED),
                    actualNote.getCategoryId());
            assertEquals("Note category name after deleting"
                    + " its original category",
                    testContext.getString(R.string.Category_Unfiled),
                    actualNote.getCategoryName());
        } finally {
            if ((actualNote != null) && (actualNote.getId() != null))
                repo.deleteNote(actualNote.getId());
            // Just in case we failed before the middle of the test
            repo.deleteCategory(testCategory.getId());
        }
    }

    /**
     * Test counting all notes, private notes, encrypted notes, and
     * notes in a category.  This requires inserting multiple categories
     * and notes.  While we&rsquo;re at it, also test returning the
     * ID&rsquo;s of all private notes.
     */
    @Test
    public void testCountNotes() {
        long[] testCategoryIds = new long[2 + RAND.nextInt(4)];
        Arrays.fill(testCategoryIds, NoteCategory.UNFILED);
        int[] expectedCounts = new int[testCategoryIds.length];
        Arrays.fill(expectedCounts, 0);
        long[] testNoteIds = new long[2 * testCategoryIds.length
                + RAND.nextInt(16)];
        SortedSet<Long> expectedPrivateNoteIds = new TreeSet<>();

        // Count how many notes were in the database when we
        // started this test.  We don't rely on a clean database.
        int baseFullCount = repo.countNotes();
        int basePrivateCount = repo.countPrivateNotes();
        int baseEncryptedCount = repo.countEncryptedNotes();
        int baseUnfiledCount = repo.countNotesInCategory(NoteCategory.UNFILED);
        int expectedPrivateCount = basePrivateCount;
        int expectedEncryptedCount = baseEncryptedCount;

        long[] basePrivateIds = repo.getPrivateNoteIds();
        for (long id : basePrivateIds)
            expectedPrivateNoteIds.add(id);

        try {
            for (int i = 1; i < testCategoryIds.length; i++) {
                int targetLen = RAND.nextInt(12) + 10;
                String categoryName =
                        RandomStringUtils.randomAlphabetic(targetLen);
                NoteCategory newCategory = repo.insertCategory(categoryName);
                assertNotNull("Repository did not return the"
                        + " newly inserted category", newCategory);
                assertNotNull("New category did not get an ID",
                        newCategory.getId());
                testCategoryIds[i] = newCategory.getId();
            }

            for (int i = 0; i < testNoteIds.length; i++) {
                NoteItem newNote = new NoteItem();
                int catIndex = RAND.nextInt(testCategoryIds.length);
                newNote.setCategoryId(testCategoryIds[catIndex]);
                int targetLen = RAND.nextInt(62) + 20;
                String noteText = RandomStringUtils.randomAscii(targetLen);
                newNote.setPrivate(RAND.nextInt(3));
                if (newNote.isEncrypted()) {
                    newNote.setEncryptedNote(noteText.getBytes());
                    expectedEncryptedCount++;
                } else {
                    newNote.setNote(noteText);
                }
                if (newNote.isPrivate())
                    expectedPrivateCount++;
                newNote.setCreateTimeNow();
                newNote.setModTime(newNote.getCreateTime());
                newNote = repo.insertNote(newNote);
                assertNotNull("Repository did not return the"
                        + " newly inserted note", newNote);
                assertNotNull("New note did not get an ID", newNote.getId());
                testNoteIds[i] = newNote.getId();
                expectedCounts[catIndex]++;
                if (newNote.isPrivate())
                    expectedPrivateNoteIds.add(newNote.getId());
            }

            int actualCount = repo.countNotes();
            int expectedCount = baseFullCount;
            for (int i = 0; i < expectedCounts.length; i++)
                expectedCount += expectedCounts[i];
            assertEquals("Total notes in the database",
                    expectedCount, actualCount);

            actualCount = repo.countPrivateNotes();
            assertEquals("Total private notes in the database",
                    expectedPrivateCount, actualCount);

            actualCount = repo.countEncryptedNotes();
            assertEquals("Total encrypted notes in the database",
                    expectedEncryptedCount, actualCount);

            for (int i = 0; i < expectedCounts.length; i++) {
                expectedCount = ((testCategoryIds[i] == NoteCategory.UNFILED)
                        ? baseUnfiledCount : 0) + expectedCounts[i];
                actualCount = repo.countNotesInCategory(testCategoryIds[i]);
                assertEquals("Number of notes in category "
                        + testCategoryIds[i], expectedCount, actualCount);
            }

            long[] actualPrivateIds = repo.getPrivateNoteIds();
            SortedSet<Long> actualPrivateIdSet = new TreeSet<>();
            for (long id : actualPrivateIds)
                actualPrivateIdSet.add(id);
            assertEquals("Private note ID's",
                    expectedPrivateNoteIds, actualPrivateIdSet);

        } finally {
            for (long id : testNoteIds)
                repo.deleteNote(id);
            for (long id : testCategoryIds) {
                if (id != NoteCategory.UNFILED)
                    repo.deleteCategory(id);
            }
        }
    }

    /**
     * Run the assertion part of a test for getting a cursor over notes
     * in the database.  This relies on the database being pre-populated
     * with the test notes.  As the database may contain other notes not
     * created by the test, we need to allow for these in verifying the
     * results.
     *
     * @param categoryId the ID of the category whose notes to count,
     * or {@link NotePreferences#ALL_CATEGORIES} to include all notes.
     * @param includePrivate whether to include private notes.
     * @param includeEncrypted whether to include encrypted notes.
     * If {@code true}, {@code includePrivate} must also be {@code true}.
     * The provider will <i>not</i> decrypt the notes; decryption must be
     * done by the UI.
     * @param sortOrder the order in which to return matching notes,
     *                  expressed as one or more field names optionally
     *                  followed by &ldquo;desc&rdquo; suitable for use
     *                  as the object of an {@code ORDER BY} clause.
     * @param expectedNotes a LinkedHashMap of the notes which we expect the
     *                      cursor to return, keyed by ID, in the order
     *                      they should be read.  If the cursor returns
     *                      any other notes, they will be allowed so long
     *                      as they match the category and privacy settings.
     *
     * @throws AssertionError if any part of the test failed
     */
    private void runGetNotesTest(long categoryId,
                                 boolean includePrivate,
                                 boolean includeEncrypted,
                                 String sortOrder,
                                 LinkedHashMap<Long, NoteItem> expectedNotes) {
        NoteCursor cursor = repo.getNotes(categoryId,
                includePrivate, includeEncrypted, sortOrder);
        assertNotNull("Repository returned a null cursor", cursor);
        LinkedHashMap<Long, NoteItem> actualNotes = new LinkedHashMap<>();
        List<Long> actualNoteIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                NoteItem note = cursor.getNote();
                assertNotNull("Cursor returned null instead of a note at position "
                        + cursor.getPosition(), note);
                actualNotes.put(note.getId(), note);
            }
        } finally {
            cursor.close();
        }

        // Check for notes which shouldn't be in the results
        for (NoteItem note : actualNotes.values()) {
            if (categoryId != NotePreferences.ALL_CATEGORIES) {
                if (note.getCategoryId() != categoryId)
                    errors.add(String.format(
                            "Note %d: Category (ID) expected:%d but was:%d",
                            note.getId(), categoryId, note.getCategoryId()));
            }
            if (!includeEncrypted) {
                if (!includePrivate) {
                    if (note.getPrivate() > 0)
                        errors.add(String.format(
                                "Note %d: Private expected:0 but was:%d",
                                note.getId(), note.getPrivate()));
                } else {
                    if (note.getPrivate() > 1)
                        errors.add(String.format(
                                "Note %d: Private expected:\u22641 but was:%d",
                                note.getId(), note.getPrivate()));
                }
            }
            if (expectedNotes.containsKey(note.getId()))
                actualNoteIds.add(note.getId());
        }

        try {
            assertEquals(new ArrayList<Long>(expectedNotes.keySet()),
                    actualNoteIds);
        } catch (AssertionError ae) {
            errors.add(String.format("Order of the returned notes (%s)"
                            + " expected:\n%s\n\nbut was:\n%s", sortOrder,
                    StringUtils.join(expectedNotes.values(), "\n"),
                    StringUtils.join(actualNotes.values(), "\n")));
        }

        if (errors.isEmpty())
            return;

        if (errors.size() == 1)
            throw new AssertionError(errors.get(0));
        throw new AssertionError("Multiple failures:\n"
                + StringUtils.join(errors, "\n"));

    }

    /**
     * Run tests for getting notes with a variety of selection parameters.
     * In order to avoid a lot of time-consuming inserts and deletes,
     * we&rsquo;ll populate the database with test notes just once,
     * run all tests for getting notes in this one method, then throw any
     * accumulated errors all at once while cleaning up.
     */
    @Test
    public void testGetNotes() throws UnsupportedEncodingException {
        long[] testCategoryIds = new long[2];
        Arrays.fill(testCategoryIds, NoteCategory.UNFILED);
        String unfiledName = testContext.getString(R.string.Category_Unfiled);
        int targetLen = RAND.nextInt(12) + 10;
        NoteCategory testCategory = repo.insertCategory(
                RandomStringUtils.randomAlphabetic(targetLen));
        assertNotNull("Newly inserted category", testCategory);
        assertNotNull("New category ID", testCategory.getId());
        testCategoryIds[1] = testCategory.getId();
        final List<NoteItem> testNotes = new ArrayList<>();

        try {
            int targetCount = 10 + RAND.nextInt(10);
            while (testNotes.size() < targetCount) {
                NoteItem note = new NoteItem();
                note.setCategoryId(testCategoryIds[
                        RAND.nextInt(testCategoryIds.length)]);
                note.setPrivate(RAND.nextInt(4));
                if (note.getPrivate() <= 1) {
                    targetLen = RAND.nextInt(32) + 10;
                    note.setNote(RandomStringUtils.randomAscii(targetLen));
                } else {
                    byte[] encrypted = new byte[32];
                    RAND.nextBytes(encrypted);
                    note.setEncryptedNote(encrypted);
                }
                note.setCreateTime(System.currentTimeMillis()
                        // Make sure notes have a variety of
                        // "creation" times for sorting purposes
                        - 60000 * RAND.nextInt(7 * 24 * 60));
                note.setModTime(note.getCreateTime());
                note = repo.insertNote(note);
                assertNotNull("Newly inserted note", note);
                assertNotNull("New note ID", note.getId());
                testNotes.add(note);
                // Ensure we have the category name in the note
                note.setCategoryName((note.getCategoryId() == NoteCategory.UNFILED)
                        ? unfiledName : testCategory.getName());
            }

            // First, get all notes ordered by note text (case-sensitive)
            Collections.sort(testNotes, NOTE_CONTENT_COMPARATOR);
            LinkedHashMap<Long, NoteItem> expectedNotes = new LinkedHashMap<>();
            for (NoteItem note : testNotes)
                expectedNotes.put(note.getId(), note);
            runGetNotesTest(NotePreferences.ALL_CATEGORIES, true, true,
                    NoteSchema.NoteItemColumns.NOTE,
                    expectedNotes);

            // Second, get all public notes ordered by note text (case-insensitive)
            Collections.sort(testNotes, NOTE_CONTENT_COMPARATOR_IGNORE_CASE);
            expectedNotes.clear();
            for (NoteItem note : testNotes) {
                if (note.getPrivate() <= 0)
                    expectedNotes.put(note.getId(), note);
            }
            runGetNotesTest(NotePreferences.ALL_CATEGORIES, false, false,
                    "lower(" + NoteSchema.NoteItemColumns.NOTE + ")",
                    expectedNotes);

            // Third, get all unfiled notes including private but not encrypted
            // ordered by most recent modification time first
            Collections.sort(testNotes, new ReverseComparator<NoteItem>(
                    NOTE_MOD_TIME_COMPARATOR));
            expectedNotes.clear();
            for (NoteItem note : testNotes) {
                if ((note.getPrivate() <= 1) &&
                        (note.getCategoryId() == NoteCategory.UNFILED))
                    expectedNotes.put(note.getId(), note);
            }
            runGetNotesTest(NoteCategory.UNFILED, true, false,
                    NoteSchema.NoteItemColumns.MOD_TIME + " desc",
                    expectedNotes);

            // Fourth, get all notes ordered by category name then by
            // creation time (oldest first).
            Collections.sort(testNotes, new ThenComparator<NoteItem>(
                    NOTE_CATEGORY_COMPARATOR_IGNORE_CASE,
                    NOTE_CREATE_TIME_COMPARATOR));
            expectedNotes.clear();
            for (NoteItem note : testNotes)
                expectedNotes.put(note.getId(), note);
            runGetNotesTest(NotePreferences.ALL_CATEGORIES, true, true,
                    "lower(" + NoteSchema.NoteItemColumns.CATEGORY_NAME + "), "
                    + NoteSchema.NoteItemColumns.CREATE_TIME,
                    expectedNotes);

        }
        finally {
            for (NoteItem note : testNotes)
                repo.deleteNote(note.getId());
            repo.deleteCategory(testCategoryIds[1]);
        }
    }

    /**
     * Test running a successful transaction.
     * This does a simple insert and ensures that the data is committed.
     */
    @Test
    public void testRunInTransactionSuccessful() {
        int targetLen = RAND.nextInt(20) + 12;
        final String name = RandomStringUtils.randomAlphabetic(targetLen);
        final byte[] value = new byte[10 + RAND.nextInt(20)];
        repo.runInTransaction(new Runnable() {
            @Override
            public void run() {
                repo.upsertMetadata(name, value);
            }
        });
        NoteMetadata newMetadata = repo.getMetadataByName(name);
        assertNotNull(String.format(
                "Newly inserted metadata \"%s\" not found", name),
                newMetadata);
        repo.deleteMetadata(name);
    }

    /**
     * Test running a failed transaction.
     * This does a simple insert and then throws an exception,
     * and checks that the data is <i>not</i> committed.
     */
    @Test
    public void testRunInTransactionRolledBack() {
        int targetLen = RAND.nextInt(20) + 12;
        final String name = RandomStringUtils.randomAlphabetic(targetLen);
        final byte[] value = new byte[10 + RAND.nextInt(20)];
        SQLException expectedException = new SQLException("Test exception");
        try {
            repo.runInTransaction(new Runnable() {
                @Override
                public void run() {
                    repo.upsertMetadata(name, value);
                    throw expectedException;
                }
            });
            NoteMetadata newMetadata = repo.getMetadataByName(name);
            assertNull(String.format(
                    "Metadata \"%s\" was committed despite an exception in the transaction",
                    name), newMetadata);
        } catch (AssertionError ae) {
            repo.deleteMetadata(name);
            throw ae;
        } catch (SQLException sx) {
            assertEquals("Thrown exception", expectedException, sx);
        }
    }

}
