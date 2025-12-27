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
import static com.xmission.trevin.android.notes.service.XMLImporterService.decodeBase64;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomWord;
import static com.xmission.trevin.android.notes.util.StringEncryption.METADATA_PASSWORD_HASH;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomNote;
import static org.junit.Assert.*;

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
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class XMLExportServiceTests {

    private static final Random RAND = new Random();

    private MockNoteRepository mockRepo = null;

    /**
     * Observer which updates a latch when the service reports either
     * completion or an error, and can be queried to wait for completion.
     */
    private static class ExportTestObserver implements HandleIntentObserver {

        private final XMLExporterService service;
        private final CountDownLatch latch;
        private boolean finished = false;
        private boolean success = false;
        private Exception e = null;
        private final List<String> toasts = new ArrayList<>();

        ExportTestObserver(XMLExporterService service) {
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
        boolean foundMatchingElements = false;
        boolean foundAttribute = false;
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                foundChildren = true;
                Element child = (Element) nodeList.item(i);
                if (child.getTagName().equals(tagName)) {
                    foundMatchingElements = true;
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
        if (foundMatchingElements)
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
     * Set up the export service, call it, then wait for it to finish.
     *
     * @param exportFile the file to export
     * @param exportPrivate whether to export private records
     *
     * @return the ExportTestObserver that was registered, which shows
     * the results of the export call
     *
     * @throws AssertionError if the service did not finish exporting the
     * file within 10 seconds.
     * @throws InterruptedException if we were interrupted while waiting
     * for the service to finish
     * @throws TimeoutException if the service did not start
     * (Android system timeout)
     * @throws Exception (any other) if the observer reports an error
     * from the service&rsquo; {@code onHandleIntent} method
     */
    private ExportTestObserver runExporter(
            File exportFile, boolean exportPrivate) throws Exception {
        // Set up the export service
        Intent intent = new Intent(InstrumentationRegistry.getTargetContext(),
                XMLExporterService.class);
        intent.putExtra(XML_DATA_FILENAME, exportFile.getAbsolutePath());
        intent.putExtra(EXPORT_PRIVATE, exportPrivate);
        IBinder binder = serviceRule.bindService(intent);
        long timeoutLimit = System.currentTimeMillis() + 10000;
        while (binder == null) {
            // serviceRule.bindService() may return null if the service
            // is already started or for some other reason.  Try to start
            // it and wait for a bit, then try binding again.
            long timeLeft = timeoutLimit - System.currentTimeMillis();
            assertTrue("Timed out waiting to bind to XMLExporterService",
                    timeLeft > 0);
            Thread.sleep((timeLeft > 200) ? 200 : timeLeft);
            binder = serviceRule.bindService(intent);
        }
        assertNotNull("Failed to bind the exporter service", binder);
        XMLExporterService service = ((XMLExporterService.ExportBinder)
                binder).getService();
        ExportTestObserver observer = new ExportTestObserver(service);
        service.setRepository(mockRepo);
        serviceRule.startService(intent);

        // Wait for the service to export the file.
        observer.await(10);

        if (observer.isError())
            throw observer.getException();
        assertTrue("Exporter service did not finish",
                observer.isFinished());

        if (observer.getNumberOfToasts() > 0)
            // Log the toasts for analysis if needed
            Log.i("XMLExportServiceTests", "Toasts: "
                    + observer.getAllToastMessages());

        return observer;
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
        File xmlFile = getTestFile();
        ExportTestObserver observer = runExporter(xmlFile, false);

        // Verify the results
        assertTrue("Exporter did not finish successfully",
                observer.isSuccess());

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
            File xmlFile = getTestFile();
            ExportTestObserver observer = runExporter(xmlFile, true);

            // Verify the results
            assertTrue("Exporter did not finish successfully",
                    observer.isSuccess());

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
     * Test that the hashed password is <i>not</i> exported
     * if we aren&rsquo;t exported private records.
     */
    @Test
    public void testExportMetadataNotPrivate() throws Exception {
        String password = RandomStringUtils.randomAlphanumeric(15);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            // Set up the test data; the only metadata
            // defined at this point is the hashed password.
            se.setPassword(password.toCharArray());
            se.addSalt();
            se.storePassword(mockRepo);

            // Call the export service
            File xmlFile = getTestFile();
            ExportTestObserver observer = runExporter(xmlFile, false);

            // Verify the results
            assertTrue("Exporter did not finish successfully",
                    observer.isSuccess());

            Element metaSection = verifyXMLFile(xmlFile, METADATA_TAG);
            try {
                getChildByAttribute(metaSection,
                        "item", "name", METADATA_PASSWORD_HASH);
            } catch (AssertionError e) {
                // Pass
                return;
            }
            fail("Password hash was exported");
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }
    }

    /**
     * Test exporting categories
     */
    @Test
    public void testExportCategories() throws Exception {
        // Set up the test data
        Map<Long,String> expectedCategories = new TreeMap<>();
        // We always expect the Unfiled category,
        // even though it's not explicitly inserted.
        expectedCategories.put((long) NoteCategory.UNFILED,
                InstrumentationRegistry.getTargetContext()
                        .getString(R.string.Category_Unfiled));
        int numCategories = RAND.nextInt(5) + 3;
        for (int i = 0; i < numCategories; i++) {
            String categoryName = (char) ('A' + RAND.nextInt(26))
                    + randomWord();
            NoteCategory newCategory = mockRepo.insertCategory(categoryName);
            assertNotNull(String.format("Failed to add category \"%s\"",
                    categoryName), newCategory);
            expectedCategories.put(newCategory.getId(), newCategory.getName());
        }

        // Call the export service
        File xmlFile = getTestFile();
        ExportTestObserver observer = runExporter(xmlFile, false);

        // Verify the results
        assertTrue("Exporter did not finish successfully",
                observer.isSuccess());

        Element catSection = verifyXMLFile(xmlFile, CATEGORIES_TAG);
        Map<Long,String> actualCategories = new TreeMap<>();
        NodeList nodeList = catSection.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                Element child = (Element) nodeList.item(i);
                assertEquals(String.format("Child element of <%s>",
                        catSection.getTagName()),
                        "category", child.getTagName());
                assertTrue("Category entry has no \"id\" attribute",
                        child.hasAttribute("id"));
                String catName = child.getTextContent();
                assertFalse("Category entry has no text",
                        StringUtils.isEmpty(catName));
                try {
                    long catId = Long.parseLong(child.getAttribute("id"));
                    actualCategories.put(catId, catName);
                } catch (NumberFormatException e) {
                    fail(String.format("Category entry has an invalid id;"
                            + " expected an integer, got \"%s\"",
                            child.getAttribute("id")));
                }
            }
        }
        assertEquals("Exported categories",
                expectedCategories, actualCategories);
    }

    /**
     * Generate a random list of categories for use in the note item tests.
     *
     * @return a {@link List} of {@link NoteCategory NoteCategories}
     */
    private List<NoteCategory> addRandomCategories() {
        List<NoteCategory> testCategories = new ArrayList<>();
        NoteCategory category = new NoteCategory();
        category.setId(NoteCategory.UNFILED);
        category.setName(InstrumentationRegistry.getTargetContext()
                .getString(R.string.Category_Unfiled));
        testCategories.add(category);
        for (int i = RAND.nextInt(3) + 2; i > 0; --i) {
            String catName = (char) ('A' + RAND.nextInt(26)) + randomWord();
            category = mockRepo.insertCategory(catName);
            assertNotNull(String.format("Failed to add category \"%s\"",
                    catName), category);
            testCategories.add(category);
        }
        return testCategories;
    }

    /**
     * Read notes out of the export file, verifying that they all contain
     * the expected fields.  If the note&rsquo;s {@code private} field is
     * &ge; 2, this will expect the note content to be encoded in Base64
     * and attempt to decode (not decrypt) it.
     *
     * @param xmlFile the XML export file to read
     *
     * @return a {@link List} of the {@link NoteItem}s read.
     * Order is not guaranteed, although the exporter <i>should</i> have
     * read the notes in order of ID.
     *
     * @throws AssertionError if any child nodes of
     * &lt;{@value XMLExporterService#ITEMS_TAG}&gt; is not an
     * &ldquo;&lt;item&gt;&rdquo; element, does not contain all of the
     * expected note attributes, or we fail to parse any attribute or
     * note content.
     */
    private List<NoteItem> readNotes(File xmlFile) throws Exception {
        Element noteSection = verifyXMLFile(xmlFile, ITEMS_TAG);
        List<NoteItem> notes = new ArrayList<>();
        NodeList nodeList = noteSection.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element) {
                Element child = (Element) nodeList.item(i);
                assertEquals(String.format("Child element of <%s>",
                        noteSection.getTagName()),
                        "item", child.getTagName());
                // These two attributes are required
                assertTrue("Note entry has no \"id\" attribute",
                        child.hasAttribute("id"));
                assertTrue("Note entry has no \"category\" attribute",
                        child.hasAttribute("category"));
                // These three grandchild elements are required
                Element createdChild = getChild(child, "created");
                Element modifiedChild = getChild(child, "modified");
                Element textChild = getChild(child, "note");
                String createdStr = getAttribute(createdChild, "time");
                String modifiedStr = getAttribute(modifiedChild, "time");
                String text = textChild.getTextContent();
                NoteItem note = new NoteItem();
                try {
                    note.setId(Long.parseLong(child.getAttribute("id")));
                } catch (NumberFormatException ne) {
                    fail(String.format("Note entry has an invalid id;"
                            + " expected an integer, got \"%s\"",
                            child.getAttribute("id")));
                }
                try {
                    note.setCategoryId(Long.parseLong(child.getAttribute("category")));
                } catch (NumberFormatException ne) {
                    fail(String.format("Note entry has an invalid category ID;"
                            + " expected an integer, got \"%s\"",
                            child.getAttribute("category")));
                }
                try {
                    note.setCreateTime(DATE_FORMAT.parse(createdStr).getTime());
                } catch (ParseException pe) {
                    fail(String.format("Note entry %d has an invalid created"
                            + " time: \"%s\"", note.getId(), createdStr));
                }
                try {
                    note.setModTime(DATE_FORMAT.parse(modifiedStr).getTime());
                } catch (ParseException pe) {
                    fail(String.format("Note entry %d has an invalid modified"
                            + " time: \"%s\"", note.getId(), modifiedStr));
                }
                // These attributes are optional
                if (child.hasAttribute("private")) {
                    String privateStr = child.getAttribute("private");
                    String encryptionStr = child.getAttribute("encryption");
                    // This parsing can't fail;
                    // anything non-truthy returns false.
                    boolean isPrivate = Boolean.parseBoolean(privateStr);
                    if (StringUtils.isNotBlank(encryptionStr)) try {
                        int level = Integer.parseInt(encryptionStr);
                        note.setPrivate(level);
                        if (level <= 1) {
                            // Plain text, although the "encrypted" attribute
                            // should not have been added in this case.
                            note.setNote(text);
                        } else {
                            // This needs decoding; use the decoder
                            // from the importer service.
                            note.setEncryptedNote(decodeBase64(text));
                        }
                    } catch (NumberFormatException ne) {
                        fail(String.format("Note entry %d has an invalid"
                                + " encryption level: \"%s\"",
                                note.getId(), encryptionStr));
                    } else {
                        note.setPrivate(isPrivate ? 1 : 0);
                        note.setNote(text);
                    }
                } else {
                    note.setPrivate(0);
                    note.setNote(text);
                }

                notes.add(note);
            }
        }
        return notes;
    }

    /** Characters that require escaping in XML */
    public static final char[] UNSAFE_XML_CHARS = { '&', '<', '>', '"', '\'' };

    /**
     * Test exporting public notes only; private notes should be omitted.
     */
    @Test
    public void testExportPublicNotes() throws Exception {
        // Set up the test data.  For this test we
        // want to include a variety of categories.
        List<NoteCategory> testCategories = addRandomCategories();

        List<NoteItem> expectedNotes = new ArrayList<>();
        int targetCount = RAND.nextInt(5) + 10;
        for (int i = 0; i < targetCount; i++) {
            NoteItem note = randomNote();
            // Randomly make some of the notes private
            note.setPrivate(RAND.nextDouble() < 0.6875 ? 0 : 1);
            NoteCategory category = testCategories.get(
                    RAND.nextInt(testCategories.size()));
            note.setCategoryId(category.getId());
            // Randomly add special XML characters to the note
            // to check whether these are encoded and parsed correctly.
            if (RAND.nextDouble() < 0.5) {
                StringBuilder sb = new StringBuilder(note.getNote());
                sb.append('\n').append(UNSAFE_XML_CHARS[RAND.nextInt(
                        UNSAFE_XML_CHARS.length)]).append('\n');
                note.setNote(sb.toString());
            }
            note = mockRepo.insertNote(note);
            if (!note.isPrivate())
                expectedNotes.add(note);
        }

        // Call the export service
        File xmlFile = getTestFile();
        ExportTestObserver observer = runExporter(xmlFile, false);

        assertTrue("Exporter did not finish successfully",
                observer.isSuccess());
        List<NoteItem> actualNotes = readNotes(xmlFile);

        // Verify the results; this relies on the NoteItem.equals(...) method.
        Map<Long,NoteItem> expectedMap = new TreeMap<>();
        for (NoteItem note : expectedNotes)
            expectedMap.put(note.getId(), note);
        Map<Long,NoteItem> actualMap = new TreeMap<>();
        for (NoteItem note : actualNotes)
            actualMap.put(note.getId(), note);
        assertEquals("Exported notes", expectedMap, actualMap);
    }

    /**
     * Test exporting private notes.
     * This version does not check encrypted notes.
     */
    @Test
    public void testExportPrivateNotes() throws Exception {
        // Set up the test data.
        List<NoteItem> expectedNotes = new ArrayList<>();
        int targetCount = RAND.nextInt(5) + 10;
        for (int i = 0; i < targetCount; i++) {
            NoteItem note = randomNote();
            int privacy = RAND.nextInt(3);
            note.setPrivate(privacy < 1 ? 0 : 1);
            note = mockRepo.insertNote(note);
            expectedNotes.add(note);
        }

        // Call the export service
        File xmlFile = getTestFile();
        ExportTestObserver observer = runExporter(xmlFile, true);

        assertTrue("Exporter did not finish successfully",
                observer.isSuccess());
        List<NoteItem> actualNotes = readNotes(xmlFile);

        // Verify the results; this relies on the NoteItem.equals(...) method.
        Map<Long,NoteItem> expectedMap = new TreeMap<>();
        for (NoteItem note : expectedNotes)
            expectedMap.put(note.getId(), note);
        Map<Long,NoteItem> actualMap = new TreeMap<>();
        for (NoteItem note : actualNotes)
            actualMap.put(note.getId(), note);
        assertEquals("Exported notes", expectedMap, actualMap);
    }

    /**
     * Test exporting encrypted notes.
     */
    @Test
    public void testExportEncryptedNotes() throws Exception {
        // Set up the test data.  For this test
        // we need to encrypt most notes.
        String password = RandomStringUtils.randomAlphanumeric(8);
        StringEncryption se = StringEncryption.holdGlobalEncryption();
        try {
            se.setPassword(password.toCharArray());
            se.addSalt();
            se.storePassword(mockRepo);

            List<NoteItem> expectedNotes = new ArrayList<>();
            int targetCount = RAND.nextInt(5) + 10;
            for (int i = 0; i < targetCount; i++) {
                NoteItem note = randomNote();
                int privacy = RAND.nextInt(6);
                if (privacy < 2)
                    note.setPrivate(privacy);
                else {
                    String clearText = note.getNote();
                    byte[] encrypted = se.encrypt(clearText);
                    note.setPrivate(2);
                    note.setNote(null);
                    note.setEncryptedNote(encrypted);
                }
                note = mockRepo.insertNote(note);
                expectedNotes.add(note);
            }

            // Call the export service
            File xmlFile = getTestFile();
            ExportTestObserver observer = runExporter(xmlFile, true);

            assertTrue("Exporter did not finish successfully",
                    observer.isSuccess());
            List<NoteItem> actualNotes = readNotes(xmlFile);

            // Verify the results; we expect encrypted notes to compare the same.
            Map<Long,NoteItem> expectedMap = new TreeMap<>();
            for (NoteItem note : expectedNotes)
                expectedMap.put(note.getId(), note);
            Map<Long,NoteItem> actualMap = new TreeMap<>();
            for (NoteItem note : actualNotes)
                actualMap.put(note.getId(), note);
            assertEquals("Exported notes", expectedMap, actualMap);
        } finally {
            StringEncryption.releaseGlobalEncryption();
        }
    }

}
