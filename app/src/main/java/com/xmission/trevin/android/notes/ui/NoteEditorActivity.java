/*
 * Copyright © 2011 Trevin Beattie
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.EncryptionException;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.app.*;
import android.content.*;
import android.database.SQLException;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;

/**
 * Displays the note of a Note item.  Will display the item from the
 * ID provided in the intent, which is required for an existing note;
 * if no ID is provided, this starts a new note.
 */
public class NoteEditorActivity extends Activity {

    private static final String TAG = "NoteEditorActivity";

    /**
     * The name of the Intent extra data that holds the category ID
     * if we are creating a new note.  This is a {@code long} value.
     */
    public static final String EXTRA_CATEGORY_ID =
            "com.xmission.trevin.android.notes.CategoryId";

    /**
     * The name of the Intent extra data that holds the note ID
     * if we are editing an existing note.  This is a {@code long} value.
     */
    public static final String EXTRA_NOTE_ID =
            "com.xmission.trevin.android.notes.NoteId";

    private static final int DETAIL_DIALOG_ID = 4;

    /** The ID of the note we are editing */
    Long noteId;

    /** The note */
    EditText noteEditBox = null;

    /** The original contents of the note (or an empty string for a new note) */
    String oldNoteText;

    /** The details dialog */
    Dialog detailsDialog = null;
    /** Category button in the details dialog */
    Spinner categorySpinner = null;
    /** Private checkbox in the details dialog */
    CheckBox privateCheckBox = null;

    /** The &ldquo;Done&rdquo; button for saving the note */
    Button okButton = null;

    /** The &ldquo;Details&rdquo; button for this note */
    Button detailsButton = null;

    AlertDialog deleteConfirmationDialog = null;

    /** The Note Pad database */
    NoteRepository repository = null;

    /** Category adapter */
    CategorySelectAdapter categoryAdapter = null;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    /** Whether the note is new */
    boolean isNewNote;

    /** The note's original creation time */
    Instant createTime;

    /** The note's last modification time */
    Instant modTime;

    /** The note's current category */
    long categoryID;

    /** Whether the note should be private */
    boolean isPrivate;

    StringEncryption encryptor;

    /**
     * Set the repository to be used by this activity.
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
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // Inflate our view so we can find our field
        setContentView(R.layout.note);
        noteEditBox = findViewById(R.id.NoteEditText);
        okButton = findViewById(R.id.NoteButtonOK);
        detailsButton = findViewById(R.id.NoteButtonDetails);

        Object savedData;
        if (savedInstanceState != null) {
            savedData = savedInstanceState.getSerializable("noteFormData");
        } else {
            savedData = getLastNonConfigurationInstance();
        }
        boolean hasSavedState = (savedData instanceof NoteFormData);

        if (hasSavedState) {
            Log.d(TAG, String.format(Locale.US,
                    ".onCreate(%s); savedData=%s",
                    savedInstanceState, savedData));
            restoreState((NoteFormData) savedData);
        } else {
            Intent intent = getIntent();
            noteId = null;
            categoryID = intent.getLongExtra(EXTRA_CATEGORY_ID,
                    NoteCategory.UNFILED);
            oldNoteText = "";
            createTime = null;
            modTime = null;
            isPrivate = false;

            if (intent.hasExtra(EXTRA_NOTE_ID)) {
                // We're editing an existing note
                noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1);
                if (noteId == -1) {
                    Log.d(TAG, ".onCreate: Invalid note ID passed");
                    noteId = null;
                }
            }
            isNewNote = (noteId == null);

            Log.d(TAG, String.format(Locale.US,
                    ".onCreate(%s); id=%s, categoryId=%d",
                    savedInstanceState, noteId, categoryID));

            setTitle(getResources().getString(R.string.app_name));
            noteEditBox.setText("");
        }

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
        encryptor = StringEncryption.holdGlobalEncryption();

        // Establish a connection to the database
        // (on a non-UI thread) to read the note.
        executor.submit(new OpenRepositoryRunner(
                !(isNewNote || hasSavedState)));
    }

    /**
     * A runner to open the database on a non-UI thread
     * (if on Honeycomb or later) and then load the note into the UI.
     */
    private class OpenRepositoryRunner implements Runnable {
        final boolean loadNote;
        OpenRepositoryRunner(boolean loadNote) {
            this.loadNote = loadNote;
        }
        @Override
        public void run() {
            Log.d(TAG, "Opening the repository");
            repository.open(NoteEditorActivity.this);
            NoteItem note = loadNote ? repository.getNoteById(noteId) : null;
            runOnUiThread(new FinalizeUIRunner(note));

        }
    }

    /**
     * Called (on the UI thread) after we&rsquo;ve established a
     * connection to the database and read the note (if any)
     * to populate the UI and enable buttons.
     */
    private class FinalizeUIRunner implements Runnable {
        final NoteItem note;
        FinalizeUIRunner(NoteItem note) {
            this.note = note;
        }
        @Override
        public void run() {
            Log.d(TAG, "Finalizing the UI");
            if (note != null) {
                isPrivate = note.getPrivate() > 0;
                String noteText;
                if (note.getPrivate() > 1) {
                    if (encryptor.hasKey()) {
                        try {
                            noteText = encryptor.decrypt(note.getEncryptedNote());
                        } catch (EncryptionException e) {
                            Log.e(TAG, String.format(Locale.US,
                                    "Error decrypting note #%d", noteId), e);
                            Toast.makeText(NoteEditorActivity.this,
                                    e.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                    } else {
                        Log.i(TAG, String.format(Locale.US,
                                "Cannot open encrypted note #%d"
                                        + " without a password", noteId));
                        Toast.makeText(NoteEditorActivity.this,
                                getString(R.string.ToastPasswordProtected),
                                Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                } else {
                    noteText = note.getNote();
                }
                oldNoteText = noteText;

                setTitleToNoteLine();

                noteEditBox.setText(noteText);
                categoryID = note.getCategoryId();
                createTime = note.getCreateTime();
                modTime = note.getModTime();
            }

            // Set callbacks
            okButton.setOnClickListener(new DoneButtonOnClickListener());
            detailsButton.setOnClickListener(new DetailsButtonOnClickListener());
        }
    }

    /**
     * Set the title of the activity based on the first line of the original
     * note text, if any; otherwise just the app title.
     */
    private void setTitleToNoteLine() {
        if (oldNoteText.length() == 0) {
            setTitle(getString(R.string.app_name));
            return;
        }
        String noteStart = oldNoteText;
        if (noteStart.length() > 80)
            noteStart = noteStart.substring(0, 80);
        if (noteStart.indexOf('\n') > -1)
            noteStart = noteStart.substring(0, noteStart.indexOf('\n'));
        setTitle(getString(R.string.app_name) + " \u2015 " + noteStart);
    }

    /**
     * Restore the state of the activity from a saved configuration
     *
     * @param data the saved configuration data
     */
    private void restoreState(NoteFormData data) {
        noteId = data.noteId;
        isNewNote = (noteId == null);
        oldNoteText = data.oldNoteText;
        noteEditBox.setText(data.currentNoteText);
        createTime = data.createTime;
        modTime = data.modTime;
        categoryID = data.categoryId;
        isPrivate = data.isPrivate;
        setTitleToNoteLine();
    }

    /**
     * Called when the activity is about to be destroyed
     * and then immediately restarted (such as an orientation change).
     */
    @Override
    public NoteFormData onRetainNonConfigurationInstance() {
        Log.d(TAG, ".onRetainNonConfigurationInstance");
        NoteFormData data = new NoteFormData();
        data.noteId = noteId;
        data.oldNoteText = oldNoteText;
        data.currentNoteText = noteEditBox.getText().toString();
        data.createTime = createTime;
        data.modTime = modTime;
        data.categoryId = categoryID;
        data.isPrivate = isPrivate;
        return data;
    }

    /**
     * Called when the activity is about to be destroyed and then
     * restarted at some indefinite point in the future,
     * for example when Android needs to reclaim resources.
     *
     * @param outState a container in which to save the state.
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.d(TAG, ".onSaveInstanceState");
        outState.putSerializable("noteFormData",
                onRetainNonConfigurationInstance());
        super.onSaveInstanceState(outState);
    }

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
        Log.d(TAG, "Back button pressed");
        // Did the user make any changes to the note?
        String note = noteEditBox.getText().toString();
        if (!oldNoteText.equals(note)) {
            Log.d(TAG, "Note has been changed; asking for confirmation");
            new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(R.string.ConfirmUnsavedChanges)
                    .setTitle(R.string.AlertUnsavedChangesTitle)
                    .setNegativeButton(R.string.ConfirmationButtonCancel, DISMISS_LISTENER)
                    .setPositiveButton(R.string.ConfirmationButtonDiscard,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                    Log.d(TAG, "Calling superclass onBackPressed");
                                    NoteEditorActivity.super.onBackPressed();
                                }
                            })
                    .create().show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, ".onDestroy()");
        StringEncryption.releaseGlobalEncryption(this);
        repository.release(this);
        super.onDestroy();
    }

    /** Called when opening a dialog for the first time */
    @Override
    public Dialog onCreateDialog(int id) {
        if (id != DETAIL_DIALOG_ID) {
            Log.e(TAG, "onCreateDialog(" + id + "): unknown dialog ID");
            return null;
        }
        Log.d(TAG, String.format(Locale.US, ".onCreateDialog(%d)", id));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.NoteButtonDetails);
        View detailView = ((LayoutInflater) getSystemService(
                LAYOUT_INFLATER_SERVICE)).inflate(R.layout.details,
                findViewById(R.id.NoteDetailsLayoutRoot));
        categorySpinner = detailView.findViewById(
                R.id.CategorySpinner);
        privateCheckBox = detailView.findViewById(
                R.id.DetailCheckBoxPrivate);
        privateCheckBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(
                            @NonNull CompoundButton buttonView,
                            boolean isChecked) {
                        isPrivate = isChecked;
                    }
                });
        categoryAdapter = new CategorySelectAdapter(this, repository);
        categorySpinner.setAdapter(categoryAdapter);
        categorySpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view,
                            int position, long id) {
                        categoryID = id;
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // Ignore
                    }
                });
        builder.setView(detailView);
        if (isNewNote) {
            TableLayout tl = detailView.findViewById(
                    R.id.TableLayoutTimestamps);
            tl.setVisibility(View.GONE);
        } else {
            DateTimeFormatter dtf = DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault());
            TextView text = detailView.findViewById(
                    R.id.DetailTextCreatedDate);
            text.setText(createTime.atZone(ZoneOffset.systemDefault())
                    .format(dtf));
            text = detailView.findViewById(
                    R.id.DetailTextModifiedDate);
            text.setText(modTime.atZone(ZoneOffset.systemDefault())
                    .format(dtf));
        }
        Button button = detailView.findViewById(R.id.DetailButtonOK);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                detailsDialog.dismiss();
            }
        });
        button = detailView.findViewById(R.id.DetailButtonCancel);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                detailsDialog.dismiss();
            }
        });
        button = detailView.findViewById(R.id.DetailButtonDelete);
        button.setOnClickListener(new DeleteButtonOnClickListener());
        detailsDialog = builder.create();
        return detailsDialog;
    }

    /** Called each time a dialog is opened */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        Log.d(TAG, String.format(Locale.US, ".onPrepareDialog(%d)", id));
        if (id != DETAIL_DIALOG_ID)
            return;
        privateCheckBox.setChecked(isPrivate);
        categorySpinner.setSelection(categoryAdapter
                .getCategoryPosition(categoryID));
    }

    /** Generic dialog dismissal listener */
    static final DialogInterface.OnClickListener DISMISS_LISTENER =
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            };

    class DoneButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "NoteButtonOK.onClick");
            NoteItem note = new NoteItem();
            if (!isNewNote)
                note.setId(noteId);
            note.setCategoryId(categoryID);
            if (createTime != null)
                note.setCreateTime(createTime);
            note.setNote(noteEditBox.getText().toString());
            if (note.getNote().length() > 0) {
                if (isPrivate) {
                    note.setPrivate(StringEncryption.NO_ENCRYPTION);
                    if (encryptor.hasKey()) {
                        try {
                            note.setEncryptedNote(encryptor.encrypt(
                                    note.getNote()));
                            note.setPrivate(StringEncryption.encryptionType());
                        } catch (EncryptionException e) {
                            Toast.makeText(NoteEditorActivity.this,
                                    e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    note.setPrivate(0);
                }
            } else {
                /*
                 * Don't bother with confirmation; the user went
                 * through all the trouble of erasing the text.
                 */
                note.setNote(null);
                if (noteId == null) {
                    // We can skip the save step
                    finish();
                    return;
                }
            }

            // Disable the note field and Done and Details buttons
            // until the save is finished.
            noteEditBox.setEnabled(false);
            okButton.setEnabled(false);
            detailsButton.setEnabled(false);

            // Write and commit the changes
            executor.submit(new SaveChangesRunner(note, isNewNote));
        }
    }

    /**
     * Save the note on a non-UI thread.  Closes the activity when finished.
     * If an error occurs, shows an alert (on the UI thread) instead.
     */
    class SaveChangesRunner implements Runnable {
        private final boolean isNew;
        private final NoteItem toSave;
        SaveChangesRunner(@NonNull NoteItem note, boolean isNew) {
            toSave = note;
            this.isNew = isNew;
        }

        @Override
        public void run() {
            try {
                if (toSave.getNote() == null) {
                    Log.d(TAG, "Deleting the empty note");
                    /* Don't bother with confirmation; the user went
                     * through all the trouble of erasing the text. */
                    if (noteId != null)
                        repository.deleteNote(noteId);
                } else {
                    Log.d(TAG, "Saving the note");
                    toSave.setModTimeNow();
                    if (isNew) {
                        toSave.setCreateTime(toSave.getModTime());
                        repository.insertNote(toSave);
                    } else {
                        repository.updateNote(toSave);
                    }
                }
                runOnUiThread(SAVE_FINISHED_RUNNER);
            } catch (SQLException sx) {
                Log.e(TAG, "SaveChangesRunner", sx);
                runOnUiThread(new SaveExceptionAlertRunner(sx));
            }
        }
    }

    class DetailsButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Log.d(TAG, "NoteButtonDetails.onClick");
            showDialog(DETAIL_DIALOG_ID);
        }
    }

    /**
     * A runner for deleting the note.  We do this in a separate thread
     * because database operations cannot be run on the main UI thread.
     */
    class DeleteNoteRunner implements Runnable {
        @Override
        public void run() {
            try {
                Log.d(TAG, "Deleting the note");
                repository.deleteNote(noteId);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (detailsDialog != null)
                            detailsDialog.dismiss();
                        NoteEditorActivity.this.finish();
                    }
                });
            } catch (SQLException sx) {
                Log.e(TAG, "DeleteNoteRunner", sx);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(NoteEditorActivity.this,
                                sx.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    class DeleteButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Log.d(TAG, "NoteButtonDelete.onClick()");
            deleteConfirmationDialog = new AlertDialog
                    .Builder(NoteEditorActivity.this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(R.string.ConfirmationTextDeleteNote)
                    .setNegativeButton(R.string.ConfirmationButtonCancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                    deleteConfirmationDialog = null;
                                }
                            })
                    .setPositiveButton(R.string.ConfirmationButtonOK,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog2, int which) {
                                    dialog2.dismiss();
                                    deleteConfirmationDialog = null;
                                    executor.submit(new DeleteNoteRunner());
                                }
                            })
                    .create();
            deleteConfirmationDialog.show();
        }
    }

    /**
     * A runner to clean up the note activity and finish on the UI thread.
     */
    private final Runnable SAVE_FINISHED_RUNNER = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Save finished");
            noteId = null;
            oldNoteText = "";
            createTime = null;
            modTime = null;
            NoteEditorActivity.this.finish();
        }
    };

    /** A runner to display an exception message on the UI thread. */
    class SaveExceptionAlertRunner implements Runnable {
        private final Exception e;
        SaveExceptionAlertRunner(Exception exception) {
            e = exception;
        }
        @Override
        public void run() {
            new AlertDialog.Builder(NoteEditorActivity.this)
                    .setMessage(e.getMessage())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setNeutralButton(R.string.ConfirmationButtonCancel,
                            DISMISS_LISTENER).create().show();
            noteEditBox.setEnabled(true);
            okButton.setEnabled(true);
            detailsButton.setEnabled(!isNewNote);
        }
    }

}
