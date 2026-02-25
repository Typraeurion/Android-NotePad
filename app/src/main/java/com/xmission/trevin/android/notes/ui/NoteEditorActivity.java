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

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.app.*;
import android.content.*;
import android.database.SQLException;
import android.net.Uri;
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

    /** The Note Pad database */
    NoteRepository repository = null;

    /** Category adapter */
    CategorySelectAdapter categoryAdapter = null;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    /** Whether the note is new */
    boolean isNewNote;

    /** The note's original creation time */
    Long createTime;

    /** The note's last modification time */
    Long modTime;

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
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

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
            isNewNote = false;
            noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1);
        } else {
            isNewNote = true;
        }

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();

        // Inflate our view so we can find our field
	setContentView(R.layout.note);

	encryptor = StringEncryption.holdGlobalEncryption();

        setTitle(getResources().getString(R.string.app_name));

	noteEditBox = (EditText) findViewById(R.id.NoteEditText);
	noteEditBox.setText("");

        // Establish a connection to the database
        // (on a non-UI thread) to read the note.
        executor.submit(new OpenRepositoryRunner());
    }

    /**
     * A runner to open the database on a non-UI thread
     * (if on Honeycomb or later) and then load the note into the UI.
     */
    private class OpenRepositoryRunner implements Runnable {
        @Override
        public void run() {
            repository.open(NoteEditorActivity.this);

            NoteItem note = (noteId == null) ? null
                    : repository.getNoteById(noteId);
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
            if (note != null) {
                isPrivate = note.getPrivate() > 0;
                String noteText;
                if (note.getPrivate() > 1) {
                    if (encryptor.hasKey()) {
                        try {
                            noteText = encryptor.decrypt(note.getEncryptedNote());
                        } catch (GeneralSecurityException gsx) {
                            Toast.makeText(NoteEditorActivity.this, gsx.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                    } else {
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

                // Set the activity title to the first line of the note
                String noteStart = noteText;
                if (noteStart.length() > 80)
                    noteStart = noteStart.substring(0, 80);
                if (noteStart.indexOf('\n') > -1)
                    noteStart = noteStart.substring(0, noteStart.indexOf('\n'));
                setTitle(getString(R.string.app_name) + " \u2015 " + noteStart);

                noteEditBox.setText(noteText);
                categoryID = note.getCategoryId();
                createTime = note.getCreateTime();
                modTime = note.getModTime();
            }

            // Set callbacks
            Button button = (Button) findViewById(R.id.NoteButtonOK);
            button.setOnClickListener(new DoneButtonOnClickListener());

            button = (Button) findViewById(R.id.NoteButtonDetails);
            button.setOnClickListener(new DetailsButtonOnClickListener());
        }
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
                    .setNegativeButton(R.string.ConfirmationButtonCancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
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

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.NoteButtonDetails);
        View detailView = ((LayoutInflater) getSystemService(
                LAYOUT_INFLATER_SERVICE)).inflate(R.layout.details,
                (LinearLayout) findViewById(
                        R.id.NoteDetailsLayoutRoot));
        categorySpinner = (Spinner) detailView.findViewById(
                R.id.CategorySpinner);
        privateCheckBox = (CheckBox) detailView.findViewById(
                R.id.DetailCheckBoxPrivate);
        categoryAdapter = new CategorySelectAdapter(this, repository);
        categorySpinner.setAdapter(categoryAdapter);
        builder.setView(detailView);
        if (isNewNote) {
            TableLayout tl = (TableLayout) detailView.findViewById(
                    R.id.TableLayoutTimestamps);
            tl.setVisibility(View.GONE);
        } else {
            DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
            TextView text = (TextView) detailView.findViewById(
                    R.id.DetailTextCreatedDate);
            text.setText(df.format(new Date(createTime)));
            text = (TextView) detailView.findViewById(
                    R.id.DetailTextModifiedDate);
            text.setText(df.format(new Date(modTime)));
        }
        Button button = (Button) detailView.findViewById(R.id.DetailButtonOK);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isPrivate = privateCheckBox.isChecked();
                categoryID = categorySpinner.getSelectedItemId();
                detailsDialog.dismiss();
            }
        });
        button = (Button) detailView.findViewById(R.id.DetailButtonCancel);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                detailsDialog.dismiss();
            }
        });
        button = (Button) detailView.findViewById(R.id.DetailButtonDelete);
        button.setOnClickListener(new DeleteButtonOnClickListener());
        detailsDialog = builder.create();
        return detailsDialog;
    }

    /** Called each time a dialog is opened */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        if (id != DETAIL_DIALOG_ID)
            return;
        privateCheckBox.setChecked(isPrivate);
        for (int position = 0; position < categorySpinner.getCount();
             position++) {
            if (categorySpinner.getItemIdAtPosition(position) == categoryID) {
                categorySpinner.setSelection(position);
                break;
            }
        }
    }

    /**
     * A runner for saving the note.  We do this in a separate thread
     * because database operations cannot be run on the main UI thread.
     */
    class SaveChangesRunner implements Runnable {

        /** The contents of the note to save */
        final String note;

        SaveChangesRunner(String noteContent) {
            note = noteContent;
        }

        @Override
        public void run() {
            try {
                if (note.length() == 0) {
                    /* Don't bother with confirmation; the user went
                     * through all the trouble of erasing the text. */
                    if (noteId != null)
                        repository.deleteNote(noteId);
                } else {
                    NoteItem newNote = new NoteItem();
                    newNote.setCategoryId(categoryID);
                    /* Figure out whether to encrypt this record. */
                    if (isPrivate && encryptor.hasKey()) {
                        try {
                            newNote.setEncryptedNote(encryptor.encrypt(note));
                            newNote.setPrivate(2);
                        } catch (GeneralSecurityException gsx) {
                            Toast.makeText(NoteEditorActivity.this,
                                    getString(R.string.ErrorEncryptionFailed,
                                            gsx.getMessage()),
                                    Toast.LENGTH_LONG).show();
                            newNote.setNote(note);
                            newNote.setPrivate(1);
                        }
                    } else {
                        newNote.setNote(note);
                        newNote.setPrivate(isPrivate ? 1 : 0);
                    }
                    newNote.setModTimeNow();
                    if (isNewNote) {
                        newNote.setCreateTime(newNote.getModTime());
                        repository.insertNote(newNote);
                    } else {
                        newNote.setId(noteId);
                        repository.updateNote(newNote);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        NoteEditorActivity.this.finish();
                    }
                });
            } catch (SQLException sx) {
                Log.e(TAG, "SaveChangesRunner", sx);
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

    class DoneButtonOnClickListener implements View.OnClickListener {
	@Override
	public void onClick(View v) {
	    Log.d(TAG, "NoteButtonOK.onClick");
	    String note = noteEditBox.getText().toString();
            executor.submit(new SaveChangesRunner(note));
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
                Log.e(TAG, "SaveChangesRunner", sx);
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
            AlertDialog.Builder builder =
                    new AlertDialog.Builder(NoteEditorActivity.this);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setMessage(R.string.ConfirmationTextDeleteNote);
            builder.setNegativeButton(R.string.ConfirmationButtonCancel,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            builder.setPositiveButton(R.string.ConfirmationButtonOK,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog2, int which) {
                            dialog2.dismiss();
                            executor.submit(new DeleteNoteRunner());
                        }
                    });
            builder.create().show();
        }
    }

}
