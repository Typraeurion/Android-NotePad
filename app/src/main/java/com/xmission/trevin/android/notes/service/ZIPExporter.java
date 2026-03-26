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

import static com.xmission.trevin.android.notes.service.XMLExporter.*;

import android.util.Log;

import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * This class exports the Note Pad notes to a ZIP file on external storage.
 * We take advantage of a few ZIP features to store our metadata:
 * <ul>
 *     <li>User-defined categories are stored as top-level directories;
 *     the category name is the name of the directory, and the category ID
 *     is stored in the entry comment as a JSON string: &ldquo;{@code {
 *     id:### }}&rdquo;.</li>
 *     <li>The note ID is stored in the file entry comment as a JSON
 *     string as for categories above.  It is also used as the file name
 *     prefix, zero-padded to the length of the maximum ID.</li>
 *     <li>The creation time is stored using
 *     {@link ZipEntry#setCreationTime(FileTime)}.  <b>WARNING:</b>
 *     The <a href="https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT">ZIP specification</a>
 *     still uses 32-bit fields for UNIX time, which means this
 *     will roll over in <a href="https://en.wikipedia.org/wiki/Year_2038_problem">2038</a>.</li>
 *     <li>The last modification time is stored using
 *     {@link ZipEntry#setLastModifiedTime(FileTime)}.  <b>WARNING:</b>
 *     See creation time above for the year-2038 limit.</li>
 *     <li>If a note is private, the privacy flag and encryption
 *     type are stored in the entry comment JSON:
 *     &ldquo;&hellip;{@code ; private:true; encryption=#}
 *     where the encryption value is one of the following values:
 *     <dl><dt>{@code 2}</dt>
 *     <dd>{@link StringEncryption#BUNDLED_ENCRYPTION}.  With this mode,
 *     a password hash is stored in the usual file encryption header
 *     for each encrypted note.</dd>
 *     <dt>{@code 99}</dt>
 *     <dd>AES, as introduced in ZIP version 5.2.  The exporter <i>will
 *     not</i> use the older &ldquo;ZipCrypto&rdquo; format, which is
 *     vulnerable to known attacks.</dd>
 *     </dl></li>
 *     <li>Any metadata (apart from
 *     {@link StringEncryption#METADATA_PASSWORD_HASH}) is stored in a
 *     hidden directory named &ldquo;{@code .notes}&rdquo; in a file
 *     named &ldquo;{@code metadata.xml}&rdquo; in the XML format used
 *     by {@link XMLExporter}.</li>
 *     <li>User preferences are stored in the hidden
 *     &ldquo;{@code .notes}&rdquo; directory in a file named
 *     &ldquo;{@code preferences.xml}&rdquo; in the XML format used
 *     by {@link XMLExporter}.</li>
 * </ul>
 *
 * @author Trevin Beattie
 */
public class ZIPExporter {

    /** Tag for the debug logger */
    public static final String LOG_TAG = "ZIPExporter";

    /** Name of the directory entry holding NotePad metadata */
    public static final String METADATA_DIR = ".notes";

    /** Name of the preferences entry within the ZIP file */
    public static final String PREFS_FILE =
            METADATA_DIR + File.separator + "preferences.xml";

    /** Name of the metadata entry within the ZIP file */
    public static final String METADATA_FILE =
            METADATA_DIR + File.separator + "metadata.xml";

    /**
     * Flag indicating which type of encryption to use
     * when exporting private notes.
     */
    public enum EncryptionType {
        /** Do not encrypt private notes */
        NO_ENCRYPTION,
        /** Use our own bundled encryption (same as XML export) */
        BUNDLED_ENCRYPTION,
        // FIXME: AES encryption can be in several varieties;
        // we should figure out how to support these.
        /** AES 128 &mdash; algorithm ID {@code 0x660E} */
        AES_128,
        /** AES 256 &mdash; algorithm ID {@code 0x6610} */
        AES_256,
        /** DES &mdash; algorithm ID {@code 0x6601} */
        DES;
    }

    /** Modes of operation */
    public enum OpMode {
        START, SETTINGS, CATEGORIES, ITEMS, FINISH
    }
    /**
     * Text to pass to the {@link ProgressBarUpdater} for each
     * mode of operation.  This may be overridden when the class
     * is initialized.
     */
    private final Map<OpMode,String> modeText;

    /** Placeholder file title for encrypted notes */
    private String encryptedTitle = "Locked";

    /** Placeholder file title for private notes */
    private String privateTitle = "Private";

    /** NotePad preferences */
    private final NotePreferences prefs;

    /** The repository from which to read records */
    private final NoteRepository repository;

    /** Progress bar updater */
    private final ProgressBarUpdater progressBarUpdater;

    /** The ZIP output stream to which we should write the data */
    private ZipOutputStream zipStream = null;

    /**
     * Encryption object used to decrypt records from the repository
     * and possibly (depending on {@link #privateEncryption} re-encrypt
     * compressed notes in the ZIP file.
     */
    private StringEncryption decryptor = null;

    /**
     * The type of encryption to use for private notes in the ZIP file,
     * or {@code null} if private notes should not be included.
     */
    private EncryptionType privateEncryption = null;

    /**
     * The password to use to encrypt private notes in the ZIP file,
     * or {@code null} if private notes should not be encrypted
     * or are not included.
     */
    private String zipPassword = null;

    /** Preferences read from the repository */
    Map<String,?> prefsMap = null;

    /**
     * Metadata read from the repository, <i>except</i> for the
     * {@link StringEncryption#METADATA_PASSWORD_HASH}.
     */
    List<NoteMetadata> metadata = null;

    /**
     * The password hash metadata, which is separately stored in
     * encrypted records iff {@link EncryptionType#BUNDLED_ENCRYPTION}
     * is used.
     */
    byte[] passwordHash = null;

    /**
     * The ID of the category whose notes to export, or
     * {@link NotePreferences#ALL_CATEGORIES} to export all categories
     * of notes.
     */
    long exportCategoryId = NotePreferences.ALL_CATEGORIES;

    /**
     * Note categories read from the repository
     * (this includes the {@link NoteCategory#UNFILED} category).
     */
    List<NoteCategory> categories = null;

    /**
     * Map of category ID&rsquo;s to directory names that we
     * create in the ZIP file.  Only used when exporting all categories.
     */
    Map<Long,String> categoryDirectoryMap = new HashMap<>();

    /**
     * The amount of zero-padding to use when writing note ID&rsquo;s
     * as part of the note file names.
     */
    int noteIdPadding = 2;

    /**
     * The total number of records to be written to the ZIP file.
     * (-1 means indeterminate.)
     */
     int totalRecordCount = -1;

    /**
     * Initialize a new ZIPExporter
     *
     * @param prefs the NotePad preferences.
     * @param repository The repository from which to read records.
     * It should have already been opened by the caller.
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    public ZIPExporter(@NonNull NotePreferences prefs,
                       @NonNull NoteRepository repository,
                       @NonNull ProgressBarUpdater progressUpdater)
    {
        modeText = new HashMap<>();
        modeText.put(OpMode.START, "Starting\u2026");
        modeText.put(OpMode.SETTINGS, "Exporting application settings\u2026");
        modeText.put(OpMode.CATEGORIES, "Exporting categories\u2026");
        modeText.put(OpMode.ITEMS, "Exporting Notes\u2026");
        modeText.put(OpMode.FINISH, "Finishing\u2026");
        this.prefs = prefs;
        this.repository = repository;
        this.progressBarUpdater = progressUpdater;
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
     * Change the file title used for encrypted notes
     *
     * @param newTitle the new title to use
     */
    void setEncryptedTitle(String newTitle) {
        encryptedTitle = newTitle;
    }

    /**
     * Change the file title used for private notes
     *
     * @param newTitle the new title to use
     */
    void setPrivateTitle(String newTitle) {
        privateTitle = newTitle;
    }

    /**
     * Export the preferences, metadata (if any), categories,
     * and notes from the database to a ZIP file.
     *
     * @param zipStream the ZIP output stream to which we should write
     * the data.
     * @param category the ID of the category whose notes to export,
     * or {@link NotePreferences#ALL_CATEGORIES} to export all categories
     * of notes
     * @param decryptor the encryption object used to decrypt records
     * from the repository if we are exporting private records
     * @param privateEncryption whether to export private records (if
     * {@code null}, private records are not exported) and which type of
     * encryption to use (if any).
     * @param zipPassword the password to use to encrypt private notes
     */
    public void export(@NonNull ZipOutputStream zipStream, long category,
                       StringEncryption decryptor,
                       EncryptionType privateEncryption, String zipPassword)
            throws IOException, JSONException {
        this.zipStream = zipStream;
        this.exportCategoryId = category;
        this.decryptor = decryptor;
        this.privateEncryption = privateEncryption;
        this.zipPassword = zipPassword;
        // Get all of the preferences, metadata, and categories;
        // these should be very short collections.
        prefsMap = prefs.getAllPreferences();
        metadata = repository.getMetadata();
        categories = repository.getCategories();
        // Get the total count of items to export
        int noteCount = repository.countNotes();
        if (privateEncryption == null) {
            noteCount -= repository.countPrivateNotes();
            // Exclude the password hash
            Iterator<NoteMetadata> iter = metadata.iterator();
            while (iter.hasNext()) {
                NoteMetadata meta = iter.next();
                if (StringEncryption.METADATA_PASSWORD_HASH
                        .equals(meta.getName()))
                    iter.remove();
            }
        }
        int currentCount = 0;
        int totalCount = prefsMap.size() + metadata.size()
                + categories.size() + noteCount;

        JSONObject zipHeaderComment = new JSONObject();
        zipHeaderComment.put(ATTR_DB_VERSION,
                NoteRepositoryImpl.DATABASE_VERSION);
        zipHeaderComment.put(ATTR_TOTAL_RECORDS, totalCount);
        zipStream.setComment(zipHeaderComment.toString());

        progressBarUpdater.updateProgress(modeText.get(OpMode.SETTINGS),
                currentCount, totalCount, true);
        if (!(prefsMap.isEmpty() && metadata.isEmpty())) {
            createMetaDirectory();
            writePreferences();
            currentCount += prefsMap.size();
            writeMetadata();
            currentCount += metadata.size();
        }

        progressBarUpdater.updateProgress(modeText.get(OpMode.CATEGORIES),
                currentCount, totalCount, true);
        writeCategories();
        currentCount += categories.size();

        progressBarUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                currentCount, totalCount, true);
        currentCount += writeNotes(currentCount);

        progressBarUpdater.updateProgress(modeText.get(OpMode.FINISH),
                currentCount, totalCount, false);
    }

    /**
     * Create a directory entry for the NotePad metadata.
     * The directory is named {@value #METADATA_DIR}.
     *
     * @throws IOException if there was an error writing the entry.
     */
    void createMetaDirectory() throws IOException {
        ZipEntry dirEnt = new ZipEntry(METADATA_DIR);
        zipStream.putNextEntry(dirEnt);
        zipStream.closeEntry();
    }

    /** Regular expression matcher for characters forbidden in a file name */
    private static final String FORBIDDEN_FILE_CHARS = "[\"*+/:<>?\\[\\\\\\]|]+";

    /**
     * Create a directory entry for a category.  The category name is used
     * as the directory name but may need modification to remove invalid
     * path component characters.  If the directory name does not match
     * the category name, the ZIP entry&rsquo;s comment will contain the
     * original category name.
     *
     * @param category the category whose entry to create
     *
     * @return the directory name
     *
     * @throws IOException if there was an error writing the entry.
     * @throws JSONException if there was an error forming its JSON comment.
     */
    String createCategoryDirectory(@NonNull NoteCategory category)
            throws IOException, JSONException {
        JSONObject comment = new JSONObject();
        comment.put(ATTR_ID, category.getId());
        String dirName = category.getName()
                // Strip any leading and trailing whitespace
                .trim()
                // Remove all leading periods
                .replaceFirst("^\\.+", "")
                // Replace all sequences of forbidden characters with an underscore
                .replaceAll(FORBIDDEN_FILE_CHARS, "_");
        // Restrict the length to 176 characters to allow room for the file name
        if (dirName.length() > 176)
            dirName = dirName.substring(0, 176);
        if (!dirName.equals(category.getName()))
            comment.put(ATTR_NAME, category.getName());
        ZipEntry dirEnt = new ZipEntry(dirName);
        dirEnt.setComment(comment.toString());
        zipStream.putNextEntry(dirEnt);
        zipStream.closeEntry();
        return dirName;
    }

    /**
     * Write out the preferences file.
     *
     * @throws IOException if there was an error writing the entry.
     */
    void writePreferences() throws IOException {
        if (prefsMap.isEmpty())
            return;
        ZipEntry prefsEnt = new ZipEntry(PREFS_FILE);
        zipStream.putNextEntry(prefsEnt);
        try (ZIPEntryPrintStream print = new ZIPEntryPrintStream(
                zipStream, false, "UTF-8")) {
            print.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            print.printf(Locale.US, "<%s %s=\"%d\">\n",
                    PREFERENCES_TAG, ATTR_COUNT, prefsMap.size());
            for (String key : prefsMap.keySet()) {
                Object value = prefsMap.get(key);
                print.printf(Locale.US, "    <%s>%s</%s>\n",
                        key, escapeXML((value == null) ? null
                                : value.toString()), key);
            }
            print.printf(Locale.US, "  </%s>\n", PREFERENCES_TAG);
        }
        zipStream.closeEntry();
        Log.i(LOG_TAG, String.format("Wrote %d preference settings",
                prefsMap.size()));
    }

    /**
     * Write out the metadata
     *
     * @throws IOException if there was an error writing the entry.
     */
    void writeMetadata() throws IOException {
        if (metadata.isEmpty())
            return;
        ZipEntry metaEnt = new ZipEntry(METADATA_FILE);
        zipStream.putNextEntry(metaEnt);
        try (ZIPEntryPrintStream print = new ZIPEntryPrintStream(
                zipStream, false, "UTF-8")) {
            print.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            print.printf(Locale.US, "<%s %s=\"%d\">\n",
                    METADATA_TAG, ATTR_COUNT, metadata.size());
            for (NoteMetadata datum : metadata) {
                print.printf(Locale.US, "    <%s %s=\"%d\" %s=\"%s\"",
                        METADATA_ITEM, ATTR_ID, datum.getId(), ATTR_NAME,
                        escapeXML(datum.getName()));
                if (datum.getValue() == null)
                    print.println("/>");
                else print.printf(Locale.US, ">%s</%s>\n",
                        encodeBase64(datum.getValue()), METADATA_ITEM);
            }
            print.printf(Locale.US, "  </%s>\n", METADATA_TAG);
        }
        zipStream.closeEntry();
        Log.i(LOG_TAG, String.format("Wrote %d metadata items",
                metadata.size()));
    }

    /**
     * Write the category list.  As it goes it fills
     * {@link #categoryDirectoryMap} with mappings from category ID&rsquo;s
     * to their respective directory names.  These will be terminated by
     * the path separator, with the exception of the &ldquo;Unfiled&rdquo;
     * category which is an empty string.
     *
     * @throws IOException if there was an error writing the entry.
     * @throws JSONException if there was an error forming its JSON comment.
     */
    void writeCategories() throws IOException, JSONException {
        for (NoteCategory category : categories) {
            if (category.getId() == NoteCategory.UNFILED) {
                categoryDirectoryMap.put(category.getId(), "");
                continue;
            }
            String dirName = createCategoryDirectory(category);
            categoryDirectoryMap.put(category.getId(), dirName);
        }
    }

    /**
     * Write the notes
     *
     * @param baseCount the number of records written from previous stages
     *
     * @return the total number of notes written
     *
     * @throws IOException if there was an error writing any entry.
     * @throws JSONException if there was an error forming any JSON comment.
     */
    int writeNotes(int baseCount) throws IOException, JSONException {
        int count = 0;
        boolean includePrivate = (privateEncryption != null);
        try (NoteCursor cursor = repository.getNotes(exportCategoryId,
                includePrivate, includePrivate,
                NoteRepositoryImpl.NOTE_TABLE_NAME + "."
                        + NoteSchema.NoteItemColumns._ID)) {
            while (cursor.moveToNext()) {
                NoteItem note = cursor.getNote();
                writeNoteItem(note);
                count++;
                progressBarUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                        baseCount + count, totalRecordCount, true);
            }
        }
        return count;
    }

    /**
     * Write out a single note.
     *
     * @param note the note to write
     *
     * @throws IOException if there was an error writing the entry.
     * @throws JSONException if there was an error forming any JSON comment.
     */
    void writeNoteItem(@NonNull NoteItem note)
            throws IOException, JSONException {
        JSONObject comment = new JSONObject();
        comment.put(ATTR_ID, note.getId());
        if (note.isPrivate()) {
            comment.put(ATTR_PRIVATE, true);
            if (privateEncryption != null) {
                switch (privateEncryption) {
                    case BUNDLED_ENCRYPTION:
                        comment.put(ATTR_ENCRYPTION,
                                StringEncryption.BUNDLED_ENCRYPTION);
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "Unsupported encryption type");
                }
            }
        }
        String nameFormat = String.format(Locale.US,
                "%%s%%0%dd - %%s.txt", noteIdPadding);
        String dirName = (exportCategoryId == NotePreferences.ALL_CATEGORIES)
                ? categoryDirectoryMap.get(note.getCategoryId()) : "";
        String firstLine = note.isPrivate() ?
                ((privateEncryption == null) ? privateTitle : encryptedTitle)
                : note.getNote();
        int maxTitleLength = 243 - dirName.length() - noteIdPadding;
        // Ignore everything after the first line
        if (firstLine.indexOf('\n') >= 0)
            firstLine = firstLine.substring(0, firstLine.indexOf('\n'));
        // and anything after the first period (or other sentence-ending punct.)
        Pattern endPunct = Pattern.compile("[!.?]");
        Matcher m = endPunct.matcher(firstLine);
        if (m.find(1))
            firstLine = firstLine.substring(0, m.start());
        // Replace all other invalid filename characters with underscores
        firstLine = firstLine.trim().replaceAll(FORBIDDEN_FILE_CHARS, "_");
        // Finally, truncate the line if it's too long to fit.
        if (firstLine.length() > maxTitleLength)
            firstLine = firstLine.substring(0, maxTitleLength);
        String entryName = String.format(Locale.US, nameFormat,
                dirName, note.getId(), firstLine);
        ZipEntry entry = new ZipEntry(entryName);
        entry.setComment(comment.toString());
        entry.setCreationTime(FileTime.from(note.getCreateTime()));
        entry.setLastModifiedTime(FileTime.from(note.getModTime()));
        if (note.isPrivate() && (privateEncryption != null) &&
                (privateEncryption != EncryptionType.NO_ENCRYPTION)) {
            // FIXME: Determine how to encrypt entries!
        }
        zipStream.putNextEntry(entry);
        try (ZIPEntryPrintStream print = new ZIPEntryPrintStream(
                zipStream, false, "UTF-8")) {
            print.print(note.getNote());
        }
        zipStream.closeEntry();
    }

}
