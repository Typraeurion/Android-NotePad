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
import static com.xmission.trevin.android.notes.service.ZIPExporter.*;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.util.EncryptionException;
import com.xmission.trevin.android.notes.util.PasswordMismatchException;
import com.xmission.trevin.android.notes.util.PasswordRequiredException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * This class imports notes from a ZIP file on external storage.
 * The ZIP file may be one created by our own {@link ZIPExporter} or
 * one created by popular, standard-compliant Zip software such as
 * the {@code zip} CLI on Linux/MacOS, WinZip, or 7-Zip.  Zip files
 * created by us will be identified by the presence of a file-level
 * comment in JSON containing the database version, export time, and
 * total record count, though it <i>may</i> contain additional files
 * which were added to it after its initial creation.  We will use
 * JSON comments in any attempt to merge; files without them will
 * be added to the database regardless of import type.
 *
 * @author Trevin Beattie
 */
public class ZIPImporter {

    /** Tag for the debug logger */
    public static final String LOG_TAG = "ZIPImporter";

    /**
     * Flag indicating how to merge items from the XML file
     * with those in the database iff this is a ZIP file we exported.
     */
    public enum ImportType {
        /**
         * The database should be cleared before importing the file.
         * This is ignored if the file does not contain the expected
         * JSON comment.
         */
        CLEAN(1),

        /**
         * Any items in the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten, regardless of which one is newer.
         * This is ignored for any entries that do not contain
         * the expected JSON comment.
         */
        REVERT(2),

        /**
         * Any item is the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten if the modification time of the item
         * in the file is newer.  This is ignored for any entries
         * that do not contain the expected JSON comment.
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
         * Find the ImportType corresponding to an integer value that
         * was passed in {@link ZIPImporter}&rsquo;s input data
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

    /** Modes of operation, for progress bar updates */
    public enum OpMode {
        START, SETTINGS, CATEGORIES, ITEMS, FINISH
    }

    /**
     * Text to pass to the {@link ProgressBarUpdater} for each
     * mode of operation.  This may be overridden after the class
     * is instantiated.
     */
    private final Map<OpMode,String> modeText;

    /** Per-entry database operations */
    private enum Operation {
        /** New item; add the record */
        INSERT,
        /** Existing item; replace the record */
        UPDATE,
        /** Existing item; do not modify it */
        SKIP
    }

    /** Category entry from the ZIP file */
    protected static class CategoryEntry {
        /** Directory name of the category, including the trailing separator */
        String dirName;
        /** The category ID in the ZIP file, if it had a JSON comment */
        Long id = null;
        /** Name of the category is it appears in our database */
        String name;
        /** The new category ID in the NotePad database */
        long newID = 0;
        /**
         * Set this once we have either added the category
         * to the repository or verified it already exists.
         */
        boolean imported = false;
    }

    /** Preferences passed to the constructor */
    @NonNull
    private final NotePreferences prefs;

    /** Repository passed to the constructor */
    @NonNull
    private final NoteRepository repository;

    /** Progress updater passed to the constructor */
    @NonNull
    private final ProgressBarUpdater progressUpdater;

    /**
     * The name of the original ZIP file being read.  This is not
     * necessarily the same as the actual ZIP file passed to the
     * {@link #importData} method; if we got an {@link InputStream}
     * from the {@link ZIPImportWorker}, it will have to be copied
     * to a local file first for random access.
     */
    private String zipFileName = null;

    /**
     * The ZIP file passed to the {@link #importData} method.
     */
    private ZipFile zipFile;

    /**
     * The version of the ZIP export file, if it contained a JSON
     * global file comment; otherwise -1 if this is not one of our
     * ZIP exports.
     */
    private int version = -1;

    /** Import type passed to the {@link #importData} method. */
    private ImportType importType = ImportType.TEST;

    /** Whether to import private / encrypted records */
    private boolean importPrivate = false;

    /**
     * The encryption object used to decrypt notes from the ZIP file,
     * if those entries were annotated with
     * {@value XMLExporter#ATTR_PRIVATE}{@code : true,}
     * {@value XMLExporter#ATTR_ENCRYPTION}{@code :}
     * {@value StringEncryption#BUNDLED_ENCRYPTION}.
     */
    private StringEncryption decryptor = null;

    /**
     * The encryption object used to encrypt private notes for the database
     */
    private StringEncryption encryptor = null;

    /** Name of the &ldquo;Unfiled&rdquo; category */
    private String unfiledCategoryName = "Unfiled";

    /**
     * The total number of records declared in the XML file,
     * or -1 if its size is unknown.
     */
    private int totalRecords = -1;

    /** The current number of records processed */
    private int processedRecords = 0;

    /**
     * Directories is the ZIP file, which map to NotePad categories.
     */
    protected final Map<String,CategoryEntry> categoriesByDir =
            new HashMap<>();

    /** Next free record ID (counting both the ZIP file and local database) */
    private long nextFreeRecordID = 1;

    /**
     * Create a new importer instance with the provided parameters.
     *
     * @param prefs the NotePad preferences
     * @param repository The repository to which we should write records.
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    public ZIPImporter(@NonNull NotePreferences prefs,
                       @NonNull NoteRepository repository,
                       @NonNull ProgressBarUpdater progressUpdater) {
        this.prefs = prefs;
        this.repository = repository;
        this.progressUpdater = progressUpdater;
        modeText = new HashMap<>();
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
    void setModeText(OpMode mode, String text) {
        modeText.put(mode, text);
    }

    /**
     * Import any categories and notes from a ZIP file to the database.
     *
     * @param fileName the name of the XML file being read, if known
     * (may be {@code null}).
     * @param inFile the ZIP file being read.
     * @param importType how to merge items from the XML file
     * with those in the database.
     * @param importPrivate whether to include private or encrypted records.
     * If the ZIP file is encrypted, {@code zipPassword} is used to try to
     * decrypt it.  If the ZIP file contains a password hash in the
     * NotePad metadata file, {@code zipPassword} is checked against it.
     * If there are any encrypted records, they are decrypted first,
     * and then if the current encryption key has been provided they will
     * be re-encrypted before inserting into the database.
     * @param zipPassword the password with which the ZIP file was exported,
     * or {@code null} if the ZIP file contains no encrypted records or
     * we are not importing private records.
     * @param currentPassword the password with which to encrypt any private
     * records imported, or {@code null} to leave them unencrypted or if
     * we are not importing any private records.
     */
    public void importData(@Nullable String fileName,
                           @NonNull File inFile,
                           @NonNull ImportType importType,
                           boolean importPrivate,
                           @Nullable char[] zipPassword,
                           @Nullable char[] currentPassword)
            throws IOException {
        zipFileName = (fileName == null) ? inFile.getAbsolutePath() : fileName;
        this.importType = importType;
        this.importPrivate = importPrivate;
        if (importPrivate && (zipPassword != null)) {
            // We don't know yet what type of encryption was used for
            // the ZIP file, so prepare for our bundled encryption.
            decryptor = new StringEncryption();
            decryptor.setPassword(zipPassword);
        }
        if (importPrivate && (currentPassword != null)) {
            encryptor = new StringEncryption();
            encryptor.setPassword(currentPassword);
        }

        unfiledCategoryName = repository.getCategoryById(
                NoteCategory.UNFILED).getName();

        try (ZipFile zf = new ZipFile(inFile)) {
            zipFile = zf;
            // A password may be set on the ZIP file regardless of whether
            // we are importing private records.
            if (zipPassword != null)
                zipFile.setPassword(zipPassword);

            readGlobalComment();
            readDirectoryEntries(repository.getMaxCategoryId());
            repository.runInTransaction(TRANSACTION_RUNNER);

            // Final update of the progress meter (unthrottled)
            progressUpdater.updateProgress(modeText.get(OpMode.FINISH),
                    processedRecords, totalRecords, false);
        } catch (UncaughtIOException uie) {
            Log.e(LOG_TAG, "I/O Error reading the ZIP file", uie);
            throw uie.getCause();
        }
    }

    /**
     * Attempt to parse a global comment from the file to see if this
     * was exported by our {@link ZIPExporter}.  If we fail,
     * assume this is not one of ours but allow importing notes.
     *
     * @throws UnsupportedOperationException if either the export version
     * or database version set in the ZIP file exceeds our own current
     * application versions.
     */
    private void readGlobalComment() throws UnsupportedOperationException {
        JSONObject json;
        try {
            String fileComment = zipFile.getComment();
            if (fileComment == null)
                return;
            try {
                json = new JSONObject(fileComment);
            } catch (JSONException je) {
                Log.i(LOG_TAG, String.format(Locale.US,
                        "%s has a comment, but not in JSON: \"%s\"",
                        zipFileName, fileComment), je);
                return;
            }
        } catch (ZipException ze) {
            Log.w(LOG_TAG, "Failed to read global comment; ignoring", ze);
            return;
        }
        if (json.has(ATTR_VERSION)) try {
            version = json.getInt(ATTR_VERSION);
            if (version > 2)
                throw new UnsupportedOperationException(String.format(Locale.US,
                        "ZIP export version %d of %s is not compatible"
                                + " with this version of NotePad",
                        version, zipFileName));
        } catch (JSONException je) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "%s comment has %s, but is not an integer: %s",
                    zipFileName, ATTR_VERSION, json));
        }
        if (json.has(ATTR_DB_VERSION)) try {
            int dbVersion = json.getInt(ATTR_DB_VERSION);
            if (dbVersion > NoteRepositoryImpl.DATABASE_VERSION)
                throw new UnsupportedOperationException(String.format(Locale.US,
                        "ZIP database version %d of %s is not compatible"
                                + " with this version of NotePad",
                        dbVersion, zipFileName));
        } catch (JSONException je) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "%s comment has %s, but is not an integer: %s",
                    zipFileName, ATTR_DB_VERSION, json));
        }
        if (json.has(ATTR_EXPORTED)) try {
            Instant exportTime = Instant.parse(json.getString(ATTR_EXPORTED));
            // We only look at this for informational purposes; it is not used.
            Log.i(LOG_TAG, String.format(Locale.US,
                    "Reading %s which was exported at %s",
                    zipFileName, exportTime));
        } catch (DateTimeParseException dte) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "%s comment has %s, but is not a valid date: %s",
                    zipFileName, ATTR_EXPORTED, json));
        } catch (JSONException je) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "%s comment has %s, but we failed to read it: %s",
                    zipFileName, ATTR_EXPORTED, json));
        }
        if (json.has(ATTR_TOTAL_RECORDS)) try {
            totalRecords = json.getInt(ATTR_TOTAL_RECORDS);
            // Note that this is just a tentative count;
            // we allow for the file to contain additional entries.
        } catch (JSONException je) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "%s comment has %s, but is not an integer: %s",
                    zipFileName, ATTR_TOTAL_RECORDS, json));
        }
    }

    /**
     * Read all directory entries from the ZIP file, creating category
     * entries for them.  Those containing a JSON comment created by
     * {@link ZIPExporter} will be imported according to the provided
     * {@link #importType}; any other directories may be added as
     * categories only if they contain text files that can be imported
     * as notes.
     *
     * @param maxCategoryId the largest existing category ID in the repository
     *
     * @throws IOException if there was an error reading the ZIP entries.
     */
    private void readDirectoryEntries(long maxCategoryId) throws IOException {
        long maxIdSeen = -1;
        for (FileHeader entry : zipFile.getFileHeaders()) {
            if (!entry.isDirectory())
                continue;

            // Convert foreign file separators
            String canonicalName = entry.getFileName()
                    .replaceAll("[/\\\\]", File.separator);
            processedRecords++;
            if (METADATA_DIR.equals(canonicalName))
                continue;

            // Skip second- and higher-level directories
            if (canonicalName.split(Pattern.quote(File.separator),
                    -1).length > 2) {
                Log.d(LOG_TAG, ".readDirectoryEntries: skipping nested directory "
                        + entry.getFileName());
            }

            CategoryEntry ce = new CategoryEntry();
            ce.dirName = canonicalName;
            ce.name = ce.dirName.replaceFirst("[/\\\\]$", "");
            String entryComment = entry.getFileComment();
            if (entryComment != null) try {
                JSONObject json = new JSONObject(entryComment);
                if (json.has(ATTR_ID)) {
                    ce.id = json.getLong(ATTR_ID);
                    if (ce.id > maxIdSeen)
                        maxIdSeen = ce.id;
                    // Tentatively accept this ID
                    ce.newID = ce.id;
                    if (json.has(ATTR_NAME))
                        ce.name = json.getString(ATTR_NAME);
                    categoriesByDir.put(ce.dirName, ce);
                    continue;
                }
            } catch (JSONException je) {
                // Fall through
            }

            // We shouldn't have a directory named "Unfiled",
            // but in case we do, force it to ID 0.
            if (unfiledCategoryName.equals(ce.name)) {
                ce.id = 0L;
                ce.newID = 0;
            }

            // Not one of our exports, or error parsing the JSON comment.
            // Treat it as a potential new category.
            categoriesByDir.put(ce.dirName, ce);
        }

        // Go through and assign new ID's to categories without an ID
        maxIdSeen = Math.max(maxIdSeen, maxCategoryId);
        for (CategoryEntry ce : categoriesByDir.values()) {
            if (ce.id == null) {
                ++maxIdSeen;
                ce.newID = maxIdSeen;
            }
        }
    }

    /**
     * Run all of the operations that write data to the repository
     * in a database transaction, so it can be rolled back if there
     * is an error.
     */
    private final Runnable TRANSACTION_RUNNER = new Runnable() {
        @Override
        public void run() {
            try {
                mergeCategories();
                for (FileHeader entry : zipFile.getFileHeaders()) {
                    if (entry.isDirectory())
                        // These were either processed in mergeCategories()
                        // or will be handled as-needed when adding notes
                        continue;

                    try {
                        mergeNote(entry);
                    } catch (ParserConfigurationException pce) {
                        // These only impact parsing XML metadata files, so we'll ignore it.
                        Log.e(LOG_TAG, String.format(Locale.US,
                                "Error reading %s; skipping it",
                                entry.getFileName()), pce);
                    } catch (SAXException se) {
                        // Ditto
                        Log.w(LOG_TAG, String.format(Locale.US,
                                "Error reading %s; skipping it",
                                entry.getFileName()), se);
                    }
                }
            } catch (IOException uie) {
                throw new UncaughtIOException(uie);
            }
        }
    };

    /**
     * Read the &ldquo;.notes/preferences.xml&rdquo; file and
     * set the current preferences accordingly.
     *
     * @param entry the ZIP entry to read the preferences from
     *
     * @throws IOException if there was an error reading the
     * .notes/preferences.xml entry from the ZIP file.
     * @throws SAXException if we failed to parse the contents as XML
     */
    private void readPreferences(FileHeader entry)
            throws IOException, ParserConfigurationException, SAXException {
        Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(zipFile.getInputStream(entry));
        Element top = doc.getDocumentElement();
        if (!PREFERENCES_TAG.equals(top.getTagName())) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "Document root of %s is <%s>; was expecting <%s>",
                    entry.getFileName(), top.getTagName(), PREFERENCES_TAG));
            return;
        }
        NodeList children = top.getChildNodes();
        Map<String,String> prefsMap = new HashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element child = (Element) children.item(i);
            prefsMap.put(child.getTagName(), child.getTextContent());
        }
        Log.d(LOG_TAG, ".readPreferences: read " + prefsMap);
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

        if (prefsMap.containsKey(NPREF_SCROLL_THRESHOLD)) {
            try {
                prefsEditor.setScrollBarThreshold(
                        Float.parseFloat(prefsMap.get(NPREF_SCROLL_THRESHOLD)));
            } catch (NumberFormatException x) {
                Log.e(LOG_TAG, "Invalid scrollbar threshold: "
                        + prefsMap.get(NPREF_SCROLL_THRESHOLD), x);
                // Ignore this change
            }
        }

        prefsEditor.finish();
    }

    /**
     * Read the &ldquo;.notes/metadata.xml&rdquo; file and process it.
     * The only metadata handled by the importer at this time is
     * {@link StringEncryption#METADATA_PASSWORD_HASH}, which we use
     * to verify the password passed to the {@link #importData}
     * method if we are importing private records.
     *
     * @param entry the ZIP entry to read the metadata from
     *
     * @throws IOException if there was an error reading the
     * .notes/metadata.xml entry from the ZIP file.
     * @throws PasswordRequiredException if the metadata includes
     * {@link StringEncryption#METADATA_PASSWORD_HASH} and
     * {@link #importPrivate} is set, but no ZIP password was
     * provided to the importer.
     * @throws PasswordMismatchException if {@link #importPrivate} is set
     * but the ZIP password provided does not match the file&rsquo;s
     * {@link StringEncryption#METADATA_PASSWORD_HASH}.
     * @throws SAXException if we failed to parse the contents as XML
     */
    private void readMetadata(FileHeader entry)
            throws IOException, ParserConfigurationException, SAXException {
        Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(zipFile.getInputStream(entry));
        Element top = doc.getDocumentElement();
        if (!METADATA_TAG.equals(top.getTagName())) {
            Log.w(LOG_TAG, String.format(Locale.US,
                    "Document root of %s is <%s>; was expecting <%s>",
                    entry.getFileName(), top.getTagName(), METADATA_TAG));
            return;
        }
        NodeList children = top.getChildNodes();
        Map<String,byte[]> metaMap = new HashMap<>();
        int childElementCount = 0;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element child = (Element) children.item(i);
            childElementCount++;
            if (!METADATA_ITEM.equals(child.getTagName())) {
                Log.w(LOG_TAG, String.format(Locale.US,
                        "Child element #%d of %s is <%s>; was expecting <%s>",
                        childElementCount, METADATA_TAG, child.getTagName(),
                        METADATA_ITEM));
                continue;
            }
            // For our purposes, we ignore the "id" attribute.
            // But we must have a name.
            if (!child.hasAttribute(ATTR_NAME)) {
                Log.w(LOG_TAG, String.format(Locale.US,
                        "%s %s #%d has no name", METADATA_TAG,
                        METADATA_ITEM, childElementCount));
                continue;
            }
            byte[] value = decodeBase64(child.getTextContent());
            metaMap.put(child.getAttribute(ATTR_NAME), value);
        }
        Log.d(LOG_TAG, ".readMetadata: read " + metaMap);
        if (importPrivate && metaMap.containsKey(
                StringEncryption.METADATA_PASSWORD_HASH)) {
            if (decryptor == null)
                throw new PasswordRequiredException("Import file is password-"
                        + "protected but no password was provided");
            if (!decryptor.checkPassword(metaMap.get(
                    StringEncryption.METADATA_PASSWORD_HASH)))
                throw new PasswordMismatchException("Password does not mach"
                        + " the one used to encrypt the import file");
        }
    }

    /**
     * Merge the category list from the ZIP file with the database.
     * This part only handles directory entries which were annotated
     * with our own comment containing the category ID, and only if
     * the ZIP file has a NotePad export version.  Otherwise we&rsquo;ll
     * use the ID&rsquo;s of any existing categories that match directory
     * entries, or add categories for new directories only if they contain
     * text files that can be imported as notes.
     */
    private void mergeCategories() {
        Log.d(LOG_TAG, ".mergeCategories(" + importType + ")");
        String opText = modeText.get(OpMode.CATEGORIES);
        // Read in the current list of categories,
        // unless we're doing a clean import.
        Map<Long,String> categoryIDMap = new HashMap<>();
        Map<String,Long> categoryNameMap = new HashMap<>();
        if ((version > 0) && (importType == ImportType.CLEAN)) {
            Log.d(LOG_TAG, ".mergeCategories: removing all existing categories");
            repository.deleteAllCategories();
        } else {
            for (NoteCategory category : repository.getCategories()) {
                categoryIDMap.put(category.getId(), category.getName());
                categoryNameMap.put(category.getName(), category.getId());
            }
        }

        ImportType effectiveType = importType;
        if ((version <= 0) && (importType != ImportType.TEST))
            effectiveType = ImportType.ADD;
        switch (effectiveType) {

            case CLEAN:
                // There are no pre-existing categories
                for (CategoryEntry ce : categoriesByDir.values()) {
                    // Skip non-annotated directories
                    if (ce.id == null)
                        continue;
                    Log.d(LOG_TAG, String.format(Locale.US,
                            ".mergeCategories: adding %d \"%s\"",
                            ce.id, ce.name));
                    NoteCategory localCategory = new NoteCategory();
                    localCategory.setId(ce.id);
                    localCategory.setName(ce.name);
                    localCategory = repository.insertCategory(localCategory);
                    ce.newID = localCategory.getId();
                    ce.imported = true;
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case REVERT:
                /*
                 * First remove all conflicting names.
                 * DO NOT add new categories from the same loop,
                 * as that may lead to inconsistencies between
                 * what's in the database and our maps.
                 */
                for (CategoryEntry ce : categoriesByDir.values()) {
                    if (categoryNameMap.containsKey(ce.name)) {
                        long oldId = categoryNameMap.get(ce.name);
                        if (ce.id == null) {
                            Log.d(LOG_TAG, String.format(Locale.US,
                                    ".mergeCategories: \"%s\" already exists"
                                            + " with ID %d; using it.",
                                    ce.name, oldId));
                            ce.newID = oldId;
                        } else if (categoryNameMap.get(ce.name) != ce.id) {
                            Log.d(LOG_TAG, String.format(Locale.US,
                                    ".mergeCategories: \"%s\" already exists"
                                    + " with ID %d; deleting it.",
                                    ce.name, oldId));
                            repository.deleteCategory(oldId);
                            categoryIDMap.remove(oldId);
                            categoryNameMap.remove(ce.name);
                        }
                    }
                }
                for (CategoryEntry ce : categoriesByDir.values()) {
                    if (categoryNameMap.containsKey(ce.name)) {
                        ce.newID = categoryNameMap.get(ce.name);
                        ce.imported = true;
                        processedRecords++;
                        continue;
                    }
                    if (ce.id == null)
                        // Leave this for later if we need it
                        continue;
                    if (categoryIDMap.containsKey(ce.id)) {
                        if (!categoryIDMap.get(ce.id).equals(ce.name)) {
                            Log.d(LOG_TAG, String.format(Locale.US,
                                    ".mergeCategories: replacing"
                                            + " \"%s\" with \"%s\"",
                                    categoryIDMap.get(ce.id), ce.name));
                            repository.updateCategory(ce.id, ce.name);
                        }
                        ce.newID = ce.id;
                    } else {
                        Log.d(LOG_TAG, String.format(Locale.US,
                                ".mergeCategories: adding %d \"%s\"",
                                ce.id, ce.name));
                        NoteCategory localCategory = new NoteCategory();
                        localCategory.setId(ce.id);
                        localCategory.setName(ce.name);
                        localCategory = repository.insertCategory(localCategory);
                        ce.newID = localCategory.getId();
                    }
                    ce.imported = true;
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case UPDATE:
            case ADD:
                for (CategoryEntry ce : categoriesByDir.values()) {
                    if (categoryNameMap.containsKey(ce.name)) {
                        ce.newID = categoryNameMap.get(ce.name);
                        ce.imported = true;
                    } else if (ce.id == null) {
                        // Leave this for later if we need it
                        continue;
                    } else {
                        Log.d(LOG_TAG, String.format(Locale.US,
                                ".mergeCategories: adding \"%s\"", ce.name));
                        NoteCategory localCategory = new NoteCategory();
                        // Use a new ID if there is a conflict
                        if (categoryIDMap.containsKey(ce.newID))
                            localCategory = repository.insertCategory(ce.name);
                        else {
                            localCategory.setId(ce.newID);
                            localCategory.setName(ce.name);
                            localCategory = repository.insertCategory(localCategory);
                        }
                        ce.newID = localCategory.getId();
                        ce.imported = true;
                    }
                    processedRecords++;
                    progressUpdater.updateProgress(opText,
                            processedRecords, totalRecords, true);
                }
                break;

            case TEST:
                // Do nothing.
                for (CategoryEntry ce : categoriesByDir.values()) {
                    if (ce.id == null)
                        continue;
                    ce.imported = true;
                    processedRecords++;
                }
                progressUpdater.updateProgress(opText,
                        processedRecords, totalRecords, true);
                break;
        }

        // Final step: assign new ID's to any categories that we haven't
        // imported yet.  After this all entries' newID field should be unique.
        SortedSet<Long> seenIds = new TreeSet<>(categoryIDMap.keySet());
        for (CategoryEntry ce : categoriesByDir.values()) {
            if (ce.imported)
                continue;
            if (seenIds.contains(ce.newID)) {
                ce.newID = seenIds.last() + 1;
                seenIds.add(ce.newID);
            }
        }
    }

    /**
     * Merge a file entry from the ZIP file into the database.  Skips
     * over any entries contained in the &ldquo;.notes/&rdquo; directory.
     * The entry file name should end in &ldquo;.txt&rdquo; or, if
     * annotated with {@value XMLExporter#ATTR_PRIVATE}{@code : true,}
     * {@value XMLExporter#ATTR_ENCRYPTION}{@code  :}
     * {@value StringEncryption#BUNDLED_ENCRYPTION}, the extension
     * &ldquo;.aes&rdquo;.
     *
     * @param entry the ZIP entry to read the note from
     */
    private void mergeNote(FileHeader entry)
            throws IOException, ParserConfigurationException, SAXException {
        if (entry.isDirectory())
            return;
        String canonicalName = entry.getFileName()
                .replaceAll("[/\\\\]", File.separator);
        if (canonicalName.startsWith(METADATA_DIR)) {
            // Special handling for preferences and metadata files
            if (canonicalName.equals(PREFS_FILE))
                readPreferences(entry);
            else if (canonicalName.equals(METADATA_FILE))
                readMetadata(entry);
            return;
        }

        // If the entry name has a directory prefix, extract it as the category.
        String[] pathParts = canonicalName.split(
                Pattern.quote(File.separator), -1);
        if (pathParts.length > 2) {
            Log.d(LOG_TAG, ".mergeNote: skipping deep file entry "
                    + entry.getFileName());
            return;
        }
        String categoryDir = (pathParts.length == 1) ? null
                : pathParts[0] + File.separator;

        NoteItem note = new NoteItem();
        String fileComment = entry.getFileComment();
        if (fileComment != null) try {
            JSONObject json = new JSONObject(fileComment);
            long noteId = -1;
            if (json.has(ATTR_ID))
                noteId = json.getLong(ATTR_ID);
            Instant createTime = null;
            if (json.has(NOTE_CREATED))
                createTime = Instant.parse(json.getString(NOTE_CREATED));
            Instant modTime = null;
            if (json.has(NOTE_MODIFIED))
                modTime = Instant.parse(json.getString(NOTE_MODIFIED));
            if (json.has(ATTR_PRIVATE) && json.getBoolean(ATTR_PRIVATE)) {
                int encryptionType = json.has(ATTR_ENCRYPTION)
                        ? json.getInt(ATTR_ENCRYPTION)
                        : StringEncryption.NO_ENCRYPTION;
                // Limit the encryption type to our own
                note.setPrivate((encryptionType <=
                        StringEncryption.MAX_SUPPORTED_ENCRYPTION)
                        ? encryptionType : StringEncryption.NO_ENCRYPTION);
            }
            if (modTime != null)
                note.setModTime(modTime);
            if (createTime != null)
                note.setCreateTime(createTime);
            if (noteId > 0)
                note.setId(noteId);
        } catch (JSONException je) {
            // Ignore the comment entirely if we can't parse it
        }
        // Regardless of the file comment, if it's encrypted make the note private.
        if (!note.isPrivate() && entry.isEncrypted())
            note.setPrivate(StringEncryption.NO_ENCRYPTION);

        // Now we can determine if this is a private note we shouldn't import.
        if (note.isPrivate() && !importPrivate) {
            processedRecords++;
            return;
        }

        CategoryEntry ce = null;
        if (categoryDir != null)
            ce = categoriesByDir.get(categoryDir);
        if (ce != null) {
            note.setCategoryName(ce.name);
            if (ce.imported)
                note.setCategoryId(ce.newID);
        } else {
            note.setCategoryId(NoteCategory.UNFILED);
            note.setCategoryName(unfiledCategoryName);
        }

        byte[] rawData = zipFile.getInputStream(entry).readAllBytes();
        if (note.isEncrypted()) {
            // Decrypt the note; we'll re-encrypt it next.
            note.setNote(decryptor.decrypt(rawData));
        } else {
            // Assume the file contains plain text or
            // if ZIP encrypted it then ZIP can decrypt it.
            note.setNote(new String(rawData, "UTF-8"));
        }

        if (note.isPrivate()) {
            if (encryptor != null) {
                // Encrypt all private notes
                note.setEncryptedNote(encryptor.encrypt(note.getNote()));
                note.setPrivate(StringEncryption.encryptionType());
            } else {
                note.setPrivate(StringEncryption.NO_ENCRYPTION);
            }
        }

        NoteItem existingRecord = null;
        if ((note.getId() != null) && (importType != ImportType.CLEAN)) {
            existingRecord = repository.getNoteById(note.getId());
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
                // Overwrite if it's the same note (same ID and creation time)
                if (existingRecord != null) {
                    if (existingRecord.getCreateTime()
                            .equals(note.getCreateTime()))
                        op = Operation.UPDATE;
                    else
                        // Not the same note!  Assign a new ID.
                        note.setId(nextFreeRecordID++);
                }
                break;

            case UPDATE:
                // Overwrite if it's the same note _and_ newer
                if (existingRecord != null) {
                    if (existingRecord.getCreateTime()
                            .equals(note.getCreateTime())) {
                        if (note.getModTime().isAfter(
                                existingRecord.getModTime()))
                            op = Operation.UPDATE;
                        else
                            op = Operation.SKIP;
                    } else {
                        // Not the same note!  Assign a new ID.
                        note.setId(nextFreeRecordID++);
                    }
                }
                break;

            case ADD:
                // All items are new, but may need a new ID
                if (existingRecord != null)
                    note.setId(nextFreeRecordID++);
                break;

            case TEST:
                // Do nohing
                op = Operation.SKIP;
                break;

        }

        // If we need to add a new category, do so now.
        if ((ce != null) && !ce.imported && (op != Operation.SKIP)) {
            NoteCategory newCategory = repository.insertCategory(ce.name);
            ce.newID = newCategory.getId();
            ce.imported = true;
            note.setCategoryId(ce.newID);
        }

        switch (op) {

            case INSERT:
                repository.insertNote(note);
                break;

            case UPDATE:
                repository.updateNote(note);
                break;

        }

        processedRecords++;
        progressUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                processedRecords, totalRecords, true);
    }

}
