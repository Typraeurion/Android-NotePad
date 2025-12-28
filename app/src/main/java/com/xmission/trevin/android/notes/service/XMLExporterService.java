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
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
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

    /** The Note Pad database */
    NoteRepository repository = null;

    /** Handler for making calls involving the UI */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * Observers to call (on the UI thread) when
     * {@link #onHandleIntent(Intent)} is finished
     */
    private final List<HandleIntentObserver> observers = new ArrayList<>();

    public class ExportBinder extends Binder {
        public XMLExporterService getService() {
            Log.d(LOG_TAG, "ExportBinder.getService()");
            return XMLExporterService.this;
        }
    }

    private final ExportBinder binder = new ExportBinder();

    /** Create the exporter service with a named worker thread */
    public XMLExporterService() {
        super(XMLExporterService.class.getSimpleName());
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
        this.repository = repository;
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
                notifyObservers(e);
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
                notifyObservers(e);
                return;
            }
        }

        PrintStream out = null;
        try {
            out = new PrintStream(oStream);
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
                showToast(getString(R.string.ErrorExportFailed));
                notifyObservers(false);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error exporting NotePad to XML", e);
            showToast(e.getMessage());
            notifyObservers(e);
        } finally {
            if (out != null)
                out.close();
            try {
                oStream.close();
            } catch (IOException ioe) {
                Log.e(LOG_TAG, "Error while closing output stream", ioe);
                notifyObservers(ioe);
            }
        }
        if (!out.checkError())
            notifyObservers(true);
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
        showToast(getString((e instanceof FileNotFoundException)
                ? R.string.ErrorExportCantMkdirs
                : R.string.ErrorExportPermissionDenied, fileName));
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
        List<NoteMetadata> metadata = repository.getMetadata();
        out.println("    <" + METADATA_TAG + ">");
        int count = 0;
        for (NoteMetadata datum : metadata) {
            // Skip the password if we are not exporting private records
            if (StringEncryption.METADATA_PASSWORD_HASH
                    .equals(datum.getName()) && !exportPrivate)
                continue;
            // FIXME: Hard-coded element and attribute names
            out.print("\t<item id=\"");
            out.print(datum.getId());
            out.print("\" name=\"");
            out.print(escapeXML(datum.getName()));
            out.print("\"");
            if (datum.getValue() == null) {
                out.println("/>");
            } else {
                out.print(">");
                out.print(encodeBase64(datum.getValue()));
                out.println("</item>");
            }
            count++;
        }
        out.println("    </" + METADATA_TAG + ">");
        Log.d(LOG_TAG, String.format("Wrote %d metadata items", count));
    }

    /** Write the category list */
    protected void writeCategories(PrintStream out) {
        List<NoteCategory> categories = repository.getCategories();
        totalCount = categories.size();
        exportCount = 0;
        out.println("    <" + CATEGORIES_TAG + ">");
        for (NoteCategory category : categories) {
            // FIXME: Hard-coded element and attribute names
            out.print("\t<category id=\"");
            out.print(category.getId());
            out.print("\">");
            out.print(escapeXML(category.getName()));
            out.println("</category>");
            exportCount++;
        }
        out.println("    </" + CATEGORIES_TAG + ">");
        Log.i(LOG_TAG, String.format("Wrote %d categories", exportCount));
    }

    /** Write the notes */
    protected void writeNotes(PrintStream out) {
        NoteCursor c = repository.getNotes(NotePreferences.ALL_CATEGORIES,
                exportPrivate, exportPrivate,
                NoteRepositoryImpl.NOTE_TABLE_NAME + "." + NoteItemColumns._ID);
        totalCount = c.getCount();
        exportCount = 0;

        try {
            out.println("    <" + ITEMS_TAG + ">");
            while (c.moveToNext()) {
                NoteItem note = c.getNote();
                if (!exportPrivate && note.isPrivate())
                    continue;
                // FIXME: Hard-coded element and attribute names
                out.print("\t<item id=\"");
                out.print(note.getId());
                out.print("\" category=\"");
                out.print(note.getCategoryId());
                out.print("\"");
                if (note.isPrivate()) {
                    out.print(" private=\"true\"");
                    if (note.isEncrypted()) {
                        out.print(" encryption=\"");
                        out.print(note.getPrivate());
                        out.print("\"");
                    }
                }
                out.println(">");
                out.print("\t    <created time=\"");
                out.print(DATE_FORMAT.format(new Date(note.getCreateTime())));
                out.println("\"/>");
                out.print("\t    <modified time=\"");
                out.print(DATE_FORMAT.format(new Date(note.getModTime())));
                out.println("\"/>");
                out.print("\t    <note>");
                if (note.isEncrypted())
                    out.print(encodeBase64(note.getEncryptedNote()));
                else
                    out.print(escapeXML(note.getNote()));
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

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, ".onBind");
        return binder;
    }

    public void registerObserver(HandleIntentObserver observer) {
        Log.d(LOG_TAG, String.format(".registerObserver(%s)",
                observer.getClass().getName()));
        observers.add(observer);
    }

    public void unregisterObserver(HandleIntentObserver observer) {
        Log.d(LOG_TAG, String.format(".unregisterObserver(%s)",
                observer.getClass().getName()));
        observers.remove(observer);
    }

    /**
     * Notify all observers that the intent handler is finished.
     * The notifications will all be done on the UI thread.
     *
     * @param success whether the intent handler completed successfully
     */
    private void notifyObservers(boolean success) {
        if (observers.isEmpty())
            // Shortcut out
            return;
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (HandleIntentObserver observer : observers) try {
                    if (success)
                        observer.onComplete();
                    else
                        observer.onRejected();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to notify "
                            + observer.getClass().getName(), e);
                }
            }
        });
    }

    /**
     * Show a toast message.  This must be done on the UI thread.
     * In addition, we&rsquo;ll notify any observers of the message.
     *
     * @param message the message to toast
     */
    private void showToast(final String message) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(XMLExporterService.this, message,
                        Toast.LENGTH_LONG).show();
                for (HandleIntentObserver observer : observers) try {
                    observer.onToast(message);
                } catch (Exception e) {
                    Log.e(LOG_TAG, String.format(
                            "Failed to notify %s of toast \"%s\"",
                            observer.getClass().getName(), message), e);
                }
            }
        });
    }

    /**
     * Notify all observers that an exception has occurred.
     * The notifications will all be done on the UI thread.
     *
     * @param e the exception that occurred
     */
    private void notifyObservers(final Exception e) {
        if (observers.isEmpty())
            // Shortcut out
            return;
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (HandleIntentObserver observer : observers) try {
                    observer.onError(e);
                } catch (Exception e2) {
                    Log.e(LOG_TAG, String.format(
                            "Failed to notify %s of %s",
                            observer.getClass().getName(),
                            e.getClass().getName()), e2);
                }
            }
        });
    }

}
