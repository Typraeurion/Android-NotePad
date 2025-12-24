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
import static com.xmission.trevin.android.notes.service.XMLExporterService.*;
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomNote;
import static org.junit.Assert.*;

import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.service.XMLExporterService;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.util.*;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class XMLExportServiceTests {

    private static final Random RAND = new Random();

    private MockNoteRepository mockRepo = null;

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
     * Compose an output file we can use for a test.
     * The base filename is a random alphanumeric string;
     * the extension will be &ldquo;<tt>.xml</tt>&rdquo;,
     * and the parent directory will be the app&rsquo;s internal
     * files directory.  The file will be set to delete itself
     * when the test finished.
     */
    File getTestFile() {
        File xmlFile = new File(InstrumentationRegistry.getTargetContext()
                .getFilesDir().getAbsolutePath(),
                RandomStringUtils.randomAlphanumeric(12) + ".xml");
        xmlFile.deleteOnExit();
        return xmlFile;
    }

    /**
     * Wait for the service to write the XML file.  The file must come
     * into existence, have a size at least large enough to hold the
     * expected XML structure, and be readable.  Will time out if
     * the file does not reach this state in ten seconds.
     * <p>
     * This method looks for the expected document closing tag
     * (&ldquo;&lt;/{@value XMLExporterService#DOCUMENT_TAG}&gt;&rdquo;)
     * to be sure the service has finished writing the file.
     * If the file exists but this closing tag is not present by the
     * time the timeout has expired, this method will silently return
     * so the following checks can assess any XML format errors.
     * </p>
     *
     * @throws AssertionError if we fail to obtain the file within
     * the prescribed time limit
     * @throws IOException if we are unable to read the file
     * @throws InterruptedException if we are interrupted wile waiting
     */
    public static void waitForXML(File file)
            throws IOException, InterruptedException {
        long timeLeft = 10000;
        long timeLimit = System.currentTimeMillis() + timeLeft;
        boolean hasContent = false;
        while (timeLeft > 0) {
            if (file.exists() && file.canRead() &&
                    (file.length() > 2 * DOCUMENT_TAG.length() + 5)) {
                hasContent = true;
                // Check that the file ends with ">".
                // Otherwise the service may be in the middle of writing it.
                RandomAccessFile raf = new RandomAccessFile(file, "r");
                try {
                    byte[] buffer = new byte[DOCUMENT_TAG.length() + 4];
                    raf.seek(file.length() - buffer.length);
                    raf.readFully(buffer);
                    String lastBit = new String(buffer).trim();
                    if (lastBit.endsWith("</" + DOCUMENT_TAG + ">"))
                        return;
                } finally {
                    raf.close();
                }
            }
            Thread.sleep((timeLeft > 200) ? 200 : timeLeft);
            timeLeft = timeLimit - System.currentTimeMillis();
        }
        if (!hasContent)
            throw new AssertionError("Timed out waiting for file");
    }

    /**
     * Verify the top-level structure of the output XML file:
     * <ul>
     *     <li>File exists</li>
     *     <li>File parses as XML</li>
     *     <li>The document root is {@value XMLExporterService#DOCUMENT_TAG}</li>
     *     <li><i>(Optional:)</i> There is a top-level element with
     *     the name given by {@code heading}</li>
     * </ul>
     *
     * @param xmlFile the file to verify
     * @param heading a 1st-level heading that must be present in the XML,
     *                or {@code null} to skip the heading check
     *
     * @return the top-level {@link Element} of the document named by
     * {@code heading}, or the root node if {@code heading} is {@code null}.
     *
     * @throws AssertionError if the XML file does not exist,
     * if there is a parse error, if the document root is not
     * {@value XMLExporterService#DOCUMENT_TAG}, or if the given
     * header does not exist as a child of the document root.
     * @throws IOException if there is an error reading the file
     * @throws ParserConfigurationException if the XML parser is not configured
     */
    public static Element verifyXMLFile(File xmlFile, String heading)
            throws IOException, ParserConfigurationException {
        assertTrue("Output XML file was not found", xmlFile.exists());
        InputStream iStream = new FileInputStream(xmlFile);
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(iStream);
            Element docRoot = doc.getDocumentElement();
            assertEquals("XML document root",
                    DOCUMENT_TAG, docRoot.getTagName());
            if (heading != null) {
                NodeList nodeList = docRoot.getChildNodes();
                for (int i = 0; i < nodeList.getLength(); i++) {
                    if (nodeList.item(i) instanceof Element) {
                        Element section = (Element) nodeList.item(i);
                        if (section.getTagName().equals(heading))
                            return section;
                    }
                }
                fail("XML document does not contain <" + heading + ">");
            }
            return docRoot;
        } catch (SAXException e) {
            Log.e("XMLExportServiceTests", e.getClass().getName(), e);
            // Reset the stream and copy the file contents to the log
            iStream.close();
            iStream = new FileInputStream(xmlFile);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(iStream));
            try {
                String nextLine = reader.readLine();
                while (nextLine != null) {
                    Log.d("XMLExportServiceTests", nextLine);
                    nextLine = reader.readLine();
                }
            } finally {
                reader.close();
            }
            // fail(...), with the cause attached
            throw new AssertionError(
                    "XML document is not properly formatted", e);
        } finally {
            iStream.close();
        }
    }

    /**
     * Get a named child element of the given XML element.
     *
     * @param parent the parent element to search
     * @param tagName the name of the child element to return
     *
     * @return the child element
     *
     * @throws AssertionError if the given element has no child element
     * named {@code tagName}
     */
    public static Element getChild(Element parent, String tagName) {
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                Element child = (Element) nodeList.item(i);
                if (child.getTagName().equals(tagName))
                    return child;
            }
        }
        throw new AssertionError(String.format(
                "Element <%s> has no child <%s>",
                parent.getTagName(), tagName));
    }

    /**
     * Get a child of the given XML element having a particular attribute
     * value.  This is used to match metadata, which are all stored as
     * &ldquo;&lt;item&ellip;&gt;&rdquo; elements with different
     * {@code name}s.
     *
     * @param parent the parent element to search
     * @param tagName the name of the child elements to look at
     * @param attrName the name of the attribute to match
     * @param attrValue the value of the {@code attrName} attribute to match
     *
     * @return the matching element
     *
     * @throws AssertionError if the given element has no child element
     * having the given attribute or value.
     */
    public static Element getChildByAttribute(
            Element parent, String tagName,
            String attrName, String attrValue) {
        boolean foundChildren = false;
        boolean foundMatchingElemnts = false;
        boolean foundAttribute = false;
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                foundChildren = true;
                Element child = (Element) nodeList.item(i);
                if (child.getTagName().equals(tagName)) {
                    foundMatchingElemnts = true;
                    if (child.hasAttribute(attrName)) {
                        foundAttribute = true;
                        if (child.getAttribute(attrName).equals(attrValue))
                            return child;
                    }
                }
            }
        }
        if (foundAttribute)
            throw new AssertionError(String.format(
                    "No <%s> element has %s \"%s\"",
                    tagName, attrName, attrValue));
        if (foundMatchingElemnts)
            throw new AssertionError(String.format(
                    "No <%s> element has an attribute named \"%s\"",
                    tagName, attrName));
        if (foundChildren)
            throw new AssertionError(String.format(
                    "Top-level <%s> has no <%s> children",
                    parent.getTagName(), tagName));
        throw new AssertionError(String.format(
                "Top-level <%s> has no children", parent.getTagName()));
    }

    /**
     * Get a named attribute of the given XML element.
     *
     * @param element the element to search
     * @param attrName the name of the attribute to return
     * @return the attribute (as a string)
     * @throws AssertionError if the given element has no attribute
     * named {@code attrName}
     */
    public static String getAttribute(Element element, String attrName) {
        assertTrue(String.format("Element <%s> has no attribute \"%s\"",
                        element.getTagName(), attrName),
                element.hasAttribute(attrName));
        return element.getAttribute(attrName);
    }

    /**
     * Assert the value of an element as text content.
     * This strips any leading or trailing whitespace.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected text
     * @param element the element whose actual value to check
     */
    public static void assertContentEquals(
            String message, String expected, Element element) {
        assertEquals(message, expected, element.getTextContent().trim());
    }

    /**
     * Assert the value of an element as a number.
     * This strips any leading or trailing whitespace.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected text
     * @param element the element whose actual value to check
     */
    public static void assertContentEquals(
            String message, long expected, Element element) {
        String content = element.getTextContent().trim();
        try {
            long actual = Long.parseLong(content);
            assertEquals(message, expected, actual);
        } catch (NumberFormatException e) {
            assertEquals(message, Long.toString(expected), content);
        }
    }

    /**
     * Assert the value of an element as a boolean.
     * This strips any leading or trailing whitespace.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected text
     * @param element the element whose actual value to check
     */
    public static void assertContentEquals(
            String message, boolean expected, Element element) {
        String content = element.getTextContent().trim();
        try {
            boolean actual = Boolean.parseBoolean(content);
            assertEquals(message, expected, actual);
        } catch (NumberFormatException e) {
            assertEquals(message, Boolean.toString(expected), content);
        }
    }

    /**
     * Assert the value of an attribute of an element as a string.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected text
     * @param element the element whose attribute to check
     * @param attrName the name of the attribute
     */
    public static void assertAttributeEquals(
            String message, String expected, Element element, String attrName) {
        String attrValue = getAttribute(element, attrName);
        assertEquals(message, expected, attrValue);
    }

    /**
     * Assert the value of an attribute of an element as a number.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected value
     * @param element the element whose attribute to check
     * @param attrName the name of the attribute
     */
    public static void assertAttributeEquals(
            String message, long expected, Element element, String attrName) {
        String attrValue = getAttribute(element, attrName);
        try {
            long actual = Long.parseLong(attrValue);
            assertEquals(message, expected, actual);
        } catch (NumberFormatException e) {
            assertEquals(message, Long.toString(expected), attrValue);
        }
    }

    /**
     * Assert the value of an attribute of an element as a boolean.
     *
     * @param message the assertion message to show if the actual value
     *               does not match the expectation
     * @param expected the expected value
     * @param element the element whose attribute to check
     * @param attrName the name of the attribute
     */
    public static void assertAttributeEquals(
            String message, boolean expected, Element element, String attrName) {
        String attrValue = getAttribute(element, attrName);
        try {
            boolean actual = Boolean.parseBoolean(attrValue);
            assertEquals(message, expected, actual);
        } catch (NumberFormatException e) {
            assertEquals(message, Boolean.toString(expected), attrValue);
        }
    }

    /**
     * Test exporting note preferences
     */
    @Test
    public void testExportPreferences() throws Exception {
        // Set up the test data
        NotePreferences prefs = NotePreferences.getInstance(
                InstrumentationRegistry.getTargetContext());
        prefs.edit()
                .setExportFile(RandomStringUtils
                        .randomAlphabetic(12) + ".xml")
                .setExportPrivate(RAND.nextBoolean())
                .setImportFile(RandomStringUtils
                        .randomAlphabetic(12) + ".xml")
                .setImportPrivate(RAND.nextBoolean())
                .setImportType(ImportType.values()[
                        RAND.nextInt(ImportType.values().length)])
                .setSelectedCategory(RAND.nextInt(100) + 1)
                .setShowCategory(RAND.nextBoolean())
                .setShowEncrypted(RAND.nextBoolean())
                .setShowPrivate(RAND.nextBoolean())
                .setSortOrder(RAND.nextInt(10) + 1)
                .finish();

        // Call the export service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                XMLExporterService.class);
        File xmlFile = getTestFile();
        intent.putExtra(XML_DATA_FILENAME, xmlFile.getAbsolutePath());
        intent.putExtra(EXPORT_PRIVATE, false); // Not needed for this test
        serviceRule.startService(intent);

        // Wait for the service to write the file.
        waitForXML(xmlFile);

        // Verify the results
        Element prefsSection = verifyXMLFile(xmlFile, PREFERENCES_TAG);
        Element prefTag = getChild(prefsSection, NPREF_EXPORT_FILE);
        assertContentEquals("Export file name",
                prefs.getExportFile("(default)"), prefTag);
        prefTag = getChild(prefsSection, NPREF_EXPORT_PRIVATE);
        assertContentEquals("Export private records",
                prefs.exportPrivate(), prefTag);
        prefTag = getChild(prefsSection, NPREF_IMPORT_FILE);
        assertContentEquals("Import file name",
                prefs.getImportFile("(default)"), prefTag);
        prefTag = getChild(prefsSection, NPREF_IMPORT_PRIVATE);
        assertContentEquals("Import private records",
                prefs.importPrivate(), prefTag);
        prefTag = getChild(prefsSection, NPREF_IMPORT_TYPE);
        assertContentEquals("Import type",
                prefs.getImportType().ordinal(), prefTag);
        prefTag = getChild(prefsSection, NPREF_SELECTED_CATEGORY);
        assertContentEquals("Selected category",
                prefs.getSelectedCategory(), prefTag);
        prefTag = getChild(prefsSection, NPREF_SHOW_CATEGORY);
        assertContentEquals("Show categories",
                prefs.showCategory(), prefTag);
        prefTag = getChild(prefsSection, NPREF_SHOW_ENCRYPTED);
        assertContentEquals("Show encrypted records",
                prefs.showEncrypted(), prefTag);
        prefTag = getChild(prefsSection, NPREF_SHOW_PRIVATE);
        assertContentEquals("Show private records",
                prefs.showPrivate(), prefTag);
    }

    /**
     * Test exporting metadata (the hashed password)
     */
    @Test
    public void testExportMetadata() throws Exception {
        String password = RandomStringUtils.randomAlphanumeric(15);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            // Set up the test data; the only metadata
            // defined at this point is the hashed password.
            se.setPassword(password.toCharArray());
            se.addSalt();
            se.storePassword(mockRepo);

            // Call the export service
            Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                    XMLExporterService.class);
            File xmlFile = getTestFile();
            intent.putExtra(XML_DATA_FILENAME, xmlFile.getAbsolutePath());
            intent.putExtra(EXPORT_PRIVATE, true);
            serviceRule.startService(intent);

            // Wait for the service to write the file.
            waitForXML(xmlFile);

            // Verify the results
            Element metaSection = verifyXMLFile(xmlFile, METADATA_TAG);
            Element passwordHash = getChildByAttribute(metaSection,
                    "item", "name", METADATA_PASSWORD_HASH);
            // Since the format of the hash should be opaque AND the salt is
            // randomly generated, we can't check the actual value.  Just
            // make sure something is there.
            String content = passwordHash.getTextContent();
            assertFalse("No password hash was exported",
                    StringUtils.isEmpty(content));
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }
    }

    /**
     * Test exporting categories
     */
    @Test
    public void testExportCategories() throws Exception {
        fail("Not yet implemented");
    }

    /**
     * Test exporting public notes only; private notes should be omitted.
     */
    @Test
    public void testExportPublicNotes() throws Exception {
    }

}
