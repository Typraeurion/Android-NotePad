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
import static com.xmission.trevin.android.notes.util.RandomNoteUtils.*;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.*;
import static org.junit.Assert.*;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.*;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteCursor;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema;
import com.xmission.trevin.android.notes.provider.TestObserver;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;

/**
 * Tests for the {@link NoteEditorActivity}
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class NoteEditorActivityTests {

    private static final Instrumentation instrument =
            InstrumentationRegistry.getInstrumentation();
    private static Context testContext = null;

    static final Random RAND = new Random();

    private static MockSharedPreferences sharedPrefs = null;
    private static MockNoteRepository mockRepo = null;

    @BeforeClass
    public static void initializeMocks() {
        testContext = instrument.getTargetContext();
        mockRepo = MockNoteRepository.getInstance();
        NoteRepositoryImpl.setInstance(mockRepo);
        sharedPrefs = MockSharedPreferences.getInstance();
        NotePreferences.setSharedPreferences(sharedPrefs);
    }

    @Before
    public void resetMocks() {
        sharedPrefs.resetMock();
        mockRepo.open(testContext);
        mockRepo.clear();
    }

    @After
    public void cleanUp() {
        mockRepo.release(testContext);
    }

    /**
     * Get the Details dialog from the editor activity.  The caller
     * <i>must</i> have pressed the &ldquo;Details&rdquo; button first,
     * otherwise this dialog will not have been created.
     *
     * @param scenario the scenario in which the test is running
     *
     * @return the details dialog
     *
     * @throws AssertionError if the dialog has not been created
     */
    private static Dialog getNoteDetailsDialog(
            ActivityScenario<NoteEditorActivity> scenario) {
        Dialog[] dialog = new Dialog[1];
        scenario.onActivity(activity -> {
            dialog[0] = activity.detailsDialog;
        });
        assertNotNull("The details dialog has not been set", dialog[0]);
        return dialog[0];
    }

    /**
     * Check that the category selection drop-down has been populated,
     * waiting for it if necessary (up to 1 second).
     *
     * @param scenario the scenario in which the test is running
     * @param dialog the details dialog where the category selection is found
     *
     * @return the category selection drop-down element
     */
    private static Spinner getCategorySpinner(
            ActivityScenario<NoteEditorActivity> scenario, Dialog dialog) {
        Spinner[] spinner = new Spinner[1];
        CategorySelectAdapter[] adapter = new CategorySelectAdapter[1];
        scenario.onActivity(activity -> {
            spinner[0] = (Spinner) dialog.findViewById(
                    R.id.CategorySpinner);
        });
        assertNotNull("Category drop-down was not found", spinner[0]);
        adapter[0] = (CategorySelectAdapter) spinner[0].getAdapter();
        // Step 2: If the adapter hasn't been populated yet,
        //         we have to wait for a data update...
        //         and then for the activity to change the selection.
        if (adapter[0].isEmpty()) {
            try (TestObserver observer = new TestObserver(adapter[0])) {
                observer.assertChanged("Timed out waiting for the category"
                        + " selection drop-down to be populated");
            }
        }
        instrument.waitForIdleSync();
        return spinner[0];
    }

    /**
     * Verify that when the details dialog is shown for a new note,
     * the category selection drop-down is set to the category
     * passed in the intent.
     */
    @Test
    public void testNewNoteDefaultCategory() {
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i)
            mockRepo.insertCategory(randomCategoryName('A', 'Z'));
        NoteCategory expectedCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'Z'));
        Intent intent = new Intent(testContext, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_CATEGORY_ID,
                expectedCategory.getId());
        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(intent)) {
            hideKeyboard(wrapper.getScenario());
            assertButtonShown(wrapper.getScenario(), "Details",
                    R.id.NoteButtonDetails);
            pressButton(wrapper.getScenario(), R.id.NoteButtonDetails);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            Dialog detailsDialog = getNoteDetailsDialog(wrapper.getScenario());
            // Get the category spinner;
            // this call will wait for its data to be ready.
            Spinner categorySelect = getCategorySpinner(
                    wrapper.getScenario(), detailsDialog);
            long actualCategoryId = categorySelect.getSelectedItemId();
            NoteCategory actualCategory =
                    mockRepo.getCategoryById(actualCategoryId);
            assertEquals("Selected category", expectedCategory, actualCategory);
            assertFalse("Private (for new note)",
                    getCheckboxState(wrapper.getScenario(), detailsDialog,
                            "Private", R.id.DetailCheckBoxPrivate));
            // Cancel the details dialog
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailButtonCancel);
        }
    }

    /**
     * Verify the editor retains its state for a new note when the activity
     * is restarted due to a configuration change (e.g. screen rotation,
     * keyboard (a/de)tachment).  When the note is saved, everything entered
     * in the editor before the restart should be saved.
     */
    @Test
    public void testRestoreEditorNewNote() {
        final String expectedContent = randomParagraph();
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i)
            mockRepo.insertCategory(randomCategoryName('A', 'Z'));
        final NoteCategory expectedCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'Z'));
        Intent intent = new Intent(testContext, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_CATEGORY_ID,
                NoteCategory.UNFILED);

        Instant minExpectedTimestamp;
        Instant maxExpectedTimestamp;
        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(intent);
             TestObserver repoObserver = new TestObserver(mockRepo)) {
            hideKeyboard(wrapper.getScenario());
            assertButtonShown(wrapper.getScenario(), "Details",
                    R.id.NoteButtonDetails);
            assertButtonShown(wrapper.getScenario(), "Done",
                    R.id.NoteButtonOK);
            // Start with the note content
            setEditText(wrapper.getScenario(), "note",
                    R.id.NoteEditText, expectedContent);
            // Open the Details dialog; this is where the restart will occur
            pressButton(wrapper.getScenario(), R.id.NoteButtonDetails);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            Dialog detailsDialog = getNoteDetailsDialog(wrapper.getScenario());
            // Check the default category;
            // this call will wait for its data to be ready.
            Spinner categorySelect = getCategorySpinner(
                    wrapper.getScenario(), detailsDialog);
            assertEquals("Default category ID", NoteCategory.UNFILED,
                    categorySelect.getSelectedItemId());
            assertFalse("Default private setting",
                    getCheckboxState(wrapper.getScenario(), detailsDialog,
                            "Private", R.id.DetailCheckBoxPrivate));
            // Now make changes in the details
            CategorySelectAdapter categoryAdapter = (CategorySelectAdapter)
                    categorySelect.getAdapter();
            wrapper.onActivity(activity -> {
                        categorySelect.setSelection(categoryAdapter
                                .getCategoryPosition(expectedCategory.getId()));
            });
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailCheckBoxPrivate);

            // Restart the activity here
            wrapper.recreate();

            // The details dialog should still be shown
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            assertDialogButtonShown(wrapper.getScenario(), detailsDialog,
                    "OK", R.id.DetailButtonOK);
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailButtonOK);
            minExpectedTimestamp = Instant.now();
            pressButton(wrapper.getScenario(), R.id.NoteButtonOK);

            // Wait for the note to be saved
            repoObserver.assertChanged();
            maxExpectedTimestamp = Instant.now();
        }

        // Lastly, verify the note that was saved.  We don't know its
        // ID, but it should be the last (and only) note in the repository.
        NoteItem actualNote = null;
        try (NoteCursor cursor = mockRepo.getNotes(
                NotePreferences.ALL_CATEGORIES, true, true,
                NoteRepositoryImpl.NOTE_TABLE_NAME + "."
                        + NoteSchema.NoteItemColumns._ID + " desc")) {
            if (cursor.moveToFirst())
                actualNote = cursor.getNote();
        }
        assertNotNull("No note was saved", actualNote);
        assertTrue(String.format(Locale.US,
                "Note creation time expected between %s and %s but was %s",
                minExpectedTimestamp, maxExpectedTimestamp,
                actualNote.getCreateTime()),
                !actualNote.getCreateTime().isBefore(minExpectedTimestamp) &&
                        !actualNote.getCreateTime().isAfter(maxExpectedTimestamp));
        assertEquals("Note last modification time",
                actualNote.getCreateTime(), actualNote.getModTime());
        assertEquals("Note content", expectedContent, actualNote.getNote());
        NoteCategory actualCategory = new NoteCategory();
        actualCategory.setId(actualNote.getCategoryId());
        actualCategory.setName(actualNote.getCategoryName());
        assertEquals("Note category", expectedCategory, actualCategory);
        assertEquals("Note privacy level", StringEncryption.NO_ENCRYPTION,
                actualNote.getPrivate());
    }

    /**
     * Verify that when a very long note is being edited and the
     * activity is restarted, the note edit box retains the cursor position.
     */
    @Test
    public void testRestoreCursorPosition() {
        List<String> paragraphs = new ArrayList<>();
        int targetPosition = 0;
        for (int i = 5 + RAND.nextInt(3); i >= 0; --i) {
            String p = randomParagraph();
            if (i > 1)
                targetPosition += p.length() + 2;
            paragraphs.add(p);
        }
        String document = String.join("\n\n", paragraphs);
        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteEditorActivity.class)) {
            hideKeyboard(wrapper.getScenario());
            assertButtonShown(wrapper.getScenario(),
                    "Done", R.id.NoteButtonOK);
            setEditText(wrapper.getScenario(), "Note",
                    R.id.NoteEditText, document);
            final int initPosition = targetPosition;
            wrapper.onActivity(activity -> {
                EditText noteView = activity.findViewById(R.id.NoteEditText);
                noteView.requestFocus();
                noteView.setSelection(initPosition);
            });

            wrapper.recreate();

            final int[] actualPosition = new int[] { -1 };
            wrapper.onActivity(activity -> {
                EditText noteView = activity.findViewById(R.id.NoteEditText);
                actualPosition[0] = noteView.getSelectionStart();
            });
            assertEquals("Cursor position after restart",
                    targetPosition, actualPosition[0]);
        }
    }

    /**
     * Verify that when the editor is shown for an existing note, it&rsquo;s
     * populated with that note&rsquo;s data from the repository.
     */
    @Test
    public void testEditNote() {
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i)
            mockRepo.insertCategory(randomCategoryName('A', 'Z'));
        final NoteCategory expectedCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'Z'));
        NoteItem newNote = randomNote();
        newNote.setCategoryId(expectedCategory.getId());
        newNote.setCategoryName(expectedCategory.getName());
        newNote = mockRepo.insertNote(newNote);
        Intent intent = new Intent(testContext, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_CATEGORY_ID,
                expectedCategory.getId());
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, newNote.getId());

        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(intent)) {
            hideKeyboard(wrapper.getScenario());
            assertButtonShown(wrapper.getScenario(), "Details",
                    R.id.NoteButtonDetails);
            assertEquals("Note content", newNote.getNote(),
                    getElementText(wrapper.getScenario(), "Note",
                            R.id.NoteEditText));
            // The rest of the data are in the details dialog
            pressButton(wrapper.getScenario(), R.id.NoteButtonDetails);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            Dialog detailsDialog = getNoteDetailsDialog(wrapper.getScenario());
            Spinner categorySelect = getCategorySpinner(
                    wrapper.getScenario(), detailsDialog);
            assertEquals("Selected category", expectedCategory,
                    mockRepo.getCategoryById(categorySelect.getSelectedItemId()));
            assertEquals("Is private", newNote.isPrivate(),
                    getCheckboxState(wrapper.getScenario(), detailsDialog,
                            "Private", R.id.DetailCheckBoxPrivate));
            // The text on the timestamp fields may be localized
            DateTimeFormatter dtf = DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault());
            assertEquals("Note creation time", newNote.getCreateTime()
                    .atZone(ZoneOffset.systemDefault()).format(dtf),
                    getElementText(wrapper.getScenario(), detailsDialog,
                            "Created time", R.id.DetailTextCreatedDate));
            assertEquals("Note modification time", newNote.getModTime()
                    .atZone(ZoneOffset.systemDefault()).format(dtf),
                    getElementText(wrapper.getScenario(), detailsDialog,
                            "Modified time", R.id.DetailTextModifiedDate));

            // Clean up
            assertDialogButtonShown(wrapper.getScenario(), detailsDialog,
                    "Cancel", R.id.DetailButtonCancel);
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailButtonCancel);
        }

    }

    /**
     * Verify the note editor retains its state for an existing note in the
     * middle of being edited when the activity is restarted due to a
     * configuration change (e.g. screen rotation, keyboard (a/de)tachment).
     * When the note is saved, everything entered in the editor before the
     * restart should be saved.
     */
    @Test
    public void testRestoreFormEditNote() {
        for (int i = RAND.nextInt(3) + 3; i >= 0; --i)
            mockRepo.insertCategory(randomCategoryName('A', 'Z'));
        NoteItem newNote = randomNote();
        NoteCategory oldCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'Z'));
        newNote.setCategoryId(oldCategory.getId());
        newNote.setCategoryName(oldCategory.getName());
        newNote = mockRepo.insertNote(newNote);

        final String expectedContent = randomParagraph();
        final NoteCategory expectedCategory = mockRepo.insertCategory(
                randomCategoryName('A', 'Z'));
        final boolean expectPrivate = !newNote.isPrivate();

        Intent intent = new Intent(testContext, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_CATEGORY_ID,
                expectedCategory.getId());
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, newNote.getId());

        Instant minExpectedTimestamp;
        Instant maxExpectedTimestamp;
        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(intent);
             TestObserver repoObserver = new TestObserver(mockRepo)) {
            hideKeyboard(wrapper.getScenario());
            assertButtonShown(wrapper.getScenario(), "Details",
                    R.id.NoteButtonDetails);
            assertButtonShown(wrapper.getScenario(), "Done",
                    R.id.NoteButtonOK);
            // Start with the note content
            setEditText(wrapper.getScenario(), "note",
                    R.id.NoteEditText, expectedContent);
            // Open the Details dialog; this is where the restart will occur
            pressButton(wrapper.getScenario(), R.id.NoteButtonDetails);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            Dialog detailsDialog = getNoteDetailsDialog(wrapper.getScenario());
            Spinner categorySelect = getCategorySpinner(
                    wrapper.getScenario(), detailsDialog);
            // Now make changes in the details
            CategorySelectAdapter categoryAdapter = (CategorySelectAdapter)
                    categorySelect.getAdapter();
            wrapper.onActivity(activity -> {
                        categorySelect.setSelection(categoryAdapter
                                .getCategoryPosition(expectedCategory.getId()));
            });
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailCheckBoxPrivate);

            // Restart the activity here
            wrapper.recreate();

            // The details dialog should still be shown
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            assertDialogButtonShown(wrapper.getScenario(), detailsDialog,
                    "OK", R.id.DetailButtonOK);
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailButtonOK);
            minExpectedTimestamp = Instant.now();
            pressButton(wrapper.getScenario(), R.id.NoteButtonOK);

            // Wait for the note to be saved
            repoObserver.assertChanged();
            maxExpectedTimestamp = Instant.now();
        }

        // Lastly, verify the note that was saved.
        NoteItem actualNote = mockRepo.getNoteById(newNote.getId());
        assertNotNull("The note disappeared!", actualNote);
        assertEquals("Note creation time was changed",
                newNote.getCreateTime(), actualNote.getCreateTime());
        assertTrue(String.format(Locale.US,
                "Note modification time expected between %s and %s but was %s",
                minExpectedTimestamp, maxExpectedTimestamp,
                actualNote.getModTime()),
                !actualNote.getModTime().isBefore(minExpectedTimestamp) &&
                        !actualNote.getModTime().isAfter(maxExpectedTimestamp));
        assertEquals("Note content", expectedContent, actualNote.getNote());
        NoteCategory actualCategory = new NoteCategory();
        actualCategory.setId(actualNote.getCategoryId());
        actualCategory.setName(actualNote.getCategoryName());
        assertEquals("Note category", expectedCategory, actualCategory);
        assertEquals("Note privacy level",
                expectPrivate ? StringEncryption.NO_ENCRYPTION : 0,
                actualNote.getPrivate());
    }

    /**
     * Verify deleting a note.  This entails:
     * <ol>
     *     <li>Opening the &ldquo;Details&rdquo; dialog</li>
     *     <li>Clicking the &ldquo;Delete&rdquo; button</li>
     *     <li>Clicking the &ldquo;OK&rdquo; button in the confirmation dialog</li>
     * </ol>
     */
    @Test
    public void testDeleteNote() {
        NoteItem targetNote = mockRepo.insertNote(randomNote());
        Intent intent = new Intent(testContext, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, targetNote.getId());
        try (ActivityScenarioResultsWrapper<NoteEditorActivity> wrapper =
                        ActivityScenarioResultsWrapper.launch(intent);
                     TestObserver repoObserver = new TestObserver(mockRepo)) {
            hideKeyboard(wrapper.getScenario());
            // Step 1: Open the Details dialog
            assertButtonShown(wrapper.getScenario(), "Details",
                    R.id.NoteButtonDetails);
            pressButton(wrapper.getScenario(), R.id.NoteButtonDetails);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.DetailTextCategory));
            Dialog detailsDialog = getNoteDetailsDialog(wrapper.getScenario());
            // Step 2: Click Delete
            assertDialogButtonShown(wrapper.getScenario(), detailsDialog,
                    "Delete", R.id.DetailButtonDelete);
            pressDialogButton(wrapper.getScenario(), detailsDialog,
                    R.id.DetailButtonDelete);
            assertDialogShown(wrapper.getScenario(),
                    testContext.getString(R.string.ConfirmationTextDeleteNote));
            Dialog[] confirmationDialog = new Dialog[1];
            wrapper.onActivity(activity -> {
                confirmationDialog[0] = activity.deleteConfirmationDialog;
            });
            assertNotNull("The confirmation dialog has not been set",
                    confirmationDialog[0]);
            pressDialogButton(wrapper.getScenario(), confirmationDialog[0],
                    android.R.id.button1);

            // Wait for the note to be deleted
            repoObserver.assertChanged();
        }

        assertNull("The note was not deleted",
                mockRepo.getNoteById(targetNote.getId()));
    }

}
