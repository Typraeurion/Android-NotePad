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

import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static org.junit.Assert.*;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tests for reading and writing Note Pad preferences
 *
 * @author Trevin Beattie
 */
public class NotePreferencesTests
        implements NotePreferences.OnNotePreferenceChangeListener {

    /** Random number generator for some tests */
    final Random RAND = new Random();
    /** Random string generator for use in the tests */
    final RandomStringUtils STRING_GEN = RandomStringUtils.insecure();

    static MockSharedPreferences mockPrefs;
    static NotePreferences notePrefs;

    boolean listenerWasCalled;

    @BeforeClass
    public static void initializeMock() {
        mockPrefs = new MockSharedPreferences();
        NotePreferences.setSharedPreferences(mockPrefs);
        // NotePreferences doesn't actually use the Context if we
        // set a custom SharedPreferences first, so it can be null.
        notePrefs = NotePreferences.getInstance(null);
    }

    @Before
    public void resetMock() {
        mockPrefs.resetMock();
        notePrefs.unregisterOnNotePreferenceChangeListener(this);
        listenerWasCalled = false;
    }

    /** Test getting the current sort order */
    @Test
    public void testGetSortOrder() {
        int expectedId = RAND.nextInt(1000);
        mockPrefs.initializePreference(NPREF_SORT_ORDER, expectedId);
        assertEquals("Sort order", expectedId, notePrefs.getSortOrder());
    }

    /** Test getting the default sort order */
    @Test
    public void testGetDefaultSortOrder() {
        assertEquals("Default sort order", 0, notePrefs.getSortOrder());
    }

    /** Test changing the sort order */
    @Test
    public void testSetSortOrder() {
        int expectedId = RAND.nextInt(1000);
        notePrefs.setSortOrder(expectedId);
        assertFalse("setSortOrder did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals("Sort order", expectedId,
                mockPrefs.getPreference(NPREF_SORT_ORDER));
    }

    /**
     * Run a test for getting a boolean value.
     *
     * @param displayName the name of the preference for display
     * @param prefName the internal name of the boolean preference
     * @param getDefault whether to get the default value rather than
     *                   a stored value
     * @param expectedValue whether to test for a true or false setting
     * @param getter a lamba function which makes the NotePreferences call
     * to get the boolean value
     */
    private void runGetBooleanPreferenceTest(
            String displayName, String prefName,
            boolean getDefault, boolean expectedValue,
            Supplier<Boolean> getter) {
        if (!getDefault)
            mockPrefs.initializePreference(prefName, expectedValue);
        assertEquals(displayName, expectedValue, getter.get());
    }

    /**
     * Run a test for setting a boolean value.
     *
     * @param displayName the name of the preference for display
     * @param prefName the internal name of the boolean preference
     * @param methodName the name of the setter method being called
     * @param expectedValue whether to test for a true or false setting
     * @param setter a lambda function which makes the NotePreferences call
     * to set the boolean value
     */
    private void runSetBooleanPreferenceTest(
            String displayName, String prefName, String methodName,
            boolean expectedValue, Consumer<Boolean> setter) {
        setter.accept(expectedValue);
        assertFalse(methodName + " did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals(displayName, expectedValue,
                mockPrefs.getPreference(prefName));
    }

    @Test
    public void testShowPrivateTrue() {
        runGetBooleanPreferenceTest("Show Private",
                NPREF_SHOW_PRIVATE, false, true,
                () -> notePrefs.showPrivate());
    }

    @Test
    public void testShowPrivateFalse() {
        runGetBooleanPreferenceTest("Show Private",
                NPREF_SHOW_PRIVATE, false, false,
                () -> notePrefs.showPrivate());
    }

    @Test
    public void testShowPrivateDefault() {
        runGetBooleanPreferenceTest("Show Private",
                NPREF_SHOW_PRIVATE, true, false,
                () -> notePrefs.showPrivate());
    }

    @Test
    public void testSetShowPrivateTrue() {
        runSetBooleanPreferenceTest("Show Private",
                NPREF_SHOW_PRIVATE, "setShowPrivate",
                true, (b) -> notePrefs.setShowPrivate(b));
    }

    @Test
    public void testSetShowPrivateFalse() {
        runSetBooleanPreferenceTest("Show Private",
                NPREF_SHOW_PRIVATE, "setShowPrivate",
                false, (b) -> notePrefs.setShowPrivate(b));
    }

    @Test
    public void testShowEncryptedTrue() {
        runGetBooleanPreferenceTest("Show Encrypted",
                NPREF_SHOW_ENCRYPTED, false, true,
                () -> notePrefs.showEncrypted());
    }

    @Test
    public void testShowEncryptedFalse() {
        runGetBooleanPreferenceTest("Show Encrypted",
                NPREF_SHOW_ENCRYPTED, false, false,
                () -> notePrefs.showEncrypted());
    }

    @Test
    public void testShowEncryptedDefault() {
        runGetBooleanPreferenceTest("Show Encrypted",
                NPREF_SHOW_ENCRYPTED, true, false,
                () -> notePrefs.showEncrypted());
    }

    @Test
    public void testSetShowEncryptedTrue() {
        runSetBooleanPreferenceTest("Show Encrypted",
                NPREF_SHOW_ENCRYPTED, "setShowEncrypted",
                true, (b) -> notePrefs.setShowEncrypted(b));
    }

    @Test
    public void testSetShowEncryptedFalse() {
        runSetBooleanPreferenceTest("Show Encrypted",
                NPREF_SHOW_ENCRYPTED, "setShowEncrypted",
                false, (b) -> notePrefs.setShowEncrypted(b));
    }

    @Test
    public void testShowCategoryTrue() {
        runGetBooleanPreferenceTest("Show Category",
                NPREF_SHOW_CATEGORY, false, true,
                () -> notePrefs.showCategory());
    }

    @Test
    public void testShowCategoryFalse() {
        runGetBooleanPreferenceTest("Show Category",
                NPREF_SHOW_CATEGORY, false, false,
                () -> notePrefs.showCategory());
    }

    @Test
    public void testShowCategoryDefault() {
        runGetBooleanPreferenceTest("Show Category",
                NPREF_SHOW_CATEGORY, true, false,
                () -> notePrefs.showCategory());
    }

    @Test
    public void testSetShowCategoryTrue() {
        runSetBooleanPreferenceTest("Show Category",
                NPREF_SHOW_CATEGORY, "setShowCategory",
                true, (b) -> notePrefs.setShowCategory(b));
    }

    @Test
    public void testSetShowCategoryFalse() {
        runSetBooleanPreferenceTest("Show Category",
                NPREF_SHOW_CATEGORY, "setShowCategory",
                false, (b) -> notePrefs.setShowCategory(b));
    }

    /** Test getting the selected category */
    @Test
    public void testGetSelectedCategory() {
        long expectedId = RAND.nextInt(1000);
        mockPrefs.initializePreference(NPREF_SELECTED_CATEGORY, expectedId);
        assertEquals("Selected category", expectedId,
                notePrefs.getSelectedCategory());
    }

    /** Test getting the default category */
    @Test
    public void testGetDefaultCategory() {
        assertEquals("Default selected category", ALL_CATEGORIES,
                notePrefs.getSelectedCategory());
    }

    /** Test setting the selected category */
    @Test
    public void testSetSelectedCategory() {
        long expectedId = RAND.nextInt(1001) - 1;
        notePrefs.setSelectedCategory(expectedId);
        assertFalse("setSelectedCategory did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals("Selected category", expectedId,
                mockPrefs.getPreference(NPREF_SELECTED_CATEGORY));
    }

    /** Test getting the export file name */
    @Test
    public void testGetExportFile() {
        String defaultFile = STRING_GEN.nextAscii(10, 33);
        String expectedFile = STRING_GEN.nextAscii(10, 33);
        mockPrefs.initializePreference(NPREF_EXPORT_FILE, expectedFile);
        assertEquals("Export file", expectedFile,
                notePrefs.getExportFile(defaultFile));
    }

    /** Test getting the default export file name */
    @Test
    public void testGetDefaultExportFile() {
        String defaultFile = STRING_GEN.nextAscii(10, 33);
        assertEquals("Default export file", defaultFile,
                notePrefs.getExportFile(defaultFile));
    }

    /** Test setting the export file name */
    @Test
    public void testSetExportFile() {
        String expectedFile = STRING_GEN.nextAscii(10, 33);
        notePrefs.setExportFile(expectedFile);
        assertFalse("setExportFile did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals("Export file", expectedFile,
                mockPrefs.getPreference(NPREF_EXPORT_FILE));
    }

    @Test
    public void testExportPrivateTrue() {
        runGetBooleanPreferenceTest("Export Private",
                NPREF_EXPORT_PRIVATE, false, true,
                () -> notePrefs.exportPrivate());
    }

    @Test
    public void testExportPrivateFalse() {
        runGetBooleanPreferenceTest("Export Private",
                NPREF_EXPORT_PRIVATE, false, false,
                () -> notePrefs.exportPrivate());
    }

    @Test
    public void testExportPrivateDefault() {
        runGetBooleanPreferenceTest("Export Private",
                NPREF_EXPORT_PRIVATE, true, false,
                () -> notePrefs.exportPrivate());
    }

    @Test
    public void testSetExportPrivateTrue() {
        runSetBooleanPreferenceTest("Export Private",
                NPREF_EXPORT_PRIVATE, "setExportPrivate",
                true, (b) -> notePrefs.setExportPrivate(b));
    }

    @Test
    public void testSetExportPrivateFalse() {
        runSetBooleanPreferenceTest("Export Private",
                NPREF_EXPORT_PRIVATE, "setExportPrivate",
                false, (b) -> notePrefs.setExportPrivate(b));
    }

    /** Test getting the import file name */
    @Test
    public void testGetImportFile() {
        String defaultFile = STRING_GEN.nextAscii(10, 33);
        String expectedFile = STRING_GEN.nextAscii(10, 33);
        mockPrefs.initializePreference(NPREF_IMPORT_FILE, expectedFile);
        assertEquals("Import file", expectedFile,
                notePrefs.getImportFile(defaultFile));
    }

    /** Test getting the default export file name */
    @Test
    public void testGetDefaultImportFile() {
        String defaultFile = STRING_GEN.nextAscii(10, 33);
        assertEquals("Default import file", defaultFile,
                notePrefs.getImportFile(defaultFile));
    }

    /** Test setting the export file name */
    @Test
    public void testSetImportFile() {
        String expectedFile = STRING_GEN.nextAscii(10, 33);
        notePrefs.setImportFile(expectedFile);
        assertFalse("setImportFile did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals("Import file", expectedFile,
                mockPrefs.getPreference(NPREF_IMPORT_FILE));
    }

    /** Test getting the import type */
    @Test
    public void testGetImportType() {
        ImportType expectedType = ImportType.values()[
                RAND.nextInt(ImportType.values().length)];
        mockPrefs.initializePreference(NPREF_IMPORT_TYPE, expectedType.ordinal());
        assertEquals("Import type", expectedType, notePrefs.getImportType());
    }

    /** Test getting the default import type */
    @Test
    public void testGetDefaultImportType() {
        assertEquals("Default import type", ImportType.UPDATE,
                notePrefs.getImportType());
    }

    /** Test setting the import type */
    @Test
    public void testSetImportType() {
        ImportType expectedType = ImportType.values()[
                RAND.nextInt(ImportType.values().length)];
        notePrefs.setImportType(expectedType);
        assertFalse("setImportType did not close the editor!",
                mockPrefs.isEditorOpen());
        assertEquals("Import type (stored value)", expectedType.ordinal(),
                mockPrefs.getPreference(NPREF_IMPORT_TYPE));
    }

    @Test
    public void testImportPrivateTrue() {
        runGetBooleanPreferenceTest("Import Private",
                NPREF_IMPORT_PRIVATE, false, true,
                () -> notePrefs.importPrivate());
    }

    @Test
    public void testImportPrivateFalse() {
        runGetBooleanPreferenceTest("Import Private",
                NPREF_IMPORT_PRIVATE, false, false,
                () -> notePrefs.importPrivate());
    }

    @Test
    public void testImportPrivateDefault() {
        runGetBooleanPreferenceTest("Import Private",
                NPREF_IMPORT_PRIVATE, true, false,
                () -> notePrefs.importPrivate());
    }

    @Test
    public void testSetImportPrivateTrue() {
        runSetBooleanPreferenceTest("Import Private",
                NPREF_IMPORT_PRIVATE, "setImportPrivate",
                true, (b) -> notePrefs.setImportPrivate(b));
    }

    @Test
    public void testSetImportPrivateFalse() {
        runSetBooleanPreferenceTest("Import Private",
                NPREF_IMPORT_PRIVATE, "setImportPrivate",
                false, (b) -> notePrefs.setImportPrivate(b));
    }

    /**
     * Called when a preference has been changed
     * (if we register this test class as a listener)
     */
    @Override
    public void onNotePreferenceChanged(NotePreferences prefs) {
        listenerWasCalled = true;
    }

    /** The set of preference keys managed by NotePreferences */
    private static final String[] NPREF_KEYS = {
            NPREF_EXPORT_FILE, NPREF_EXPORT_PRIVATE,
            NPREF_IMPORT_FILE, NPREF_IMPORT_PRIVATE, NPREF_IMPORT_TYPE,
            NPREF_SELECTED_CATEGORY, NPREF_SHOW_CATEGORY,
            NPREF_SHOW_ENCRYPTED, NPREF_SHOW_PRIVATE
    };

    /**
     * Test that when a preference is set, a listener
     * for that preference is called.
     *
     * @param prefKey the key for the preference we@rsquo;re listening for
     * @param methodName the name of the method that should result in
     *                   listener notification
     * @param setter a function for setting the preference.
     *               (Caller supplies the value.)
     */
    private void runListenerCalledTest(
            String prefKey, String methodName, Runnable setter) {
        notePrefs.registerOnNotePreferenceChangeListener(this, prefKey);
        setter.run();
        assertTrue("Listener was not called for " + methodName,
                listenerWasCalled);
    }

    /**
     * Test that when a preference is set, listeners for all other
     * preferences are <i>not</i> called.
     *
     * @param prefKey the key for the preference we@rsquo;re <i>not</i>
     *                listening for
     * @param methodName the name of the method that could result in
     *                   listener notification
     * @param setter a function for setting the preference.
     *               (Caller supplies the value.)
     */
    private void runListenerNotCalledTest(
            String prefKey, String methodName, Runnable setter) {
        List<String> otherPrefs = new ArrayList<>(NPREF_KEYS.length - 1);
        for (String key : NPREF_KEYS) {
            if (!key.equals(prefKey))
                otherPrefs.add(key);
        }
        notePrefs.registerOnNotePreferenceChangeListener(this,
                otherPrefs.toArray(new String[otherPrefs.size()]));
        setter.run();
        assertFalse("Unassociated listener was called for " + methodName,
                listenerWasCalled);
    }

    @Test
    public void testSortOrderListener() {
        runListenerCalledTest(NPREF_SORT_ORDER, "setSortOrder",
                () -> notePrefs.setSortOrder(RAND.nextInt(100)));
    }

    @Test
    public void testSortOrderIgnored() {
        runListenerNotCalledTest(NPREF_SORT_ORDER, "setSortOrder",
                () -> notePrefs.setSortOrder(RAND.nextInt(100)));
    }

    @Test
    public void testShowPrivateListener() {
        runListenerCalledTest(NPREF_SHOW_PRIVATE, "setShowPrivate",
                () -> notePrefs.setShowPrivate(RAND.nextBoolean()));
    }

    @Test
    public void testShowPrivateIgnored() {
        runListenerNotCalledTest(NPREF_SHOW_PRIVATE, "setShowPrivate",
                () -> notePrefs.setShowPrivate(RAND.nextBoolean()));
    }

    @Test
    public void testShowEncryptedListener() {
        runListenerCalledTest(NPREF_SHOW_ENCRYPTED, "setShowEncrypted",
                () -> notePrefs.setShowEncrypted(RAND.nextBoolean()));
    }

    @Test
    public void testShowEncryptedIgnored() {
        runListenerNotCalledTest(NPREF_SHOW_ENCRYPTED, "setShowEncrypted",
                () -> notePrefs.setShowEncrypted(RAND.nextBoolean()));
    }

    @Test
    public void testShowCategoryListener() {
        runListenerCalledTest(NPREF_SHOW_CATEGORY, "setShowCategory",
                () -> notePrefs.setShowCategory(RAND.nextBoolean()));
    }

    @Test
    public void testShowCategoryIgnored() {
        runListenerNotCalledTest(NPREF_SHOW_CATEGORY, "setShowCategory",
                () -> notePrefs.setShowCategory(RAND.nextBoolean()));
    }

    @Test
    public void testSelectedCategoryListener() {
        runListenerCalledTest(NPREF_SELECTED_CATEGORY, "setSelectedCategory",
                () -> notePrefs.setSelectedCategory(RAND.nextInt(100)));
    }

    @Test
    public void testSelectedCategoryIgnored() {
        runListenerNotCalledTest(NPREF_SELECTED_CATEGORY, "setSelectedCategory",
                () -> notePrefs.setSelectedCategory(RAND.nextInt(100)));
    }

    @Test
    public void testExportFileListener() {
        runListenerCalledTest(NPREF_EXPORT_FILE, "setExportFile",
                () -> notePrefs.setExportFile(STRING_GEN.nextAscii(10, 33)));
    }

    @Test
    public void testExportFileIgnored() {
        runListenerNotCalledTest(NPREF_EXPORT_FILE, "setExportFile",
                () -> notePrefs.setExportFile(STRING_GEN.nextAscii(10, 33)));
    }

    @Test
    public void testExportPrivateListener() {
        runListenerCalledTest(NPREF_EXPORT_PRIVATE, "setExportPrivate",
                () -> notePrefs.setExportPrivate(RAND.nextBoolean()));
    }

    @Test
    public void testExportPrivateIgnored() {
        runListenerNotCalledTest(NPREF_EXPORT_PRIVATE, "setImportPrivate",
                () -> notePrefs.setExportPrivate(RAND.nextBoolean()));
    }

    @Test
    public void testImportFileListener() {
        runListenerCalledTest(NPREF_IMPORT_FILE, "setImportFile",
                () -> notePrefs.setImportFile(STRING_GEN.nextAscii(10, 33)));
    }

    @Test
    public void testImportFileIgnored() {
        runListenerNotCalledTest(NPREF_IMPORT_FILE, "setImportFile",
                () -> notePrefs.setImportFile(STRING_GEN.nextAscii(10, 33)));
    }

    @Test
    public void testImportTypeListener() {
        runListenerCalledTest(NPREF_IMPORT_TYPE, "setImportType",
                () -> notePrefs.setImportType(ImportType.values()[
                        RAND.nextInt(ImportType.values().length)]));
    }

    @Test
    public void testImportTypeIgnored() {
        runListenerNotCalledTest(NPREF_IMPORT_TYPE, "setImportType",
                () -> notePrefs.setImportType(ImportType.values()[
                        RAND.nextInt(ImportType.values().length)]));
    }

    @Test
    public void testImportPrivateListener() {
        runListenerCalledTest(NPREF_IMPORT_PRIVATE, "setImportPrivate",
                () -> notePrefs.setImportPrivate(RAND.nextBoolean()));
    }

    @Test
    public void testImportPrivateIgnored() {
        runListenerNotCalledTest(NPREF_IMPORT_PRIVATE, "setImportPrivate",
                () -> notePrefs.setImportPrivate(RAND.nextBoolean()));
    }

}
