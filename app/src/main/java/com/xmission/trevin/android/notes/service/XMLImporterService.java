/*
 * Copyright © 2014–2025 Trevin Beattie
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

import java.io.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.*;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.NoteCategoryColumns;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.app.IntentService;
import android.content.*;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;

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
public class XMLImporterService extends IntentService
        implements ProgressReportingService {

    public static final String LOG_TAG = "XMLImporterService";

    /** The name of the Intent extra data that holds the import type */
    public static final String XML_IMPORT_TYPE =
        "com.xmission.trevin.android.notes.XMLImportType";

    /**
     * The name of the Intent extra that indicates whether to
     * import private records.
     */
    public static final String IMPORT_PRIVATE =
        "com.xmission.trevin.android.notes.XMLImportPrivate";

    /**
     * The name of the Intent extra data that holds
     * the password for the backup
     */
    public static final String OLD_PASSWORD =
        "com.xmission.trevin.android.notes.XMLImportPassword";

    /** The current mode of operation */
    public enum OpMode {
        PARSING, SETTINGS, CATEGORIES, ITEMS
    }
    private OpMode currentMode = OpMode.PARSING;

    /** The current number of entries imported */
    private int importCount = 0;

    /** The total number of entries to be imported */
    private int totalCount = 0;

    /** The Note Pad database */
    NoteRepository repository = null;

    /**
     * Category entry from the XML file
     */
    protected static class CategoryEntry {
        /** The category ID in the XML file */
        long id;
        String name;
        /** If adding, the new category ID in the Android database */
        long newID;
    }

    /** Categories from the XML file, mapped by the XML id */
    protected Map<Long,CategoryEntry> categoriesByID =
        new HashMap<>();

    /** Next free record ID (counting both the Palm and Android databases)
     * @deprecated  */
    private long nextFreeRecordID = 1;

    public class ImportBinder extends Binder {
        public XMLImporterService getService() {
            Log.d(LOG_TAG, "ImportBinder.getService()");
            return XMLImporterService.this;
        }
    }

    private final ImportBinder binder = new ImportBinder();

    /** Create the exporter service with a named worker thread */
    public XMLImporterService() {
        super(XMLImporterService.class.getSimpleName());
        Log.d(LOG_TAG, "created");
        // If we die in the middle of an import, restart the request.
        setIntentRedelivery(true);
    }

    /**
     * Set the repository to be used by this service.
     * This is meant for UI tests to override the repository with a mock;
     * if not called explicitly, the activity will use the regular
     * repository implementation.
     *
     * @param repository the repository to use for notes
     */
    public void setRepository(@NonNull NoteRepository repository) {
        if (this.repository != null) {
            if (this.repository == repository)
                return;
            throw new IllegalStateException(String.format(
                    "Attempted to set the repository to %s"
                    + " when it had previously been set to %s",
                    repository.getClass().getCanonicalName(),
                    this.repository.getClass().getCanonicalName()));
        }
    }

    /**
     * For the import binder:
     * @return the stage of import we're working on
     */
    @Override
    public String getCurrentMode() {
        switch (currentMode) {
        case PARSING:
            return getString(R.string.ProgressMessageImportParsing);
        case SETTINGS:
            return getString(R.string.ProgressMessageImportSettings);
        case CATEGORIES:
            return getString(R.string.ProgressMessageImportCategories);
        case ITEMS:
            return getString(R.string.ProgressMessageImportItems);
        default:
            return "";
        }
    }

    /**
     * For the import binder:
     * @return the total number of items in the current stage
     */
    @Override
    public int getMaxCount() {
        return totalCount;
    }

    /**
     * For the import binder:
     * @return the number of items in the current stage we've imported so far
     */
    @Override
    public int getChangedCount() {
        return importCount;
    }

    /** Called when an activity requests an import */
    @Override
    protected void onHandleIntent(Intent intent) {
        // Get the location of the notes.xml file
        String fileLocation = intent.getStringExtra(XML_DATA_FILENAME);
        // Get the import type
        ImportType importType = (ImportType)
                intent.getSerializableExtra(XML_IMPORT_TYPE);
        boolean importPrivate = Boolean.TRUE.equals(
                intent.getSerializableExtra(IMPORT_PRIVATE));
        Log.d(LOG_TAG, String.format(".onHandleIntent(%s,\"%s\")",
                importType, fileLocation));
        importCount = 0;
        totalCount = 0;

        InputStream iStream;

        if (fileLocation.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(fileLocation);
                iStream = getContentResolver()
                        .openInputStream(contentUri);
            } catch (Exception e) {
                showFileOpenError(fileLocation, e);
                return;
            }
        }
        else {
            File dataFile = new File(fileLocation);
            try {
                iStream = new FileInputStream(dataFile);
            } catch (Exception e) {
                showFileOpenError(fileLocation, e);
                return;
            }
        }

        try {
            // Start parsing
            currentMode = OpMode.PARSING;
            // To do: rewrite this code to stream the XML
            // data instead of parsing the whole thing at
            // once, so we can handle very large files.
            DocumentBuilder builder =
                DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Log.d(LOG_TAG, "Parsing " + fileLocation);
            Document document = builder.parse(iStream);
            Element docRoot = document.getDocumentElement();
            if (!docRoot.getTagName().equals(DOCUMENT_TAG))
                throw new SAXException("Document root is not " + DOCUMENT_TAG);

            // Gather and count all of the child elements of the major headings
            Map<String,Element> headers = mapChildren(docRoot);
            Map<String,Element> prefs = null;
            if (headers.containsKey(PREFERENCES_TAG)) {
                prefs = mapChildren(headers.get(PREFERENCES_TAG));
                totalCount += prefs.size();
            }
            List<Element> metadata = null;
            if (headers.containsKey(METADATA_TAG))
                metadata = listChildren(headers.get(METADATA_TAG), "item");
            List<Element> categories = null;
            if (headers.containsKey(CATEGORIES_TAG)) {
                categories = listChildren(headers.get(CATEGORIES_TAG), "category");
                totalCount += categories.size();
            }
            List<Element> notes = null;
            if (headers.containsKey(ITEMS_TAG)) {
                notes = listChildren(headers.get(ITEMS_TAG), "item");
                totalCount += notes.size();
            }

            StringEncryption oldCrypt = null;
            if (importPrivate && (metadata != null)) {
                for (Element e : metadata) {
                    if (e.getAttribute("name").equals(
                            StringEncryption.METADATA_PASSWORD_HASH)) {
                        byte[] oldHash = decodeBase64(getText(e));
                        // Get the password
                        char[] oldPassword =
                            intent.getCharArrayExtra(OLD_PASSWORD);
                        if (oldPassword != null) {
                            oldCrypt = new StringEncryption();
                            oldCrypt.setPassword(oldPassword);
                            Arrays.fill(oldPassword, (char) 0);
                            // Check the old password
                            if (!oldCrypt.checkPassword(oldHash)) {
                                Toast.makeText(this, getResources().getString(
                                        R.string.ToastBadPassword),
                                        Toast.LENGTH_LONG).show();
                                Log.d(LOG_TAG, "Password does not match hash in the XML file");
                                return;
                            }
                        } else {
                            Toast.makeText(this, getResources().getString(
                                    R.string.ToastPasswordProtected),
                                    Toast.LENGTH_LONG).show();
                            Log.d(LOG_TAG, "XML file is password protected");
                            return;
                        }
                        break;
                    }
                }
            }

            if (categories != null) {
                currentMode = OpMode.CATEGORIES;
                mergeCategories(importType, categories);
            }

            /*
             * Import the preferences after importing categories
             * in case we're importing a selected category which is new.
             */
            if (prefs != null) {
                currentMode = OpMode.SETTINGS;
                switch (importType) {
                    case CLEAN:
                    case REVERT:
                    case UPDATE:
                        setPreferences(prefs);
                        break;

                    default:
                        // Ignore the preferences
                        break;
                }
                importCount += prefs.size();
            }

            if (notes != null) {
                currentMode = OpMode.ITEMS;
                mergeNotes(importType, notes, importPrivate, oldCrypt);
            }

            Toast.makeText(this, getString(R.string.ProgressMessageImportFinished),
                    Toast.LENGTH_LONG).show();

        } catch (Exception x) {
            Log.e(LOG_TAG, "XML Import Error at item " + importCount
                    + "/" + totalCount, x);
            Toast.makeText(this, x.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * General error handling for opening an import file.
     * This is to reduce repetitive code in catch blocks.
     *
     * @param fileName the name of the file we tried to open
     * @param e the exception that was thrown
     */
    private void showFileOpenError(String fileName, Exception e) {
        Log.e(LOG_TAG, String.format("Failed to open %s for reading",
                fileName), e);
        Toast.makeText(this,
                getString((e instanceof FileNotFoundException)
                        ? R.string.ErrorImportNotFound
                        : R.string.ErrorImportCantRead, fileName),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Gather children of an XML element into a Map.
     * All children are expected to have different names.
     *
     * @param parentNode The parent element
     *
     * @return a {@link Map} of {@link Element}s keyed by the
     * element name.
     *
     * @throws SAXException if any two child elements have the same name.
     */
    protected static Map<String,Element> mapChildren(Element parentNode)
        throws SAXException {
        NodeList nl = parentNode.getChildNodes();
        Map<String,Element> map = new HashMap<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            // The document may contain Text (whitespace); ignore it.
            if (n instanceof Element) {
                Element e = (Element) n;
                String tag = e.getTagName();
                if (map.containsKey(tag))
                    throw new SAXException(parentNode.getTagName()
                            + " has multiple " + tag + " children");
                map.put(tag, e);
            }
        }
        return map;
    }

    /**
     * Gather children of an XML element into a List.
     *
     * @param parentNode The parent element
     * @param childName The expected name of the child elements.
     *
     * @return a {@link List} of {@link Element}s, in the same
     * order in which they were parsed from the XML document.
     *
     * @throws SAXException if any child has a different element name
     * than we expected.
     */
    protected static List<Element> listChildren(Element parentNode,
            String childName) throws SAXException {
        NodeList nl = parentNode.getChildNodes();
        List<Element> list = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (!e.getTagName().equals(childName))
                    throw new SAXException("Child " + (i + 1) + " of "
                            + parentNode.getTagName() + " is not " + childName);
                list.add(e);
            }
        }
        return list;
    }

    /**
     * Gather the text nodes from an element.
     *
     * @return a String containing the text from the element,
     * or null if the element is null or does not contain any text nodes.
     */
    protected static String getText(Element e) {
        if (e == null)
            return null;

        StringBuffer sb = null;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Text) {
                if (sb == null)
                    sb = new StringBuffer();
                sb.append(n.getNodeValue());
            }
            else if (n instanceof Element) {
                String s = getText((Element) n);
                if (s != null) {
                    if (sb == null)
                        sb = new StringBuffer();
                    sb.append(s);
                }
            }
        }
        return (sb == null) ? null : sb.toString();
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

    /**
     * Set the current preferences by the ones read from the XML file.
     */
    void setPreferences(Map<String,Element> prefsMap) {
        Log.d(LOG_TAG, ".setPreferences(" + prefsMap.keySet() + ")");
        NotePreferences.Editor prefsEditor =
                NotePreferences.getInstance(this).edit();
        if (prefsMap.containsKey(NPREF_SORT_ORDER)) {
            try {
                prefsEditor.setSortOrder(
                        Integer.parseInt(getText(prefsMap.get(
                                NPREF_SORT_ORDER))));
            } catch (NumberFormatException x) {
                Log.e(LOG_TAG, "Invalid sort order index: "
                        + getText(prefsMap.get(NPREF_SORT_ORDER)), x);
                // Ignore this change
            }
        }
        if (prefsMap.containsKey(NPREF_SHOW_CATEGORY))
            prefsEditor.setShowCategory(
                    Boolean.parseBoolean(getText(prefsMap.get(
                            NPREF_SHOW_CATEGORY))));
        /*
         * Note that we are not changing whether private/encrypted records
         * are shown.  If the user wanted encrypted records, he should have
         * set the password in the PreferencesActivity both when exporting
         * and importing the file.
         */
        if (prefsMap.containsKey(NPREF_SELECTED_CATEGORY)) {
            try {
                prefsEditor.setSelectedCategory(
                        Long.parseLong(getText(prefsMap.get(
                                NPREF_SELECTED_CATEGORY))));
            } catch (NumberFormatException x) {
                Log.e(LOG_TAG, "Invalid category index: "
                        + getText(prefsMap.get(NPREF_SELECTED_CATEGORY)), x);
            }
        }
        prefsEditor.finish();
    }

    /**
     * Merge the category list from the XML file
     * with the Android database.
     */
    void mergeCategories(ImportType importType, List<Element> categories) {
        Log.d(LOG_TAG, ".mergeCategories(" + importType + ")");
        Map<Long,String> categoryIDMap = new HashMap<>();
        Map<String,Long> categoryNameMap = new HashMap<>();

        if (importType == ImportType.CLEAN) {
            Log.d(LOG_TAG, ".mergeCategories: removing all existing categories");
            repository.deleteAllCategories();
            categoryIDMap.clear();
            categoryNameMap.clear();
        }
        else {
            // Read in the current list of categories
            List<NoteCategory> oldCategories = repository.getCategories();
            for (NoteCategory category : oldCategories) {
                categoryIDMap.put(category.getId(), category.getName());
                categoryNameMap.put(category.getName(), category.getId());
            }
        }

        for (Element categorE : categories) {
            CategoryEntry entry = new CategoryEntry();
            entry.name = getText(categorE);
            entry.id = Long.parseLong(categorE.getAttribute("id"));
            // Skip the NoteCategory.UNFILED
            if (entry.id == NoteCategoryColumns.UNFILED) {
                importCount++;
                continue;
            }

            entry.newID = entry.id;
            categoriesByID.put(entry.id, entry);

            NoteCategory importCategory = new NoteCategory();
            importCategory.setName(entry.name);
            importCategory.setId(entry.id);

            NoteCategory newCategory = new NoteCategory();
            newCategory.setName(entry.name);
            newCategory.setId(entry.newID);

            switch (importType) {
            case CLEAN:
                // There are no pre-existing categories
                Log.d(LOG_TAG, ".mergeCategories: adding " + newCategory);
                repository.insertCategory(newCategory);
                break;

            case REVERT:
                // Always overwrite
                if (categoryNameMap.containsKey(entry.name) &&
                        (categoryNameMap.get(entry.name) != entry.id)) {
                    long oldId = categoryNameMap.get(entry.name);
                    Log.d(LOG_TAG, ".mergeCategories: \"" + entry.name
                            + "\" already exists with ID " + oldId
                            + "; deleting it.");
                    // This has the side effect of changing the category
                    // of any existing notes in this category to Unfiled.
                    repository.deleteCategory(oldId);
                    categoryIDMap.remove(oldId);
                    categoryNameMap.remove(entry.name);
                }
                if (categoryIDMap.containsKey(entry.id)) {
                    if (!categoryIDMap.get(entry.id).equals(entry.name)) {
                        Log.d(LOG_TAG, ".mergeCategories: replacing \""
                                + categoryIDMap.get(entry.id)
                                + "\" with \"" + entry.name + "\"");
                        repository.updateCategory(entry.id, entry.name);
                    }
                }
                else {
                    Log.d(LOG_TAG, ".mergeCategories: adding \""
                            + entry.name + "\"");
                    repository.insertCategory(importCategory);
                }
                break;

            case UPDATE:
                /*
                 * Overwrite if newer.  But since categories
                 * have no time stamp, this item acts like merge.
                 */
            case ADD:
                if (categoryNameMap.containsKey(entry.name)) {
                    if (entry.id != categoryNameMap.get(entry.name))
                        entry.newID = categoryNameMap.get(entry.name);
                } else {
                    Log.d(LOG_TAG, ".mergeCategories: adding \""
                            + entry.name + "\"");
                    // Use a new ID if there is a conflict
                    if (categoryIDMap.containsKey(entry.id)) {
                        newCategory = repository.insertCategory(entry.name);
                        entry.newID = newCategory.getId();
                    } else {
                        repository.insertCategory(importCategory);
                    }
                }
                break;

            case TEST:
                // Do nothing.
                break;
            }

            importCount++;
        }
    }

    private enum Operation { INSERT, UPDATE, SKIP }

    private static final Pattern DATE_PATTERN =
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final Pattern NUMBER_PATTERN =
        Pattern.compile("-?\\d+(\\.\\d*)?");

    /**
     * Earlier exports did not use the ISO date format,
     * so we need to check for both. :(
     *
     * @throws ParseException if the string does not look like a date or a number.
     */
    Date parseDate(String str) throws ParseException {
        if (DATE_PATTERN.matcher(str).matches())
            return DATE_FORMAT.parse(str);
        if (NUMBER_PATTERN.matcher(str).matches())
            return new Date(Long.parseLong(str));
        throw new ParseException("Cannot interpret " + str + " as a date", 0);
    }

    /** Quick implementation of StringUtils.isEmpty(String) */
    boolean isEmpty(String s) {
        return (s == null) || (s.length() == 0);
    }

    /**
     * Merge the notes from the XML file with the Android database.
     */
    void mergeNotes(ImportType importType, List<Element> items,
            boolean importPrivate, StringEncryption oldCrypt)
                throws GeneralSecurityException, ParseException, SAXException {
        Log.d(LOG_TAG, ".mergeNotes(" + importType + ")");
        StringEncryption newCrypt = StringEncryption.holdGlobalEncryption();

        try {
            if (importType == ImportType.CLEAN) {
                Log.d(LOG_TAG, ".mergeNotes: removing all existing notes");
                repository.deleteAllNotes();
            }

            NoteItem newNote;
            NoteItem existingNote;
            for (Element itemE : items) {
                Map<String,Element> itemMap = mapChildren(itemE);
                newNote = new NoteItem();
                String valueStr = itemE.getAttribute("id");
                newNote.setId(Long.parseLong(valueStr));
                valueStr = itemE.getAttribute("category");
                long categoryID = Long.parseLong(valueStr);
                if (categoriesByID.get(categoryID) != null)
                    categoryID = categoriesByID.get(categoryID).newID;
                else
                    categoryID = NoteCategoryColumns.UNFILED;
                newNote.setCategoryId(categoryID);

                valueStr = itemE.getAttribute("private");
                int privacy = 0;
                if (Boolean.parseBoolean(valueStr)) {
                    valueStr = itemE.getAttribute("encryption");
                    if (!isEmpty(valueStr))
                        privacy = Integer.parseInt(valueStr);
                    else
                        privacy = 1;
                }

                Element child = itemMap.get("created");
                valueStr = child.getAttribute("time");
                newNote.setCreateTime(parseDate(valueStr).getTime());
                child = itemMap.get("modified");
                valueStr = child.getAttribute("time");
                newNote.setModTime(parseDate(valueStr).getTime());
                String note = getText(itemMap.get("note"));
                if (privacy > 0) {
                    if (!importPrivate) {
                        importCount++;
                        continue;
                    }
                    byte[] encryptedNote;
                    if (privacy >= 2) {
                        // Decrypt first — Base64 in XML
                        encryptedNote = decodeBase64(note);
                        note = oldCrypt.decrypt(encryptedNote);
                    }
                    // Re-encrypt if possible — binary in DB
                    if (newCrypt.hasKey()) {
                        encryptedNote = newCrypt.encrypt(note);
                        newNote.setEncryptedNote(encryptedNote);
                        privacy = 2;
                    } else {
                        privacy = 1;
                    }
                }
                if (privacy < 2)
                    newNote.setNote(note);
                newNote.setPrivate(privacy);

                existingNote = (importType == ImportType.CLEAN) ? null
                        : repository.getNoteById(newNote.getId());

                Operation op = Operation.INSERT;
                switch (importType) {
                case CLEAN:
                    // All items are new
                    break;

                case REVERT:
                    // Overwrite if it's the same item
                    if (existingNote != null) {
                        if (newNote.getCreateTime().equals(
                                existingNote.getCreateTime()))
                            op = Operation.UPDATE;
                        else
                            // Not the same note!
                            newNote.clearId();
                    }
                    break;

                case UPDATE:
                    // Overwrite if it's the same item and newer
                    if (existingNote != null) {
                        if (newNote.getCreateTime().equals(
                                existingNote.getCreateTime())) {
                            if (newNote.getModTime() > existingNote.getModTime())
                                op = Operation.UPDATE;
                            else
                                op = Operation.SKIP;
                        } else {
                            // Not the same note!
                            newNote.clearId();
                        }
                    }
                    break;

                case ADD:
                    // All items are new, but may need a new ID
                    if (existingNote != null)
                        newNote.clearId();
                    break;

                case TEST:
                    // Do nothing
                    op = Operation.SKIP;
                    break;
                }
                switch (op) {
                case INSERT:
                    if (items.size() < 64) {
                        String shortNote = "[private]";
                        if (!newNote.isPrivate()) {
                            shortNote = newNote.getNote();
                            if (shortNote.length() > 64)
                                shortNote = shortNote.substring(0, 64);
                        }
                        Log.d(LOG_TAG, ".mergeNotes: adding "
                                + (newNote.getId() == null ? "new note"
                                : newNote.getId())
                                + " \"" + shortNote + "\"");
                    }
                    repository.insertNote(newNote);
                    break;

                case UPDATE:
                    if (items.size() < 64) {
                        String shortOldNote = "[private]";
                        String shortNewNote = "[private]";
                        if (!existingNote.isPrivate()) {
                            shortOldNote = newNote.getNote();
                            if (shortOldNote.length() > 64)
                                shortOldNote = shortOldNote.substring(0, 64);
                        }
                        if (!newNote.isPrivate()) {
                            shortNewNote = newNote.getNote();
                            if (shortNewNote.length() > 64)
                                shortNewNote = shortNewNote.substring(0, 64);
                        }
                        Log.d(LOG_TAG, ".mergeNotes: replacing existing record "
                                + newNote.getId() + " \"" + shortOldNote
                                + "\" with \"" + shortNewNote + "\"");
                    }
                    repository.updateNote(newNote);
                    break;
                }
                importCount++;
            }
        }
        finally {
            StringEncryption.releaseGlobalEncryption();
        }
    }

    /**
     * Called when the service is created.
     */
    @Override
    public void onCreate() {
        Log.d(LOG_TAG, ".onCreate");
        super.onCreate();

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, ".onBind");
        return binder;
    }
}
