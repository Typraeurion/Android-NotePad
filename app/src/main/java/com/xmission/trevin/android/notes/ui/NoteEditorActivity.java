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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.EncryptionException;
import com.xmission.trevin.android.notes.util.FileUtils;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.app.*;
import android.content.*;
import android.database.SQLException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

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

    /**
     * Arbitrary request code for selecting a text file from
     * Android&rsquo;s Open Document intent (Kit Kat or higher)
     * for importing the file into the current note.
     */
    private static final int SAF_PICK_TXT_FOR_READ = 18;

    /**
     * Arbitrary request code for selecting a text file from
     * Android&rsquo;s Open Document intent (Kit Kat or higher)
     * for exporting the current note.
     */
    private static final int SAF_PICK_TXT_FOR_WRITE = 23;

    private static final int DETAIL_DIALOG_ID = 4;

    /**
     * The maximum length of a note allowed for the purpose of importing
     * text from a file.
     */
    public static final int MAX_NOTE_LENGTH = 1 << 30;

    /** The ID of the note we are editing */
    Long noteId;

    /** The note */
    ObservableEditText noteEditBox = null;

    /** Vertical scroll bar */
    ScrollBar scrollBar = null;

    /**
     * Flag indicating we are programmatically scrolling the edit box.
     * This is to prevent unnecessary callback loops since we also
     * listen for scroll events on the edit view.
     */
    boolean isScrolling = false;

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
     * Current threshold ratio between the view size and note content size
     * for showing the scroll bar, taken from preferences on starting.
     * 0 = always hidden, {@link Double#POSITIVE_INFINITY} = always shown.
     */
    double scrollBarThreshold = 0;
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
        scrollBar = findViewById(R.id.NoteScrollBar);
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

        NotePreferences prefs = NotePreferences.getInstance(this);
        scrollBarThreshold = prefs.getScrollBarThreshold();
        if (scrollBarThreshold <= 0.0)
            scrollBar.setVisibility(View.GONE);
        else {
            if (scrollBarThreshold >= Double.POSITIVE_INFINITY)
                scrollBar.setVisibility(View.VISIBLE);
            // Otherwise leave it until we have loaded our content
            noteEditBox.addOnLayoutChangeListener(
                    new NoteEditorLayoutChangeListener());
            noteEditBox.addTextChangedListener(
                    new NoteEditorTextChangeListener());
            noteEditBox.setOnScrollChangedListener(
                    new NoteEditorScrollListener());
            scrollBar.registerOnScrollChangeListener(
                    new ScrollBarChangeListener());
        }

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
     * Check whether we need to change the visibility of the scroll bar.
     */
    private void checkScrollBarVisibility() {
        if (scrollBarThreshold == Double.POSITIVE_INFINITY)
            // Always visible
            return;

        double viewRatio = (scrollBar.getContentSize() == 0.0)
                ? Double.POSITIVE_INFINITY
                : (scrollBar.getViewSize() / scrollBar.getContentSize());
        if (scrollBar.getVisibility() == View.VISIBLE) {
            if (viewRatio > scrollBarThreshold)
                scrollBar.setVisibility(View.GONE);
        } else {
            if (viewRatio < scrollBarThreshold)
                scrollBar.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Called when the layout of the note edit box changes.
     */
    private class NoteEditorLayoutChangeListener
            implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View box, int left, int top,
                                   int right, int bottom,
                                   int oldLeft, int oldTop,
                                   int oldRight, int oldBottom) {
            int oldHeight = oldBottom - oldTop;
            int newHeight = bottom - top;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            if ((newHeight != oldHeight) || (newWidth != oldWidth)) {
                Log.d(TAG, String.format(Locale.US,
                        "NoteEditorLayoutChangeListener.onLayoutChange(): "
                        + "dimensions changed from %d \u00d7 %d to %d \u00d7 %d",
                        oldHeight, oldWidth, newHeight, newWidth));
                scrollBar.setViewSize(newHeight);
                Layout textLayout = noteEditBox.getLayout();
                if (textLayout != null)
                    scrollBar.setContentSize(textLayout.getHeight());
                checkScrollBarVisibility();
            }
        }
    }

    /**
     * Called when the user scrolls the note apart from using the scroll bar.
     * Also called when we explicitly call {@code noteEditBox.scrollTo(0,y)},
     * so we guard against unnecessary {@code scrollBar} updates using the
     * {@code isScrolling} flag.
     */
    private class NoteEditorScrollListener
            implements ViewTreeObserver.OnScrollChangedListener {
        @Override
        public void onScrollChanged() {
            if (isScrolling) {
                return;
            }
            int scrollY = noteEditBox.getScrollY();
            // DO NOT enable these log messages unless necessary for debugging;
            // it can slow down frequent event processing.
//            Log.d(TAG, String.format(Locale.US,
//                    "NoteEditorScrollListener.onScrollChanged(): top=%d",
//                    scrollY));
            scrollBar.setPosition(scrollY);
        }
    }

    /**
     * Called when the user updates the next in the note edit box.
     */
    private class NoteEditorTextChangeListener implements TextWatcher {
        // Unused, but required
        @Override
        public void beforeTextChanged(
                CharSequence s, int start, int count, int after)
        {}

        @Override
        public void onTextChanged(CharSequence s,
                                  int start, int before, int count) {
            // The layout might not be updated immediately,
            // so defer this to the next available UI slot.
            noteEditBox.post(TEXT_CHANGED_RUNNER);
        }

        // Unused, but required
        @Override
        public void afterTextChanged(Editable s)
        {}
    }

    /**
     * Called by the {@link NoteEditorTextChangeListener}
     * when the text changes.
     */
    private final Runnable TEXT_CHANGED_RUNNER = new Runnable() {
        @Override
        public void run() {
            Layout textLayout = noteEditBox.getLayout();
            if (textLayout == null)
                return;
            scrollBar.setContentSize(textLayout.getHeight());
            checkScrollBarVisibility();

        }
    };

    /**
     * Called when the user moves the scroll bar.
     */
    private class ScrollBarChangeListener
            implements ScrollBar.OnScrollBarChangeListener {

        long lastSyncTime = 0;
        int lastScrollY = -1;
        final float DISPLAY_PIXEL_SIZE = 1.0f /
                getResources().getDisplayMetrics().density;

        @Override
        public void onScrollBarChange(
                ScrollBar scrollBar, float position, boolean isInFlux) {

            // Ignore rapid successive calls; 24 fps should be sufficient.
            long nowTime = System.nanoTime();
            if (nowTime - lastSyncTime < 41666667)
                return;
            lastSyncTime = nowTime;

            if (Math.abs(position - lastScrollY) <= DISPLAY_PIXEL_SIZE)
                // Not enough movement; ignore it.
                return;
            lastScrollY = (int) position;

            // DO NOT enable these log messages unless necessary for debugging;
            // it can slow down frequent event processing.
//            Log.d(TAG, String.format(Locale.US,
//                    "ScrollBarChangeListener.onScrollBarChange(%f,%s)",
//                    position, isInFlux));
            isScrolling = true;
            noteEditBox.scrollTo(0, lastScrollY);

            // The edit box may stall if the cursor would go out of view.
            // Try to keep it within the visible area.
            Layout textLayout = noteEditBox.getLayout();
            if (textLayout == null)
                return;
            int line = textLayout.getLineForOffset(
                    noteEditBox.getSelectionStart());
            int topY = textLayout.getLineTop(line);
            int bottomY = textLayout.getLineBottom(line);
            int viewHeight = noteEditBox.getHeight()
                    - noteEditBox.getCompoundPaddingTop()
                    - noteEditBox.getCompoundPaddingBottom();

            int newLine = line;
            final int JITTER_BUFFER = 10;
            if (bottomY < lastScrollY) {
                // Cursor is above the visible area; find the top visible line.
                newLine = textLayout.getLineForVertical(
                        lastScrollY + JITTER_BUFFER);
            } else if (topY > lastScrollY + viewHeight) {
                newLine = textLayout.getLineForVertical(
                        lastScrollY + viewHeight - JITTER_BUFFER);
            }
            if (newLine != line)
                noteEditBox.setSelection(textLayout.getLineStart(newLine));
            if (!isInFlux)
                isScrolling = false;
        }
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            View portRow = detailView.findViewById(R.id.DetailButtonRowPort);
            portRow.setVisibility(View.GONE);
        } else {
            button = detailView.findViewById(R.id.DetailButtonImportNote);
            button.setOnClickListener(new ImportButtonOnClickListener());
            button = detailView.findViewById(R.id.DetailButtonExportNote);
            button.setOnClickListener(new ExportButtonOnClickListener());
        }

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
        Button exportButton = dialog.findViewById(R.id.DetailButtonExportNote);
        exportButton.setEnabled(noteEditBox.length() > 0);
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

    /**
     * Called when the user clicks &ldquo;Details&hellip;&rdquo; on the
     * note editor screen.
     */
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

    /**
     * Called when the user clicks &ldquo;Delete&rdquo;
     * on the Details dialog.
     */
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

    /**
     * Output stream wrapper which keeps track of the
     * number of bytes written through the stream
     */
    public static class CountingOutputStream extends FilterOutputStream {
        private long bytesWritten;
        public CountingOutputStream(OutputStream out) {
            super(out);
            bytesWritten = 0;
        }
        /** @return the number of bytes written to the output stream */
        public long getBytesWritten() {
            return bytesWritten;
        }
        @Override
        public void write(int b) throws IOException {
            out.write(b);
            bytesWritten++;
        }
        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
            bytesWritten += b.length;
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            bytesWritten += len;
        }
    }

    /**
     * Called when the user clicks &ldquo;Import Note&hellip;&rdquo;
     * on the Details dialog.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    class ImportButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Log.d(TAG, "ImportButtonOnClickListener.onClick()");
            detailsDialog.dismiss();
            Intent openFileActivity = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            openFileActivity.addCategory(Intent.CATEGORY_OPENABLE);
            openFileActivity.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            openFileActivity.setType("text/plain");
            openFileActivity.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[] { "text/plain" });
            startActivityForResult(Intent.createChooser(
                    openFileActivity,
                    getString(R.string.ImportSingleNoteDialogTitle)),
                    SAF_PICK_TXT_FOR_READ);
        }
    }

    /**
     * Called when the user clicks &ldquo;Export Note&hellip;&rdquo;
     * on the Details dialog.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    class ExportButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            detailsDialog.dismiss();
            Intent createFileActivity = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                createFileActivity.addCategory(Intent.CATEGORY_OPENABLE);
                createFileActivity.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                createFileActivity.setType("text/plain");
            startActivityForResult(Intent.createChooser(
                            createFileActivity,
                            getString(R.string.ExportSingleNoteDialogTitle)),
                    SAF_PICK_TXT_FOR_WRITE);
        }
    }

    /**
     * Called when the user selects an import file through
     * the Storage Access Framework (KitKat and above)
     *
     * @param requestCode The value that we passed to
     * {@link #startActivityForResult) when we opened the file picker.
     * @param resultCode Whether the user selected a file
     * or canceled the operation.
     * @praam resultData Contains the URI of the file we can read/write,
     * if the user selected a file.  Ignore if the user canceled.
     */
    @Override
    @RequiresApi(Build.VERSION_CODES.N)
    public void onActivityResult(
            int requestCode, int resultCode, Intent resultData) {
        Log.d(TAG, String.format(Locale.US, ".onActivityResult(%d,%d,%s)",
                requestCode, resultCode, (resultData == null) ?
                        null : resultData.getData()));
        if ((requestCode != SAF_PICK_TXT_FOR_READ)
                && (requestCode != SAF_PICK_TXT_FOR_WRITE)) {
            // Request code not recognized; ignore it
            Log.w(TAG, String.format(Locale.US,
                    "Ignoring unexpected request code %d", requestCode));
            return;
        }
        if (resultCode == Activity.RESULT_CANCELED)
            return;
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, String.format(Locale.US,
                    "Ignoring unexpected result code %d", resultCode));
            return;
        }
        if ((resultData == null) || (resultData.getData() == null)) {
            Log.w(TAG, "No data returned from result!");
            return;
        }
        if (requestCode == SAF_PICK_TXT_FOR_READ) {
            // Disable all controls on the note editor
            // during the import operation.
            Log.d(TAG, "Disabling the note form during text import");
            okButton.setEnabled(false);
            detailsButton.setEnabled(false);
            noteEditBox.setEnabled(false);
            int maxLength = MAX_NOTE_LENGTH - noteEditBox.length();
            executor.submit(new ImportNoteRunner(
                    resultData.getData(), maxLength));
        } else {
            if (isPrivate && encryptor.hasKey()) {
                Log.d(TAG, "Note is encrypted; asking for confirmation");
                new AlertDialog.Builder(this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.ConfirmationTextExportPrivateNote)
                        .setTitle(R.string.AlertPrivateNote)
                        .setNegativeButton(R.string.ConfirmationButtonCancel,
                                DISMISS_LISTENER)
                        .setPositiveButton(R.string.ConfirmationButtonOK,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog,
                                                        int which) {
                                        dialog.dismiss();
                                        executor.submit(new ExportNoteRunner(
                                                resultData.getData(),
                                                noteEditBox.getText().toString()));
                                    }
                                })
                        .create().show();
            } else {
                executor.submit(new ExportNoteRunner(
                        resultData.getData(), noteEditBox.getText().toString()));
            }
        }
    }

    /**
     * Runner to import a given file into the current note on a non-UI thread.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private class ImportNoteRunner implements Runnable {
        private final Uri uri;
        private final int maxLength;
        ImportNoteRunner(Uri uri, int maxLength) {
            this.uri = uri;
            this.maxLength = maxLength;
        }
        @Override
        public void run() {
            Log.d(TAG, String.format(Locale.US,
                    "Importing text from %s...", uri));
            StringBuilder sb = new StringBuilder();
            boolean lengthExceeded = false;
            try (InputStream iStream = NoteEditorActivity.this
                    .getContentResolver().openInputStream(uri);
                 InputStreamReader iRead = new InputStreamReader(iStream);
                 BufferedReader reader = new BufferedReader(iRead)) {
                String line = reader.readLine();
                while (line != null) {
                    if (sb.length() + line.length() > maxLength) {
                        Log.w(TAG, String.format(Locale.US,
                                "Next line of %d characters would exceed"
                                        + " maximum allowed length of %d",
                                line.length(), maxLength));
                        lengthExceeded = true;
                        break;
                    }
                    sb.append(line).append(System.lineSeparator());
                    line = reader.readLine();
                }
                Log.d(TAG, String.format(Locale.US,
                        "Imported %d characters%s", sb.length(),
                        lengthExceeded ? "; max length exceeded!" : ""));
                runOnUiThread(new ImportNoteAppender(sb.toString()));
                if (lengthExceeded)
                    runOnUiThread(new ErrorDialogRunner(
                            R.string.ErrorImportFailed,
                            getString(R.string.ErrorImportTooLong)));
            } catch (FileNotFoundException fnf) {
                Log.w(TAG, "Note import failed; file not found", fnf);
                runOnUiThread(new ErrorDialogRunner(
                        R.string.ErrorImportFailed,
                        getString(R.string.ErrorImportNotFound,
                                FileUtils.getFileNameFromUri(
                                        NoteEditorActivity.this, uri))));
            } catch (IOException iox) {
                Log.w(TAG, "Note import failed; I/O error", iox);
                String message = String.format(Locale.US,
                        "%s: %s: %s",
                        getString(R.string.ErrorImportCantRead,
                                FileUtils.getFileNameFromUri(
                                        NoteEditorActivity.this, uri)),
                        iox.getClass().getSimpleName(),
                        iox.getLocalizedMessage());
                runOnUiThread(new ErrorDialogRunner(
                        R.string.ErrorImportFailed, message));
            } finally {
                runOnUiThread(new EnableFormRunner());
            }
        }
    }

    /**
     * Runner to take the text we got from an import file and append it
     * to the note on the UI thread.
     */
    private class ImportNoteAppender implements Runnable {
        private final String newText;
        ImportNoteAppender(String text) {
            newText = text;
        }
        @Override
        public void run() {
            Log.d(TAG, String.format(Locale.US,
                    "Appending %d characters to the %d-character note",
                    newText.length(), noteEditBox.length()));
            noteEditBox.getText().insert(noteEditBox
                    .getSelectionStart(), newText);
        }
    }

    /**
     * Runner to re-enable the form elements after an import is complete
     */
    private class EnableFormRunner implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Re-enabling the note editor form");
            okButton.setEnabled(true);
            detailsButton.setEnabled(true);
            noteEditBox.setEnabled(true);
        }
    }

    /**
     * Runner to export the current note to the given file on a non-UI thread.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private class ExportNoteRunner implements Runnable {
        private final Uri uri;
        private final String noteContent;
        ExportNoteRunner(Uri uri, String content) {
            this.uri = uri;
            noteContent = content;
        }
        @Override
        public void run() {
            Log.d(TAG, String.format(Locale.US,
                    "Exporting %d characters to %s...",
                    noteContent.length(), uri));
            try (OutputStream oStream = NoteEditorActivity.this
                    .getContentResolver().openOutputStream(uri);
                 CountingOutputStream oCount = new CountingOutputStream(oStream);
                 OutputStreamWriter oWrite = new OutputStreamWriter(oCount);
                 BufferedWriter writer = new BufferedWriter(oWrite)) {
                writer.write(noteContent);
                writer.flush();
                runOnUiThread(new ExportNoteFinishedRunner(uri,
                        oCount.getBytesWritten()));
            } catch (FileNotFoundException fnf) {
                Log.w(TAG, "Note export failed; file not found", fnf);
                runOnUiThread(new ErrorDialogRunner(
                        R.string.ErrorExportFailed,
                        getString(R.string.ErrorExportPermissionDenied,
                                FileUtils.getFileNameFromUri(
                                        NoteEditorActivity.this, uri))));
            } catch (IOException iox) {
                Log.w(TAG, "Note export failed; I/O error", iox);
                runOnUiThread(new ErrorDialogRunner(
                        R.string.ErrorExportFailed,
                        String.format(Locale.US, "%s: %S",
                                FileUtils.getFileNameFromUri(
                                        NoteEditorActivity.this, uri),
                                iox.getMessage())));
            }
        }
    }

    /**
     * A runner to show a toast message on the UI thread
     * after the {@link ExportNoteRunner} has finished saving the note.
     */
    private class ExportNoteFinishedRunner implements Runnable {
        private final Uri uri;
        private final long fileSize;
        ExportNoteFinishedRunner(Uri uri, long size) {
            this.uri = uri;
            fileSize = size;
        }
        @Override
        public void run() {
            Toast.makeText(NoteEditorActivity.this,
                    getString(R.string.ExportNoteFinishedToast,
                            FileUtils.getFileNameFromUri(
                                    NoteEditorActivity.this, uri),
                            fileSize),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * A runner to show an error dialog on the UI thread from
     * the background importer or exporter runner.
     */
    private class ErrorDialogRunner implements Runnable {
        private final int titleId;
        private final String message;
        /**
         * Create a runner that shows an error dialog.
         *
         * @param titleId ID of the string resource providing
         *                the title of the dialog
         * @param message the error message
         */
        ErrorDialogRunner(int titleId, String message) {
            this.titleId = titleId;
            this.message = message;
        }
        @Override
        public void run() {
            new AlertDialog.Builder(NoteEditorActivity.this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(titleId)
                    .setMessage(message)
                    .setNeutralButton(R.string.ConfirmationButtonOK,
                            DISMISS_LISTENER)
                    .create().show();
        }
    }

}
