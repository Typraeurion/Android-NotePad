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
package com.xmission.trevin.android.notes.provider;

import android.app.Activity;
import android.content.Context;
import android.database.DataSetObserver;
import android.database.SQLException;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;

import java.util.*;

import org.apache.commons.lang3.StringUtils;

/**
 * In-memory implementation of the Note Pad repository for use in UI tests.
 *
 * @author Trevin Beattie
 */
public class MockNoteRepository implements NoteRepository {

    private static final String TAG = "MockNoteRepository";

    /** Singleton instance of this repository */
    private static MockNoteRepository instance;

    private String unfiledCategoryName = null;

    private final LinkedHashMap<Context,Integer> openContexts =
            new LinkedHashMap<>();

    /**
     * Category table.  This will be initialized the first time a context
     * opens the repository, since we need the &ldquo;Unfiled&rdquo;
     * category name from the context resources.
     */
    private final Map<Long,String> categories = new TreeMap<>();

    private long nextCategoryId = 1;

    /**
     * Metadata table.  This is keyed by name which is the most common
     * use case.
     */
    private final Map<String,NoteMetadata> metadata = new TreeMap<>();

    private long nextMetadataId = 1;

    /**
     * Note table.  This is indexed by the note ID.
     */
    private final Map<Long,NoteItem> noteTable = new TreeMap<>();

    private long nextNoteId = 1;

    private int transactionLevel = 0;

    /** Observers to call when any Note Pad data changes */
    private final ArrayList<DataSetObserver> registeredObservers =
            new ArrayList<>();

    /**
     * Instantiate the Note Pad repository.  This should be a singleton.
     */
    private MockNoteRepository() {}

    /** @return the singleton instance of the Note Pad repository */
    public static MockNoteRepository getInstance() {
        if (instance == null) {
            instance = new MockNoteRepository();
        }
        return instance;
    }

    /**
     * Call the registered observers when any mock note data changes.
     * The calls <b>must</b> be done on the main UI thread.
     */
    private Runnable observerNotificationRunner = new Runnable() {
        @Override
        public void run() {
            for (DataSetObserver observer : registeredObservers) try {
                observer.onChanged();
            } catch (Exception e) {
                Log.w(TAG, "Caught exception when notifying observer "
                        + observer.getClass().getCanonicalName(), e);
            }
        }
    };

    /**
     * Call the registered observers when any Note Pad data changes
     */
    private void notifyObservers() {
        // Shortcut out if there are no observers
        if (registeredObservers.isEmpty())
            return;

        // Use the context's UI thread if we have any
        for (Context context : openContexts.keySet()) {
            if (context instanceof Activity) {
                ((Activity) context).runOnUiThread(
                        observerNotificationRunner);
                return;
            }
        }

        // Otherwise fall back to the main looper
        new Handler(Looper.getMainLooper()).post(
                observerNotificationRunner);
    }

    @Override
    public void open(@NonNull Context context) throws SQLException {
        Log.d(TAG, ".open");
        if (unfiledCategoryName == null) {
            unfiledCategoryName = context.getString(R.string.Category_Unfiled);
            categories.put((long) NoteCategory.UNFILED, unfiledCategoryName);
        }
        if (openContexts.containsKey(context)) {
            openContexts.put(context, openContexts.get(context) + 1);
            Log.d(TAG, String.format(
                    "Context has opened the repository %d times",
                    openContexts.get(context)));
        } else {
            openContexts.put(context, 1);
        }
    }

    @Override
    public void release(@NonNull Context context) {
        if (!openContexts.containsKey(context)) {
            Log.e(TAG, ".release called from context"
                    + " which did not open the repository!");
            return;
        }
        Log.d(TAG, ".release");
        int openCount = openContexts.get(context) - 1;
        if (openCount > 0) {
            openContexts.put(context, openCount);
            Log.d(TAG, String.format(
                    "Context has %d remaining connections to the repository",
                    openCount));
        } else {
            openContexts.remove(context);
            if (openContexts.isEmpty()) {
                Log.d(TAG, "The last context has released the repository");
            }
        }
    }

    /**
     * Test verification method: determine whether
     * all contexts have closed the repository
     *
     * @return {@code true} if there are no open contexts,
     * {@code false} if there are any still open.
     */
    public boolean isOpenContextsEmpty() {
        return openContexts.isEmpty();
    }

    /**
     * Clear the mock database.  This is intended to be used between tests.
     */
    public synchronized void clear() {
        categories.clear();
        if (unfiledCategoryName != null)
            categories.put((long) NoteCategory.UNFILED, unfiledCategoryName);
        nextCategoryId = 1;
        metadata.clear();
        nextMetadataId = 1;
        noteTable.clear();
        nextNoteId = 1;
        transactionLevel = 0;
    }

    @Override
    public int countCategories() {
        Log.d(TAG, ".countCategories");
        return categories.size();
    }

    /** The categories must be sorted alphabetically */
    private final Comparator<NoteCategory> CATEGORY_COMPARATOR =
            new Comparator<NoteCategory>() {
                @Override
                public int compare(NoteCategory cat1, NoteCategory cat2) {
                    return cat1.getName().compareTo(cat2.getName());
                }
            };

    @Override
    public List<NoteCategory> getCategories() {
        Log.d(TAG, ".getCategories");
        List<NoteCategory> list = new ArrayList<>(categories.size());
        for (Map.Entry<Long,String> categoryEntry : categories.entrySet()) {
            NoteCategory category = new NoteCategory();
            category.setId(categoryEntry.getKey());
            category.setName(categoryEntry.getValue());
            list.add(category);
        }
        Collections.sort(list, CATEGORY_COMPARATOR);
        return list;
    }

    @Override
    public NoteCategory getCategoryById(long categoryId) {
        Log.d(TAG, String.format(".getCategoryById(%d)", categoryId));
        if (categories.containsKey(categoryId)) {
            NoteCategory category = new NoteCategory();
            category.setId(categoryId);
            category.setName(categories.get(categoryId));
            return category;
        }
        return null;
    }

    @Override
    public NoteCategory insertCategory(@NonNull String categoryName)
            throws IllegalArgumentException {
        Log.d(TAG, String.format(".insertCategory(\"%s\")", categoryName));
        if (StringUtils.isEmpty(categoryName))
            throw new IllegalArgumentException("Category name cannot by empty");
        NoteCategory newCategory = new NoteCategory();
        newCategory.setId(nextCategoryId++);
        newCategory.setName(categoryName);
        categories.put(newCategory.getId(), categoryName);
        if (transactionLevel <= 0)
            notifyObservers();
        return newCategory;
    }

    @Override
    public NoteCategory insertCategory(@NonNull NoteCategory newCategory)
            throws IllegalArgumentException {
        Log.d(TAG, String.format(".insertCategory(%s)", newCategory));
        if (newCategory.getId() == null)
            return insertCategory(newCategory.getName());
        if (StringUtils.isEmpty(newCategory.getName()))
            throw new IllegalArgumentException("Category name cannot by empty");
        if (categories.containsKey(newCategory.getId()))
            throw new IllegalArgumentException("Category ID already exists");
        categories.put(newCategory.getId(), newCategory.getName());
        if (transactionLevel <= 0)
            notifyObservers();
        return newCategory;
    }

    @Override
    public synchronized NoteCategory updateCategory(
            long categoryId, @NonNull String newName)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".updateCategory(%d, \"%s\")",
                categoryId, newName));
        if (StringUtils.isEmpty(newName))
            throw new IllegalArgumentException("Category name cannot be empty");
        if ((categoryId == NoteCategory.UNFILED) &&
                !newName.equals(unfiledCategoryName)) {
            Log.w(TAG, String.format("Unfiled category cannot be changed;"
                    + " using \"%s\" instead", unfiledCategoryName));
            newName = unfiledCategoryName;
        }
        if (!categories.containsKey(categoryId))
            throw new SQLException("No rows matched category " + categoryId);
        categories.put(categoryId, newName);
        if (transactionLevel <= 0)
            notifyObservers();
        NoteCategory retCat = new NoteCategory();
        retCat.setId(categoryId);
        retCat.setName(newName);
        return retCat;
    }

    @Override
    public synchronized boolean deleteCategory(long categoryId)
            throws IllegalArgumentException {
        Log.d(TAG, String.format(".deleteCategory(%d)", categoryId));
        if (categoryId == NoteCategory.UNFILED)
            throw new IllegalArgumentException(
                    "Will not delete the Unfiled category");
        if (!categories.containsKey(categoryId))
            return false;
        for (NoteItem note : noteTable.values()) {
            if (note.getCategoryId() == categoryId)
                note.setCategoryId(NoteCategory.UNFILED);
        }
        categories.remove(categoryId);
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public synchronized boolean deleteAllCategories() throws SQLException {
        Log.d(TAG, ".deleteAllCategories");
        if (categories.isEmpty())
            return false;
        if (categories.size() == 1) {
            if (categories.containsKey((long) NoteCategory.UNFILED))
                return false;
        }
        categories.clear();
        if (unfiledCategoryName != null)
            categories.put((long) NoteCategory.UNFILED, unfiledCategoryName);
        for (NoteItem note : noteTable.values())
            note.setCategoryId(NoteCategory.UNFILED);
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public int countMetadata() {
        Log.d(TAG, ".countMetadata");
        return metadata.size();
    }

    /** Metadata must be sorted alphabetically */
    private final Comparator<NoteMetadata> METADATA_COMPARATOR =
            new Comparator<NoteMetadata>() {
                @Override
                public int compare(NoteMetadata meta1, NoteMetadata meta2) {
                    return meta1.getName().compareTo(meta2.getName());
                }
            };

    @Override
    public List<NoteMetadata> getMetadata() {
        Log.d(TAG, ".getMetadata");
        List<NoteMetadata> list = new ArrayList<>(metadata.size());
        for (NoteMetadata datum : metadata.values()) {
            list.add(datum.clone());
        }
        Collections.sort(list, METADATA_COMPARATOR);
        return list;
    }

    @Override
    public NoteMetadata getMetadataByName(@NonNull String key) {
        Log.d(TAG, String.format(".getMetadataByName(\"%s\")", key));
        return metadata.get(key);
    }

    @Override
    public NoteMetadata getMetadataById(long id) {
        Log.d(TAG, String.format(".getMetadataById(%d)", id));
        for (NoteMetadata datum : metadata.values()) {
            if (datum.getId() == id)
                return datum.clone();
        }
        return null;
    }

    @Override
    public synchronized NoteMetadata upsertMetadata(
            @NonNull String name, @NonNull byte[] value)
            throws IllegalArgumentException {
        Log.d(TAG, String.format(".upsertMetadata(\"%s\", (%d bytes))",
                name, value.length));
        if (StringUtils.isEmpty(name))
            throw new IllegalArgumentException("Metadata name cannot be empty");
        NoteMetadata newMeta = new NoteMetadata();
        newMeta.setName(name);
        newMeta.setValue(new byte[value.length]);
        System.arraycopy(value, 0, newMeta.getValue(), 0, value.length);
        NoteMetadata oldMeta = metadata.get(name);
        if (oldMeta != null) {
            newMeta.setId(oldMeta.getId());
            oldMeta.setValue(newMeta.getValue());
        } else {
            newMeta.setId(nextMetadataId++);
            metadata.put(name, newMeta.clone());
        }
        if (transactionLevel <= 0)
            notifyObservers();
        return newMeta;
    }

    @Override
    public boolean deleteMetadata(@NonNull String name) throws IllegalArgumentException {
        Log.d(TAG, String.format(".deleteMetadata(\"%s\")", name));
        if (!metadata.containsKey(name))
            return false;
        metadata.remove(name);
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public boolean deleteMetadataById(long id) throws IllegalArgumentException {
        Log.d(TAG, String.format(".deleteMetadataById(%d)", id));
        Iterator<Map.Entry<String,NoteMetadata>> iter =
                metadata.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String,NoteMetadata> metaEntry = iter.next();
            if (metaEntry.getValue().getId() == id) {
                iter.remove();
                if (transactionLevel <= 0)
                    notifyObservers();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean deleteAllMetadata() {
        Log.d(TAG, ".deleteAllMetadata");
        if (metadata.isEmpty())
            return false;
        metadata.clear();
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public int countNotes() {
        Log.d(TAG, ".countNotes");
        return noteTable.size();
    }

    @Override
    public int countNotesInCategory(long categoryId) {
        Log.d(TAG, String.format(".countNotesInCategory(%d)", categoryId));
        int counter = 0;
        for (NoteItem note : noteTable.values()) {
            if (note.getCategoryId() == categoryId)
                counter++;
        }
        return counter;
    }

    @Override
    public int countPrivateNotes() {
        Log.d(TAG, ".countPrivateNotes");
        int count = 0;
        for (NoteItem note : noteTable.values()) {
            if (note.getPrivate() >= 1)
                count++;
        }
        return count;
    }

    @Override
    public int countEncryptedNotes() {
        Log.d(TAG, ".countEncryptedNotes");
        int count = 0;
        for (NoteItem note : noteTable.values()) {
            if (note.getPrivate() > 1)
                count++;
        }
        return count;
    }

    /**
     * A comparator for comparing note ID&rsquo;s.
     * Handles {@code null} fields.
     */
    public static final Comparator<NoteItem> NOTE_ID_COMPARATOR =
            new Comparator<NoteItem>() {
                @Override
                public int compare(NoteItem note1, NoteItem note2) {
                    if (note1.getId() == note2.getId())
                        return 0;
                    if (note1.getId() == null)
                        return -1;
                    if (note2.getId() == null)
                        return 1;
                    return (note1.getId() < note2.getId()) ? -1 : 1;
                }
            };

    public static final Comparator<NoteItem> NOTE_CONTENT_COMPARATOR =
            new NoteContentComparator(false);

    public static final Comparator<NoteItem> NOTE_CONTENT_COMPARATOR_IGNORE_CASE =
            new NoteContentComparator(true);

    /**
     * A comparator for note content which may or may not be encrypted.
     * We don&rsquo;t do any decrypting on the storage side, which means
     * if a note is encrypted it&rsquo;s content is treated as a BLOB.
     * There is no good alternative that doesn&rsquo;t expose plain-text
     * content.  According to the SQLite documentation for <a
     * href="https://www.sqlite.org/datatype3.html#sort_order">Sort Order</a>,
     * &ldquo;A TEXT value is less than a BLOB value.&rdquo;  Although note
     * content should never be empty, this comparator handles nulls by
     * ordering them first (as if an empty string).
     * <p>
     * This comparator can be set up as case-sensitive or case-insensitive;
     * that distinction only applies when comparing non-encrypted notes.
     * </p>
     */
    private static class NoteContentComparator implements Comparator<NoteItem> {
        private final boolean isCaseInsensitive;
        /**
         * @param caseInsensitive Whether this comparator should compare
         * (public) note content ignoring case
         */
        NoteContentComparator(boolean caseInsensitive) {
            isCaseInsensitive = caseInsensitive;
        }
        @Override
        public int compare(NoteItem note1, NoteItem note2) {
            if (note1.getPrivate() <= 1) {
                if (note2.getPrivate() <= 1) {
                    // Both notes are plain; we can compare the fields directly
                    if (note1.getNote() == null) {
                        if (note2.getNote() == null)
                            return 0;
                        return -1;
                    }
                    if (note2.getNote() == null)
                        return 1;
                    return isCaseInsensitive
                            ? note1.getNote().compareToIgnoreCase(note2.getNote())
                            : note1.getNote().compareTo(note2.getNote());
                }
                // First note is plain, second note is encrypted.
                // Check for nulls.
                if (note2.getEncryptedNote() == null)
                    return (note1.getNote() == null) ? 0 : 1;
                return -1;
            }
            if (note2.getPrivate() <= 1) {
                // First note is encrypted, second note is plain.
                // Check for nulls.
                if (note2.getNote() == null)
                    return (note1.getEncryptedNote() == null) ? 0 : 1;
                return 1;
            }
            byte[] ba1 = note1.getEncryptedNote();
            byte[] ba2 = note2.getEncryptedNote();
            if (ba1 == null)
                ba1 = new byte[0];
            if (ba2 == null)
                ba2 = new byte[0];
            // Both notes are encrypted.  The byte arrays should
            // both be non-null; do an unsigned byte-by-byte comparison.
            int i = 0;
            while ((i < ba1.length) || (i < ba2.length)) {
                if (i >= ba1.length)
                    return -1;
                if (i >= ba2.length)
                    return 1;
                if (ba1[i] != ba2[i])
                    return ((ba1[i] & 0xff) < (ba2[i] & 0xff)) ? -1 : 1;
                i++;
            }
            return 0;
        }
    }

    public static final Comparator<NoteItem> NOTE_CREATE_TIME_COMPARATOR =
            new NoteCreateTimeComparator();

    /**
     * A comparator for a note&rsquo;s modification time.
     * Handles null fields.
     */
    private static class NoteCreateTimeComparator implements Comparator<NoteItem> {
        @Override
        public int compare(NoteItem note1, NoteItem note2) {
            if (note1.getCreateTime() == note2.getCreateTime())
                return 0;
            if (note1.getCreateTime() == null)
                return -1;
            if (note2.getCreateTime() == null)
                return 1;
            return (note1.getCreateTime() < note2.getCreateTime()) ? -1 : 1;
        }
    }

    public static final Comparator<NoteItem> NOTE_MOD_TIME_COMPARATOR =
            new NoteModTimeComparator();

    /**
     * A comparator for a note&rsquo;s modification time.
     * Handles null fields.
     */
    private static class NoteModTimeComparator implements Comparator<NoteItem> {
        @Override
        public int compare(NoteItem note1, NoteItem note2) {
            if (note1.getModTime() == note2.getModTime())
                return 0;
            if (note1.getModTime() == null)
                return -1;
            if (note2.getModTime() == null)
                return 1;
            return (note1.getModTime() < note2.getModTime()) ? -1 : 1;
        }
    }

    /**
     * A comparator for a note&rsquo;s category ID.
     * A note whose category ID is not set is treated as Unfiled.
     */
    public static final Comparator<NoteItem> NOTE_CATEGORY_ID_COMPARATOR =
            new Comparator<NoteItem>() {
                @Override
                public int compare(NoteItem note1, NoteItem note2) {
                    long cat1 = (note1.getCategoryId() == null)
                            ? NoteCategory.UNFILED : note1.getCategoryId();
                    long cat2 = (note2.getCategoryId() == null)
                            ? NoteCategory.UNFILED : note2.getCategoryId();
                    if (cat1 == cat2)
                        return 0;
                    return (cat1 < cat2) ? -1 : 1;
                }
            };

    public static final Comparator<NoteItem> NOTE_CATEGORY_COMPARATOR =
            new NoteCategoryComparator(false);

    public static final Comparator<NoteItem> NOTE_CATEGORY_COMPARATOR_IGNORE_CASE =
            new NoteCategoryComparator(true);

    /**
     * A comparator for a note&rsquo;s category name.
     * Names are compare case-sensitive.
     * If the category names are null, compares the category ID instead.
     * If one note has a category name but the other only has an ID,
     * order ID&rsquo;s before names.
     * <p>
     * This comparator can be set up as case-sensitive or case-insensitive;
     * that distinction only applies when comparing non-encrypted notes.
     * </p>
     */
    private static class NoteCategoryComparator implements Comparator<NoteItem> {
        private final boolean isCaseInsensitive;
        NoteCategoryComparator(boolean caseInsensitive) {
            isCaseInsensitive = caseInsensitive;
        }
        @Override
        public int compare(NoteItem note1, NoteItem note2) {
            if (note1.getCategoryName() == null) {
                if (note2.getCategoryName() == null)
                    // Fall back to the category ID's
                    return NOTE_CATEGORY_ID_COMPARATOR.compare(note1, note2);
                return -1;
            }
            if (note2.getCategoryName() == null)
                return 1;
            return isCaseInsensitive
                    ? note1.getCategoryName().compareToIgnoreCase(note2.getCategoryName())
                    : note1.getCategoryName().compareTo(note2.getCategoryName());
        }
    }

    /**
     * A comparator for a note&rsquo;s privacy level.
     * At this point we&rsquo;re only defining these comparators for
     * completeness; the mock should support whatever fields a user
     * may throw at it.  Notes which don&rsquo;t have their private
     * field set are treated as public.
     */
    public static final Comparator<NoteItem> NOTE_PRIVATE_COMPARATOR =
            new Comparator<NoteItem>() {
                @Override
                public int compare(NoteItem note1, NoteItem note2) {
                    int priv1 = (note1.getPrivate() == null)
                            ? 0 : note1.getPrivate();
                    int priv2 = (note2.getPrivate() == null)
                            ? 0 : note2.getPrivate();
                    if (priv1 == priv2)
                        return 0;
                    return (priv1 < priv2) ? -1 : 1;
                }
            };

    /**
     * A comparator that gives the reverse order of another comparator.
     * Use instead of {@link Comparator#reversed()} because older
     * Android platforms do not support that.
     */
    public static class ReverseComparator<T> implements Comparator<T> {
        private final Comparator<T> originalComparator;
        public ReverseComparator(Comparator<T> ofComparator) {
            originalComparator = ofComparator;
        }
        @Override
        public int compare(T a, T b) {
            return originalComparator.compare(b, a);
        }
    }

    /**
     * A comparator that extends one comparator with another in the event
     * that the first comparator shows objects are equal.  Use instead of
     * {@link Comparator#thenComparing(Comparator)} because older Android
     * platforms do not support that.
     */
    public static class ThenComparator<T> implements Comparator<T> {
        private final Comparator<T> firstComparator;
        private final Comparator<T> secondComparator;
        public ThenComparator(Comparator<T> first, Comparator<T> second) {
            firstComparator = first;
            secondComparator = second;
        }
        @Override
        public int compare(T a, T b) {
            int order = firstComparator.compare(a, b);
            return (order == 0) ? secondComparator.compare(a, b) : order;
        }
    }

    @Override
    public NoteCursor getNotes(long categoryId,
                               boolean includePrivate,
                               boolean includeEncrypted,
                               @NonNull String sortOrder) {
        Log.d(TAG, String.format(".getNotes(%d,%s,%s)",
                categoryId, includePrivate, includeEncrypted));
        List<NoteItem> foundNotes = new ArrayList<>();
        for (NoteItem note : noteTable.values()) {
            if (categoryId != NotePreferences.ALL_CATEGORIES) {
                if (note.getCategoryId() != categoryId)
                    continue;
            }
            if (!includePrivate && note.getPrivate() > 0)
                continue;
            if (!includeEncrypted && note.getPrivate() > 1)
                continue;
            // Add the category name to a copy of the note
            NoteItem noteCopy = note.clone();
            noteCopy.setCategoryName(categories.get(note.getCategoryId()));
            foundNotes.add(noteCopy);
        }
        Comparator<NoteItem> comparator = null;
        for (String sortItem : sortOrder.split(",")) {
            sortItem = sortItem.trim();
            String[] sortParts = sortItem.split(" +");
            Comparator<NoteItem> nextComparator;
            if (sortParts[0].equalsIgnoreCase(NoteRepositoryImpl.NOTE_TABLE_NAME
                    + "." + NoteSchema.NoteItemColumns._ID))
                nextComparator = NOTE_ID_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.CREATE_TIME))
                nextComparator = NOTE_CREATE_TIME_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.MOD_TIME))
                nextComparator = NOTE_MOD_TIME_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.CATEGORY_ID))
                nextComparator = NOTE_CATEGORY_ID_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.CATEGORY_NAME))
                nextComparator = NOTE_CATEGORY_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.PRIVATE))
                nextComparator = NOTE_PRIVATE_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase(NoteSchema.NoteItemColumns.NOTE))
                nextComparator = NOTE_CONTENT_COMPARATOR;
            else if (sortParts[0].equalsIgnoreCase("lower("
                    + NoteSchema.NoteItemColumns.CATEGORY_NAME + ")"))
                nextComparator = NOTE_CATEGORY_COMPARATOR_IGNORE_CASE;
            else if (sortParts[0].equalsIgnoreCase("lower("
                    + NoteSchema.NoteItemColumns.NOTE + ")"))
                nextComparator = NOTE_CONTENT_COMPARATOR_IGNORE_CASE;
            else
                throw new SQLException(
                        "Unrecognized sort field: " + sortParts[0]);
            if (sortParts.length > 1) {
                if (sortParts[1].equalsIgnoreCase("desc"))
                    nextComparator = new ReverseComparator<>(nextComparator);
                else if (!sortParts[1].equalsIgnoreCase("asc"))
                    throw new SQLException(
                            "Unrecognized sort direction: " + sortParts[1]);
                if (sortParts.length > 2)
                    throw new SQLException(
                            "Invalid ORDER BY clause: " + sortItem);
            }
            if (comparator == null)
                comparator = nextComparator;
            else
                comparator = new ThenComparator<>(comparator, nextComparator);
        }
        Collections.sort(foundNotes, comparator);
        return new MockNoteCursor(foundNotes);
    }

    @Override
    public long[] getPrivateNoteIds() {
        Log.d(TAG, ".getPrivateNoteIds");
        long[] ids = new long[countPrivateNotes()];
        int i = 0;
        for (NoteItem note : noteTable.values()) {
            if (note.getPrivate() >= 1) {
                if (i >= ids.length) {
                    // Something must have changed the notes
                    // while we were iterating!  Resize the array.
                    Log.w(TAG, "Private note count mismatch");
                    ids = Arrays.copyOf(ids, i+1);
                }
                ids[i++] = note.getId();
            }
        }
        if (i < ids.length) {
            Log.w(TAG, "Private note count mismatch");
            ids = Arrays.copyOf(ids, i);
        }
        return ids;
    }

    @Override
    public NoteItem getNoteById(long noteId) {
        Log.d(TAG, String.format(".getNoteById(%d)", noteId));
        NoteItem foundNote = noteTable.get(noteId);
        if (foundNote == null)
            return null;
        // Add the category name
        foundNote = foundNote.clone();
        foundNote.setCategoryName(categories.get(foundNote.getCategoryId()));
        return foundNote;
    }

    /**
     * Verify the fields of a note are set properly
     * when inserting or updating.
     *
     * @param note the note to check
     *
     * @throws IllegalArgumentException if any fields are invalid
     */
    private void checkNoteFields(NoteItem note)
            throws IllegalArgumentException {
        if (note.getPrivate() == null)
            throw new IllegalArgumentException("Privacy level must be set");
        if (note.getPrivate() <= 1) {
            if (StringUtils.isEmpty(note.getNote()))
                throw new IllegalArgumentException("Note cannot be empty");
        } else {
            if ((note.getEncryptedNote() == null) ||
                    (note.getEncryptedNote().length == 0))
                throw new IllegalArgumentException("Note must be encrypted");
        }
        if (note.getCategoryId() == null)
            note.setCategoryId(NoteCategory.UNFILED);
        if (note.getCreateTime() == null)
            note.setCreateTimeNow();
        if (note.getModTime() == null)
            note.setModTimeNow();
    }

    /**
     * Clone a note for storage in the notes table.
     * This ensures that fields which aren&rsquo;t
     * stored in the real database are empty.
     *
     * @param note the original note passed in
     *
     * @return a clean clone of the note
     */
    private NoteItem cloneForStorage(NoteItem note) {
        NoteItem noteClone = note.clone();
        // Clean up items which aren't stored in the database
        noteClone.setCategoryName(null);
        if (noteClone.getPrivate() <= 1)
            noteClone.setEncryptedNote(null);
        else
            noteClone.setNote(null);
        return noteClone;
    }

    @Override
    public NoteItem insertNote(@NonNull NoteItem note) throws IllegalArgumentException {
        Log.d(TAG, String.format(".insertNote(%s)", note));
        checkNoteFields(note);
        note.setId(nextNoteId++);
        NoteItem noteClone = cloneForStorage(note);
        noteTable.put(note.getId(), noteClone);
        if (transactionLevel <= 0)
            notifyObservers();
        return note;
    }

    @Override
    public synchronized NoteItem updateNote(@NonNull NoteItem note)
            throws IllegalArgumentException, SQLException {
        Log.d(TAG, String.format(".updateNote(%s)", note));
        if (note.getId() == null)
            throw new IllegalArgumentException("Missing note ID");
        if (!noteTable.containsKey(note.getId()))
            throw new SQLException("No rows matched note " + note.getId());
        // We replace the note in-place since it's
        // easier than updating the fields.
        noteTable.put(note.getId(), cloneForStorage(note));
        if (transactionLevel <= 0)
            notifyObservers();
        return note;
    }

    @Override
    public synchronized boolean deleteNote(long noteId) {
        Log.d(TAG, String.format(".deleteNote(%d)", noteId));
        if (!noteTable.containsKey(noteId))
            return false;
        noteTable.remove(noteId);
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public boolean deleteAllNotes() throws SQLException {
        Log.d(TAG, ".deleteAllNotes");
        if (noteTable.isEmpty())
            return false;
        noteTable.clear();
        if (transactionLevel <= 0)
            notifyObservers();
        return true;
    }

    @Override
    public synchronized void runInTransaction(@NonNull Runnable callback) {
        Log.d(TAG, String.format(".runInTransaction(%s)",
                callback.getClass().getCanonicalName()));

        // In order to support a rollback, copy our current data
        Map<Long,String> originalCategories = new HashMap<>(categories);
        long originalNextCategoryId = nextCategoryId;
        Map<String,NoteMetadata> originalMetadata = new HashMap<>();
        for (NoteMetadata metadatum : metadata.values())
            originalMetadata.put(metadatum.getName(), metadatum.clone());
        long originalNextMetadataId = nextMetadataId;
        Map<Long,NoteItem> originalNotes = new HashMap<>(noteTable.size());
        for (NoteItem note : noteTable.values())
            originalNotes.put(note.getId(), note.clone());
        long originalNextNoteId = nextNoteId;

        boolean nestedTransaction = (transactionLevel > 0);
        try {
            transactionLevel++;
            callback.run();
            if (!nestedTransaction)
                notifyObservers();
        } catch (RuntimeException e) {
            Log.e(TAG, "Exception thrown from transaction operation;"
                    + " rolling back the mock repository.", e);
            categories.clear();
            categories.putAll(originalCategories);
            nextCategoryId = originalNextCategoryId;
            metadata.clear();
            metadata.putAll(originalMetadata);
            nextMetadataId = originalNextMetadataId;
            noteTable.clear();
            noteTable.putAll(originalNotes);
            nextNoteId = originalNextNoteId;
            throw e;
        } finally {
            --transactionLevel;
        }
    }

    @Override
    public void registerDataSetObserver(@NonNull DataSetObserver observer) {
        registeredObservers.add(observer);
    }

    @Override
    public void unregisterDataSetObserver(@NonNull DataSetObserver observer) {
        registeredObservers.remove(observer);
    }

}
