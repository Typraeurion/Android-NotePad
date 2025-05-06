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

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

import android.app.IntentService;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.provider.NoteProvider;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * This class exports the Note Pad notes to an XML file on external storage.
 *
 * @author Trevin Beattie
 */
public class XMLExporterService extends IntentService
        implements ProgressReportingService {

    public static final String LOG_TAG = "XMLExporterService";

    /**
     * The name of the Intent extra data that holds
     * the location of the notes.xml file
     */
    public static final String XML_DATA_FILENAME =
        "com.xmission.trevin.android.notes.XMLDataFileName";

    /**
     * The name of the Intent extra that indicates whether to
     * export private records.
     */
    public static final String EXPORT_PRIVATE =
        "com.xmission.trevin.android.notes.XMLExportPrivate";

    /** The document element name */
    public static final String DOCUMENT_TAG = "NotePadApp";

    /** The preferences element name */
    public static final String PREFERENCES_TAG = "Preferences";

    /** The metadata element name */
    public static final String METADATA_TAG = "Metadata";

    /** The categories element name */
    public static final String CATEGORIES_TAG = "Categories";

    /** The to-do items element name */
    public static final String ITEMS_TAG = "NoteList";

    /** The current mode of operation */
    public enum OpMode {
        SETTINGS, CATEGORIES, ITEMS
    }
    private OpMode currentMode = OpMode.SETTINGS;

    /** Whether private records should be exported */
    private boolean exportPrivate = true;

    /** The current number of entries exported */
    private int exportCount = 0;

    /** The total number of entries to be exported */
    private int totalCount = 0;

    public class ExportBinder extends Binder {
        public XMLExporterService getService() {
            Log.d(LOG_TAG, "ExportBinder.getService()");
            return XMLExporterService.this;
        }
    }

    private ExportBinder binder = new ExportBinder();

    /** Create the exporter service with a named worker thread */
    public XMLExporterService() {
        super(XMLExporterService.class.getSimpleName());
        Log.d(LOG_TAG, "created");
        // If we die in the middle of an import, restart the request.
        setIntentRedelivery(true);
    }

    /** @return the current mode of operation */
    @Override
    public String getCurrentMode() {
        switch (currentMode) {
        case SETTINGS:
            return getString(R.string.ProgressMessageExportSettings);
        case CATEGORIES:
            return getString(R.string.ProgressMessageExportCategories);
        case ITEMS:
            return getString(R.string.ProgressMessageExportItems);
        default:
            return "";
        }
    }

    /** @return the total number of entries to be changed */
    @Override
    public int getMaxCount() { return totalCount; }

    /** @return the number of entries changed so far */
    @Override
    public int getChangedCount() { return exportCount; }

    /** Format a Date for XML output */
    public final static SimpleDateFormat DATE_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    static { DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC")); }

    /** Called when an activity requests an export */
    @Override
    protected void onHandleIntent(Intent intent) {
        // Get the location of the notes.xml file
        String fileLocation = intent.getStringExtra(XML_DATA_FILENAME);
        exportPrivate = intent.getBooleanExtra(EXPORT_PRIVATE, true);
        Log.d(LOG_TAG, String.format(".onHandleIntent(\"%s\", %s)",
                fileLocation, exportPrivate));
        exportCount = 0;
        totalCount = 0;

        OutputStream oStream;

        if (fileLocation.startsWith("content://")) {
            // This is a URI from the Storage Access Framework
            try {
                Uri contentUri = Uri.parse(fileLocation);
                oStream = getContentResolver().openOutputStream(
                        contentUri, "wt");
            } catch (Exception e) {
                showFileOpenError(fileLocation, e);
                return;
            }
        } else {
            try {
                File dataFile = new File(fileLocation);
                if (!dataFile.exists())
                    dataFile.createNewFile();
                oStream = new FileOutputStream(dataFile, false);
            } catch (Exception e) {
                showFileOpenError(fileLocation, e);
                return;
            }
        }

        try {
            PrintStream out = new PrintStream(oStream);
            out.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            out.print("<" + DOCUMENT_TAG + " db-version=\""
                      + NoteRepositoryImpl.DATABASE_VERSION + "\" exported=\"");
            out.print(DATE_FORMAT.format(new Date()));
            out.println("\">");
            currentMode = OpMode.SETTINGS;
            writePreferences(out);
            writeMetadata(out);
            currentMode = OpMode.CATEGORIES;
            writeCategories(out);
            currentMode = OpMode.ITEMS;
            writeNotes(out);
            out.println("</" + DOCUMENT_TAG + ">");
            if (out.checkError()) {
                Toast.makeText(this, getString(R.string.ErrorExportFailed),
                        Toast.LENGTH_LONG).show();
            }
            out.close();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * General error handling for opening an export file.
     * This is to reduce repetitive code in catch blocks.
     *
     * @param fileName the name of the file we tried to open
     * @param e the exception that was thrown
     */
    private void showFileOpenError(String fileName, Exception e) {
        Log.e(LOG_TAG, String.format("Failed to open %s for writing",
                fileName), e);
        Toast.makeText(this,
                getString((e instanceof FileNotFoundException)
                        ? R.string.ErrorExportCantMkdirs
                        : R.string.ErrorExportPermissionDenied, fileName),
                Toast.LENGTH_LONG).show();
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

    /** Write out the preferences section */
    protected void writePreferences(PrintStream out) {
        NotePreferences prefs = NotePreferences.getInstance(this);
        Map<String,?> prefMap = prefs.getAllPreferences();
        out.println("    <" + PREFERENCES_TAG + ">");
        for (String key : prefMap.keySet()) {
            out.print("\t<");
            out.print(key);
            out.print(">");
            out.print(escapeXML(prefMap.get(key).toString()));
            out.print("</");
            out.print(key);
            out.println(">");
        }
        out.println("    </" + PREFERENCES_TAG + ">");
        Log.d(LOG_TAG, String.format("Wrote %d preference settings",
                prefMap.size()));
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

    /** Write out the metadata */
    protected void writeMetadata(PrintStream out) {
        final String[] PROJECTION = {
                NoteMetadataColumns._ID,
                NoteMetadataColumns.NAME,
                NoteMetadataColumns.VALUE,
        };
        Cursor c = getContentResolver().query(NoteMetadataColumns.CONTENT_URI,
                PROJECTION, null, null, NoteMetadataColumns.NAME);
        try {
            out.println("    <" + METADATA_TAG + ">");
            int count = 0;
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndex(NoteMetadataColumns.NAME));
                // Skip the password if we are not exporting private records
                if (StringEncryption.METADATA_PASSWORD_HASH[0].equals(name) &&
                        !exportPrivate)
                    continue;
                int ival = c.getColumnIndex(NoteMetadataColumns.VALUE);
                out.print("\t<item id=\"");
                out.print(c.getLong(c.getColumnIndex(NoteMetadataColumns._ID)));
                out.print("\" name=\"");
                out.print(escapeXML(name));
                out.print("\"");
                if (c.isNull(ival)) {
                    out.println("/>");
                } else {
                    out.print(">");
                    out.print(encodeBase64(c.getBlob(ival)));
                    out.println("</item>");
                }
                count++;
            }
            out.println("    </" + METADATA_TAG + ">");
            Log.d(LOG_TAG, String.format("Wrote %d metadata items", count));
        } finally {
            c.close();
        }
    }

    /** Write the category list */
    protected void writeCategories(PrintStream out) {
        final String[] PROJECTION = {
                NoteCategoryColumns._ID,
                NoteCategoryColumns.NAME,
        };
        Cursor c = getContentResolver().query(NoteCategoryColumns.CONTENT_URI,
                PROJECTION, null, null, NoteCategoryColumns.NAME);
        totalCount = c.getCount();
        exportCount = 0;
        try {
            out.println("    <" + CATEGORIES_TAG + ">");
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndex(NoteCategoryColumns.NAME));
                out.print("\t<category id=\"");
                out.print(c.getLong(c.getColumnIndex(NoteCategoryColumns._ID)));
                out.print("\">");
                out.print(escapeXML(name));
                out.println("</category>");
                exportCount++;
            }
            out.println("    </" + CATEGORIES_TAG + ">");
            Log.i(LOG_TAG, String.format("Wrote %d categories", exportCount));
        } finally {
            c.close();
        }
    }

    /** Write the notes */
    protected void writeNotes(PrintStream out) {
        final String[] PROJECTION = {
                NoteItemColumns._ID,
                NoteItemColumns.CATEGORY_ID,
                NoteItemColumns.CREATE_TIME,
                NoteItemColumns.MOD_TIME,
                NoteItemColumns.PRIVATE,
                NoteItemColumns.NOTE,
        };
        Cursor c = getContentResolver().query(NoteItemColumns.CONTENT_URI,
                PROJECTION, null, null,
                NoteProvider.NOTE_TABLE_NAME + "." + NoteItemColumns._ID);
        totalCount = c.getCount();
        exportCount = 0;

        try {
            out.println("    <" + ITEMS_TAG + ">");
            while (c.moveToNext()) {
                int privacy = c.getInt(c.getColumnIndex(NoteItemColumns.PRIVATE));
                if (!exportPrivate && (privacy > 0))
                    continue;
                out.print("\t<item id=\"");
                out.print(c.getLong(c.getColumnIndex(NoteItemColumns._ID)));
                out.print("\" category=\"");
                out.print(c.getLong(c.getColumnIndex(NoteItemColumns.CATEGORY_ID)));
                out.print("\"");
                if (privacy != 0) {
                    out.print(" private=\"true\"");
                    if (privacy > 1) {
                        out.print(" encryption=\"");
                        out.print(privacy);
                        out.print("\"");
                    }
                }
                out.println(">");
                out.print("\t    <created time=\"");
                out.print(DATE_FORMAT.format(new Date(c.getLong(
                                c.getColumnIndex(NoteItemColumns.CREATE_TIME)))));
                out.println("\"/>");
                out.print("\t    <modified time=\"");
                out.print(DATE_FORMAT.format(new Date(c.getLong(
                                c.getColumnIndex(NoteItemColumns.MOD_TIME)))));
                out.println("\"/>");
                out.print("\t    <note>");
                int i = c.getColumnIndex(NoteItemColumns.NOTE);
                if (privacy < 2) {
                    String note = c.getString(i);
                    out.print(escapeXML(note));
                } else {
                    byte[] note = c.getBlob(i);
                    out.print(encodeBase64(note));
                }
                out.println("</note>");
                out.println("\t</item>");
                exportCount++;
            }
            out.println("    </" + ITEMS_TAG + ">");
            Log.i(LOG_TAG, String.format("Wrote %d NotePad items", exportCount));
        } finally {
            c.close();
        }
    }

    /**
     * Called when the service is created.
     */
    @Override
    public void onCreate() {
        Log.d(LOG_TAG, ".onCreate");
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, ".onBind");
        return binder;
    }
}
