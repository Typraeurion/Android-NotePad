/*
 * $Id: XMLExporterService.java,v 1.1 2014/04/06 21:19:39 trevin Exp trevin $
 * Copyright © 2014 Trevin Beattie
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
 *
 * $Log: XMLExporterService.java,v $
 * Revision 1.1  2014/04/06 21:19:39  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

import android.app.IntentService;
import android.content.*;
import android.database.Cursor;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.xmission.trevin.android.notes.Note.*;

public class XMLExporterService extends IntentService implements
	ProgressReportingService {

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

    /** The location of the notes.xml file */
    private File dataFile;

    /** The current mode of operation */
    public enum OpMode {
	SETTINGS, CATEGORIES, ITEMS
    };
    private OpMode currentMode = OpMode.SETTINGS;

    /** Whether private records should be exported */
    private boolean exportPrivate = true;

    /** The current number of entries exported */
    private int exportCount = 0;

    /** The total number of entries to be exported */
    private int totalCount = 0;

    public class ExportBinder extends Binder {
	XMLExporterService getService() {
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

    @Override
    public int getMaxCount() { return totalCount; }

    @Override
    public int getChangedCount() { return exportCount; }

    /** Format a Date for XML output */
    public final static SimpleDateFormat DATE_FORMAT =
	new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    static { DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC")); }

    /** Called when an activity requests an export */
    @Override
    protected void onHandleIntent(Intent intent) {
	// Get the location of the notes.xml file
	dataFile = new File(intent.getStringExtra(XML_DATA_FILENAME));
	exportPrivate = intent.getBooleanExtra(EXPORT_PRIVATE, true);
	Log.d(LOG_TAG, ".onHandleIntent(\""
		+ dataFile.getAbsolutePath() + "\", " + exportPrivate + ")");
	exportCount = 0;
	totalCount = 0;

	try {
	    if (!dataFile.exists())
		dataFile.createNewFile();
	    PrintStream out = new PrintStream(
		    new FileOutputStream(dataFile, false));
	    out.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
	    out.println(String.format(
		    "<" + DOCUMENT_TAG + " db-version=\"%d\" exported=\"%s\">",
		    NoteProvider.DATABASE_VERSION,
		    DATE_FORMAT.format(new Date())));
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
			Toast.LENGTH_LONG);
	    }
	    out.close();
	} catch (IOException iofx) {
	    Toast.makeText(this, iofx.getMessage(), Toast.LENGTH_LONG);
	}
    }

    private static final Pattern XML_RESERVED_CHARACTERS =
	Pattern.compile("[\"&'<>]");

    /** Escape a string for XML sequences */
    public static String escapeXML(String raw) {
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
	SharedPreferences prefs = this.getSharedPreferences(
		NoteListActivity.NOTE_PREFERENCES, MODE_PRIVATE);
	Map<String,?> prefMap = prefs.getAll();
	out.println("    <" + PREFERENCES_TAG + ">");
	for (String key : prefMap.keySet()) {
	    out.println(String.format("\t<%s>%s</%s>",
		    key, escapeXML(prefMap.get(key).toString()), key));
	}
	out.println("    </" + PREFERENCES_TAG + ">");
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
		NoteMetadata._ID,
		NoteMetadata.NAME,
		NoteMetadata.VALUE,
	};
	Cursor c = getContentResolver().query(NoteMetadata.CONTENT_URI,
		PROJECTION, null, null, NoteMetadata.NAME);
	try {
	    out.println("    <" + METADATA_TAG + ">");
	    while (c.moveToNext()) {
		String name = c.getString(c.getColumnIndex(NoteMetadata.NAME));
		// Skip the password if we are not exporting private records
		if (StringEncryption.METADATA_PASSWORD_HASH[0].equals(name) &&
			!exportPrivate)
		    continue;
		int ival = c.getColumnIndex(NoteMetadata.VALUE);
		out.print(String.format("\t<item id=\"%d\" name=\"%s\"",
			c.getLong(c.getColumnIndex(NoteMetadata._ID)),
			escapeXML(name)));
		if (c.isNull(ival)) {
		    out.println("/>");
		} else {
		    out.println(String.format(">%s</item>",
			    encodeBase64(c.getBlob(ival))));
		}
	    }
	    out.println("    </" + METADATA_TAG + ">");
	} finally {
	    c.close();
	}
    }

    /** Write the category list */
    protected void writeCategories(PrintStream out) {
	final String[] PROJECTION = {
		NoteCategory._ID,
		NoteCategory.NAME,
	};
	Cursor c = getContentResolver().query(NoteCategory.CONTENT_URI,
		PROJECTION, null, null, NoteCategory.NAME);
	totalCount = c.getCount();
	exportCount = 0;
	try {
	    out.println("    <" + CATEGORIES_TAG + ">");
	    while (c.moveToNext()) {
		String name = c.getString(c.getColumnIndex(NoteCategory.NAME));
		out.println(String.format("\t<category id=\"%d\">%s</category>",
			c.getLong(c.getColumnIndex(NoteCategory._ID)),
			escapeXML(name)));
		exportCount++;
	    }
	    out.println("    </" + CATEGORIES_TAG + ">");
	} finally {
	    c.close();
	}
    }

    /** Write the notes */
    protected void writeNotes(PrintStream out) {
	final String[] PROJECTION = {
		NoteItem._ID,
		NoteItem.CATEGORY_ID,
		NoteItem.CREATE_TIME,
		NoteItem.MOD_TIME,
		NoteItem.PRIVATE,
		NoteItem.NOTE,
	};
	Cursor c = getContentResolver().query(NoteItem.CONTENT_URI,
		PROJECTION, null, null,
		NoteProvider.NOTE_TABLE_NAME + "." + NoteItem._ID);
	totalCount = c.getCount();
	exportCount = 0;

	try {
	    out.println("    <" + ITEMS_TAG + ">");
	    while (c.moveToNext()) {
		int privacy = c.getInt(c.getColumnIndex(NoteItem.PRIVATE));
		if (!exportPrivate && (privacy > 0))
		    continue;
		out.print(String.format("\t<item id=\"%d\" category=\"%d\"",
			c.getLong(c.getColumnIndex(NoteItem._ID)),
			c.getLong(c.getColumnIndex(NoteItem.CATEGORY_ID))));
		if (privacy != 0) {
		    out.print(" private=\"true\"");
		    if (privacy > 1)
			out.print(String.format(" encryption=\"%d\"", privacy));
		}
		out.println(">");
		out.println(String.format("\t    <created time=\"%s\"/>",
			DATE_FORMAT.format(new Date(c.getLong(
				c.getColumnIndex(NoteItem.CREATE_TIME))))));
		out.println(String.format("\t    <modified time=\"%s\"/>",
			DATE_FORMAT.format(new Date(c.getLong(
				c.getColumnIndex(NoteItem.MOD_TIME))))));
		out.print("\t    <note>");
		int i = c.getColumnIndex(NoteItem.NOTE);
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
