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
package com.xmission.trevin.android.notes.ui;

import static com.xmission.trevin.android.notes.ui.CategoryFilterTests.randomCategoryName;
import static com.xmission.trevin.android.notes.util.LaunchUtils.*;
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.randomNote;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.MockSharedPreferences;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.TestObserver;
import com.xmission.trevin.android.notes.provider.TestPreferencesObserver;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;

/**
 * Tests for {@link NoteListActivity}
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class NoteListActivityTests {

    private static Instrumentation instrument = null;
    private static Context testContext = null;
    private static MockSharedPreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;
    private StringEncryption globalEncryption = null;

    static final Random RAND = new Random();

    @BeforeClass
    public static void initializeMocks() {
        mockRepo = MockNoteRepository.getInstance();
        NoteRepositoryImpl.setInstance(mockRepo);
        mockPrefs = MockSharedPreferences.getInstance();
        NotePreferences.setSharedPreferences(mockPrefs);
        instrument = InstrumentationRegistry.getInstrumentation();
        testContext = instrument.getTargetContext();
    }

    @Before
    public void resetMocks() {
//        NotePreferences.setSharedPreferences(mockPrefs);
        mockPrefs.resetMock();
//        NoteRepositoryImpl.setInstance(mockRepo);
        mockRepo.clear();
        mockRepo.open(testContext);
        globalEncryption = StringEncryption.holdGlobalEncryption();
        initializeIntents();
    }

    @After
    public void cleanUp() {
        releaseIntents();
        StringEncryption.releaseGlobalEncryption(testContext);
        globalEncryption = null;
        mockRepo.release(testContext);
    }

    /**
     * Check that the category filter drop-down contains:
     * <ul>
     *     <li>&ldquo;All&rdquo; in the top position</li>
     *     <li>&ldquo;Unfiled&rdquo; in the middle (this test is predicated
     *     on that there are no other categories in the repository)</li>
     *     <li>&ldquo;Edit categories&rdquo; in the last position</li>
     * </ul>
     */
    @Test
    public void noteListCategoryFilterDropDownTest() throws Throwable {
        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            CategoryFilterAdapter[] adapter = new CategoryFilterAdapter[1];
            wrapper.onActivity(activity -> {
                adapter[0] = activity.categoryAdapter;
            });
            assertNotNull("Category filter adapter has not been set",
                    adapter[0]);
            assertSpinnerShown(wrapper.getScenario(), "Category filter",
                    R.id.ListSpinnerCategory);

            // Verify the data in the adapter
            assertTrue(String.format(Locale.US,
                    "Spinner should have at least 3 entries; found %d",
                    adapter[0].getCount()), adapter[0].getCount() >= 3);

            // Check items by name in the adapter
            assertEquals("First item sholud be All categories",
                    testContext.getString(R.string.Category_All),
                    adapter[0].getItem(0).getName());
            assertEquals("Middle item should be the Unfiled category",
                    testContext.getString(R.string.Category_Unfiled),
                    adapter[0].getItem(adapter[0].getCount() - 2).getName());

            assertEquals("Last item should be Edit categories",
                    testContext.getString(R.string.Category_Edit),
                    adapter[0].getItem(adapter[0].getCount() - 1).getName());
        }
    }

    /**
     * When the note list activity is starting up from scratch,
     * verify that the selected category from the preferences is
     * retained.  The activity needs to set this after the adapter
     * has been loaded from the database on a non-UI thread.
     */
    @Test
    public void testPreserveSelectedCategory() {
        List<NoteCategory> testCategories = new ArrayList<>();
        for (int i = RAND.nextInt(3) + 7; i >= 0; --i) {
            NoteCategory category = mockRepo.insertCategory(
                    randomCategoryName('A', 'Z'));
            testCategories.add(category);
            NoteItem note = new NoteItem();
            note.setCategoryId(category.getId());
            note.setNote(category.getName());
            mockRepo.insertNote(note);
        }
        NoteCategory targetCategory = testCategories.get(
                RAND.nextInt(testCategories.size()));
        NotePreferences prefs = NotePreferences.getInstance(testContext);
        prefs.setSelectedCategory(targetCategory.getId());

        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            final Spinner[] spinner = new Spinner[1];
            CategoryFilterAdapter[] adapter = new CategoryFilterAdapter[1];
            wrapper.onActivity(activity -> {
                spinner[0] = activity.categoryList;
                adapter[0] = activity.categoryAdapter;
            });
            assertNotNull("Category filter adapter has not been set",
                    adapter[0]);
            long timeLimit = System.nanoTime() + 5000000000L;
            while (adapter[0].getCount() < 3) {
                // The adapter always has its "All Categories" and
                // "Edit Categories" items; we need to wait for more...
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertFalse("Timed out waiting for the adapter to be populated",
                        System.nanoTime() > timeLimit);
                try {
                    Thread.sleep(128);
                } catch (InterruptedException ix) {
                    // Ignore
                }
            }
            /*
             * Once the adapter has been loaded, the activity then needs to
             * update the list query which will result in another call to
             * the repository.  But we don't need to wait for all of this
             * to make its way back to the main list.
             */
            assertEquals("Selected category ID",
                    targetCategory.getId().longValue(),
                    spinner[0].getSelectedItemId());
        }
    }

    /**
     * Test creating a new note.  We only go as far as verifying the
     * {@link NoteEditorActivity} is started, then we cancel that activity
     * and verify that no changes were made to the database.
     */
    @Test
    public void testNewThenCancel() {
        // Add a test category; ensure it's in the first position below All.
        NoteCategory testCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'T'));
        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class);
             TestObserver repoObserver = new TestObserver(mockRepo);
             TestPreferencesObserver prefsObserver =
                new TestPreferencesObserver(testContext,
                        NotePreferences.NPREF_SELECTED_CATEGORY)) {
            // Step 1: Verify the category drop-down and "New" button exist
            assertSpinnerShown(wrapper.getScenario(), "Category filter",
                    R.id.ListSpinnerCategory);
            assertButtonShown(wrapper.getScenario(),
                    "New", R.id.ListButtonNew);
            // Step 2: Select the test category
            selectFromSpinner(wrapper.getScenario(),
                    R.id.ListSpinnerCategory, 1);
            prefsObserver.assertChanged(
                    "The selected category was not saved to preferences");
            assertEquals("Selected category ID saved to preferences",
                    testCategory.getId(), mockPrefs.getPreference(
                            NotePreferences.NPREF_SELECTED_CATEGORY));
            // Step 3: Click the "New" button; wait for idle sync
            pressButton(wrapper.getScenario(), R.id.ListButtonNew);
            // Step 4: Verify the NoteEditorActivity is launched,
            assertActivityLaunched(NoteEditorActivity.class);
            //     ... that it was provided a category ID,
            assertIntentHasLongExtra(NoteEditorActivity.class,
                    NoteEditorActivity.EXTRA_CATEGORY_ID, testCategory.getId());
            //     ... and that it was NOT given a note ID.
            assertIntentDoesNotHaveExtra(NoteEditorActivity.class,
                    NoteEditorActivity.EXTRA_NOTE_ID);
            waitForFocus();
            // Step 4: Press the Back button
            pressBackButton();
            // Step 5: Wait and verify that nothing was added to the repository
            repoObserver.assertNotChanged(
                    "Changes were made to the repository when no note was saved!");
        }
    }

    /**
     * Test editing an existing note.  We only go as far as verifying the
     * {@link NoteEditorActivity} is started, then we cancel that activity
     * and verify that no changes were made to the database.
     */
    @Test
    public void testEditThenCancel() {
        NoteItem testNote = mockRepo.insertNote(randomNote());
        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class);
             TestObserver repoObserver = new TestObserver(mockRepo)) {
            // Step 1: Verify the note is listed
            NoteCursorAdapter[] adapter = new NoteCursorAdapter[1];
            wrapper.onActivity(activity -> {
                adapter[0] = activity.itemAdapter;
            });
            assertNotNull("Note cursor adapter has not been set",
                    adapter[0]);
            // Wait if necessary for the adapter to be populated
            if (adapter[0].getCount() <= 0) {
                long timeLimit = System.nanoTime() + 5000000000L;
                try (TestObserver adapterObserver = new TestObserver(adapter[0])) {
                    while (System.nanoTime() < timeLimit) {
                        adapterObserver.waitToClear();
                        if (adapter[0].getCount() > 0)
                            break;
                        adapterObserver.reset();
                    }
                }
            }
            assertEquals("Number of notes listed", 1, adapter[0].getCount());
            /*
             * Step 2: Click the note; wait for idle sync.  Note that we
             * have to use the long click on the container view; short
             * click is set only on the embedded text.
             */
            wrapper.onActivity(activity -> {
                adapter[0].getView(0, null, null).performLongClick();
            });
            instrument.waitForIdleSync();
            // Step 3: Verify the NoteEditorActivity is launched,
            assertActivityLaunched(NoteEditorActivity.class);
            //     ... and that it was provided a note ID.
            assertIntentHasLongExtra(NoteEditorActivity.class,
                    NoteEditorActivity.EXTRA_NOTE_ID, testNote.getId());
            waitForFocus();
            // Step 4: Press the Back button
            pressBackButton();
            // Step 5: Wait and verify that nothing was added to the repository
            repoObserver.assertNotChanged(
                    "Changes were made to the repository when the editor was cancelled!");
        }
    }

    /**
     * Verify that private records are hidden when the &ldquo;Show
     * private records&rdquo; preference is off, and then displayed
     * when this preference is turned on.
     */
    @Test
    public void testToggleShowPrivate() {
        List<NoteItem> testNotes = new ArrayList<>();
        List<NoteItem> publicNotes = new ArrayList<>();
        List<NoteItem> privateNotes = new ArrayList<>();
        for (int i = RAND.nextInt(5) + 7; (i >= 0)
                || publicNotes.isEmpty() || privateNotes.isEmpty(); --i) {
            NoteItem note = randomNote();
            note.setPrivate(RAND.nextBoolean()
                    ? StringEncryption.NO_ENCRYPTION : 0);
            testNotes.add(mockRepo.insertNote(note));
            if (note.isPrivate())
                privateNotes.add(note);
            else
                publicNotes.add(note);
        }
        NotePreferences prefs = NotePreferences.getInstance(testContext);
        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            NoteCursorAdapter[] adapter = new NoteCursorAdapter[1];
            wrapper.onActivity(activity -> {
                adapter[0] = activity.itemAdapter;
            });
            assertNotNull("Note cursor adapter has not been set",
                    adapter[0]);
            // Wait if necessary for the adapter to be populated
            if (adapter[0].getCount() <= 0) {
                long timeLimit = System.nanoTime() + 5000000000L;
                try (TestObserver adapterObserver = new TestObserver(adapter[0])) {
                    while (System.nanoTime() < timeLimit) {
                        adapterObserver.waitToClear();
                        if (adapter[0].getCount() > 0)
                            break;
                        adapterObserver.reset();
                    }
                }
            }
            // By default, private notes should not be shown
            assertEquals("Number of notes listed", publicNotes.size(),
                    adapter[0].getCount());

            // Now toggle the "Show private" flag, and wait for the callback
            try (TestPreferencesObserver prefsObserver =
                    new TestPreferencesObserver(testContext,
                            NotePreferences.NPREF_SHOW_PRIVATE)) {
                prefs.setShowPrivate(true);
                prefsObserver.assertChanged("Show private preference was not changed");
            }
            // It may take a little more time
            // for the adapter to get a new cursor...
            try {
                Thread.sleep(250);
            } catch (InterruptedException ix) {
                // Ignore
            }
            instrument.waitForIdleSync();
            assertEquals("Number of notes listed", testNotes.size(),
                    adapter[0].getCount());

            // Do it again in reverse
            try (TestPreferencesObserver prefsObserver =
                    new TestPreferencesObserver(testContext,
                            NotePreferences.NPREF_SHOW_PRIVATE)) {
                prefs.setShowPrivate(false);
                prefsObserver.assertChanged("Show private preference was not changed");
            }
            // It may take a little more time
            // for the adapter to get a new cursor...
            try {
                Thread.sleep(250);
            } catch (InterruptedException ix) {
                // Ignore
            }
            instrument.waitForIdleSync();
            assertEquals("Number of notes listed", publicNotes.size(),
                    adapter[0].getCount());
        }
    }

    /**
     * Open the activity&rsquo;s options menu, verify that a specific
     * item is shown, and select it.
     *
     * @param scenario the scenario in which the test is running
     * @param expectedText the expected text of the menu item
     * @param itemId the resource ID of the menu item to select
     *
     * @throws AssertionError if the options menu could not be opened,
     * if it does not contain the given item, if the item is not visible,
     * if the item text does not match the expected label, or if the
     * item is disabled.
     */
    static void pressOptionsMenuItem(
            ActivityScenario<NoteListActivity> scenario,
            String expectedText, int itemId) {
        scenario.onActivity(Activity::openOptionsMenu);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final Menu[] menuRef = new Menu[1];
        scenario.onActivity(activity -> {
            menuRef[0] = activity.menu;
        });
        assertNotNull("The options menu has not been set", menuRef[0]);
        final MenuItem[] itemRef = new MenuItem[1];
        final boolean[] visibility = new boolean[1];
        final String[] itemText = new String[1];
        scenario.onActivity(activity -> {
            itemRef[0] = menuRef[0].findItem(itemId);
            if (itemRef[0] == null)
                return;
            visibility[0] = itemRef[0].isVisible();
            itemText[0] = itemRef[0].getTitle().toString();
        });
        assertNotNull(String.format(Locale.US,
                "Menu item \"%s\" with resource ID %d is missing",
                expectedText, itemId), itemRef[0]);
        assertTrue(String.format(Locale.US,
                "Menu item \"%s\" with resource ID %d is not visible",
                expectedText, itemId), visibility[0]);
        assertEquals(String.format(Locale.US,
                "Text of menu item #%d", itemId), expectedText, itemText[0]);
        scenario.onActivity(activity -> {
            activity.onOptionsItemSelected(itemRef[0]);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * Get the equivalent shortened heading of a note as
     * {@link NoteCursorAdapter} would use for the note list.
     *
     * @param noteText the full text of the note
     */
    public static String getNoteHeading(String noteText) {
        String noteHeading =
                (noteText.length() > NoteCursorAdapter.MAX_HEADER_LENGTH)
                        ? noteText.substring(0,
                        NoteCursorAdapter.MAX_HEADER_LENGTH)
                : noteText;
        if (noteHeading.indexOf('\n') >= 0)
            noteHeading = noteHeading.substring(0, noteHeading.indexOf('\n'));
        return noteHeading;
    }

    /**
     * Verify that encrypted records display as &ldquo;[Locked]&rdquo; when
     * a password has not been provided; then after unlocking encrypted
     * records, their plain text should be shown.
     */
    @Test
    public void testUnlockEncryptedNotes() {
        final String password = RandomStringUtils.randomAlphanumeric(8);
        globalEncryption.setPassword(password.toCharArray());
        globalEncryption.addSalt();
        globalEncryption.storePassword(mockRepo);

        List<NoteItem> testNotes = new ArrayList<>();
        final Map<Long,String> decryptedNotes = new HashMap<>();
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i) {
            NoteItem note = randomNote();
            String plainText = note.getNote();
            note.setEncryptedNote(globalEncryption.encrypt(plainText));
            note.setPrivate(StringEncryption.encryptionType());
            note.setNote(null);
            note = mockRepo.insertNote(note);
            testNotes.add(note);
            decryptedNotes.put(note.getId(), plainText);
        }

        globalEncryption.forgetPassword();
        final String lockedText = testContext
                .getString(R.string.PasswordProtected);
        mockPrefs.initializePreference(NotePreferences.NPREF_SHOW_PRIVATE, true);

        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            NoteCursorAdapter[] adapter = new NoteCursorAdapter[1];
            wrapper.onActivity(activity -> {
                adapter[0] = activity.itemAdapter;
            });
            assertNotNull("Note cursor adapter has not been set",
                    adapter[0]);
            // Wait if necessary for the adapter to be populated
            if (adapter[0].getCount() <= 0) {
                long timeLimit = System.nanoTime() + 5000000000L;
                try (TestObserver adapterObserver = new TestObserver(adapter[0])) {
                    while (System.nanoTime() < timeLimit) {
                        adapterObserver.waitToClear();
                        if (adapter[0].getCount() > 0)
                            break;
                        adapterObserver.reset();
                    }
                }
            }
            assertEquals("Number of notes listed", testNotes.size(),
                    adapter[0].getCount());

            // Verify all entries are locked
            final Map<Long,String> expectedText = new TreeMap<>();
            final Map<Long,String> actualText = new TreeMap<>();
            for (long id : decryptedNotes.keySet())
                expectedText.put(id, lockedText);
            wrapper.onActivity(activity -> {
                for (int i = 0; i < adapter[0].getCount(); ++i) {
                    long id = adapter[0].getItemId(i);
                    View row = adapter[0].getView(i, null, null);
                    TextView description =
                            row.findViewById(R.id.NoteEditDescription);
                    actualText.put(id, description.getText().toString());
                }
            });
            assertEquals("Encrypted note list while locked",
                    expectedText, actualText);

            // Run through the "Unlock encrypted notes" dialog
            pressOptionsMenuItem(wrapper.getScenario(),
                    testContext.getString(R.string.MenuUnlock),
                    R.id.menuUnlock);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.MenuUnlock));
            Dialog[] dialogRef = new Dialog[1];
            wrapper.onActivity(activity -> {
                dialogRef[0] = activity.unlockDialog;
            });
            instrument.waitForIdleSync();
            assertNotNull("The unlock dialog has not been set",
                    dialogRef[0]);
            setEditText(wrapper.getScenario(), dialogRef[0],
                    "Password", R.id.EditTextPassword, password);
            // Once we press the button we have to wait for the
            // "Show encrypted notes" preference to change.
            try (TestPreferencesObserver prefObserver =
                    new TestPreferencesObserver(testContext,
                            NotePreferences.NPREF_SHOW_ENCRYPTED)) {
                pressDialogButton(wrapper.getScenario(), dialogRef[0],
                        android.R.id.button1);
                prefObserver.assertChanged(
                        "The show encrypted notes preference was not changed");
            }

            /*
             * Re-read the adapter entries and verify that they are
             * showing plain text.  This part shouldn't care about the
             * entire content of the note, just the start of it.
             */
            for (long id : decryptedNotes.keySet())
                // Temporary placeholder for any mismatches
                expectedText.put(id, getNoteHeading(decryptedNotes.get(id)));
            actualText.clear();
            wrapper.onActivity(activity -> {
                for (int i = 0; i < adapter[0].getCount(); ++i) {
                    long id = adapter[0].getItemId(i);
                    View row = adapter[0].getView(i, null, null);
                    TextView description =
                            row.findViewById(R.id.NoteEditDescription);
                    String textShown = description.getText().toString();
                    actualText.put(id, textShown);
                    if (expectedText.containsKey(id) &&
                            (expectedText.get(id).length() != textShown.length())) {
                        String fullDecrypted = decryptedNotes.get(id);
                        expectedText.put(id, fullDecrypted.substring(0,
                                Math.min(textShown.length(), fullDecrypted.length())));
                    }
                }
            });
            assertEquals("Decrypted note list after unlocking",
                    expectedText, actualText);

            // Finally, lock the encrypted notes up again.
            // This menu option shouldn't show any dialog.
            try (TestPreferencesObserver prefObserver =
                    new TestPreferencesObserver(testContext,
                            NotePreferences.NPREF_SHOW_ENCRYPTED)) {
                pressOptionsMenuItem(wrapper.getScenario(),
                        testContext.getString(R.string.MenuLock),
                        R.id.menuUnlock);
                prefObserver.assertChanged(
                        "The show encrypted notes preference was not changed");
            }

            for (long id : decryptedNotes.keySet())
                expectedText.put(id, lockedText);
            wrapper.onActivity(activity -> {
                for (int i = 0; i < adapter[0].getCount(); ++i) {
                    long id = adapter[0].getItemId(i);
                    View row = adapter[0].getView(i, null, null);
                    TextView description =
                            row.findViewById(R.id.NoteEditDescription);
                    actualText.put(id, description.getText().toString());
                }
            });
            assertEquals("Encrypted note list after locking back up",
                    expectedText, actualText);

        }

    }

}
