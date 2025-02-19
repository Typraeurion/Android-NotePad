/*
 * $Id: XMLImporterService.java,v 1.1 2014/04/06 21:49:48 trevin Exp trevin $
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
 * $Log: XMLImporterService.java,v $
 * Revision 1.1  2014/04/06 21:49:48  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import static com.xmission.trevin.android.notes.XMLExporterService.*;

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

import com.xmission.trevin.android.notes.Note.NoteCategory;
import com.xmission.trevin.android.notes.Note.NoteItem;

import android.app.IntentService;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.util.LongSparseArray;
import android.widget.Toast;

/**
 * This class imports the note list from an XML file on external storage.
 * There are several modes of import available; see the enumeration
 * {@link ImportType} for details on each mode.
 *
 * If a password was used for the exported XML file or on the database,
 * the password <b>must be the same</b> in both in order to import any
 * encrypted records, and the password must have been provided to the
 * application.  If they are not, the unencrypted records may be
 * imported but all encrypted records will be skipped.
 */
public class XMLImporterService extends IntentService implements
	ProgressReportingService {

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

    /**
     * Flag indicating how to merge items from the XML file
     * with those in the database.
     */
    public enum ImportType {
	/** The database should be cleared before importing the XML file. */
	CLEAN,

	/**
	 * Any items in the database with the same internal ID
	 * and creation time as an item in the XML file should
	 * be overwritten, regardless of which one is newer.
	 */
	REVERT,

	/**
	 * Any item is the database with the same internal ID
	 * and creation time as an item in the XML file should
	 * be overwritten if the modification time of the item
	 * in the XML file is newer.
	 */
	UPDATE,

	/**
	 * Any items in the XML file with the same internal ID as an
	 * item in the Android database should be added as a new item
	 * with a newly assigned ID.  Will result in duplicates if the
	 * file had been imported before, but is the safest option
	 * if importing a different file.
	 */
	ADD,

	/**
	 * Don't actually write anything to the android database.
	 * Just read the XML file to verify the integrity of the data.
	 */
	TEST,
    }

    /** The location of the notes.xml file */
    private File dataFile;

    /** The current mode of operation */
    public enum OpMode {
	PARSING, SETTINGS, CATEGORIES, ITEMS
    }
    private OpMode currentMode = OpMode.PARSING;

    /** The current number of entries imported */
    private int importCount = 0;

    /** The total number of entries to be imported */
    private int totalCount = 0;

    /** Category entry from the XML file */
    protected static class CategoryEntry {
	/** The category ID in the XML file */
	long id;
	String name;
	/** If adding, the new category ID in the Android database */
	long newID;
    }

    /** Categories from the XML file, mapped by the XML id */
    protected LongSparseArray<CategoryEntry> categoriesByID =
	new LongSparseArray<>();

    /** Next free record ID (counting both the Palm and Android databases) */
    private long nextFreeRecordID = 1;

    public class ImportBinder extends Binder {
	XMLImporterService getService() {
	    Log.d(LOG_TAG, "ImportBinder.getService()");
	    return XMLImporterService.this;
	}
    }

    private ImportBinder binder = new ImportBinder();

    /** Create the exporter service with a named worker thread */
    public XMLImporterService() {
	super(XMLImporterService.class.getSimpleName());
	Log.d(LOG_TAG, "created");
	// If we die in the middle of an import, restart the request.
	setIntentRedelivery(true);
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
	dataFile = new File(intent.getStringExtra(XML_DATA_FILENAME));
	// Get the import type
	ImportType importType = (ImportType)
		intent.getSerializableExtra(XML_IMPORT_TYPE);
	boolean importPrivate = Boolean.TRUE.equals(
		intent.getSerializableExtra(IMPORT_PRIVATE));
	Log.d(LOG_TAG, ".onHandleIntent(" + importType + ",\""
		+ dataFile.getAbsolutePath() + "\")");
	importCount = 0;
	totalCount = 0;

	if (!dataFile.exists()) {
	    Toast.makeText(this, String.format(
		    getString(R.string.ErrorImportNotFound),
		    dataFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
	    return;
	}
	if (!dataFile.canRead()) {
	    Toast.makeText(this, String.format(
		    getString(R.string.ErrorImportCantRead),
		    dataFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
	    return;
	}

	try {
	    // Start parsing
	    currentMode = OpMode.PARSING;
	    DocumentBuilder builder =
		DocumentBuilderFactory.newInstance().newDocumentBuilder();
	    Document document = builder.parse(dataFile);
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
			    StringEncryption.METADATA_PASSWORD_HASH[0])) {
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
					R.string.ToastBadPassword), Toast.LENGTH_LONG).show();
				Log.d(LOG_TAG, "Password does not match hash in the XML file");
				return;
			    }
			} else {
			    Toast.makeText(this, getResources().getString(
				    R.string.ToastPasswordProtected), Toast.LENGTH_LONG).show();
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
     * Merge the category list from the XML file
     * with the Android database.
     */
    void mergeCategories(ImportType importType, List<Element> categories) {
	Log.d(LOG_TAG, ".mergeCategories(" + importType + ")");
	// Read in the current list of categories
	Map<Long,String> categoryIDMap = new HashMap<>();
	Map<String,Long> categoryNameMap = new HashMap<>();
	ContentResolver resolver = getContentResolver();
	Cursor c = resolver.query(NoteCategory.CONTENT_URI, new String[] {
		NoteCategory._ID, NoteCategory.NAME }, null, null, null);
	while (c.moveToNext()) {
	    long id = c.getLong(c.getColumnIndex(NoteCategory._ID));
	    String name = c.getString(c.getColumnIndex(NoteCategory.NAME));
	    categoryIDMap.put(id, name);
	    categoryNameMap.put(name, id);
	}
	c.close();

	if (importType == ImportType.CLEAN) {
	    Log.d(LOG_TAG, ".mergeCategories: removing all existing categories");
	    resolver.delete(NoteCategory.CONTENT_URI, null, null);
	    categoryIDMap.clear();
	    categoryNameMap.clear();
	}

	ContentValues values = new ContentValues();
	for (Element categorE : categories) {
	    CategoryEntry entry = new CategoryEntry();
	    entry.name = getText(categorE);
	    entry.id = Integer.parseInt(categorE.getAttribute("id"));
	    // Skip the NoteCategory.UNFILED
	    if (entry.id == NoteCategory.UNFILED) {
		importCount++;
		continue;
	    }

	    entry.newID = entry.id;
	    categoriesByID.put(entry.id, entry);

	    switch (importType) {
	    case CLEAN:
		// There are no pre-existing categories
		Log.d(LOG_TAG, ".mergeCategories: adding " + entry.id
			+ " \"" + entry.name + "\"");
		values.put(NoteCategory._ID, entry.id);
		values.put(NoteCategory.NAME, entry.name);
		resolver.insert(NoteCategory.CONTENT_URI, values);
		break;

	    case REVERT:
		// Always overwrite
		if (categoryNameMap.containsKey(entry.name) &&
			(categoryNameMap.get(entry.name) != entry.id)) {
		    long oldId = categoryNameMap.get(entry.name);
		    Log.d(LOG_TAG, ".mergeCategories: \"" + entry.name
			    + "\" already exists with ID " + oldId
			    + "; deleting it.");
		    // Change the category of all items using the old ID
		    values.clear();
		    values.put(NoteItem.CATEGORY_ID, oldId);
		    resolver.update(NoteItem.CONTENT_URI, values,
			    NoteItem.CATEGORY_ID + "=" + oldId, null);
		    values.clear();
		    values.put(NoteCategory._ID, oldId);
		    resolver.delete(ContentUris.withAppendedId(
			    NoteCategory.CONTENT_URI, oldId), null, null);
		    categoryIDMap.remove(oldId);
		    categoryNameMap.remove(entry.name);
		}
		if (categoryIDMap.containsKey(entry.id)) {
		    if (!categoryIDMap.get(entry.id).equals(entry.name)) {
			Log.d(LOG_TAG, ".mergeCategories: replacing \""
				+ categoryIDMap.get(entry.id)
				+ "\" with \"" + entry.name + "\"");
			values.remove(NoteCategory._ID);
			values.put(NoteCategory.NAME, entry.name);
			resolver.update(ContentUris.withAppendedId(
				NoteCategory.CONTENT_URI, entry.id),
				values, null, null);
		    }
		}
		else {
		    Log.d(LOG_TAG, ".mergeCategories: adding \""
			    + entry.name + "\"");
		    values.put(NoteCategory._ID, entry.id);
		    values.put(NoteCategory.NAME, entry.name);
		    resolver.insert(NoteCategory.CONTENT_URI, values);
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
			values.remove(NoteCategory._ID);
			values.put(NoteCategory.NAME, entry.name);
			Uri newItem = resolver.insert(
				NoteCategory.CONTENT_URI, values);
			entry.newID = Long.parseLong(
				newItem.getPathSegments().get(1));
		    } else {
			values.put(NoteCategory._ID, entry.id);
			values.put(NoteCategory.NAME, entry.name);
			resolver.insert(NoteCategory.CONTENT_URI, values);
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
	ContentResolver resolver = getContentResolver();
	StringEncryption newCrypt = StringEncryption.holdGlobalEncryption();

	try {
	    if (importType == ImportType.CLEAN) {
		Log.d(LOG_TAG, ".mergeNotes: removing all existing To Do items");
		resolver.delete(NoteItem.CONTENT_URI, null, null);
	    }

	    final String[] EXISTING_ITEM_PROJECTION = {
		    NoteItem._ID, NoteItem.CATEGORY_ID, NoteItem.CATEGORY_NAME,
		    NoteItem.PRIVATE, NoteItem.NOTE,
		    NoteItem.CREATE_TIME, NoteItem.MOD_TIME };

	    // Find the highest available record ID
	    Cursor c = resolver.query(NoteItem.CONTENT_URI,
		    EXISTING_ITEM_PROJECTION, null, null,
		    // The table prefix is required here because
		    // the provider joins the note table with the category table.
		    NoteProvider.NOTE_TABLE_NAME + "." + NoteItem._ID + " DESC");
	    if (c.moveToFirst()) {
		long nextID = c.getLong(c.getColumnIndex(NoteItem._ID));
		if (nextID >= nextFreeRecordID)
		    nextFreeRecordID = nextID + 1;
	    }
	    c.close();

	    ContentValues values = new ContentValues();
	    ContentValues existingRecord = new ContentValues();
	    for (Element itemE : items) {
		Map<String,Element> itemMap = mapChildren(itemE);
		values.clear();
		String value = itemE.getAttribute("id");
		values.put(NoteItem._ID, Long.parseLong(value));
		value = itemE.getAttribute("category");
		long categoryID = Integer.parseInt(value);
		if (categoriesByID.get(categoryID) != null)
		    categoryID = categoriesByID.get(categoryID).newID;
		else
		    categoryID = NoteCategory.UNFILED;
		values.put(NoteItem.CATEGORY_ID, (int) categoryID);

		value = itemE.getAttribute("private");
		int privacy = 0;
		if (Boolean.parseBoolean(value)) {
		    value = itemE.getAttribute("encryption");
		    if (!isEmpty(value))
			privacy = Integer.parseInt(value);
		    else
			privacy = 1;
		}

		Element child = itemMap.get("created");
		value = child.getAttribute("time");
		values.put(NoteItem.CREATE_TIME, parseDate(value).getTime());
		child = itemMap.get("modified");
		value = child.getAttribute("time");
		values.put(NoteItem.MOD_TIME, parseDate(value).getTime());
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
			values.put(NoteItem.NOTE, encryptedNote);
			privacy = 2;
		    } else {
			privacy = 1;
		    }
		}
		if (privacy < 2)
		    values.put(NoteItem.NOTE, note);
		values.put(NoteItem.PRIVATE, privacy);

		if (importType != ImportType.CLEAN) {
		    existingRecord.clear();
		    c = resolver.query(ContentUris.withAppendedId(
			    NoteItem.CONTENT_URI, values.getAsLong(NoteItem._ID)),
			    EXISTING_ITEM_PROJECTION, null, null, null);
		    if (c.moveToFirst()) {
			int oldPrivacy =
			    c.getInt(c.getColumnIndex(NoteItem.PRIVATE));
			existingRecord.put(NoteItem.PRIVATE, oldPrivacy);
			existingRecord.put(NoteItem.CATEGORY_ID,
				c.getLong(c.getColumnIndex(NoteItem.CATEGORY_ID)));
			existingRecord.put(NoteItem.CATEGORY_NAME,
				c.getString(c.getColumnIndex(NoteItem.CATEGORY_NAME)));
			existingRecord.put(NoteItem.CREATE_TIME,
				c.getLong(c.getColumnIndex(NoteItem.CREATE_TIME)));
			existingRecord.put(NoteItem.MOD_TIME,
				c.getLong(c.getColumnIndex(NoteItem.MOD_TIME)));
		    }
		    c.close();
		}

		Operation op = Operation.INSERT;
		switch (importType) {
		case CLEAN:
		    // All items are new
		    break;

		case REVERT:
		    // Overwrite if it's the same item
		    if (existingRecord.size() > 0) {
			if (values.getAsLong(NoteItem.CREATE_TIME).equals(
			    existingRecord.getAsLong(NoteItem.CREATE_TIME)))
			    op = Operation.UPDATE;
			else
			    // Not the same note!
			    values.put(NoteItem._ID, nextFreeRecordID++);
		    }
		    break;

		case UPDATE:
		    // Overwrite if it's the same item and newer
		    if (existingRecord.size() > 0) {
			if (values.getAsLong(NoteItem.CREATE_TIME).equals(
				existingRecord.getAsLong(NoteItem.CREATE_TIME))) {
			    if (values.getAsLong(NoteItem.MOD_TIME) >
				existingRecord.getAsLong(NoteItem.MOD_TIME))
				op = Operation.UPDATE;
			    else
				op = Operation.SKIP;
			} else {
			    // Not the same note!
			    values.put(NoteItem._ID, nextFreeRecordID++);
			}
		    }
		    break;

		case ADD:
		    // All items are new, but may need a new ID
		    if (existingRecord.size() > 0)
			values.put(NoteItem._ID, nextFreeRecordID++);
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
			if (values.getAsInteger(NoteItem.PRIVATE) == 0) {
			    shortNote = values.getAsString(NoteItem.NOTE);
			    if (shortNote.length() > 64)
				shortNote = shortNote.substring(0, 64);
			}
			Log.d(LOG_TAG, ".mergeNotes: adding "
				+ values.getAsLong(NoteItem._ID)
				+ " \"" + shortNote + "\"");
		    }
		    resolver.insert(NoteItem.CONTENT_URI, values);
		    break;

		case UPDATE:
		    if (items.size() < 64) {
			String shortOldNote = "[private]";
			String shortNewNote = "[private]";
			if (existingRecord.getAsInteger(NoteItem.PRIVATE) == 0) {
			    shortOldNote = values.getAsString(NoteItem.NOTE);
			    if (shortOldNote.length() > 64)
				shortOldNote = shortOldNote.substring(0, 64);
			}
			if (values.getAsInteger(NoteItem.PRIVATE) == 0) {
			    shortNewNote = values.getAsString(NoteItem.NOTE);
			    if (shortNewNote.length() > 64)
				shortNewNote = shortNewNote.substring(0, 64);
			}
			Log.d(LOG_TAG, ".mergeNotes: replacing existing record "
				+ values.getAsLong(NoteItem._ID)
				+ " \"" + shortOldNote + "\" with \""
				+ shortNewNote + "\"");
		    }
		    resolver.update(ContentUris.withAppendedId(NoteItem.CONTENT_URI,
			    values.getAsLong(NoteItem._ID)), values, null, null);
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, ".onBind");
	return binder;
    }
}
