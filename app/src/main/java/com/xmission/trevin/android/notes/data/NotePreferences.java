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
package com.xmission.trevin.android.notes.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.*;

/**
 * Wrapper around @link{SharedPreferences} which provides accessors
 * to Note Pad&rsquo;s preferences.  We also can be configured to
 * provide mock data or spy on the actual preferences for
 * testing purposes.
 *
 * @author Trevin Beattie
 */
public class NotePreferences
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "NotePreferences";

    /** Preferences tag for the To Do application */
    public static final String NOTE_PREFERENCES = "NotePrefs";

    /** Label for the preferences option "Sort order" */
    public static final String NPREF_SORT_ORDER = "SortOrder";

    /** Label for the preferences option "Show private records" */
    public static final String NPREF_SHOW_PRIVATE = "ShowPrivate";

    /** The preferences option for showing encrypted records */
    public static final String NPREF_SHOW_ENCRYPTED = "ShowEncrypted";

    /** Label for the preferences option "Show categories" */
    public static final String NPREF_SHOW_CATEGORY = "ShowCategory";

    /** The category index for &ldquo;All&rdquo; categories */
    public static final int ALL_CATEGORIES = -1;

    /** Label for the currently selected category */
    public static final String NPREF_SELECTED_CATEGORY = "SelectedCategory";

    /** Label for the last exported file name */
    public static final String NPREF_EXPORT_FILE = "ExportFile";

    /** Label for the preferences option "Include Private" */
    public static final String NPREF_EXPORT_PRIVATE = "ExportPrivate";

    /** Label for the last imported file name */
    public static final String NPREF_IMPORT_FILE = "ImportFile";

    /** Label for the preferences option "Import type" */
    public static final String NPREF_IMPORT_TYPE = "ImportType";

    /** Label for the preferences option "Include Private" */
    public static final String NPREF_IMPORT_PRIVATE = "ImportPrivate";

    /** The singleton note preferences object */
    private static NotePreferences instance = null;

    /** The actual shared preferences we&rsquo;re relaying through */
    private final SharedPreferences prefs;

    /**
     * Flag indicating how to merge items from an imported file
     * with those in the database.
     */
    public enum ImportType {
        /** The database should be cleared before importing the file. */
        CLEAN,

        /**
         * Any items in the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten, regardless of which one is newer.
         */
        REVERT,

        /**
         * Any item is the database with the same internal ID
         * and creation time as an item in the file should
         * be overwritten if the modification time of the item
         * in the file is newer.
         */
        UPDATE,

        /**
         * Any items in the file with the same internal ID as an
         * item in the Android database should be added as a new item
         * with a newly assigned ID.  Will result in duplicates if the
         * file had been imported before, but is the safest option
         * if importing a different file.
         */
        ADD,

        /**
         * Don't actually write anything to the android database.
         * Just read the file to verify the integrity of the data.
         */
        TEST,
    }

    /**
     * Definition of a listener that should be called when a given Note Pad
     * preference has been changed.
     */
    public interface OnNotePreferenceChangeListener {
        /** Called when a Note Pad preference is changed, added, or removed */
        void onNotePreferenceChanged(NotePreferences prefs);
    }

    /**
     * Registered listeners for Note Pad preference changes
     */
    private final Map<String, List<OnNotePreferenceChangeListener>> listeners;

    /**
     * Wrapper around {@link SharedPreferences.Editor} for Note Pad
     * preferences.  This allows callers to modify any number of
     * preferences in a single operation.  The caller <i>must</i>
     * end the call chain with {@link Editor#finish}.
     */
    public class Editor {

        private final SharedPreferences.Editor actualEditor;

        Editor() {
            actualEditor = prefs.edit();
        }

        /** Apply all pending changes to the Note Pad preferences */
        public void finish() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD)
                actualEditor.commit();
            else
                actualEditor.apply();
        }

        /**
         * Change the sort order.
         *
         * @param newOrder the ID of the new sorting order
         *
         * @return this Editor for chaining
         */
        public Editor setSortOrder(int newOrder) {
            actualEditor.putInt(NPREF_SORT_ORDER, newOrder);
            return this;
        }

        /**
         * Change whether to show private records.
         *
         * @param show {@code true} if private records should be shown
         *
         * @return this Editor for chaining
         */
        public Editor setShowPrivate(boolean show) {
            actualEditor.putBoolean(NPREF_SHOW_PRIVATE, show);
            return this;
        }

        /**
         * Change whether to show encrypted records.
         *
         * @param show {@code true} if encrypted records should be shown
         *
         * @return this Editor for chaining
         */
        public Editor setShowEncrypted(boolean show) {
            actualEditor.putBoolean(NPREF_SHOW_ENCRYPTED, show);
            return this;
        }

        /**
         * Change whether to show categories in the notes list.
         *
         * @param show {@code true} if categories should be shown
         *
         * @return this Editor for chaining
         */
        public Editor setShowCategory(boolean show) {
            actualEditor.putBoolean(NPREF_SHOW_CATEGORY, show);
            return this;
        }

        /**
         * Change the selected category for notes.
         *
         * @param newCategory the index of the category to select,
         *                    or {@link #ALL_CATEGORIES}.
         *
         * @return this Editor for chaining
         */
        public Editor setSelectedCategory(long newCategory) {
            actualEditor.putLong(NPREF_SELECTED_CATEGORY, newCategory);
            return this;
        }

        /**
         * Change the name of the export file.
         *
         * @param fileName the name of the export file
         *
         * @return this Editor for chaining
         */
        public Editor setExportFile(String fileName) {
            actualEditor.putString(NPREF_EXPORT_FILE, fileName);
            return this;
        }

        /**
         * Change whether to export private records.
         *
         * @param include {@code true} if private records should be
         *                           included in the export
         *
         * @return this Editor for chaining
         */
        public Editor setExportPrivate(boolean include) {
            actualEditor.putBoolean(NPREF_EXPORT_PRIVATE, include);
            return this;
        }

        /**
         * Change the name of the import file.
         *
         * @param fileName the name of the import file
         *
         * @return this Editor for chaining
         */
        public Editor setImportFile(String fileName) {
            actualEditor.putString(NPREF_IMPORT_FILE, fileName);
            return this;
        }

        /**
         * Change the type of import.
         *
         * @param newType the import type
         *
         * @return this Editor for chaining
         */
        public Editor setImportType(ImportType newType) {
            actualEditor.putInt(NPREF_IMPORT_TYPE, newType.ordinal());
            return this;
        }

        /**
         * Change whether to import private records.
         *
         * @param include {@code true} if private records should be
         *                           included from the import
         *
         * @return this Editor for chaining
         */
        public Editor setImportPrivate(boolean include) {
            actualEditor.putBoolean(NPREF_IMPORT_PRIVATE, include);
            return this;
        }

    }

    /**
     * Instantiate note preferences for a calling context.
     */
    private NotePreferences(Context context) {
        this(context.getSharedPreferences(
                NOTE_PREFERENCES, Context.MODE_PRIVATE));
    }

    /**
     * Instantiate note preferences with a given {@link SharedPreferences}
     * object.  This is for testing purposes.
     */
    private NotePreferences(SharedPreferences otherPrefs) {
        prefs = otherPrefs;
        listeners = new HashMap<>();
        listeners.put(NPREF_SORT_ORDER, new LinkedList<>());
        listeners.put(NPREF_SHOW_PRIVATE, new LinkedList<>());
        listeners.put(NPREF_SHOW_ENCRYPTED, new LinkedList<>());
        listeners.put(NPREF_SHOW_CATEGORY, new LinkedList<>());
        listeners.put(NPREF_SELECTED_CATEGORY, new LinkedList<>());
        listeners.put(NPREF_EXPORT_FILE, new LinkedList<>());
        listeners.put(NPREF_EXPORT_PRIVATE, new LinkedList<>());
        listeners.put(NPREF_IMPORT_FILE, new LinkedList<>());
        listeners.put(NPREF_IMPORT_TYPE, new LinkedList<>());
        listeners.put(NPREF_IMPORT_PRIVATE, new LinkedList<>());
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    /**
     * Set the {@link SharedPreferences} that NotePreferences should wrap.
     * This is meant for unit testing, where the test class provides a mock
     * or spy SharedPreferences.
     *
     * @throws IllegalStateException if the SharedPreferences had already
     * been set in a previous call.
     */
    public static void setSharedPreferences(SharedPreferences prefs) {
        if (instance != null) {
            if (instance.prefs == prefs)
                // We'll allow setting the same preferences object again.
                return;
            throw new IllegalStateException(String.format(
                    "Attempt to replace previously set %s with %s",
                    instance.prefs, prefs));
        }
        instance = new NotePreferences(prefs);
    }

    /**
     * @param context the context for which shared preferences is needed
     * @return a shared instance of NotePreferences
     */
    public static NotePreferences getInstance(Context context) {
        if (instance == null)
            instance = new NotePreferences(context);
        return instance;
    }

    /**
     * @return an {@link Editor} for updating multiple preferences.  The
     * caller <i>must</i> finish the method chain with {@link Editor#finish()}.
     */
    public Editor edit() {
        return new Editor();
    }

    /**
     * @return the current sort order (default 0)
     */
    public int getSortOrder() {
        return prefs.getInt(NPREF_SORT_ORDER, 0);
    }

    /**
     * Change the sort order (immediate).
     *
     * @param newOrder the ID of the new sorting order
     */
    public void setSortOrder(int newOrder) {
        edit().setSortOrder(newOrder).finish();
    }

    /**
     * @return whether to show private records (default {@code false})
     */
    public boolean showPrivate() {
        return prefs.getBoolean(NPREF_SHOW_PRIVATE, false);
    }

    /**
     * Change whether to show private records (immediate).
     *
     * @param show {@code true} if private records should be shown
     */
    public void setShowPrivate(boolean show) {
        edit().setShowPrivate(show).finish();
    }

    /**
     * @return whether to show encrypted records (default {@code false})
     */
    public boolean showEncrypted() {
        return prefs.getBoolean(NPREF_SHOW_ENCRYPTED, false);
    }

    /**
     * Change whether to show encrypted records (immediate).
     *
     * @param show {@code true} if encrypted records should be shown
     */
    public void setShowEncrypted(boolean show) {
        edit().setShowEncrypted(show).finish();
    }

    /**
     * @return whether to show categories in the notes list
     * (default {@code false})
     */
    public boolean showCategory() {
        return prefs.getBoolean(NPREF_SHOW_CATEGORY, false);
    }

    /**
     * Change whether to show categories in the notes list (immediate).
     *
     * @param show {@link true} if categories should be shown
     */
    public void setShowCategory(boolean show) {
        edit().setShowCategory(show).finish();
    }

    /**
     * @return the currently selected category for notes
     * (default {@link #ALL_CATEGORIES})
     */
    public long getSelectedCategory() {
        return prefs.getLong(NPREF_SELECTED_CATEGORY, ALL_CATEGORIES);
    }

    /**
     * Change the selected category for notes (immediate).
     *
     * @param newCategory the index of the category to select,
     *                    or {@link #ALL_CATEGORIES}.
     */
    public void setSelectedCategory(long newCategory) {
        edit().setSelectedCategory(newCategory).finish();
    }

    /**
     * @param defaultFile the default filename to return
     *
     * @return the name of the export file, or {@code defaultFile}
     * if no preference has been set
     */
    public String getExportFile(String defaultFile) {
        return prefs.getString(NPREF_EXPORT_FILE, defaultFile);
    }

    /**
     * Change the name of the export file (immediate).
     *
     * @param fileName the name of the export file
     */
    public void setExportFile(String fileName) {
        edit().setExportFile(fileName).finish();
    }

    /**
     * @return whether to export private records (default {@code false})
     */
    public boolean exportPrivate() {
        return prefs.getBoolean(NPREF_EXPORT_PRIVATE, false);
    }

    /**
     * Change whether to export private records (immediate).
     *
     * @param include {@code true} if private records should be
     *                           included in the export
     */
    public void setExportPrivate(boolean include) {
        edit().setExportPrivate(include).finish();
    }

    /**
     * @param defaultFile the default filename to return
     *
     * @return the name of the import file, or {@code defaultFile}
     * if no preference has been set
     */
    public String getImportFile(String defaultFile) {
        return prefs.getString(NPREF_IMPORT_FILE, defaultFile);
    }

    /**
     * Change the name of the import file (immediate).
     *
     * @param fileName the name of the import file
     */
    public void setImportFile(String fileName) {
        edit().setImportFile(fileName).finish();
    }

    /**
     * @return the type of import to perform
     * (default {@link ImportType#UPDATE}
     */
    public ImportType getImportType() {
        int typeIndex = prefs.getInt(NPREF_IMPORT_TYPE,
                ImportType.UPDATE.ordinal());
        if ((typeIndex < 0) || (typeIndex >= ImportType.values().length)) {
            Log.w(TAG, String.format("Invalid import type index (%d) in preferences!",
                    typeIndex));
            return ImportType.UPDATE;
        }
        return ImportType.values()[typeIndex];
    }

    /**
     * Change the type of import (immediate).
     *
     * @param newType the import type
     */
    public void setImportType(ImportType newType) {
        edit().setImportType(newType).finish();
    }

    /**
     * @return whether to import private records (default {@code false})
     */
    public boolean importPrivate() {
        return prefs.getBoolean(NPREF_IMPORT_PRIVATE, false);
    }

    /**
     * Change whether to import private records (immediate).
     *
     * @param include {@code true} if private records should be
     *                           included from the import
     */
    public void setImportPrivate(boolean include) {
        edit().setImportPrivate(include).finish();
    }

    /**
     * This is used when exporting Note Pad data to save the user preferences.
     *
     * @return a {@link Map} of all Note Pad preferences
     */
    public Map<String,?> getAllPreferences() {
        return Collections.unmodifiableMap(prefs.getAll());
    }

    /**
     * Register a listener for changes in a Note Pad preference.
     *
     * @param listener the preference listener
     * @param keys the name(s) of the preference(s) to listen for.
     *             If omitted, listen for all preferences.
     */
    public void registerOnNotePreferenceChangeListener(
            OnNotePreferenceChangeListener listener, String... keys) {
        if (keys.length == 0) {
            for (String key : listeners.keySet())
                listeners.get(key).add(listener);
        } else {
            for (String key : keys) {
                if (listeners.containsKey(key))
                    listeners.get(key).add(listener);
                else
                    throw new IllegalArgumentException(
                            "Invalid note preference: " + key);
            }
        }
    }

    /**
     * Remove a callback for changes in a Not Pad preference.
     *
     * @param listener the listener to remove
     * @param keys the name(s) of the preference(s) the listener is
     *             no longer interested it.  If omitted, remove the
     *             listener from all preferences.
     */
    public void unregisterOnNotePreferenceChangeListener(
            OnNotePreferenceChangeListener listener, String... keys) {
        if (keys.length == 0) {
            for (String key : listeners.keySet())
                listeners.get(key).remove(listener);
        } else {
            for (String key : keys) {
                if (listeners.containsKey(key))
                    listeners.get(key).remove(listener);
                else
                    throw new IllegalArgumentException(
                            "Invalid note preference: " + key);
            }
        }
    }

    /**
     * When a shared preference has been changed, notify any registered
     * listener for that preference.
     */
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        Log.d(TAG, String.format(".onSharedPreferenceChanged(%s)", key));
        if (listeners.containsKey(key)) {
            for (OnNotePreferenceChangeListener listener : listeners.get(key)) {
                listener.onNotePreferenceChanged(this);
            }
        } else {
            Log.w(TAG, "Received change notice for unhandled preference: " + key);
        }
    }

}
