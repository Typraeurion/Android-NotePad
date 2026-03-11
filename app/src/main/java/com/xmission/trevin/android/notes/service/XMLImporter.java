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

// Use all of the element and attribute names from the exporter
import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static com.xmission.trevin.android.notes.service.XMLExporter.*;

import android.util.Log;
import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.util.EncryptionException;
import com.xmission.trevin.android.notes.util.PasswordMismatchException;
import com.xmission.trevin.android.notes.util.PasswordRequiredException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * This class imports the note list from an XML file on external storage.
 * There are several modes of import available; see the enumeration
 * {@link ImportType} for details on each mode.
 * <p>
 * If a password was used for the exported XML file or on the database,
 * the password <b>must be the same</b> in both in order to import any
 * encrypted records, and the password must have been provided to the
 * application.  If they are not, the unencrypted records may be
 * imported but all encrypted records will be skipped.
 * </p>
 *
 * @author Trevin Beattie
 */
public class XMLImporter extends org.xml.sax.helpers.DefaultHandler
        implements Runnable {

    /** Tag for the debug logger */
    public static final String LOG_TAG = "XMLImporter";

    /**
     * Flag indicating how to merge items from the XML file
     * with those in the database.
     */
    public enum ImportType {
        /** The database should be cleared before importing the file. */
        CLEAN(1),

        /**
         * Any items in the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten, regardless of which one is newer.
         */
        REVERT(2),

        /**
         * Any item is the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten if the modification time of the item
         * in the file is newer.
         */
        UPDATE(3),

        /**
         * Any items in the file with the same internal ID as an
         * item in the Android database should be added as a new item
         * with a newly assigned ID.  Will result in duplicates if the
         * file had been imported before, but is the safest option
         * if importing a different file.
         */
        ADD(5),

        /**
         * Don't actually write anything to the android database.
         * Just read the file to verify the integrity of the data.
         */
        TEST(0);

        private final int intValue;

        ImportType(int value) {
            intValue = value;
        }

        /**
         * @return the value of this enum that can be passed
         * as input data to the {@link XMLImporter}
         */
        public int getIntValue() {
            return intValue;
        }

        /**
         * Find the ImportType corresponding to an integer value that
         * was passed in {@link XMLImporter}&rsquo;s input data
         *
         * @param value the numerical value of the ImportType
         *
         * @return the matching {@link ImportType}
         *
         * @throws IllegalArgumentException if {@code value} does not
         * match any {@link ImportType} value.
         */
        public static ImportType fromInt(int value) {
            for (ImportType type : values()) {
                if (type.intValue == value)
                    return type;
            }
            throw new IllegalArgumentException(
                    "Unknown ImportType value: " + value);
        }

    }

    /**
     * XML parsing states
     */
    private enum ParseState {
        /** Starting state */
        NONE(null, null, false),
        /** Root node */
        DOCUMENT(DOCUMENT_TAG, NONE, false),
        /**
         * Preferences section.  Transitions from this state
         * are handled specially, because each child element
         * is the name of a preference.
         */
        PREFERENCES(PREFERENCES_TAG, DOCUMENT, false),
        /**
         * An item within the Preferences section.  Its tag name
         * is arbitrary; its text content is the value
         * which may be any type represented as a string.
         */
        PREFERENCE("", PREFERENCES, true),
        /** Metadata section */
        METADATA(METADATA_TAG, DOCUMENT, false),
        /**
         * Metadata item
         * <p><b>WARNING:</b> This is ambiguous with the note item element!</p>
         */
        METADATUM(METADATA_ITEM, METADATA, true),
        /** Categories section */
        CATEGORIES(CATEGORIES_TAG, DOCUMENT, false),
        /** Category item */
        CATEGORY(CATEGORIES_ITEM, CATEGORIES, true),
        /** Notes section */
        NOTES(NOTES_TAG, DOCUMENT, false),
        /**
         * Wrapper for a note
         * <p><b>WARNING:</b> This is ambiguous with the metadata item element!</p>
         */
        NOTE_HEAD(NOTE_ITEM, NOTES, false),
        /** Creation time */
        CREATED(NOTE_CREATED, NOTE_HEAD, true),
        /** Modification time */
        MODIFIED(NOTE_MODIFIED, NOTE_HEAD, true),
        /** Note */
        NOTE(NOTE_NOTE, NOTE_HEAD, true);

        /** The tag associated with a given state */
        private final String elementTag;
        /**
         * The expected parent state of this one, or
         * {@code null} for the starting state
         */
        private final ParseState parentState;
        /** Whether we expect text content in this state */
        private final boolean hasText;

        ParseState(String tag,
                   ParseState parent,
                   boolean hasText) {
            elementTag = tag;
            parentState = parent;
            this.hasText = hasText;
        }

        /** @return whether this state expects text content */
        public boolean hasText() {
            return hasText;
        }

        /**
         *  @return this state&rsquo;s element tag name.
         *  May be the empty string for a preference item.
         */
        public String getElementTag() {
            return elementTag;
        }

        /**
         * @return the parent of this state.  Should be used
         * when encountering the closing tag of this state.
         */
        public ParseState getParent() {
            return parentState;
        }

        /**
         * Test whether this state is valid for a given parent state.
         *
         * @param currentState the current parent state
         *
         * @throws IllegalStateException if this state is not valid
         * for the given parent state
         */
        public void checkParent(ParseState currentState)
                throws IllegalStateException {
            if (parentState != currentState) {
                String source = (this == PREFERENCE) ? "preference item"
                        : String.format("<%s> element", elementTag);
                String parent = (parentState.elementTag == null)
                        ? "at the document root"
                        : ((parentState == PREFERENCE)
                        ? "within a preference item"
                        : String.format(Locale.US, "within <%s>",
                        parentState.elementTag));
                throw new IllegalStateException(String.format(Locale.US,
                        "%s is not valid %s", source, parent));
            }
        }

        /**
         * @param currentState the current parent state, which is used
         * to disambiguate elements of the same name in different
         * hierarchies or to check whether we are parsing a preference
         * item.
         * @param tag the name of an XML tag
         *
         * @return the state corresponding to a given tag,
         * or {@code null} if the tag does not correspond to any defined
         * state.  In the latter case, the parser should use
         * {@link #PREFERENCE} iff the parent state is
         * {@link #PREFERENCES}, otherwise throw an error.
         */
        public static ParseState fromTag(ParseState currentState, String tag) {
            if (currentState == PREFERENCES)
                // No point in comparing; all preference children are custom names.
                return PREFERENCE;

            List<ParseState> matches = new ArrayList<>(2);
            for (ParseState state : values()) {
                if (state.elementTag == null)
                    continue;
                if (state.elementTag.equals(tag)) {
                    if (currentState == state.parentState)
                        return state;
                    matches.add(state);
                }
            }
            if (matches.isEmpty())
                return null;
            Log.w(LOG_TAG, String.format(Locale.US,
                    "ParseState: found ambiguous tag \"%s\" within <%s>",
                    tag, currentState.elementTag));
            // Return the first match
            return matches.get(0);
        }

    }

    /** Current parsing state when running as a SAX handler */
    private ParseState currentState = ParseState.NONE;

    /** Modes of operation, for progress bar updates */
    public enum OpMode {
        START, SETTINGS, CATEGORIES, ITEMS, FINISH
    }
    /**
     * Text to pass to the {@link ProgressBarUpdater} for each
     * mode of operation.  This may be overridden when the class
     * is initialized.
     */
    private static final Map<OpMode,String> modeText = new HashMap<>();
    static {
        modeText.put(OpMode.START, "Starting\u2026");
        modeText.put(OpMode.SETTINGS, "Importing application settings\u2026");
        modeText.put(OpMode.CATEGORIES, "Importing categories\u2026");
        modeText.put(OpMode.ITEMS, "Importing notes\u2026");
        modeText.put(OpMode.FINISH, "Finishing\u2026");
    }

    /**
     * Change the text associated with a mode of operation.
     *
     * @param mode the mode whose text to change
     * @param text the new text to use
     */
    static void setModeText(OpMode mode, String text) {
        modeText.put(mode, text);
    }

    /** Per-entry database operations */
    private enum Operation {
        /** New item; add the record */
        INSERT,
        /** Existing item; replace the record */
        UPDATE,
        /** Existing item; do not modify it */
        SKIP
    }

    /** Category entry from the XML file */
    protected static class CategoryEntry {
        /** The category ID in the XML file */
        long id;
        String name;
        /** If merging or adding, the new category ID in the Android database */
        long newID;
    }

    /** Preferences passed to the {@link #importData} method */
    private final NotePreferences prefs;

    /** Repository passed to the {@link #importData} method */
    private final NoteRepository repository;

    /** The name of the XML file being read, if known */
    private final String xmlFileName;

    /** Input stream passed to the {@link #importData} method */
    private final InputStream inStream;

    /** The version of the XML export file */
    private int version = 1;

    /** Import type passed to the {@link #importData} method */
    private final ImportType importType;

    /** Whether to import private records */
    private final boolean importPrivate;

    /** The encryption object used to decrypt records from the XML file */
    private final StringEncryption decryptor;

    /**
     * The encryption object used to encrypt private records for the database
     */
    private final StringEncryption encryptor;

    /** Progress updater passed to the {@link #importData} method */
    private final ProgressBarUpdater progressUpdater;

    /**
     * The total number of records declared in the XML file,
     * or -1 if its size is unknown.
     */
    private int totalRecords = -1;

    /** The current number of records processed */
    private int processedRecords = 0;

    /** Categories from the XML file, mapped by the XML id */
    protected final Map<Long,CategoryEntry> categoriesByID =
        new HashMap<>();

    /** Flag indicating we have read the categories section */
    private boolean categoriesRead = false;

    /** Flag indicating we have read the NoteList section */
    private boolean noteListRead = false;

    /** Next free record ID (counting both the XML file and local database) */
    private long nextFreeRecordID = 1;

    /**
     * Create a new importer instance with the provided parameters.
     * This will be passed to the repository to run in a single
     * transaction.
     *
     * @param prefs the NotePad preferences
     * @param repository The repository to which we should write records.
     * @param xmlFileName the name of the XML file being read, if known
     * (may be {@code null}).
     * @param inStream the stream from which we should read the XML data.
     * @param importType how to merge items from the XML file
     * with those in the database.
     * @param importPrivate whether to include private records and the
     * password hash in the import.
     * @param decryptor an encryption object used to decrypt encrypted
     * records from the XML file, or {@code null} if no password
     * was given for the XML file or we are not importing private records.
     * @param encryptor an encryption object used to encrypt private
     * records before writing to the database, or {@code null} to leave
     * private records unencrypted or if not importing private records.
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    private XMLImporter(NotePreferences prefs,
                        NoteRepository repository,
                        String xmlFileName,
                        InputStream inStream,
                        ImportType importType,
                        boolean importPrivate,
                        StringEncryption decryptor,
                        StringEncryption encryptor,
                        ProgressBarUpdater progressUpdater) {
        this.prefs = prefs;
        this.repository = repository;
        this.xmlFileName = xmlFileName;
        this.inStream = inStream;
        this.importType = importType;
        this.importPrivate = importPrivate;
        this.decryptor = decryptor;
        this.encryptor = encryptor;
        this.progressUpdater = progressUpdater;
    }

    /**
     * Import the preferences, metadata, categories, and notes
     * from an XML file to the database.
     *
     * @param prefs theNotePad preferences
     * @param repository The repository to which we should write records.
     * It should have already been opened by the caller.
     * @param fileName the name of the XML file being read, if known
     * (may be {@code null}).
     * @param inStream the stream from which we should read the XML data.
     * @param importType how to merge items from the XML file
     * with those in the database.
     * @param importPrivate whether to include private records and the
     * password hash in the import.  If there is a password hash in the
     * XML file&rsquo;s metadata, {@code xmlPassword} is checked against
     * it.  If there are any encrypted records, they are decrypted first,
     * and then if the current encryption key has been provided they will
     * be re-encrypted before inserting into the database.
     * @param xmlPassword the password with which the XML file was exported,
     * or {@code null} if the XML file contains no encrypted records or
     * we are not importing any private records.
     * @param currentPassword the password with which to encrypt any private
     * records imported, or {@code null} to leave them unencrypted or if
     * we are not importing any private records.
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    public static void importData(NotePreferences prefs,
                                  NoteRepository repository,
                                  String fileName,
                                  InputStream inStream,
                                  ImportType importType,
                                  boolean importPrivate,
                                  String xmlPassword,
                                  String currentPassword,
                                  ProgressBarUpdater progressUpdater)
            throws IOException {
        StringEncryption decryptor = null;
        StringEncryption encryptor = null;
        if (xmlPassword != null) {
            decryptor = new StringEncryption();
            decryptor.setPassword(xmlPassword.toCharArray());
        }
        if (currentPassword != null) {
            encryptor = new StringEncryption();
            encryptor.setPassword(currentPassword.toCharArray());
        }
        try {
            XMLImporter importer = new XMLImporter(prefs, repository,
                    fileName, inStream, importType, importPrivate,
                    decryptor, encryptor, progressUpdater);
            repository.runInTransaction(importer);
            // Final update of the progress meter (unthrottled)
            progressUpdater.updateProgress(modeText.get(OpMode.FINISH),
                    importer.processedRecords, importer.totalRecords, false);
        } catch (UncaughtIOException ue) {
            Log.e(LOG_TAG, "I/O Error reading the XML file", ue);
            throw ue.getCause();
        } finally {
            inStream.close();
        }
    }

    @Override
    public void run() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            parser.parse(inStream, this);
        } catch (IOException iox) {
            throw new UncaughtIOException(iox);
        } catch (ParserConfigurationException ce) {
            throw new XMLParseException(ce.getMessage(), ce);
        } catch (SAXParseException px) {
            throw new XMLParseException(px.getMessage(), xmlFileName,
                    px.getLineNumber(), px.getColumnNumber(), px);
        } catch (SAXException se) {
            throw new XMLParseException(se);
        }
    }

    /** Current location in the input file for any error reporting */
    @NonNull
    private Locator xmlLocator = new Locator() {
        // The default implementation returns unknown lines and columns
        @Override
        public int getLineNumber() { return -1; }
        @Override
        public int getColumnNumber() { return -1; }
        @Override
        public String getPublicId() { return ""; }
        @Override
        public String getSystemId() { return ""; }
    };

    /**
     * The current text being read from the XML file, if expected;
     * otherwise {@code null}.
     */
    private StringBuffer currentText = null;

    /**
     * A {@link Map} of preference items collected from the import file
     * if we are in the &lt;Preferences&gt; section; {@code null} otherwise.
     */
    private Map<String,String> prefsMap = null;

    /**
     * The name of the current preference tag if we are
     * processing preference items.  Null otherwise.
     */
    private String preferenceName = null;

    /**
     * A {@link Map} of metadata items collected from the import file
     * if we are in the &lt;Metadata&gt; section; {@code null} otherwise.
     */
    private Map<String,byte[]> metadata = null;

    /**
     * The current metadatum if we are
     * processing metadata.  Null otherwise.
     */
    private NoteMetadata metadatum = null;

    /**
     * A {@link List} of category items collected from the import file
     * if we are in the &lt;Categories&gt; section; {@code null} otherwise.
     */
    private List<CategoryEntry> categories = null;

    /**
     * The current category entry if we are
     * processing categories.  Null otherwise.
     */
    private CategoryEntry currentCategory = null;

    /**
     * The note currently being processed if we are in a note
     * &ldquo;item&rdquo; element; {@code null} otherwise.
     */
    private NoteItem currentNote = null;

    /**
     * Process text content.  If we are expecting text, adds the
     * characters to {@link #currentText}; otherwise ignores them.
     */
    @Override
    public void characters(char[] chars, int start, int length) {
        if (currentState.hasText())
            currentText.append(chars, start, length);
    }

    /**
     * Attempt to parse an attribute from an element as an integer.
     *
     * @param attributes the attributes of the element
     * @param elementName the name of the element for any error messages
     * @param attrName the name of the attribute to read
     * @param defaultValue the value to return if the attribute is not found,
     * or {@code null} if the attribute is required.
     * @param minAllowed the minimum value allowed for the attribute,
     * or {@code null} if it can be as low as {@value Integer#MIN_VALUE}.
     * @param maxAllowed the maximum value allowed for the attribute,
     * or {@code null} if it can be as high as {@value Integer#MAX_VALUE}.
     *
     * @return the value of the attribute if present, or {@code defaultValue}
     * if missing.
     *
     * @throws XMLParseException if the attribute is not found and
     * {@code defaultValue} is {@code null}, or if its value is less than
     * {@code minAllowed} or greater than {@code maxAllowed}.
     */
    private int parseIntAttribute(Attributes attributes,
                                  String elementName, String attrName,
                                  Integer defaultValue,
                                  Integer minAllowed, Integer maxAllowed)
            throws XMLParseException {
        String attrValue = attributes.getValue(attrName);
        if (attrValue == null) {
            if (defaultValue != null)
                return defaultValue;
            throw new XMLMissingRequiredAttributeException(
                    elementName, attrName, xmlFileName,
                    xmlLocator.getLineNumber(), xmlLocator.getColumnNumber());
        }
        try {
            int value = Integer.parseInt(attrValue);
            if (((minAllowed != null) && (value < minAllowed)) ||
                    ((maxAllowed != null) && (value > maxAllowed)))
                throw new XMLBadValueException(elementName, attrName,
                        attrValue, xmlFileName, xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
            return value;
        } catch (NumberFormatException x) {
            throw new XMLBadValueException(elementName, attrName,
                    attrValue, xmlFileName, xmlLocator.getLineNumber(),
                    xmlLocator.getColumnNumber(), x);
        }
    }

    /**
     * Attempt to parse an attribute from an element as a long.
     *
     * @param attributes the attributes of the element
     * @param elementName the name of the element for any error messages
     * @param attrName the name of the attribute to read
     * @param defaultValue the value to return if the attribute is not found,
     * or {@code null} if the attribute is required.
     * @param minAllowed the minimum value allowed for the attribute,
     * or {@code null} if it can be as low as {@value Integer#MIN_VALUE}.
     * @param maxAllowed the maximum value allowed for the attribute,
     * or {@code null} if it can be as high as {@value Integer#MAX_VALUE}.
     *
     * @return the value of the attribute if present, or {@code defaultValue}
     * if missing.
     *
     * @throws XMLParseException if the attribute is not found and
     * {@code defaultValue} is {@code null}, or if its value is less than
     * {@code minAllowed} or greater than {@code maxAllowed}.
     */
    private long parseLongAttribute(Attributes attributes,
                                  String elementName, String attrName,
                                  Long defaultValue,
                                  Long minAllowed, Long maxAllowed)
            throws XMLParseException {
        String attrValue = attributes.getValue(attrName);
        if (attrValue == null) {
            if (defaultValue != null)
                return defaultValue;
            throw new XMLMissingRequiredAttributeException(
                    elementName, attrName, xmlFileName,
                    xmlLocator.getLineNumber(), xmlLocator.getColumnNumber());
        }
        try {
            long value = Long.parseLong(attrValue);
            if (((minAllowed != null) && (value < minAllowed)) ||
                    ((maxAllowed != null) && (value > maxAllowed)))
                throw new XMLBadValueException(elementName, attrName,
                        attrValue, xmlFileName, xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
            return value;
        } catch (NumberFormatException x) {
            throw new XMLBadValueException(elementName, attrName,
                    attrValue, xmlFileName, xmlLocator.getLineNumber(),
                    xmlLocator.getColumnNumber(), x);
        }
    }

    /**
     * Attempt to read a string attribute from an element.
     * It is assumed that the value is required and must not be blank.
     *
     * @param attributes the attributes of the element
     * @param elementName the name of the element for any error messages
     * @param attrName the name of the attribute to read
     *
     * @return the value of the attribute.
     *
     * @throws XMLParseException if the attribute is not found or
     * is an empty string.
     */
    private String getRequiredStringAttribute(
            Attributes attributes,
            String elementName, String attrName)
            throws XMLParseException {
        String attrValue = attributes.getValue(attrName);
        if (attrValue == null) {
            if (xmlLocator == null)
                throw new XMLMissingRequiredAttributeException(
                        elementName, attrName, xmlFileName,
                        xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
            throw new XMLMissingRequiredAttributeException(
                        elementName, attrName, xmlFileName,
                    xmlLocator.getLineNumber(), xmlLocator.getColumnNumber());
        }
        if (attrValue.length() == 0)
            throw new XMLBadValueException(elementName, attrName,
                    attrValue, xmlFileName, xmlLocator.getLineNumber(),
                    xmlLocator.getColumnNumber());
        return attrValue;
    }

    /**
     * Attempt to parse an attribute from an element as an ISO timestamp
     * ({@link Instant}).
     *
     * @param attributes the attributes of the element
     * @param elementName the name of the element for any error messages
     * @param attrName the name of the attribute to read
     * @param required whether the attribute is required {@code true}
     * or optional {@code false}.
     *
     * @return the value of the attribute if present, or {@code defaultValue}
     * if missing.
     *
     * @throws XMLParseException if the attribute is not found and
     * {@code defaultValue} is {@code null}.
     */
    private Instant parseTimestampAttribute(
            Attributes attributes,
            String elementName, String attrName,
            boolean required)
            throws XMLParseException {
        String attrValue = attributes.getValue(attrName);
        if (attrValue == null) {
            if (required)
                throw new XMLMissingRequiredAttributeException(
                        elementName, attrName, xmlFileName,
                        xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
            return null;
        }
        try {
            return Instant.parse(attrValue);
        } catch (NumberFormatException x) {
            throw new XMLBadValueException(elementName, attrName,
                    attrValue, xmlFileName, xmlLocator.getLineNumber(),
                    xmlLocator.getColumnNumber(), x);
        }
    }

    /**
     * Get the document&rsquo;s {@link Locator}
     *
     * @param locator the {@link Locator} used in the SAX parser
     */
    @Override
    public void setDocumentLocator(Locator locator) {
        Log.d(LOG_TAG, ".setDocumentLocator");
        xmlLocator = locator;
    }

    /**
     * Process the start of the document.
     * For debug tracing only.
     */
    @Override
    public void startDocument() {
        Log.d(LOG_TAG, ".startDocument");
    }

    /**
     * Process the end of the document.  At this time we can set any
     * preferences since we should be done with database operations.
     */
    @Override
    public void endDocument() {
        Log.d(LOG_TAG, ".endDocument");
        if (prefsMap != null)
            setPreferences();
    }

    /**
     * Process element start tags.  This checks and updates the
     * parsing state accordingly, and initializes any fields
     * that may be needed to process the element.
     *
     * @param uri the namespace URI (not used)
     * @param localName local name of the element (not used; empty)
     * @param qName the full name of the element
     * @param attributes any attributes attached to the element
     *
     * @throws XMLParseException if we encounter an unexpected element
     * or do not find an element&rsquo;s required attributes
     * or if any attribute value is invalid.
     */
    @Override
    public void startElement(String uri, String localName,
                             String qName, Attributes attributes)
            throws XMLParseException {
        Log.d(LOG_TAG, String.format(Locale.US,
                ".startElement(<%s>)", qName));

        // Special handling for preference items
        if (currentState == ParseState.PREFERENCES) {
            preferenceName = qName;
            currentState = ParseState.PREFERENCE;
        }

        else {
            ParseState nextState = ParseState.fromTag(currentState, qName);
            if (nextState == null)
                throw new XMLUnexpectedElementException(qName, true,
                        (currentState == ParseState.PREFERENCE)
                                ? preferenceName
                                : currentState.getElementTag(),
                        xmlFileName, xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
            nextState.checkParent(currentState);
            currentState = nextState;
        }

        if (currentState.hasText())
            currentText = new StringBuffer();

        String attrValue;
        switch (currentState) {

            case DOCUMENT:
                version = parseIntAttribute(attributes, DOCUMENT_TAG,
                        ATTR_VERSION, 1, 1, 2);
                totalRecords = parseIntAttribute(attributes, DOCUMENT_TAG,
                        ATTR_TOTAL_RECORDS, -1, 0, null);
                String dbVersion = attributes.getValue(ATTR_DB_VERSION);
                if (dbVersion == null)
                    dbVersion = "(unknown)";
                String exportTime = attributes.getValue(ATTR_EXPORTED);
                if (exportTime == null)
                    exportTime = "(unknown)";
                Log.i(LOG_TAG, String.format(Locale.US,
                        "Beginning XML document, export version %d,"
                                + " DB version %s, exported at %s,"
                                + " %d total records",
                        version, dbVersion, exportTime, totalRecords));
                break;

            case PREFERENCES:
                if (prefsMap != null)
                    throw new XMLParseException(String.format(Locale.US,
                            "Multiple <%s> sections in document", qName),
                            xmlFileName, xmlLocator.getLineNumber(),
                            xmlLocator.getColumnNumber());
                int prefsCount = parseIntAttribute(attributes,
                        PREFERENCES_TAG, ATTR_COUNT, -1, 0, null);
                if (prefsCount <= 0)
                    prefsMap = new HashMap<>();
                else
                    prefsMap = new HashMap<>(prefsCount);
                break;

            case METADATA:
                if (metadata != null)
                    throw new XMLParseException(String.format(Locale.US,
                            "Multiple <%s> sections in document", qName),
                            xmlFileName, xmlLocator.getLineNumber(),
                            xmlLocator.getColumnNumber());
                int metaCount = parseIntAttribute(attributes,
                        METADATA_TAG, ATTR_COUNT, -1, 0, null);
                if (metaCount <= 0)
                    metadata = new HashMap<>();
                else
                    metadata = new HashMap<>(metaCount);
                break;

            case METADATUM:
                metadatum = new NoteMetadata();
                metadatum.setId(parseLongAttribute(attributes,
                        METADATA_ITEM, ATTR_ID, null, 0L, null));
                metadatum.setName(getRequiredStringAttribute(attributes,
                        METADATA_ITEM, ATTR_NAME));
                break;

            case CATEGORIES:
                if (categoriesRead)
                    throw new XMLParseException(String.format(Locale.US,
                            "Multiple <%s> sections in document", qName),
                            xmlFileName, xmlLocator.getLineNumber(),
                            xmlLocator.getColumnNumber());
                int catCount = parseIntAttribute(attributes,
                        CATEGORIES_TAG, ATTR_COUNT, -1, 0, null);
                if (catCount < 0)
                    categories = new ArrayList<>();
                else
                    categories = new ArrayList<>(catCount);
                break;

            case CATEGORY:
                currentCategory = new CategoryEntry();
                currentCategory.id = parseLongAttribute(attributes,
                        CATEGORIES_ITEM, ATTR_ID, null, 0L, null);
                break;

            case NOTES:
                if (noteListRead)
                    throw new XMLParseException(String.format(Locale.US,
                            "Multiple <%s> sections in document", qName),
                            xmlFileName, xmlLocator.getLineNumber(),
                            xmlLocator.getColumnNumber());
                // Unused ... for now
                int noteCount = parseIntAttribute(attributes,
                        NOTES_TAG, ATTR_COUNT, -1, 0, null);
                // For this section we currently don't do anything with
                // the count; the only value we need for the progress
                // bar was the total record count in the document root.
                long maxNoteId = parseLongAttribute(attributes,
                        NOTES_TAG, ATTR_MAX_ID, -1L, 0L, null);
                startNoteList(maxNoteId);
                break;

            case NOTE_HEAD:
                currentNote = new NoteItem();
                currentNote.setId(parseLongAttribute(attributes,
                        NOTE_ITEM, ATTR_ID, null, 0L, null));
                currentNote.setCategoryId(parseLongAttribute(attributes,
                        NOTE_ITEM, ATTR_CATEGORY_ID, null, 0L, null));
                if (Boolean.parseBoolean(attributes.getValue(ATTR_PRIVATE)))
                    currentNote.setPrivate(parseIntAttribute(attributes,
                            NOTE_ITEM, ATTR_ENCRYPTION,
                            StringEncryption.NO_ENCRYPTION,
                            StringEncryption.NO_ENCRYPTION,
                            StringEncryption.MAX_SUPPORTED_ENCRYPTION));
                else
                    currentNote.setPrivate(0);
                break;

            case CREATED:
                currentNote.setCreateTime(parseTimestampAttribute(
                        attributes, NOTE_CREATED, ATTR_TIME, true));
                break;

            case MODIFIED:
                currentNote.setModTime(parseTimestampAttribute(
                        attributes, NOTE_MODIFIED, ATTR_TIME, true));
                break;

            default:
                break;

        }

    }

    /**
     * Process element end tags.  If we were in the middle of any element
     * that has child elements or text, they can be closed now.
     *
     * @param uri the namespace URI (not used)
     * @param localName local name of the element (not used; empty)
     * @param qName the full name of the element
     *
     * @throws XMLParseException if {@code qName} does not represent the
     * current element being processed or if we fail to parse any
     * intervening text content.
     */
    @Override
    public void endElement(String uri, String localName, String qName)
            throws XMLParseException {
        Log.d(LOG_TAG, String.format(Locale.US,
                ".endElement(</%s>)", qName));

        // Special handling for preference items
        if (currentState == ParseState.PREFERENCE) {
            if (!preferenceName.equals(qName))
                throw new XMLUnexpectedElementException(qName, false,
                        preferenceName, xmlFileName,
                        xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
        }

        else {
            if (!currentState.getElementTag().equals(qName))
                throw new XMLUnexpectedElementException(qName, false,
                        currentState.getElementTag(),
                        xmlFileName, xmlLocator.getLineNumber(),
                        xmlLocator.getColumnNumber());
        }

        String textContent = currentState.hasText()
                ? currentText.toString() : null;

        switch (currentState) {

            case PREFERENCE:
                prefsMap.put(preferenceName, textContent);
                preferenceName = null;
                processedRecords++;
                progressUpdater.updateProgress(modeText.get(OpMode.SETTINGS),
                        processedRecords, totalRecords, true);
                break;

            case METADATA:
                if (metadata != null)
                    readMetadata();
                break;

            case METADATUM:
                metadatum.setValue(decodeBase64(textContent));
                metadata.put(metadatum.getName(), metadatum.getValue());
                metadatum = null;
                processedRecords++;
                progressUpdater.updateProgress(modeText.get(OpMode.SETTINGS),
                        processedRecords, totalRecords, true);
                break;

            case CATEGORIES:
                if (categories != null)
                    mergeCategories();
                categories = null;
                break;

            case CATEGORY:
                currentCategory.name = textContent;
                categories.add(currentCategory);
                currentCategory = null;
                break;

            case NOTES:
                noteListRead = true;
                break;

            case NOTE_HEAD:
                mergeNote();
                currentNote = null;
                break;

            case NOTE:
                if (currentNote.isEncrypted()) {
                    currentNote.setEncryptedNote(
                            decodeBase64(textContent));
                } else {
                    currentNote.setNote(textContent);
                }
                break;

            default:
                break;
        }

        currentText = null;
        currentState = currentState.getParent();
    }

    /**
     * RFC 3548 sec. 3 and 4 compatible,
     * reversed ASCII value to Base64 value.
     * Entries with -1 are not valid Base64 characters.
     * Entries with -2 are skipped whitespace.
     */
    private static final byte[] BASE64_VALUES = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -2, -2, -1, -1, -2, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, 62, -1, 63,
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
        -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63,
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1 };

    /** Convert a Base64 string to a stream of bytes */
    public static byte[] decodeBase64(String text) {
        if (text == null)
            return null;
        ByteBuffer bb = ByteBuffer.allocate(text.length());
        int temp = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c > BASE64_VALUES.length) ||
                    (BASE64_VALUES[c] == -1))
                throw new IllegalArgumentException(
                        "Invalid Base64 character: " + c);
            if (BASE64_VALUES[c] == -2)
                continue;
            temp = (temp << 6) + BASE64_VALUES[c];
            bits += 6;
            // Store bytes once we have three
            if (bits >= 24) {
                bb.put((byte) (temp >> 16));
                bb.put((byte) (temp >> 8));
                bb.put((byte) temp);
                temp = 0;
                bits = 0;
            }
        }
        // Special handling for the last byte(s).  The encoder would
        // have emitted characters to cover full bytes.
        switch (bits) {
        case 12:
            bb.put((byte) (temp >> 4));
            break;
        case 18:
            bb.put((byte) (temp >> 10));
            bb.put((byte) (temp >> 2));
            break;
        }
        byte[] result = new byte[bb.position()];
        System.arraycopy(bb.array(), 0, result, 0, result.length);
        return result;
    }

    /** Quick implementation of StringUtils.isEmpty(String) */
    public static boolean isEmpty(String s) {
        return (s == null) || (s.length() == 0);
    }

    /**
     * Set the current preferences by the ones read from the XML file.
     */
    private void setPreferences() {
        Log.d(LOG_TAG, ".setPreferences(" + prefsMap.keySet() + ")");
        if (importType == ImportType.TEST)
            // Don't set anything in test mode.
            return;

        NotePreferences.Editor prefsEditor = prefs.edit();
        if (prefsMap.containsKey(NPREF_SORT_ORDER)) {
            try {
                prefsEditor.setSortOrder(
                        Integer.parseInt(prefsMap.get(NPREF_SORT_ORDER)));
            } catch (NumberFormatException x) {
                Log.e(LOG_TAG, "Invalid sort order index: "
                        + prefsMap.get(NPREF_SORT_ORDER));
                // Ignore this change
            }
        }
        if (prefsMap.containsKey(NPREF_SHOW_CATEGORY))
            prefsEditor.setShowCategory(
                    Boolean.parseBoolean(prefsMap.get(NPREF_SHOW_CATEGORY)));
        /*
         * Note that we are not changing whether private/encrypted records
         * are shown.  If the user wanted encrypted records, he should have
         * set the password in the PreferencesActivity both when exporting
         * and importing the file.
         */
        if (prefsMap.containsKey(NPREF_SELECTED_CATEGORY)) {
            try {
                prefsEditor.setSelectedCategory(
                        Long.parseLong(prefsMap.get(NPREF_SELECTED_CATEGORY)));
            } catch (NumberFormatException x) {
                Log.e(LOG_TAG, "Invalid category index: "
                        + prefsMap.get(NPREF_SELECTED_CATEGORY), x);
                // Ignore this change
            }
        }

        prefsEditor.finish();
    }

    /**
     * Import metadata from the XML file.
     * At this time we only support checking the password hash,
     * but this <i>must</i> be done before attempting to import any
     * encrypted records.
     *
     * @throws RuntimeException if {@code importPrivate} is true
     * and the metadata includes {@value StringEncryption#METADATA_PASSWORD_HASH}
     * but {@code xmlPassword} is {@code null} or does not match
     * the password hash.
     */
    private void readMetadata() {
        Log.d(LOG_TAG, ".readMetadata(" + metadata.keySet() + ")");
        if (importPrivate && metadata.containsKey(
                StringEncryption.METADATA_PASSWORD_HASH)) {
            if (decryptor == null)
                throw new PasswordRequiredException("Import file is password-"
                        + "protected but no password was provided");
            if (!decryptor.checkPassword(metadata.get(
                    StringEncryption.METADATA_PASSWORD_HASH)))
                throw new PasswordMismatchException("Password does not match"
                        + " the one used to encrypt the import file");
        }
    }

    /**
     * Merge the category list from the XML file with the database.
     */
    private void mergeCategories() {
        Log.d(LOG_TAG, ".mergeCategories(" + importType + ")");
        String opText = modeText.get(OpMode.CATEGORIES);
        long maxId = -1;
        for (CategoryEntry category : categories) {
            if (category.id > maxId)
                maxId = category.id;
        }
        // Read in the current list of categories
        Map<Long,String> categoryIDMap = new HashMap<>();
        Map<String,Long> categoryNameMap = new HashMap<>();
        if (importType == ImportType.CLEAN) {
            Log.d(LOG_TAG,
                    ".mergeCategories: removing all existing categories");
            repository.deleteAllCategories();
        } else {
            for (NoteCategory category : repository.getCategories()) {
                categoryIDMap.put(category.getId(), category.getName());
                categoryNameMap.put(category.getName(), category.getId());
                if (category.getId() > maxId)
                    maxId = category.getId();
            }
        }

        switch (importType) {

            case CLEAN:
                // There are no pre-existing categories
                for (CategoryEntry fileCategory : categories) {
                    // Skip the Unfiled category
                    if (fileCategory.id == NoteCategory.UNFILED) {
                        fileCategory.newID = fileCategory.id;
                    } else {
                        Log.d(LOG_TAG, String.format(
                                ".mergeCategories: adding %d \"%s\"",
                                fileCategory.id, fileCategory.name));
                        NoteCategory localCategory = new NoteCategory();
                        localCategory.setId(fileCategory.id);
                        localCategory.setName(fileCategory.name);
                        localCategory = repository.insertCategory(localCategory);
                        fileCategory.newID = localCategory.getId();
                    }
                    categoriesByID.put(fileCategory.id, fileCategory);
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case REVERT:
                /*
                 * First remove all conflicting names.
                 * DO NOT add new categories in the same loop,
                 * as that may lead to inconsistencies between
                 * what's in the database and our maps.
                 */
                for (CategoryEntry fileCategory : categories) {
                    if ((categoryNameMap.containsKey(fileCategory.name)) &&
                            (categoryNameMap.get(fileCategory.name)
                                    != fileCategory.id)) {
                        long oldId = categoryNameMap.get(fileCategory.name);
                        Log.d(LOG_TAG, String.format(Locale.US,
                                ".mergeCategories: \"%s\" already exists"
                                        + " with ID %d; deleting it.",
                                fileCategory.name, oldId));
                        repository.deleteCategory(oldId);
                        categoryIDMap.remove(oldId);
                        categoryNameMap.remove(fileCategory.name);
                    }
                }
                for (CategoryEntry fileCategory : categories) {
                    if (categoryIDMap.containsKey(fileCategory.id)) {
                        if (!categoryIDMap.get(fileCategory.id)
                                .equals(fileCategory.name)) {
                            Log.d(LOG_TAG, String.format(Locale.US,
                                    ".mergeCategories: replacing"
                                            + " \"%s\" with \"%s\"",
                                    categoryIDMap.get(fileCategory.id),
                                    fileCategory.name));
                            repository.updateCategory(fileCategory.id,
                                    fileCategory.name);
                        }
                        fileCategory.newID = fileCategory.id;
                    }
                    else {
                        Log.d(LOG_TAG, String.format(Locale.US,
                                ".mergeCategories: adding %d \"%s\"",
                                fileCategory.id, fileCategory.name));
                        NoteCategory localCategory = new NoteCategory();
                        localCategory.setId(fileCategory.id);
                        localCategory.setName(fileCategory.name);
                        localCategory = repository.insertCategory(localCategory);
                        fileCategory.newID = localCategory.getId();
                    }
                    categoriesByID.put(fileCategory.id, fileCategory);
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case UPDATE:
                /*
                 * Overwrite if newer.  But since categories
                 * have no time stamp, this item acts like merge.
                 */
            case ADD:
                for (CategoryEntry fileCategory : categories) {
                    if (categoryNameMap.containsKey(fileCategory.name)) {
                        fileCategory.newID =
                                categoryNameMap.get(fileCategory.name);
                    } else {
                        Log.d(LOG_TAG, String.format(Locale.US,
                                ".mergeCategories: adding \"%s\"",
                                fileCategory.name));
                        NoteCategory localCategory = new NoteCategory();
                        localCategory.setId(fileCategory.id);
                        localCategory.setName(fileCategory.name);
                        // Use a new ID if there is a conflict
                        if (categoryIDMap.containsKey(fileCategory.id))
                            localCategory.setId(++maxId);
                        localCategory = repository.insertCategory(localCategory);
                        fileCategory.newID = localCategory.getId();
                    }
                    categoriesByID.put(fileCategory.id, fileCategory);
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case TEST:
                // Do nothing.
                for (CategoryEntry fileCategory : categories) {
                    fileCategory.newID = fileCategory.id;
                    categoriesByID.put(fileCategory.newID, fileCategory);
                }
                processedRecords += categories.size();
                progressUpdater.updateProgress(opText,
                        processedRecords, totalRecords, true);
                break;
            }

        categoriesRead = true;
    }

    /**
     * Prepare to import notes.
     *
     * @param maxId the maximum ID read from the XML section header, if any;
     * otherwise this should be -1.
     */
    private void startNoteList(long maxId) {
        if (!categoriesRead)
            throw new IllegalStateException(String.format(Locale.US,
                    "<%s> section encountered before <%s>",
                    NOTES_TAG, CATEGORIES_TAG));
        if ((encryptor != null) && !encryptor.checkPassword(repository))
            throw new PasswordMismatchException(
                    "Current password is incorrect");
        if (importType == ImportType.CLEAN) {
            Log.d(LOG_TAG, "Removing all existing notes");
            repository.deleteAllNotes();
        }
        nextFreeRecordID = Math.max(maxId, repository.getMaxNoteId()) + 1;
    }

    /**
     * Merge the current note from the XML file into the database.
     *
     * @throws RuntimeException if we fail to encrypt a private note.
     */
    private void mergeNote() {
        if (currentNote.isPrivate() && !importPrivate) {
            // Skip private records
            processedRecords++;
            return;
        }

        if (categoriesByID.containsKey(currentNote.getCategoryId())) {
            currentNote.setCategoryId(categoriesByID.get(
                    currentNote.getCategoryId()).newID);
        } else {
            Log.d(LOG_TAG, String.format(Locale.US,
                    "Note #%d's category #%d was not imported",
                    currentNote.getId(), currentNote.getCategoryId()));
            currentNote.setCategoryId(NoteCategory.UNFILED);
        }

        if (currentNote.isEncrypted()) {
            if (decryptor == null)
                throw new PasswordRequiredException(
                        "No password provided for decrypting private records");
            try {
                currentNote.setNote(decryptor.decrypt(
                        currentNote.getEncryptedNote()));
                // Temporarily mark unencrypted
                currentNote.setPrivate(StringEncryption.NO_ENCRYPTION);
            } catch (EncryptionException e) {
                throw new EncryptionException(String.format(Locale.US,
                        "Failed to decrypt note #%d",
                        currentNote.getId()), e);
            }
        }
        if (currentNote.isPrivate() && (encryptor != null)) {
            // Re-encrypt if possible
            try {
                currentNote.setEncryptedNote(encryptor.encrypt(
                        currentNote.getNote()));
                currentNote.setPrivate(StringEncryption.encryptionType());
            } catch (EncryptionException e) {
                throw new EncryptionException(String.format(Locale.US,
                        "Failed to encrypt note #%d",
                        currentNote.getId()), e);
            }
        }

        NoteItem existingRecord = null;
        if (importType != ImportType.CLEAN) {
            existingRecord = repository.getNoteById(currentNote.getId());
            if ((existingRecord != null) && existingRecord.isEncrypted()) {
                if (encryptor == null) {
                    /*
                     * Since we don't know the content,
                     * assume it's different from anything else.
                     */
                    existingRecord.setNote(UUID
                            .nameUUIDFromBytes(existingRecord
                                    .getEncryptedNote()).toString());
                } else try {
                    existingRecord.setNote(encryptor.decrypt(
                            existingRecord.getEncryptedNote()));
                } catch (EncryptionException e) {
                    throw new RuntimeException(String.format(Locale.US,
                            "Failed to decrypt existing note #%d",
                            existingRecord.getId()), e);
                }
            }
        }

        // Assume we're going to insert a new record by default
        Operation op = Operation.INSERT;
        switch (importType) {

            case CLEAN:
                // All items are new
                break;

            case REVERT:
                // Overwrite if it's the same item (same ID and creation time)
                if (existingRecord != null) {
                    if (existingRecord.getCreateTime()
                            .equals(currentNote.getCreateTime()))
                        op = Operation.UPDATE;
                    else
                        // Not the same item!  Assign a new ID.
                        currentNote.setId(nextFreeRecordID++);
                }
                break;

            case UPDATE:
                // Overwrite if it's the same item _and_ newer
                if (existingRecord != null) {
                    if (existingRecord.getCreateTime()
                            .equals(currentNote.getCreateTime())) {
                        if (currentNote.getModTime().isAfter(
                                existingRecord.getModTime()))
                            op = Operation.UPDATE;
                        else
                            op = Operation.SKIP;
                    } else {
                        // Not the same item!  Assign a new ID.
                        currentNote.setId(nextFreeRecordID++);
                    }
                }
                break;

            case ADD:
                // All items are new, but may need a new ID
                if (existingRecord != null)
                    currentNote.setId(nextFreeRecordID++);
                break;

            case TEST:
                // Do nothing
                op = Operation.SKIP;
                break;

        }

        switch (op) {

            case INSERT:
                repository.insertNote(currentNote);
                break;

            case UPDATE:
                repository.updateNote(currentNote);
                break;

        }

        processedRecords++;
        progressUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                processedRecords, totalRecords, true);
    }

}
