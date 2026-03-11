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

import android.util.Log;

import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema;
import com.xmission.trevin.android.notes.util.StringEncryption;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class exports the Note Pad notes to an XML file on external storage.
 *
 * @author Trevin Beattie
 */
public class XMLExporter {

    /** Tag for the debug logger */
    public static final String LOG_TAG = "XMLExporter";

    /** The document element name */
    public static final String DOCUMENT_TAG = "NotePadApp";

    /**
     * The export file version attribute name.
     * If omitted, readers should assume version 1 which is what
     * the old {@code XMLExporterService} class wrote.  This
     * class uses version 2, which includes a few new attributes.
     */
    public static final String ATTR_VERSION = "version";

    /** The database version attribute name */
    public static final String ATTR_DB_VERSION = "db-version";

    /** The exported timestamp attribute name */
    public static final String ATTR_EXPORTED = "exported";

    /** The total record count attribute name */
    public static final String ATTR_TOTAL_RECORDS = "total-records";

    /** The preferences element name */
    public static final String PREFERENCES_TAG = "Preferences";

    /** The metadata element name */
    public static final String METADATA_TAG = "Metadata";

    /** Name of metadata child elements */
    public static final String METADATA_ITEM = "item";

    /** The ID attribute name */
    public static final String ATTR_ID = "id";

    /** The name attribute &hellip; name */
    public static final String ATTR_NAME = "name";

    /** The categories element name */
    public static final String CATEGORIES_TAG = "Categories";

    /** The count attribute name */
    public static final String ATTR_COUNT = "count";

    /** The maximum ID attribute name */
    public static final String ATTR_MAX_ID = "max-id";

    /** Name of categories child elements */
    public static final String CATEGORIES_ITEM = "category";

    /** The notes element name */
    public static final String NOTES_TAG = "NoteList";

    /** Name of th e note list child elements */
    public static final String NOTE_ITEM = "item";

    /** The category ID attribute name */
    public static final String ATTR_CATEGORY_ID = "category";

    /** The private flag attribute name */
    public static final String ATTR_PRIVATE = "private";

    /** The encryption level attribute name */
    public static final String ATTR_ENCRYPTION = "encryption";

    /** The name of the creation time sub-element */
    public static final String NOTE_CREATED = "created";

    /** The name of the modification time sub-element */
    public static final String NOTE_MODIFIED = "modified";

    /** The time attribute name */
    public static final String ATTR_TIME = "time";

    /** The name of the note sub-element */
    public static final String NOTE_NOTE = "note";

    /** Modes of operation */
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
        modeText.put(OpMode.SETTINGS, "Exporting application settings\u2026");
        modeText.put(OpMode.CATEGORIES, "Exporting categories\u2026");
        modeText.put(OpMode.ITEMS, "Exporting Notes\u2026");
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

    /**
     * Export the preferences, metadata, categories, and notes
     * from the database to an XML file.
     *
     * @param prefs the NotePad preferences.
     * @param repository The repository from which to read records.
     * It should have already been opened by the caller.
     * @param outStream the stream to which we should write the data in XML.
     * @param exportPrivate whether to include private records and the
     * password hash in the export.  This will include encrypted records;
     * we don&rsquo;t decrypted anything here, just write the encrypted data.
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     */
    public static void export(NotePreferences prefs,
                              NoteRepository repository,
                              OutputStream outStream,
                              boolean exportPrivate,
                              ProgressBarUpdater progressUpdater) {

        try (PrintStream out = new PrintStream(outStream)) {
            // Get all of the preferences, metadata, and categories;
            // these should be very short collections.
            Map<String,?> prefsMap = prefs.getAllPreferences();
            List<NoteMetadata> metadata = repository.getMetadata();
            List<NoteCategory> categories = repository.getCategories();
            // Get the total count of items to export
            int noteCount = repository.countNotes();
            if (!exportPrivate) {
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
            int totalCount = prefsMap.size() + metadata.size()
                    + categories.size() + noteCount;

            out.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            out.printf(Locale.US, "<%s %s=\"2\" %s=\"%d\" %s=\"%s\" %s=\"%d\">\n",
                    DOCUMENT_TAG, ATTR_VERSION,
                    ATTR_DB_VERSION, NoteRepositoryImpl.DATABASE_VERSION,
                    ATTR_EXPORTED, Instant.now().atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_INSTANT),
                    ATTR_TOTAL_RECORDS, totalCount);

            progressUpdater.updateProgress(modeText.get(OpMode.SETTINGS),
                    0, totalCount, true);
            int prefsCount = writePreferences(prefsMap, out);
            int metaCount = writeMetadata(metadata, out);

            progressUpdater.updateProgress(modeText.get(OpMode.CATEGORIES),
                    prefsCount + metaCount, totalCount, true);
            long maxCatId = repository.getMaxCategoryId();
            int catCount = writeCategories(categories, maxCatId, out);

            progressUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                    prefsCount + metaCount + catCount, totalCount, true);
            long maxNoteId = repository.getMaxNoteId();
            NoteCursor cursor = repository.getNotes(
                    NotePreferences.ALL_CATEGORIES,
                    exportPrivate, exportPrivate,
                    NoteRepositoryImpl.NOTE_TABLE_NAME + "."
                    + NoteSchema.NoteItemColumns._ID);
            noteCount = writeNotes(cursor, maxNoteId, out, progressUpdater,
                    prefsCount + metaCount + catCount, totalCount);

            progressUpdater.updateProgress(modeText.get(OpMode.FINISH),
                    prefsCount + metaCount + catCount + noteCount,
                    totalCount, false);

            out.printf(Locale.US, "</%s>\n", DOCUMENT_TAG);
        }
    }

    private static final Pattern XML_RESERVED_CHARACTERS =
        Pattern.compile("[\"&'<>]");

    /** Escape a string for XML sequences */
    public static String escapeXML(String raw) {
        if (raw == null)
            raw = "";
        Matcher m = XML_RESERVED_CHARACTERS.matcher(raw);
        if (m.find()) {
            String step1 = raw.replace("&", "&amp;");
            String step2 = step1.replace("<", "&lt;");
            String step3 = step2.replace(">", "&gt;");
            String step4 = step3.replace("\"", "&quot;");
            String step5 = step4.replace("'", "&apos;");
            return step5;
        } else {
            return raw;
        }
    }

    /** RFC 3548 sec. 4 */
    private static final char[] BASE64_CHARACTERS = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
        'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
        'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
        'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_',
    };

    /** Convert a stream of bytes to Base64 */
    public static String encodeBase64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        // Process bytes in groups of three
        int i;
        for (i = 0; i + 3 <= data.length; i += 3) {
            // Insert line breaks every 64 characters
            if ((i > 0) && (i % 48 == 0))
                sb.append(System.getProperty("line.separator", "\n"));
            sb.append(BASE64_CHARACTERS[(data[i] >> 2) & 0x3f])
            .append(BASE64_CHARACTERS[((data[i] & 3) << 4) + ((data[i+1] >> 4) & 0x0f)])
            .append(BASE64_CHARACTERS[((data[i+1] & 0xf) << 2) + ((data[i+2] >> 6) & 3)])
            .append(BASE64_CHARACTERS[data[i+2] & 0x3f]);
        }
        // Special handling for the last one or two bytes -- no padding
        if (i < data.length) {
            sb.append(BASE64_CHARACTERS[(data[i] >> 2) & 0x3f]);
            if (i + 1 < data.length) {
                sb.append(BASE64_CHARACTERS[((data[i] & 3) << 4) + ((data[i+1] >> 4) & 0x0f)]);
                sb.append(BASE64_CHARACTERS[(data[i+1] & 0xf) << 2]);
            } else {
                sb.append(BASE64_CHARACTERS[(data[i] & 3) << 4]);
            }
        }
        return sb.toString();
    }

    /** Write out the preferences section */
    static int writePreferences(
            Map<String,?> prefs, PrintStream out) {
        out.printf(Locale.US, "  <%s %s=\"%d\">\n",
                PREFERENCES_TAG, ATTR_COUNT, prefs.size());
        for (String key : prefs.keySet()) {
            Object value = prefs.get(key);
            out.printf(Locale.US, "    <%s>%s</%s>\n",
                    key, escapeXML((value == null) ? null
                            : value.toString()), key);
        }
        out.printf(Locale.US, "  </%s>\n", PREFERENCES_TAG);
        Log.i(LOG_TAG, String.format("Wrote %d preference settings",
                prefs.size()));
        return prefs.size();
    }

    /** Write out the metadata */
    static int writeMetadata(
            List<NoteMetadata> metadata, PrintStream out) {
        out.printf(Locale.US, "  <%s %s=\"%d\">\n",
                METADATA_TAG, ATTR_COUNT, metadata.size());
        for (NoteMetadata datum : metadata) {
            out.printf(Locale.US, "    <%s %s=\"%d\" %s=\"%s\"",
                    METADATA_ITEM, ATTR_ID, datum.getId(), ATTR_NAME,
                    escapeXML(datum.getName()));
            if (datum.getValue() == null)
                out.println("/>");
            else out.printf(Locale.US, ">%s</%s>\n",
                    encodeBase64(datum.getValue()), METADATA_ITEM);
        }
        out.printf(Locale.US, "  </%s>\n", METADATA_TAG);
        Log.i(LOG_TAG, String.format("Wrote %d metadata items",
                metadata.size()));
        return metadata.size();
    }

    /** Write the category list
     *
     * @param categories the categories to write
     * @param maxId the highest category ID in the database
     * @param out the PrintStream to which we should write the data
     *
     * @return the total number of categories written
     */
    static int writeCategories(
            List<NoteCategory> categories, long maxId, PrintStream out) {
        out.printf(Locale.US, "  <%s %s=\"%d\" %s=\"%d\">\n",
                CATEGORIES_TAG, ATTR_COUNT, categories.size(),
                ATTR_MAX_ID, maxId);
        for (NoteCategory category : categories) {
            out.printf(Locale.US, "    <%s %s=\"%d\">%s</%s>\n",
                    CATEGORIES_ITEM, ATTR_ID, category.getId(),
                    escapeXML(category.getName()), CATEGORIES_ITEM);
        }
        out.printf(Locale.US, "  </%s>\n", CATEGORIES_TAG);
        Log.i(LOG_TAG, String.format("Wrote %d categories",
                categories.size()));
        return categories.size();
    }

    /** Write the notes
     *
     * @param cursor the cursor over the notes to write
     * @param maxId the highest note ID in the database
     * @param out the PrintStream to which we should write the data
     * @param progressUpdater a class to call back while we are processing
     * the data to mark our progress.
     * @param baseCount the number of records written from previous stages
     * @param totalCount the total number of records to be written
     * for the progress bar
     *
     * @return the total number of note written
     */
    static int writeNotes(NoteCursor cursor, long maxId, PrintStream out,
                          ProgressBarUpdater progressUpdater,
                          int baseCount, int totalCount) {
        out.printf(Locale.US, "  <%s %s=\"%d\" %s=\"%d\">\n",
                NOTES_TAG, ATTR_COUNT, cursor.getCount(),
                ATTR_MAX_ID, maxId);
        int count = 0;
        while (cursor.moveToNext()) {
            NoteItem note = cursor.getNote();
            writeNoteItem(note, out);
            count++;
            progressUpdater.updateProgress(modeText.get(OpMode.ITEMS),
                    baseCount + count, totalCount, true);
        }
        out.printf(Locale.US, "  </%s>\n", NOTES_TAG);
        return count;
    }

    /**
     * Write out a single note
     *
     * @param note the note to write
     * @param out the PrintStream to which we should write the note
     */
    static void writeNoteItem(NoteItem note, PrintStream out) {
        out.printf(Locale.US,
                "    <%s %s=\"%d\" %s=\"%d\"",
                NOTE_ITEM, ATTR_ID, note.getId(),
                ATTR_CATEGORY_ID, note.getCategoryId());
        if (note.isPrivate()) {
            out.printf(Locale.US, " %s=\"%s\"",
                    ATTR_PRIVATE, note.isPrivate());
            if (note.isEncrypted())
                out.printf(Locale.US, " %s=\"%d\"",
                        ATTR_ENCRYPTION, note.getPrivate());
        }
        out.println(">");
        out.printf(Locale.US, "      <%s %s=\"%s\"/>\n",
                NOTE_CREATED, ATTR_TIME,
                note.getCreateTime().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT));
        out.printf(Locale.US, "      <%s %s=\"%s\"/>\n",
                NOTE_MODIFIED, ATTR_TIME,
                note.getModTime().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT));
        out.printf(Locale.US, "        <%s>%s</%s>\n",
                NOTE_NOTE, note.isEncrypted()
                        ? encodeBase64(note.getEncryptedNote())
                        : escapeXML(note.getNote()),
                NOTE_NOTE);
        out.printf(Locale.US, "    </%s>\n", NOTE_ITEM);
    }

}
