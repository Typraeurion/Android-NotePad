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
package com.xmission.trevin.android.notes;

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;

import com.xmission.trevin.android.notes.Note.*;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDoneException;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;

/**
 * Displays the note of a Note item.  Will display the item from the
 * {@link Uri} provided in the intent, which is required.
 */
public class NoteEditorActivity extends Activity {

    private static final String TAG = "NoteEditorActivity";

    private static final int DETAIL_DIALOG_ID = 4;

    /**
     * The columns we are interested in from the item table
     */
    private static final String[] ITEM_PROJECTION = new String[] {
	NoteItem._ID,
	NoteItem.NOTE,
	NoteItem.CATEGORY_ID,
	NoteItem.PRIVATE,
	NoteItem.CREATE_TIME,
	NoteItem.MOD_TIME
    };

    /** Columns we need for the category adapter */
    private static final String[] CATEGORY_PROJECTION = new String[] {
	NoteCategory._ID,
	NoteCategory.NAME
    };

    /** The URI by which we were started for the To-Do item */
    private Uri noteUri = NoteItem.CONTENT_URI;

    /** The note */
    EditText toDoNote = null;

    /** The original contents of the note (or an empty string for a new note) */
    String oldNoteText = "";

    /** The details dialog */
    Dialog detailsDialog = null;
    /** Category button in the details dialog */
    Spinner categorySpinner = null;
    /** Private checkbox in the details dialog */
    CheckBox privateCheckBox = null;

    /** Category adapter */
    SimpleCursorAdapter categoryAdapter = null;

    /** Whether the note is new */
    boolean isNewNote = false;

    /** The note's original creation time */
    Long createTime;

    /** The note's last modification time */
    Long modTime;

    /** The note's current category */
    long categoryID;

    /** Whether the note should be private */
    boolean isPrivate = false;

    StringEncryption encryptor;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        Intent intent = getIntent();
        if (intent.getData() == null)
            throw new NullPointerException("No data provided with the intent");
        noteUri = intent.getData();

        // Perform a managed query. The Activity will handle closing and
        // requerying the cursor when needed.
        Cursor itemCursor = getContentResolver().query(noteUri,
        	ITEM_PROJECTION, null, null, null);
        if (!itemCursor.moveToFirst())
            throw new SQLiteDoneException();

        // Inflate our view so we can find our field
	setContentView(R.layout.note);

	int privacyFlag = itemCursor.getInt(
        	itemCursor.getColumnIndex(NoteItem.PRIVATE));
	isPrivate = privacyFlag > 0;

	encryptor = StringEncryption.holdGlobalEncryption();
        String note = getResources().getString(R.string.PasswordProtected);
	int i = itemCursor.getColumnIndex(NoteItem.NOTE);
        if (privacyFlag > 1) {
            if (encryptor.hasKey()) {
        	try {
        	    note = encryptor.decrypt(itemCursor.getBlob(i));
        	    oldNoteText = note;
        	} catch (GeneralSecurityException gsx) {
        	    Toast.makeText(this, gsx.getMessage(),
        		    Toast.LENGTH_LONG).show();
        	    finish();
        	}
            } else {
        	Toast.makeText(this, R.string.PasswordProtected,
        		Toast.LENGTH_LONG).show();
        	finish();
            }
        } else {
            note = itemCursor.getString(i);
            oldNoteText = note;
        }
        isNewNote = (note.length() == 0);

        String shortNote = note;
        if (shortNote.length() > 80)
            shortNote = shortNote.substring(0, 80);
        if (shortNote.indexOf('\n') > -1)
            shortNote = shortNote.substring(0, shortNote.indexOf('\n'));
        setTitle(getResources().getString(R.string.app_name)
		+ " \u2015 " + shortNote);

	toDoNote = (EditText) findViewById(R.id.NoteEditText);
	toDoNote.setText(note);

	categoryID = itemCursor.getInt(
		itemCursor.getColumnIndex(NoteItem.CATEGORY_ID));

	createTime = itemCursor.getLong(
		itemCursor.getColumnIndex(NoteItem.CREATE_TIME));
	modTime = itemCursor.getLong(
		itemCursor.getColumnIndex(NoteItem.MOD_TIME));

	// Set callbacks
        Button button = (Button) findViewById(R.id.NoteButtonOK);
        button.setOnClickListener(new DoneButtonOnClickListener());

        button = (Button) findViewById(R.id.NoteButtonDetails);
        // button.setOnClickListener(new DetailsButtonOnClickListener());
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
        	Log.d(TAG, "NoteButtonDetails.onClick");
        	showDialog(DETAIL_DIALOG_ID);
            }
        });

	itemCursor.close();
    }

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
        Log.d(TAG, "Back button pressed");
        // Did the user make any changes to the note?
        String note = toDoNote.getText().toString();
        if (!oldNoteText.equals(note)) {
            Log.d(TAG, "Note has been changed; asking for confirmation");
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setMessage(R.string.ConfirmUnsavedChanges);
            builder.setTitle(R.string.AlertUnsavedChangesTitle);
            builder.setNegativeButton(R.string.ConfirmationButtonCancel,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            builder.setPositiveButton(R.string.ConfirmationButtonDiscard,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            if (isNewNote) {
                                Log.d(TAG, "Deleting new note");
                                getContentResolver().delete(noteUri, null, null);
                            }
                            Log.d(TAG, "Calling superclass onBackPressed");
                            NoteEditorActivity.super.onBackPressed();
                        }
                    });
            builder.create().show();
            return;
        }
	if (isNewNote) {
            // We don't allow notes with no content
            Log.d(TAG, "Deleting empty note");
            getContentResolver().delete(noteUri, null, null);
        }
	super.onBackPressed();
    }

    @Override
    public void onDestroy() {
	StringEncryption.releaseGlobalEncryption(this);
	super.onDestroy();
    }

    /** Called when opening a dialog for the first time */
    @Override
    public Dialog onCreateDialog(int id) {
	switch (id) {
	case DETAIL_DIALOG_ID:
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
	    Cursor categoryCursor = managedQuery(NoteCategory.CONTENT_URI,
		    CATEGORY_PROJECTION, null, null,
		    NoteCategory.DEFAULT_SORT_ORDER);
	    categoryAdapter = new SimpleCursorAdapter(this,
		    android.R.layout.simple_spinner_item,
		    categoryCursor, new String[] { NoteCategory.NAME },
		    new int[] { android.R.id.text1 });
	    categoryAdapter.setDropDownViewResource(
		    android.R.layout.simple_spinner_dropdown_item);
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

	default:
	    Log.e(TAG, "onCreateDialog(" + id + "): unknown dialog ID");
	    return null;
	}
    }

    /** Called each time a dialog is opened */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
	switch (id) {
	case DETAIL_DIALOG_ID:
	    privateCheckBox.setChecked(isPrivate);
	    for (int position = 0; position < categorySpinner.getCount();
		position++) {
		if (categorySpinner.getItemIdAtPosition(position) == categoryID) {
		    categorySpinner.setSelection(position);
		    break;
		}
	    }
	    break;
	}
    }

    class DoneButtonOnClickListener implements View.OnClickListener {
	@Override
	public void onClick(View v) {
	    Log.d(TAG, "NoteButtonOK.onClick");
	    ContentValues values = new ContentValues();
	    String note = toDoNote.getText().toString();
	    try {
		if (note.length() == 0) {
		    /* Don't bother with confirmation; the user went
		     * through all the trouble of erasing the text. */
		    getContentResolver().delete(noteUri, null, null);
		} else {
		    /* Figure out whether to encrypt this record. */
		    values.put(NoteItem.NOTE, note);
		    values.put(NoteItem.CATEGORY_ID, categoryID);
		    if (isPrivate) {
			if (encryptor.hasKey()) {
			    try {
				values.put(NoteItem.NOTE, encryptor.encrypt(note));
				values.put(NoteItem.PRIVATE, 2);
			    } catch (GeneralSecurityException gsx) {
				values.put(NoteItem.PRIVATE, 1);
			    }
			} else {
			    values.put(NoteItem.PRIVATE, 1);
			}
		    }
		    values.put(NoteItem.MOD_TIME, System.currentTimeMillis());
		    getContentResolver().update(noteUri, values, null, null);
		}
		NoteEditorActivity.this.finish();
	    } catch (SQLException sx) {
		Toast.makeText(NoteEditorActivity.this,
			sx.getMessage(), Toast.LENGTH_LONG).show();
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
		    try {
			NoteEditorActivity.this.getContentResolver().delete(
				NoteEditorActivity.this.noteUri, null, null);
			detailsDialog.dismiss();
			NoteEditorActivity.this.finish();
		    } catch (SQLException sx) {
			Toast.makeText(NoteEditorActivity.this,
				sx.getMessage(), Toast.LENGTH_LONG).show();
		    }
		}
	    });
	    builder.create().show();
	}
    }
}
