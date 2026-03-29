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

import static com.xmission.trevin.android.notes.service.XMLExporter.encodeBase64;
import static com.xmission.trevin.android.notes.service.XMLImporter.decodeBase64;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.*;
import static org.junit.Assert.*;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Unit tests for exporting notes to a ZIP file.
 *
 * @author Trevin Beattie
 */
public class ZIPExporterTests {

    private static final Random RAND = new Random();
    private static final RandomStringUtils SRAND = RandomStringUtils.insecure();

    private static MockSharedPreferences underlyingPrefs = null;
    private static NotePreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;
    XPath xpath = XPathFactory.newInstance().newXPath();

    @BeforeClass
    public static void createMocks() {
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
    public void clearRepository() {
        underlyingPrefs.resetMock();
        mockRepo.clear();
    }

    /**
     * Basic test of creating a ZIP file with no records.
     */
    @Test
    public void testExportEmpty() throws Exception {
        File testFile = File.createTempFile("notes-test-empty-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        Instant minTimestamp = Instant.now();
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);
        Instant maxTimestamp = Instant.now();

        assertTrue("ZIP file was not created", testFile.exists());
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no export version",
                    commentContent.has(XMLExporter.ATTR_VERSION));
            assertTrue("Global comment has no database version",
                    commentContent.has(XMLExporter.ATTR_DB_VERSION));
            assertTrue("Global comment has no export timestamp",
                    commentContent.has(XMLExporter.ATTR_EXPORTED));
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment export version", 2,
                    commentContent.getInt(XMLExporter.ATTR_VERSION));
            assertEquals("Global comment database version",
                    NoteRepositoryImpl.DATABASE_VERSION,
                    commentContent.getInt(XMLExporter.ATTR_DB_VERSION));
            assertEquals("Global comment total record count", 0,
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Instant exportTime = Instant.parse(commentContent
                    .getString(XMLExporter.ATTR_EXPORTED));
            Duration timeFuzz = Duration.between(
                    minTimestamp, maxTimestamp).dividedBy(2);
            Instant expectedTime = minTimestamp.plus(timeFuzz);
            assertTrue(String.format(Locale.US,
                    "Global comment export time expected:%s\u00b1%s but was:%s",
                    expectedTime, timeFuzz, exportTime),
                    !exportTime.isBefore(minTimestamp) &&
                            !exportTime.isAfter(maxTimestamp));
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                0, lastProgress.total);
    }

    /**
     * Verify the presence of a preference element
     * in {@link ZIPExporter#PREFS_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the preference
     *
     * @return the preference {@link Node}
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file.
     */
    private Node assertPreferenceExists(Document doc, String name)
            throws XPathExpressionException {
        Node preference = (Node) xpath.evaluate(String.format(Locale.US,
                        "/%s/%s", XMLExporter.PREFERENCES_TAG,
                name), doc, XPathConstants.NODE);
        assertNotNull(String.format(Locale.US, "Missing %s preference",
                name), preference);
        return preference;
    }

    /**
     * Verify the presence and value of an integer preference
     * in {@link ZIPExporter#PREFS_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the preference
     * @param expectedValue the expected value of the preference
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its value is not {@code expectedValue}.
     */
    private void assertLongPreferenceEquals(
            Document doc, String name, long expectedValue)
            throws AssertionError, XPathExpressionException {
        Node node = assertPreferenceExists(doc, name);
        try {
            assertEquals(name, expectedValue,
                    Long.parseLong(node.getTextContent()));
        } catch (NumberFormatException x) {
            fail(String.format(Locale.US, "%s is not an integer", name));
        }
    }

    /**
     * Verify the presence and value of an floating-point preference
     * in {@link ZIPExporter#PREFS_FILE}.  This will allow for
     * variances after 7 digits of precision, since {@code float}
     * values only have 24 bits
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the preference
     * @param expectedValue the expected value of the preference
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its value is not {@code expectedValue}.
     */
    private void assertDoublePreferenceEquals(
            Document doc, String name, double expectedValue)
            throws AssertionError, XPathExpressionException {
        Node node = assertPreferenceExists(doc, name);
        try {
            double fudge = (expectedValue == 0.0) ? 0
                    : Math.pow(10.0, Math.ceil(Math.log10(
                            Math.abs(expectedValue))) - 7);
            assertEquals(name, expectedValue,
                    Double.parseDouble(node.getTextContent()), fudge);
        } catch (NumberFormatException x) {
            fail(String.format(Locale.US, "%s is not an integer", name));
        }
    }

    /**
     * Verify the presence and value of a boolean preference
     * in {@link ZIPExporter#PREFS_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the preference
     * @param expectedValue the expected value of the preference
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its value is not {@code expectedValue}.
     */
    private void assertBooleanPreferenceEquals(
            Document doc, String name, boolean expectedValue)
            throws AssertionError, XPathExpressionException {
        Node node = assertPreferenceExists(doc, name);
        assertEquals(name, expectedValue,
                Boolean.parseBoolean(node.getTextContent()));
    }

    /**
     * Verify the presence and value of a string preference
     * in {@link ZIPExporter#PREFS_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the preference
     * @param expectedValue the expected value of the preference
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its value is not {@code expectedValue}.
     */
    private void assertStringPreferenceEquals(
            Document doc, String name, String expectedValue)
            throws AssertionError, XPathExpressionException {
        Node node = assertPreferenceExists(doc, name);
        assertEquals(name, expectedValue, node.getTextContent());
    }

    /**
     * Test exporting the preferences only
     */
    @Test
    public void testExportPreferences() throws Exception {
        mockPrefs.setSelectedCategory(RAND.nextLong(1000) + 1);
        mockPrefs.setShowPrivate(RAND.nextBoolean());
        mockPrefs.setShowEncrypted(RAND.nextBoolean());
        mockPrefs.setShowCategory(RAND.nextBoolean());
        mockPrefs.setScrollBarThreshold((float) Math.exp(RAND.nextGaussian()));
        mockPrefs.setExportFile(String.format("file:///%s/%s/%s.zip",
                randomWord(), randomWord(), randomWord()));
        mockPrefs.setExportPrivate(RAND.nextBoolean());
        mockPrefs.setImportFile(String.format("file:///%s/%s/%s.zip",
                randomWord(), randomWord(), randomWord()));
        mockPrefs.setImportType(NotePreferences.ImportType.values()[RAND
                .nextInt(NotePreferences.ImportType.values().length)]);
        mockPrefs.setImportPrivate(RAND.nextBoolean());

        File testFile = File.createTempFile("notes-test-prefs-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Document doc;
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count", 2,
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            ZipEntry dirEntry = zipIn.getEntry(ZIPExporter.METADATA_DIR);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.METADATA_DIR,
                    testFile.getAbsolutePath()), dirEntry);
            assertTrue(String.format(Locale.US,
                    "%s is not a directory", ZIPExporter.METADATA_DIR),
                    dirEntry.isDirectory());
            ZipEntry prefsEntry = zipIn.getEntry(ZIPExporter.PREFS_FILE);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.PREFS_FILE,
                    testFile.getAbsolutePath()), prefsEntry);
            doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(
                            zipIn.getInputStream(prefsEntry));
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                2, lastProgress.total);

        // Check the preferences that were written
        assertLongPreferenceEquals(doc,
                NotePreferences.NPREF_SELECTED_CATEGORY,
                mockPrefs.getSelectedCategory());
        assertBooleanPreferenceEquals(doc,
                NotePreferences.NPREF_SHOW_PRIVATE,
                mockPrefs.showPrivate());
        assertBooleanPreferenceEquals(doc,
                NotePreferences.NPREF_SHOW_ENCRYPTED,
                mockPrefs.showEncrypted());
        assertBooleanPreferenceEquals(doc,
                NotePreferences.NPREF_SHOW_CATEGORY,
                mockPrefs.showCategory());
        assertDoublePreferenceEquals(doc,
                NotePreferences.NPREF_SCROLL_THRESHOLD,
                mockPrefs.getScrollBarThreshold());
        assertStringPreferenceEquals(doc,
                NotePreferences.NPREF_EXPORT_FILE,
                mockPrefs.getExportFile(""));
        assertBooleanPreferenceEquals(doc,
                NotePreferences.NPREF_EXPORT_PRIVATE,
                mockPrefs.exportPrivate());
        assertStringPreferenceEquals(doc,
                NotePreferences.NPREF_IMPORT_FILE,
                mockPrefs.getImportFile(""));
        // The import type is exported by its ordinal number
        assertLongPreferenceEquals(doc,
                NotePreferences.NPREF_IMPORT_TYPE,
                mockPrefs.getImportType().ordinal());
        assertBooleanPreferenceEquals(doc,
                NotePreferences.NPREF_IMPORT_PRIVATE,
                mockPrefs.importPrivate());
    }

    /**
     * Verify the presence of a metadata element
     * in {@link ZIPExporter#METADATA_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the metadata
     *
     * @return the metadata {@link Node}
     *
     * @throws AssertionError if the element does not exist in the
     * metadata file.
     */
    private Node assertMetadataExists(Document doc, String name)
        throws XPathExpressionException {
        Node metadata = (Node) xpath.evaluate(String.format(Locale.US,
                        "/%s/%s[@%s='%s']", XMLExporter.METADATA_TAG,
                XMLExporter.METADATA_ITEM, XMLExporter.ATTR_NAME,
                name), doc, XPathConstants.NODE);
        assertNotNull(String.format(Locale.US,
                "Missing metadata item named \"%s\"", name), metadata);
        return metadata;
    }

    /**
     * Verify the absence of a metadata element
     * in {@link ZIPExporter#METADATA_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the metadata
     *
     * @throws AssertionError if the element exists in the metadata file.
     */
    private void assertMetadataDoesNotExist(Document doc, String name)
        throws XPathExpressionException {
        Node metadata = (Node) xpath.evaluate(String.format(Locale.US,
                        "/%s/%s[@%s='%s']", XMLExporter.METADATA_TAG,
                XMLExporter.METADATA_ITEM, XMLExporter.ATTR_NAME,
                name), doc, XPathConstants.NODE);
        assertNull(String.format(Locale.US,
                "Metadata item named \"%s\" was found", name), metadata);
    }

    /**
     * Verify the presence and base64-encoded value of metadata
     * in {@link ZIPExporter#METADATA_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the metadata
     * @param expectedRaw the raw byte array of the expected metadata value
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its value is not {@code encodeBase64(expectedValue)}.
     */
    private void assertRawMetadataEquals(
            Document doc, String name, byte[] expectedRaw)
            throws AssertionError, XPathExpressionException {
        Node node = assertMetadataExists(doc, name);
        String expected64 = encodeBase64(expectedRaw);
        assertEquals(name, expected64, node.getTextContent());
    }

    /**
     * Verify the presence and base64-decoded value of metadata
     * in {@link ZIPExporter#METADATA_FILE}.
     *
     * @param doc the preferences {@link Document} to read
     * @param name the name of the metadata
     * @param expectedText the decoded text of the expected metadata value
     *
     * @throws AssertionError if the element does not exist in the
     * preferences file or its {@code decodeBase64(value)} is not
     * {@code expectedValue}.
     */
    private void assertStringMetadataEquals(
            Document doc, String name, String expectedText)
            throws AssertionError, XPathExpressionException {
        Node node = assertMetadataExists(doc, name);
        String actualText = new String(decodeBase64(node.getTextContent()));
        assertEquals(name, expectedText, actualText);
    }

    /**
     * Test exporting metadata only (no encryption)
     */
    @Test
    public void testExportPublicMetadata() throws Exception {
        // Store a password we don't expect to be exported
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        Map<String,String> extraMeta = new TreeMap<>();
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i) {
            String name = randomWord();
            String value = randomSentence();
            mockRepo.upsertMetadata(name,
                    value.getBytes(StandardCharsets.UTF_8));
            extraMeta.put(name, value);
        }

        File testFile = File.createTempFile(
                "notes-test-public-meta-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Document doc;
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count", 2,
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            ZipEntry dirEntry = zipIn.getEntry(ZIPExporter.METADATA_DIR);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.METADATA_DIR,
                    testFile.getAbsolutePath()), dirEntry);
            assertTrue(String.format(Locale.US,
                    "%s is not a directory", ZIPExporter.METADATA_DIR),
                    dirEntry.isDirectory());
            ZipEntry metaEntry = zipIn.getEntry(ZIPExporter.METADATA_FILE);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.METADATA_FILE,
                    testFile.getAbsolutePath()), metaEntry);
            doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(
                            zipIn.getInputStream(metaEntry));
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                2, lastProgress.total);

        assertMetadataDoesNotExist(doc, StringEncryption.METADATA_PASSWORD_HASH);
        for (String name : extraMeta.keySet())
            assertStringMetadataEquals(doc, name, extraMeta.get(name));
    }

    /**
     * Test exporting metadata including the password hash
     */
    @Test
    public void testExportMetadataBundledEncryption() throws Exception {
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);
        NoteMetadata expectedMeta = mockRepo.getMetadataByName(
                StringEncryption.METADATA_PASSWORD_HASH);

        File testFile = File.createTempFile(
                "notes-test-encrypt2-meta-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                se, ZIPExporter.EncryptionType.BUNDLED_ENCRYPTION,
                se.getPassword());

        assertTrue("ZIP file was not created", testFile.exists());
        Document doc;
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count", 2,
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            ZipEntry dirEntry = zipIn.getEntry(ZIPExporter.METADATA_DIR);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.METADATA_DIR,
                    testFile.getAbsolutePath()), dirEntry);
            assertTrue(String.format(Locale.US,
                    "%s is not a directory", ZIPExporter.METADATA_DIR),
                    dirEntry.isDirectory());
            ZipEntry metaEntry = zipIn.getEntry(ZIPExporter.METADATA_FILE);
            assertNotNull(String.format(Locale.US,
                    "%s not found in %s", ZIPExporter.METADATA_FILE,
                    testFile.getAbsolutePath()), metaEntry);
            doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(
                            zipIn.getInputStream(metaEntry));
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                2, lastProgress.total);

        assertRawMetadataEquals(doc, StringEncryption.METADATA_PASSWORD_HASH,
                expectedMeta.getValue());
    }

    /**
     * Test exporting metadata excludes password hash
     * when using ZIP's AES encryption.
     */
    @Test
    public void testExportMetadataAESEncryption() throws Exception {
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        File testFile = File.createTempFile(
                "notes-test-encrypt99-meta-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                se, ZIPExporter.EncryptionType.AES_128,
                se.getPassword());

        assertTrue("ZIP file was not created", testFile.exists());
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            ZipEntry dirEntry = zipIn.getEntry(ZIPExporter.METADATA_DIR);
            // Since we are excluding the only metadata in this test and
            // there are no preferences set, the metadata directory
            // should not exist.  But if it does, it should be empty.
            if (dirEntry != null) {
                ZipEntry metaEntry = zipIn.getEntry(ZIPExporter.METADATA_FILE);
                assertNull(String.format(Locale.US,
                        "%s was found in %s", ZIPExporter.METADATA_FILE,
                        testFile.getAbsolutePath()), metaEntry);
            }
        }
    }

    /**
     * Test exporting categories (without notes)
     */
    @Test
    public void testExportCategories() throws Exception {
        Map<Long,String> testCategories = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 5; i >= 0; --i) {
            NoteCategory category = mockRepo.insertCategory(
                    // Random sentences only contain letters and spaces
                    // so these should also be valid directory names.
                    randomSentence());
            testCategories.put(category.getId(), category.getName());
        }

        File testFile = File.createTempFile("notes-test-categories-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testCategories.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            for (long id : testCategories.keySet()) {
                String name = testCategories.get(id);
                String expectedDirName = name
                        .replaceAll("\\.*$", "") + File.separator;
                ZipEntry catDirectory = zipIn.getEntry(expectedDirName);
                assertNotNull(String.format(Locale.US,
                        "%s not found in %s", expectedDirName,
                        testFile.getAbsolutePath()), catDirectory);
                assertTrue(String.format(Locale.US,
                        "%s is not a directory", expectedDirName),
                        catDirectory.isDirectory());
                commentContent = new JSONObject(catDirectory.getComment());
                assertNotNull(String.format(Locale.US,
                        "No comment found in category \"%s\" directory",
                        name), commentContent);
                assertTrue(String.format(Locale.US,
                        "Category \"%s\" directory comment has no ID", name),
                        commentContent.has(XMLExporter.ATTR_ID));
                assertEquals(String.format(Locale.US,
                        "Category \"%s\" ID in directory comment", name),
                        id, commentContent.getLong(XMLExporter.ATTR_ID));
                if (commentContent.has(XMLExporter.ATTR_NAME))
                    assertEquals(String.format(Locale.US,
                                    "Category \"%s\" name in directory comment",
                                    name), name,
                            commentContent.getString(XMLExporter.ATTR_NAME));
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testCategories.size(), lastProgress.total);
    }

    /**
     * Test exporting public notes.  All notes in this test case are
     * Unfiled.
     */
    @Test
    public void testExportPublicNotesUnfiled() throws Exception {
        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.NO_ENCRYPTION : 0);
            note = mockRepo.insertNote(note);
            if (!note.isPrivate())
                testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-public-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;
                String noteComment = entry.getComment();
                assertNotNull("No note comment found for " + entry.getName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        entry.getName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                note.setNote(new String(zipIn.getInputStream(entry)
                        .readAllBytes(), StandardCharsets.UTF_8));
                actualNotes.put(note.getId(), note);
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size(), lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size(), lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting notes in a variety of categories.
     */
    @Test
    public void testExportNotesAllCategories() throws Exception {
        List<NoteCategory> testCategories = new ArrayList<>();
        testCategories.add(mockRepo.getCategoryById(NoteCategory.UNFILED));
        String unfiledName = testCategories.get(0).getName();
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i)
            testCategories.add(mockRepo.insertCategory(randomSentence()));

        Map<Long,NoteItem> testNotes = new TreeMap<>();
        int numNotes = RAND.nextInt(testCategories.size())
                + 3 * testCategories.size();
        for (int i = 0; i < numNotes; i++) {
            NoteItem note = randomNote();
            NoteCategory cat = testCategories.get(RAND.nextInt(
                    testCategories.size()));
            note.setCategoryId(cat.getId());
            note.setCategoryName(cat.getName());
            note = mockRepo.insertNote(note);
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile(
                "notes-test-all-categories-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<String,Long> directoryIds = new HashMap<>();
        Map<Long,String> categoryNames = new TreeMap<>();
        SortedMap<Long,NoteItem> actualNotes = new TreeMap<>();
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    // Exclude the Unfiled category from the count
                    testCategories.size() - 1 + testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    // Process category
                    String dirComment = entry.getComment();
                    long categoryId = -1;
                    if (dirComment != null) {
                        commentContent = new JSONObject(dirComment);
                        if (commentContent.has(XMLExporter.ATTR_ID))
                            categoryId = commentContent.getLong(
                                    XMLExporter.ATTR_ID);
                        if (commentContent.has(XMLExporter.ATTR_NAME))
                            categoryNames.put(categoryId,
                                    commentContent.getString(XMLExporter.ATTR_NAME));
                        else
                            categoryNames.put(categoryId, entry.getName()
                                    .replaceAll(File.separator + "$", ""));
                    }
                    directoryIds.put(entry.getName(), categoryId);
                }
                else {
                    // Process note
                    // To Do: Move all of this processing to a common method
                    String noteComment = entry.getComment();
                    long noteId = -1;
                    Instant createTime = null;
                    Instant modTime = null;
                    long categoryId = -1;
                    String categoryName = null;
                    if (noteComment != null) {
                        commentContent = new JSONObject(noteComment);
                        if (commentContent.has(XMLExporter.ATTR_ID))
                            noteId = commentContent.getLong(
                                    XMLExporter.ATTR_ID);
                        if (commentContent.has(XMLExporter.NOTE_CREATED))
                            createTime = Instant.parse(commentContent
                                    .getString(XMLExporter.NOTE_CREATED));
                        if (commentContent.has(XMLExporter.NOTE_MODIFIED))
                            modTime = Instant.parse(commentContent
                                    .getString(XMLExporter.NOTE_MODIFIED));
                    }
                    Pattern namePattern = Pattern.compile("^([^/]+/)?(\\d+)?.*");
                    Matcher m = namePattern.matcher(entry.getName());
                    if (m.matches()) {
                        if ((noteId < 0) && (m.group(2) != null)) {
                            // Fall back to getting the note ID from the entry name
                            noteId = Long.parseLong(m.group(2));
                        }
                        if (m.group(1) != null) {
                            String categoryDir = m.group(1);
                            // Tentative category name
                            categoryName = categoryDir.replaceFirst(
                                    File.separator + "$", "");
                            if (directoryIds.containsKey(categoryDir)) {
                                categoryId = directoryIds.get(categoryDir);
                                if (categoryNames.containsKey(categoryId))
                                    categoryName = categoryNames.get(categoryId);
                            }
                        } else {
                            categoryName = unfiledName;
                            categoryId = NoteCategory.UNFILED;
                        }
                    } else {
                        categoryName = unfiledName;
                        categoryId = NoteCategory.UNFILED;
                    }
                    if (createTime == null) {
                        FileTime ft = entry.getCreationTime();
                        if (ft != null)
                            createTime = ft.toInstant();
                    }
                    if (modTime == null) {
                        FileTime ft = entry.getLastModifiedTime();
                        if (ft != null)
                            modTime = ft.toInstant();
                    }
                    NoteItem actualNote = new NoteItem();
                    actualNote.setId(noteId);
                    actualNote.setCategoryId(categoryId);
                    actualNote.setCategoryName(categoryName);
                    actualNote.setCreateTime((createTime == null)
                            ? Instant.EPOCH : createTime);
                    actualNote.setModTime((modTime == null)
                            ? Instant.EPOCH : modTime);
                    actualNote.setNote(new String(zipIn.getInputStream(entry)
                            .readAllBytes(), StandardCharsets.UTF_8));
                    if (actualNotes.containsKey(noteId))
                        // Move this to an unused spot
                        noteId = actualNotes.firstKey() - 1;
                    actualNotes.put(noteId, actualNote);
                }
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testCategories.size() - 1 + testNotes.size(),
                lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testCategories.size() - 1 + testNotes.size(),
                lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting notes from a single category.
     */
    @Test
    public void testExportNotesOneCategory() throws Exception {
        List<NoteCategory> testCategories = new ArrayList<>();
        testCategories.add(mockRepo.getCategoryById(NoteCategory.UNFILED));
        String unfiledName = testCategories.get(0).getName();
        for (int i = 0; i < 2; i++)
            testCategories.add(mockRepo.insertCategory(randomSentence()));

        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        NoteCategory targetCategory = testCategories.get(RAND.nextInt(
                testCategories.size() - 1) + 1);
        for (int i = RAND.nextInt(5) + 5; i >= 0; --i) {
            NoteItem note = randomNote();
            NoteCategory cat = testCategories.get(RAND.nextInt(
                    testCategories.size() - 1) + 1);
            note.setCategoryId(cat.getId());
            note.setCategoryName(cat.getName());
            note = mockRepo.insertNote(note);
            if (note.getCategoryId() == targetCategory.getId())
                expectedNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile(
                "notes-test-one-category-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, targetCategory.getId(),
                null, null, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<String,Long> directoryIds = new HashMap<>();
        Map<Long,String> categoryNames = new TreeMap<>();
        SortedMap<Long,NoteItem> actualNotes = new TreeMap<>();
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    1 + expectedNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    // Process category
                    String dirComment = entry.getComment();
                    long categoryId = -1;
                    if (dirComment != null) {
                        commentContent = new JSONObject(dirComment);
                        if (commentContent.has(XMLExporter.ATTR_ID))
                            categoryId = commentContent.getLong(
                                    XMLExporter.ATTR_ID);
                        if (commentContent.has(XMLExporter.ATTR_NAME))
                            categoryNames.put(categoryId,
                                    commentContent.getString(XMLExporter.ATTR_NAME));
                        else
                            categoryNames.put(categoryId, entry.getName()
                                    .replaceAll(File.separator + "$", ""));
                    }
                    directoryIds.put(entry.getName(), categoryId);
                }
                else {
                    // Process note
                    // To Do: Move all of this processing to a common method
                    String noteComment = entry.getComment();
                    long noteId = -1;
                    Instant createTime = null;
                    Instant modTime = null;
                    long categoryId = -1;
                    String categoryName = null;
                    if (noteComment != null) {
                        commentContent = new JSONObject(noteComment);
                        if (commentContent.has(XMLExporter.ATTR_ID))
                            noteId = commentContent.getLong(
                                    XMLExporter.ATTR_ID);
                        if (commentContent.has(XMLExporter.NOTE_CREATED))
                            createTime = Instant.parse(commentContent
                                    .getString(XMLExporter.NOTE_CREATED));
                        if (commentContent.has(XMLExporter.NOTE_MODIFIED))
                            modTime = Instant.parse(commentContent
                                    .getString(XMLExporter.NOTE_MODIFIED));
                    }
                    Pattern namePattern = Pattern.compile("^([^/]+/)?(\\d+)?.*");
                    Matcher m = namePattern.matcher(entry.getName());
                    if (m.matches()) {
                        if ((noteId < 0) && (m.group(2) != null)) {
                            // Fall back to getting the note ID from the entry name
                            noteId = Long.parseLong(m.group(2));
                        }
                        if (m.group(1) != null) {
                            String categoryDir = m.group(1);
                            // Tentative category name
                            categoryName = categoryDir.replaceFirst(
                                    File.separator + "$", "");
                            if (directoryIds.containsKey(categoryDir)) {
                                categoryId = directoryIds.get(categoryDir);
                                if (categoryNames.containsKey(categoryId))
                                    categoryName = categoryNames.get(categoryId);
                            }
                        } else {
                            categoryName = unfiledName;
                            categoryId = NoteCategory.UNFILED;
                        }
                    } else {
                        categoryName = unfiledName;
                        categoryId = NoteCategory.UNFILED;
                    }
                    if (createTime == null) {
                        FileTime ft = entry.getCreationTime();
                        if (ft != null)
                            createTime = ft.toInstant();
                    }
                    if (modTime == null) {
                        FileTime ft = entry.getLastModifiedTime();
                        if (ft != null)
                            modTime = ft.toInstant();
                    }
                    NoteItem actualNote = new NoteItem();
                    actualNote.setId(noteId);
                    actualNote.setCategoryId(categoryId);
                    actualNote.setCategoryName(categoryName);
                    actualNote.setCreateTime((createTime == null)
                            ? Instant.EPOCH : createTime);
                    actualNote.setModTime((modTime == null)
                            ? Instant.EPOCH : modTime);
                    actualNote.setNote(new String(zipIn.getInputStream(entry)
                            .readAllBytes(), StandardCharsets.UTF_8));
                    if (actualNotes.containsKey(noteId))
                        // Move this to an unused spot
                        noteId = actualNotes.firstKey() - 1;
                    actualNotes.put(noteId, actualNote);
                }
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testCategories.size() - 1 + expectedNotes.size(),
                lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testCategories.size() - 1 + expectedNotes.size(),
                lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                expectedNotes, actualNotes);
    }

    /**
     * Test exporting private notes with no encryption (either side).
     */
    @Test
    public void testExportPrivateNotesNoEncryption() throws Exception {
        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.NO_ENCRYPTION : 0);
            note = mockRepo.insertNote(note);
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-private-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, ZIPExporter.EncryptionType.NO_ENCRYPTION, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;
                String noteComment = entry.getComment();
                assertNotNull("No note comment found for " + entry.getName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        entry.getName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                if (commentContent.has(XMLExporter.ATTR_PRIVATE))
                    note.setPrivate(commentContent.getBoolean(
                            XMLExporter.ATTR_PRIVATE)
                            ? StringEncryption.NO_ENCRYPTION : 0);
                note.setNote(new String(zipIn.getInputStream(entry)
                        .readAllBytes(), StandardCharsets.UTF_8));
                actualNotes.put(note.getId(), note);
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size(), lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size(), lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting private notes encrypted locally with no ZIP encryption.
     */
    @Test
    public void testExportDecryptedNotes() throws Exception {
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            // Keep the plain text for reference; this won't be store in the repo
            String plainText = note.getNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.encryptionType() : 0);
            if (note.isEncrypted()) {
                note.setEncryptedNote(se.encrypt(plainText));
                note.setNote(null);
            }
            note = mockRepo.insertNote(note);
            if (note.isEncrypted()) {
                // Use the plain text for comparison;
                // the repo stored a clone, so this won't affect it.
                note.setNote(plainText);
                note.setPrivate(StringEncryption.NO_ENCRYPTION);
                note.setEncryptedNote(null);
            }
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-decrypted-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                se, ZIPExporter.EncryptionType.NO_ENCRYPTION, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;
                String noteComment = entry.getComment();
                assertNotNull("No note comment found for " + entry.getName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        entry.getName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                if (commentContent.has(XMLExporter.ATTR_PRIVATE) &&
                        commentContent.getBoolean(XMLExporter.ATTR_PRIVATE)) {
                    if (commentContent.has(XMLExporter.ATTR_ENCRYPTION)) {
                        int encType = commentContent.getInt(
                                XMLExporter.ATTR_ENCRYPTION);
                        note.setPrivate(encType);
                    } else {
                        note.setPrivate(StringEncryption.NO_ENCRYPTION);
                    }
                }
                byte[] rawContent = zipIn.getInputStream(entry).readAllBytes();
                if (note.isEncrypted()) {
                    note.setEncryptedNote(rawContent);
                } else {
                    note.setNote(new String(rawContent, StandardCharsets.UTF_8));
                }
                actualNotes.put(note.getId(), note);
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size(), lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size(), lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting private notes encrypted locally stored as-is
     * (no decryption).
     */
    @Test
    public void testExportPrivateNotesBundledEncryption() throws Exception {
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.encryptionType() : 0);
            if (note.isEncrypted()) {
                note.setEncryptedNote(se.encrypt(note.getNote()));
                note.setNote(null);
            }
            note = mockRepo.insertNote(note);
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-encrypted-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, ZIPExporter.EncryptionType.BUNDLED_ENCRYPTION, null);

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        boolean metadataDirSeen = false;
        Document doc = null;
        try (ZipFile zipIn = new ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    // Include the notes plus two entries for
                    // the metadata directory and metadata file
                    testNotes.size() + 2,
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    metadataDirSeen = entry.getName().equals(
                            ZIPExporter.METADATA_DIR);
                    continue;
                }
                if (entry.getName().equals(ZIPExporter.METADATA_FILE)) {
                    doc = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(
                                    zipIn.getInputStream(entry));
                    continue;
                }
                String noteComment = entry.getComment();
                assertNotNull("No note comment found for " + entry.getName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        entry.getName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", entry.getName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                if (commentContent.has(XMLExporter.ATTR_PRIVATE) &&
                        commentContent.getBoolean(XMLExporter.ATTR_PRIVATE)) {
                    if (commentContent.has(XMLExporter.ATTR_ENCRYPTION)) {
                        int encType = commentContent.getInt(
                                XMLExporter.ATTR_ENCRYPTION);
                        note.setPrivate(encType);
                    } else {
                        note.setPrivate(StringEncryption.NO_ENCRYPTION);
                    }
                }
                byte[] rawContent = zipIn.getInputStream(entry).readAllBytes();
                if (note.isEncrypted()) {
                    note.setEncryptedNote(rawContent);
                } else {
                    note.setNote(new String(rawContent, StandardCharsets.UTF_8));
                }
                actualNotes.put(note.getId(), note);
            }
        }

        assertTrue(String.format(Locale.US, "%s did not contain %s directory",
                        testFile.getAbsolutePath(), ZIPExporter.METADATA_DIR),
                metadataDirSeen);
        assertNotNull(String.format(Locale.US, "%s did not contain %s",
                        testFile.getAbsolutePath(), ZIPExporter.METADATA_FILE),
                doc);
        assertRawMetadataEquals(doc, StringEncryption.METADATA_PASSWORD_HASH,
                mockRepo.getMetadataByName(
                        StringEncryption.METADATA_PASSWORD_HASH).getValue());

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size() + 2, lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size() + 2, lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting private notes <i>not</i> encrypted locally
     * using ZIP's AES encryption.
     */
    @Test
    public void testExportPrivateNotesEncrypt() throws Exception {
        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.NO_ENCRYPTION : 0);
            note = mockRepo.insertNote(note);
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-encrypt-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        final String zipPassword = SRAND.nextAlphanumeric(12);
        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                null, ZIPExporter.EncryptionType.AES_256,
                zipPassword.toCharArray());

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        // We need to use Zip4j to read these back, since
        // java.util.zip can't handle ZIP encryption.
        try (net.lingala.zip4j.ZipFile zipIn =
                     new net.lingala.zip4j.ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            zipIn.setPassword(zipPassword.toCharArray());
            for (net.lingala.zip4j.model.FileHeader file :
                    zipIn.getFileHeaders()) {
                if (file.isDirectory())
                    continue;
                String noteComment = file.getFileComment();
                assertNotNull("No note comment found for " + file.getFileName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        file.getFileName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", file.getFileName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", file.getFileName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                if (commentContent.has(XMLExporter.ATTR_PRIVATE)) {
                    note.setPrivate(commentContent.getBoolean(
                            XMLExporter.ATTR_PRIVATE)
                            ? StringEncryption.NO_ENCRYPTION : 0);
                    // The note *should* have an encryption type of 99 set,
                    // but that won't carry over to the local data.
                    if (file.isEncrypted()) {
                        assertTrue(String.format(Locale.US,
                                "%s comment does not specify the encryption type",
                                file.getFileName()), commentContent.has(
                                XMLExporter.ATTR_ENCRYPTION));
                        assertEquals(String.format(Locale.US,
                                        "Encryption type in %s comment",
                                        file.getFileName()), 99,
                                commentContent.getInt(
                                        XMLExporter.ATTR_ENCRYPTION));
                    }
                }
                note.setNote(new String(zipIn.getInputStream(file)
                        .readAllBytes(), StandardCharsets.UTF_8));
                actualNotes.put(note.getId(), note);
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size(), lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size(), lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

    /**
     * Test exporting private notes encrypted locally
     * using ZIP's AES encryption.
     */
    @Test
    public void testExportReencryptedNotes() throws Exception {
        StringEncryption se = new StringEncryption();
        se.setPassword(SRAND.nextAlphanumeric(12).toCharArray());
        se.addSalt();
        se.storePassword(mockRepo);

        Map<Long,NoteItem> testNotes = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 10; (i >= 0) ||
                testNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.encryptionType() : 0);
            if (note.isEncrypted()) {
                note.setEncryptedNote(se.encrypt(note.getNote()));
                note.setNote(null);
            }
            note = mockRepo.insertNote(note);
            testNotes.put(note.getId(), note);
        }

        File testFile = File.createTempFile("notes-test-reencrypted-", ".zip");
        testFile.deleteOnExit();
        MockProgressBar progress = new MockProgressBar();

        final String zipPassword = SRAND.nextAlphanumeric(12);
        ZIPExporter exporter = new ZIPExporter(mockPrefs, mockRepo, progress);
        exporter.export(testFile, NotePreferences.ALL_CATEGORIES,
                se, ZIPExporter.EncryptionType.AES_256,
                zipPassword.toCharArray());

        assertTrue("ZIP file was not created", testFile.exists());
        Map<Long,NoteItem> actualNotes = new TreeMap<>();
        // We need to use Zip4j to read these back, since
        // java.util.zip can't handle ZIP encryption.
        try (net.lingala.zip4j.ZipFile zipIn =
                     new net.lingala.zip4j.ZipFile(testFile)) {
            String fileComment = zipIn.getComment();
            assertNotNull("No global comment found in the ZIP file",
                    fileComment);
            JSONObject commentContent = new JSONObject(fileComment);
            assertTrue("Global comment has no total record count",
                    commentContent.has(XMLExporter.ATTR_TOTAL_RECORDS));
            assertEquals("Global comment total record count",
                    testNotes.size(),
                    commentContent.getInt(XMLExporter.ATTR_TOTAL_RECORDS));
            zipIn.setPassword(zipPassword.toCharArray());
            for (net.lingala.zip4j.model.FileHeader file :
                    zipIn.getFileHeaders()) {
                if (file.isDirectory())
                    continue;
                String noteComment = file.getFileComment();
                assertNotNull("No note comment found for " + file.getFileName(),
                        noteComment);
                commentContent = new JSONObject(noteComment);
                assertTrue(String.format(Locale.US, "%s comment has no ID",
                        file.getFileName()), commentContent.has(XMLExporter.ATTR_ID));
                assertTrue(String.format(Locale.US,
                        "%s comment has no creation time", file.getFileName()),
                        commentContent.has(XMLExporter.NOTE_CREATED));
                assertTrue(String.format(Locale.US,
                        "%s comment has no modification time", file.getFileName()),
                        commentContent.has(XMLExporter.NOTE_MODIFIED));
                NoteItem note = new NoteItem();
                note.setId(commentContent.getLong(XMLExporter.ATTR_ID));
                note.setCreateTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_CREATED)));
                note.setModTime(Instant.parse(commentContent
                        .getString(XMLExporter.NOTE_MODIFIED)));
                if (commentContent.has(XMLExporter.ATTR_PRIVATE)) {
                    note.setPrivate(commentContent.getBoolean(
                            XMLExporter.ATTR_PRIVATE)
                            ? StringEncryption.encryptionType() : 0);
                    // The note *should* have an encryption type of 99 set,
                    // but that won't carry over to the local data.
                    if (file.isEncrypted()) {
                        assertTrue(String.format(Locale.US,
                                "%s comment does not specify the encryption type",
                                file.getFileName()), commentContent.has(
                                XMLExporter.ATTR_ENCRYPTION));
                        assertEquals(String.format(Locale.US,
                                        "Encryption type in %s comment",
                                        file.getFileName()), 99,
                                commentContent.getInt(
                                        XMLExporter.ATTR_ENCRYPTION));
                    }
                }
                String noteContent = new String(zipIn.getInputStream(file)
                        .readAllBytes(), StandardCharsets.UTF_8);
                if (note.isEncrypted()) {
                    note.setEncryptedNote(se.encrypt(noteContent));
                } else {
                    note.setNote(noteContent);
                }
                actualNotes.put(note.getId(), note);
            }
        }

        MockProgressBar.Progress lastProgress = progress.getEndProgress();
        assertNotNull("Exporter progress was not recorded", lastProgress);
        assertEquals("Total number of records for progress meter",
                testNotes.size(), lastProgress.total);
        assertEquals("Number of records processed for progress meter",
                testNotes.size(), lastProgress.current);

        assertEquals("Notes read back from ZIP file",
                testNotes, actualNotes);
    }

}
