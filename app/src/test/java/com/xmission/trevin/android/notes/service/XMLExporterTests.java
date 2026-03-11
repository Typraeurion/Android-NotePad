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
import static com.xmission.trevin.android.notes.service.XMLExporter.*;
import static com.xmission.trevin.android.notes.service.XMLImporter.decodeBase64;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.*;
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static org.junit.Assert.*;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Unit tests for exporting notes to an XML file.
 *
 * @author Trevin Beattie
 */
public class XMLExporterTests {

    private static final Random RAND = new Random();
    private static final RandomStringUtils SRAND = RandomStringUtils.insecure();

    private static MockSharedPreferences underlyingPrefs = null;
    private static NotePreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;
    // Common XPath instance for evaluating XML document items
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
     * Test the root node and major section wrappers with no data
     * (except the repository&rsquo;s default &ldquo;Unfiled&rdquo; category).
     */
    @Test
    public void testExportWrapper() throws Exception {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, false, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);
        assertEquals("Document root", DOCUMENT_TAG,
                doc.getDocumentElement().getTagName());

        XPath xpath = XPathFactory.newInstance().newXPath();
        assertNotNull(String.format(Locale.US,
                        "Missing <%s> section", PREFERENCES_TAG),
                xpath.evaluate(String.format(Locale.US, "/%s/%s",
                        DOCUMENT_TAG, PREFERENCES_TAG), doc));
        assertNotNull(String.format(Locale.US,
                        "Missing <%s> section", METADATA_TAG),
                xpath.evaluate(String.format(Locale.US, "/%s/%s",
                        DOCUMENT_TAG, METADATA_TAG), doc));
        assertNotNull(String.format(Locale.US,
                        "Missing <%s> section", CATEGORIES_TAG),
                xpath.evaluate(String.format(Locale.US, "/%s/%s",
                        DOCUMENT_TAG, CATEGORIES_TAG), doc));
        assertNotNull(String.format(Locale.US,
                        "Missing <%s> section", NOTES_TAG),
                xpath.evaluate(String.format(Locale.US, "/%s/%s",
                        DOCUMENT_TAG, NOTES_TAG), doc));

        String attrValue = xpath.evaluate(String.format(Locale.US,
                "/%s/@%s", DOCUMENT_TAG, ATTR_VERSION), doc);
        assertNotNull(String.format(Locale.US,
                        "Missing %s attribute in document root",
                        ATTR_VERSION), attrValue);
        assertEquals(String.format(Locale.US,
                "Document root %s attribute", ATTR_VERSION),
                "2", attrValue);

        attrValue = xpath.evaluate(String.format(Locale.US,
                "/%s/@%s", DOCUMENT_TAG, ATTR_TOTAL_RECORDS), doc);
        assertNotNull(String.format(Locale.US,
                        "Missing %s attribute in document root",
                        ATTR_TOTAL_RECORDS), attrValue);

        attrValue = xpath.evaluate(String.format(Locale.US,
                "/%s/@%s", DOCUMENT_TAG, ATTR_EXPORTED), doc);
        assertNotNull(String.format(Locale.US,
                        "Missing %s attribute in document root",
                        ATTR_EXPORTED), attrValue);

        attrValue = xpath.evaluate(String.format(Locale.US,
                "/%s/%s/@%s", DOCUMENT_TAG, CATEGORIES_TAG, ATTR_COUNT), doc);
        assertNotNull(String.format(Locale.US,
                        "Missing %s attribute in %s section",
                        ATTR_COUNT, CATEGORIES_TAG), attrValue);

        attrValue = xpath.evaluate(String.format(Locale.US,
                "/%s/%s/@%s", DOCUMENT_TAG, NOTES_TAG, ATTR_COUNT), doc);
        assertNotNull(String.format(Locale.US,
                        "Missing %s attribute in %s section",
                        ATTR_COUNT, NOTES_TAG), attrValue);
    }

    /**
     * Helper method for checking a preference value.
     *
     * @param message the string to show if the assertion fails
     * (should describe which preference is being checked)
     * @param element the name of the preference element to look at
     * @param expected the expected value as a string
     * @param doc the XML document to look at
     *
     * @throws AssertionError if the value of the element
     * does not match the expected value
     * @throws XPathExpressionException if the XPath evaluation fails
     */
    private void assertPreferenceEquals(
            String message, String element, String expected, Document doc)
            throws AssertionError, XPathExpressionException {
        assertEquals(message, expected, xpath.evaluate(String.format(
                "/%s/%s/%s", DOCUMENT_TAG, PREFERENCES_TAG, element), doc));
    }

    /**
     * Test writing out preferences
     */
    @Test
    public void testExportPreferences() throws Exception {
        mockPrefs.edit()
                .setExportFile(SRAND.nextAlphabetic(12, 24))
                .setExportPrivate(RAND.nextBoolean())
                .setImportFile(SRAND.nextAlphabetic(12, 24))
                .setImportPrivate(RAND.nextBoolean())
                .setImportType(NotePreferences.ImportType.values()[RAND
                        .nextInt(NotePreferences.ImportType.values().length)])
                .setSelectedCategory(RAND.nextLong(100000))
                .setShowCategory(RAND.nextBoolean())
                .setShowEncrypted(RAND.nextBoolean())
                .setShowPrivate(RAND.nextBoolean())
                .finish();

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, false, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, PREFERENCES_TAG, ATTR_COUNT), doc);
        assertFalse(String.format("Missing %s %s",
                PREFERENCES_TAG, ATTR_COUNT), countStr.isEmpty());
        int expectedCount = mockPrefs.getAllPreferences().size();
        try {
            int actualCount = Integer.parseInt(countStr);
            assertEquals("Number of preferences exported",
                    expectedCount, actualCount);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    PREFERENCES_TAG, ATTR_COUNT, countStr));
        }

        assertPreferenceEquals("Export file name", NPREF_EXPORT_FILE,
                mockPrefs.getExportFile("(default)"), doc);

        assertPreferenceEquals("Export private records", NPREF_EXPORT_PRIVATE,
                Boolean.toString(mockPrefs.exportPrivate()), doc);

        assertPreferenceEquals("Import file name", NPREF_IMPORT_FILE,
                mockPrefs.getImportFile(""), doc);

        assertPreferenceEquals("Import private records", NPREF_IMPORT_PRIVATE,
                Boolean.toString(mockPrefs.importPrivate()), doc);

        assertPreferenceEquals("Import type", NPREF_IMPORT_TYPE,
                Integer.toString(mockPrefs.getImportType().ordinal()), doc);

        assertPreferenceEquals("Selected category", NPREF_SELECTED_CATEGORY,
                Long.toString(mockPrefs.getSelectedCategory()), doc);

        assertPreferenceEquals("Show categories", NPREF_SHOW_CATEGORY,
                Boolean.toString(mockPrefs.showCategory()), doc);

        assertPreferenceEquals("Show encrypted records", NPREF_SHOW_ENCRYPTED,
                Boolean.toString(mockPrefs.showEncrypted()), doc);

        assertPreferenceEquals("Show private records", NPREF_SHOW_PRIVATE,
                Boolean.toString(mockPrefs.showPrivate()), doc);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Final progress data", endProgress);
        assertEquals("Total records to be processed",
                // Need to add one record for the Unfiled category
                expectedCount + 1, endProgress.total);
        assertEquals("Number of records processed",
                expectedCount + 1, endProgress.current);
    }

    /**
     * Test writing out metadata, excluding the password hash.
     * Since we control the mock repository, we can come up with any
     * random metadata we need.
     */
    @Test
    public void testExportPublicMetadata() throws Exception {
        Map<String,String> expectedMetadata = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 5; i >= 0; --i) {
            String key = SRAND.nextAlphabetic(10, 13);
            String value = SRAND.nextAlphanumeric(20, 25);
            expectedMetadata.put(key, value);
            mockRepo.upsertMetadata(key,
                    value.getBytes(StandardCharsets.UTF_8));
        }
        // The password "hash" doesn't have to be a real hash for this test
        byte[] hash = new byte[32];
        RAND.nextBytes(hash);
        mockRepo.upsertMetadata(METADATA_PASSWORD_HASH, hash);

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, false, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, METADATA_TAG, ATTR_COUNT), doc);
        assertFalse(String.format("Missing %s %s",
                METADATA_TAG, ATTR_COUNT), countStr.isEmpty());
        try {
            int actualCount = Integer.parseInt(countStr);
            assertEquals("Number of metadata exported",
                    expectedMetadata.size(), actualCount);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    METADATA_TAG, ATTR_COUNT, countStr));
        }

        Map<String,String> actualMetadata = new TreeMap<>();
        NodeList children = (NodeList) xpath.evaluate(String.format(
                "/%s/%s/%s", DOCUMENT_TAG, METADATA_TAG, METADATA_ITEM),
                doc, XPathConstants.NODESET);
        for (int i = 0; i < children.getLength(); i++) {
            Element el = (Element) children.item(i);
            assertEquals("Metadata item tag",
                    METADATA_ITEM, el.getTagName());
            String elementStr = el.toString();
            assertTrue(String.format("Metadata %s has no ID", elementStr),
                    el.hasAttribute(ATTR_ID));
            assertTrue(String.format("Metadata %s has no name", elementStr),
                    el.hasAttribute(ATTR_NAME));
            String value = el.getTextContent();
            assertTrue(String.format("Metadata %s has no content", elementStr),
                    StringUtils.isNotEmpty(value));
            // All values must be base64-encoded
            try {
                byte[] b = decodeBase64(value);
                value = new String(b, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException x) {
                fail(String.format("Metadata %s has corrupted content: %s",
                        elementStr, x.getMessage()));
            }
            actualMetadata.put(el.getAttribute(ATTR_NAME), value);
        }

        assertEquals("Exported metadata",
                expectedMetadata, actualMetadata);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Final progress data", endProgress);
        assertEquals("Total records to be processed",
                // Need to add one record for the Unfiled category
                expectedMetadata.size() + 1, endProgress.total);
        assertEquals("Number of records processed",
                expectedMetadata.size() + 1, endProgress.current);
    }

    /**
     * Test writing out metadata, including the password hash.
     */
    @Test
    public void testExportPrivateMetadata() throws Exception {
        // The password "hash" doesn't have to be a real hash for this test
        byte[] expectedHash = new byte[32];
        RAND.nextBytes(expectedHash);
        mockRepo.upsertMetadata(METADATA_PASSWORD_HASH,
                expectedHash);

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, true, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, METADATA_TAG, ATTR_COUNT), doc);
        assertEquals(String.format("%s %s", METADATA_TAG, ATTR_COUNT),
                "1", countStr);

        assertNotNull(String.format("%s %s is missing",
                METADATA_TAG, METADATA_ITEM),
                xpath.evaluate(String.format("(/%s/%s/%s)[1]",
                        DOCUMENT_TAG, METADATA_TAG, METADATA_ITEM), doc));
        String name = xpath.evaluate(String.format("(/%s/%s/%s)[1]/@%s",
                DOCUMENT_TAG, METADATA_TAG, METADATA_ITEM, ATTR_NAME), doc);
        assertEquals("Metadata name",
                METADATA_PASSWORD_HASH, name);
        String value = xpath.evaluate(String.format("(/%s/%s/%s)[1]/text()",
                DOCUMENT_TAG, METADATA_TAG, METADATA_ITEM), doc);
        assertEquals(String.format("%s value (encoded as Base64)",
                METADATA_PASSWORD_HASH),
                encodeBase64(expectedHash), value);

    }

    /**
     * Test writing out categories
     */
    @Test
    public void testExportCategories() throws Exception {
        Map<Long,String> expectedCategories = new TreeMap<>();
        for (int i = RAND.nextInt(5) + 5; i >= 0; --i) {
            NoteCategory category = mockRepo.insertCategory(
                    SRAND.nextAlphabetic(5, 8) + " "
                            + SRAND.nextAlphanumeric(5, 9));
            expectedCategories.put(category.getId(), category.getName());
        }
        expectedCategories.put((long) NoteCategory.UNFILED,
                mockRepo.getCategoryById(NoteCategory.UNFILED).getName());

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, false, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, CATEGORIES_TAG, ATTR_COUNT), doc);
        assertFalse(String.format("Missing %s %s",
                CATEGORIES_TAG, ATTR_COUNT), countStr.isEmpty());
        try {
            int actualCount = Integer.parseInt(countStr);
            assertEquals("Number of categories exported",
                    expectedCategories.size(), actualCount);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    CATEGORIES_TAG, ATTR_COUNT, countStr));
        }

        String maxIdStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, CATEGORIES_TAG, ATTR_MAX_ID), doc);
        assertFalse(String.format("Missing %s %S",
        CATEGORIES_TAG, ATTR_MAX_ID), maxIdStr.isEmpty());
        try {
            long actualId = Long.parseLong(maxIdStr);
            assertEquals("Maximum category ID",
                    mockRepo.getMaxCategoryId(), actualId);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    CATEGORIES_TAG, ATTR_MAX_ID, countStr));
        }

        Map<Long,String> actualCategories = new TreeMap<>();
        NodeList children = (NodeList) xpath.evaluate(String.format(
                "/%s/%s/%s", DOCUMENT_TAG, CATEGORIES_TAG, CATEGORIES_ITEM),
                doc, XPathConstants.NODESET);
        for (int i = 0; i < children.getLength(); i++) {
            Element el = (Element) children.item(i);
            assertEquals("Category item tag",
                    CATEGORIES_ITEM, el.getTagName());
            String elementStr = el.toString();
            assertTrue(String.format("Category %s has no ID", elementStr),
                    el.hasAttribute(ATTR_ID));
            long id = -1;
            try {
                id = Long.parseLong(el.getAttribute(ATTR_ID));
            } catch (NumberFormatException x) {
                fail(String.format("Category %s ID is not an integer",
                        elementStr));
            }
            String value = el.getTextContent();
            assertTrue(String.format("Category %s has no content", elementStr),
                    StringUtils.isNotEmpty(value));
            actualCategories.put(id, value);
        }

        assertEquals("Exported categories",
                expectedCategories, actualCategories);

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Final progress data", endProgress);
        assertEquals("Total records to be processed",
                expectedCategories.size(), endProgress.total);
        assertEquals("Number of records processed",
                expectedCategories.size(), endProgress.current);
    }

    /**
     * Get the ID of a note element.
     *
     * @param element the note &ldquo;item&rdquo; element
     *
     * @return the note ID
     *
     * @throws AssertionError if the element has no ID attribute
     * or if its value is not an integer
     */
    private long getItemId(Element element) throws AssertionError {
        assertEquals("Note item tag", NOTE_ITEM, element.getTagName());
        String elementStr = element.toString();
        assertTrue(String.format("To Do item %s has no ID", elementStr),
                element.hasAttribute(ATTR_ID));
        try {
            return Long.parseLong(element.getAttribute(ATTR_ID));
        } catch (NumberFormatException x) {
            fail(String.format("To Do item %s ID is not an integer",
                    elementStr));
            // Unreachable
            return -1;
        }
    }

    /**
     * Get a single child Element from a note XML node.
     * Throw an error if there is not exactly one child.
     *
     * @param id the ID of the note
     * @param tag the name of the element to find
     * @param parent the parent element
     *
     * @throws AssertionError if there is not exactly one {@code tag} child.
     */
    private Element getOnlyChild(long id, String tag, Element parent)
        throws AssertionError, Exception {
        NodeList children = (NodeList) xpath.evaluate(
                tag, parent, XPathConstants.NODESET);
        if (children.getLength() != 1) {
            if (children.getLength() < 1)
                fail(String.format("Exported note #%d has no %s child element",
                        id, tag));
            else
                assertEquals(String.format(
                        "Exported note #%d number of %s child elements",
                                id, tag), 1, children.getLength());
        }
        return (Element) children.item(0);
    }

    /**
     * Check whether a note item parsed from XML matches the expected note.
     *
     * @param expectedNote the original note
     * @param element the XML element
     *
     * @throws AssertionError if the element doesn&rsquo;t contain
     * any of the expected attributes or child elements or if their
     * values don&rsquo;t match.
     */
    private void checkNoteXML(NoteItem expectedNote, Element element)
            throws AssertionError, Exception {
        long noteId = expectedNote.getId();
        assertTrue(String.format("Note #%d has no %s attribute",
                        noteId, ATTR_CATEGORY_ID),
                element.hasAttribute(ATTR_CATEGORY_ID));
        assertEquals(String.format("Exported item #%d %s attribute",
                        noteId, ATTR_CATEGORY_ID),
                Long.toString(expectedNote.getCategoryId()),
                element.getAttribute(ATTR_CATEGORY_ID));

        // The `private' and `encryption' attributes are optional if public
        if (expectedNote.isPrivate()) {
            assertTrue(String.format("Private item #%d has no %s attribute",
                            noteId, ATTR_PRIVATE),
                    element.hasAttribute(ATTR_PRIVATE));
            if (expectedNote.isEncrypted()) {
                assertTrue(String.format(
                        "Encrypted item #%d has no %s attribute",
                                noteId, ATTR_ENCRYPTION),
                        element.hasAttribute(ATTR_ENCRYPTION));
            }
        }
        if (element.hasAttribute(ATTR_PRIVATE)) {
            assertEquals(String.format("Exported item #%d %s attribute",
                            noteId, ATTR_PRIVATE),
                    Boolean.toString(expectedNote.isPrivate()),
                    element.getAttribute(ATTR_PRIVATE));
        }
        if (element.hasAttribute(ATTR_ENCRYPTION)) {
            assertEquals(String.format("Exported item #%d %s attribute",
                            noteId, ATTR_ENCRYPTION),
                    Integer.toString(expectedNote.getPrivate()),
                    element.getAttribute(ATTR_ENCRYPTION));
        }

        Element childElement = getOnlyChild(noteId, NOTE_CREATED, element);
        assertTrue(String.format("Exported item #%d %s has no %s",
                        noteId, NOTE_CREATED, ATTR_TIME),
                childElement.hasAttribute(ATTR_TIME));
        assertEquals(String.format("Exported item #%d created time", noteId),
                expectedNote.getCreateTime().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT),
                childElement.getAttribute(ATTR_TIME));

        childElement = getOnlyChild(noteId, NOTE_MODIFIED, element);
        assertTrue(String.format("Exported item #%d %s has no %s",
                        noteId, NOTE_MODIFIED, ATTR_TIME),
                childElement.hasAttribute(ATTR_TIME));
        assertEquals(String.format("Exported item #%d last modified time",
                        noteId), expectedNote.getModTime().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT),
                childElement.getAttribute(ATTR_TIME));

        childElement = getOnlyChild(noteId, NOTE_NOTE, element);
        String content = childElement.getTextContent();
        if (expectedNote.isEncrypted()) {
            assertEquals(String.format(
                    "Exported note #%d encrypted content", noteId),
                    encodeBase64(expectedNote.getEncryptedNote()),
                    content);
        } else {
            assertEquals(String.format("Exported note #%d content", noteId),
                    expectedNote.getNote(), content);
        }
    }

    /**
     * Test writing out public notes.
     */
    @Test
    public void testExportPublicNotes() throws Exception {
        List<NoteCategory> testCategories = new ArrayList<>();
        NoteCategory category = mockRepo.getCategoryById(NoteCategory.UNFILED);
        testCategories.add(category);
        for (int i = RAND.nextInt(5) + 5; i >= 0; --i) {
            category = mockRepo.insertCategory(
                    SRAND.nextAlphabetic(5, 8) + " "
                            + SRAND.nextAlphanumeric(5, 9));
            testCategories.add(category);
        }

        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (int i = RAND.nextInt(10) + 10; i >= 0; --i) {
            NoteItem note = randomNote();
            category = testCategories.get(
                    RAND.nextInt(testCategories.size()));
            note.setCategoryId(category.getId());
            note = mockRepo.insertNote(note);
            if (!note.isPrivate())
                expectedNotes.put(note.getId(), note);
        }

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, false, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, NOTES_TAG, ATTR_COUNT), doc);
        assertFalse(String.format("Missing %s %s",
                NOTES_TAG, ATTR_COUNT), countStr.isEmpty());
        try {
            int actualCount = Integer.parseInt(countStr);
            assertEquals("Number of notes exported",
                    expectedNotes.size(), actualCount);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    NOTES_TAG, ATTR_COUNT, countStr));
        }

        String maxIdStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, NOTES_TAG, ATTR_MAX_ID), doc);
        assertFalse(String.format("Missing %s %S",
                NOTES_TAG, ATTR_MAX_ID), maxIdStr.isEmpty());
        try {
            long actualId = Long.parseLong(maxIdStr);
            assertEquals("Maximum note ID",
                    mockRepo.getMaxNoteId(), actualId);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    NOTES_TAG, ATTR_MAX_ID, countStr));
        }

        Set<Long> idsSeen = new TreeSet<>();
        NodeList children = (NodeList) xpath.evaluate(String.format(
                "/%s/%s/%s", DOCUMENT_TAG, NOTES_TAG, NOTE_ITEM),
                doc, XPathConstants.NODESET);
        for (int i = 0; i < children.getLength(); i++) {
            Element el = (Element) children.item(i);
            String elementStr = el.toString();
            long id = getItemId(el);
            idsSeen.add(id);
            NoteItem expectedItem = expectedNotes.get(id);
            if (expectedItem == null) {
                NoteItem unexpectedItem = mockRepo.getNoteById(id);
                if (unexpectedItem == null)
                    fail(String.format(
                            "Exported %s does not exist in the repository",
                            elementStr));
                if (unexpectedItem.isPrivate())
                    fail(String.format("Exported note #%d is private",
                            id));
                fail(String.format("TEST ERROR: %s wasn't added"
                        + " to the expected items map", unexpectedItem));
                // Unreachable
                continue;
            }
            checkNoteXML(expectedItem, el);
        }
        if (!idsSeen.equals(expectedNotes.keySet())) {
            assertFalse("No notes were exported", idsSeen.isEmpty());
            Set<Long> missingIds = new TreeSet<>(expectedNotes.keySet());
            missingIds.removeAll(idsSeen);
            assertFalse(String.format("Most notes were not exported: %s",
                    missingIds), missingIds.size() >= idsSeen.size());
            List<NoteItem> missingItems = new ArrayList<>();
            for (Long id : missingIds)
                missingItems.add(expectedNotes.get(id));
            fail(String.format("Expected notes were not exported: %s",
                    missingItems));
        }

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Final progress data", endProgress);
        assertEquals("Total records to be processed",
                testCategories.size() + expectedNotes.size(),
                endProgress.total);
        assertEquals("Number of records processed",
                testCategories.size() + expectedNotes.size(),
                endProgress.current);
    }

    /**
     * Test writing out private notes.
     */
    @Test
    public void testExportPrivateNotes() throws Exception {
        // Set up the test data.
        Map<Long,NoteItem> expectedNotes = new TreeMap<>();
        for (int i = RAND.nextInt(10) + 10; i >= 0; --i) {
            NoteItem note = randomNote();
            if (note.isPrivate() && RAND.nextBoolean()) {
                // "encrypt" the record
                note.setPrivate(StringEncryption.encryptionType());
                note.setEncryptedNote(note.getNote()
                        .getBytes(StandardCharsets.UTF_8));
                note.setNote(null);
            }
            note = mockRepo.insertNote(note);
            expectedNotes.put(note.getId(), note);
        }

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        MockProgressBar progress = new MockProgressBar();
        XMLExporter.export(mockPrefs, mockRepo, outStream, true, progress);
        progress.setEndTime();
        outStream.close();

        ByteArrayInputStream inStream = new ByteArrayInputStream(
                outStream.toByteArray());
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(inStream);

        String countStr = xpath.evaluate(String.format("/%s/%s/@%s",
                DOCUMENT_TAG, NOTES_TAG, ATTR_COUNT), doc);
        assertFalse(String.format("Missing %s %s",
                NOTES_TAG, ATTR_COUNT), countStr.isEmpty());
        try {
            int actualCount = Integer.parseInt(countStr);
            assertEquals("Number of notes exported",
                    expectedNotes.size(), actualCount);
        } catch (NumberFormatException x) {
            fail(String.format("%s %s \"%s\" is not an integer",
                    NOTES_TAG, ATTR_COUNT, countStr));
        }

        Set<Long> idsSeen = new TreeSet<>();
        NodeList children = (NodeList) xpath.evaluate(String.format(
                "/%s/%s/%s", DOCUMENT_TAG, NOTES_TAG, NOTE_ITEM),
                doc, XPathConstants.NODESET);
        for (int i = 0; i < children.getLength(); i++) {
            Element el = (Element) children.item(i);
            String elementStr = el.toString();
            long id = getItemId(el);
            idsSeen.add(id);
            NoteItem expectedNote = expectedNotes.get(id);
            if (expectedNote == null) {
                NoteItem unexpectedNote = mockRepo.getNoteById(id);
                if (unexpectedNote == null)
                    fail(String.format(
                            "Exported %s does not exist in the repository",
                            elementStr));
                fail(String.format("TEST ERROR: %s wasn't added"
                        + " to the expected items map", unexpectedNote));
                // Unreachable
                continue;
            }
            checkNoteXML(expectedNote, el);
        }
        if (!idsSeen.equals(expectedNotes.keySet())) {
            assertFalse("No notes were exported", idsSeen.isEmpty());
            Set<Long> missingIds = new TreeSet<>(expectedNotes.keySet());
            missingIds.removeAll(idsSeen);
            assertFalse(String.format("Most notes were not exported: %s",
                    missingIds), missingIds.size() >= idsSeen.size());
            List<NoteItem> missingItems = new ArrayList<>();
            for (Long id : missingIds)
                missingItems.add(expectedNotes.get(id));
            fail(String.format("Expected notes were not exported: %s",
                    missingItems));
        }

        MockProgressBar.Progress endProgress = progress.getEndProgress();
        assertNotNull("Final progress data", endProgress);
        assertEquals("Total records to be processed",
                // Add 1 for the Unfiled category
                expectedNotes.size() + 1, endProgress.total);
        assertEquals("Number of records processed",
                expectedNotes.size() + 1, endProgress.current);
    }

}
