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

import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static com.xmission.trevin.android.notes.ui.NoteEditorActivity.EXTRA_CATEGORY_ID;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.ItemLoaderCallbacks;
import com.xmission.trevin.android.notes.provider.NoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.service.PasswordChangeService;
import com.xmission.trevin.android.notes.service.ProgressReportingService;
import com.xmission.trevin.android.notes.util.StringEncryption;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;

/**
 * Displays a list of Notes.  Will display items from the {@link Uri}
 * provided in the intent if there is one, otherwise defaults to displaying the
 * contents of the {@link NoteRepository}.
 *
 * @author Trevin Beattie
 */
@SuppressLint("NewApi")
@SuppressWarnings("deprecation")
public class NoteListActivity extends ListActivity {

    private static final String TAG = "NoteListActivity";

    private static final int ABOUT_DIALOG_ID = 1;
    private static final int CATEGORY_DIALOG_ID = 2;
    private static final int PASSWORD_DIALOG_ID = 8;
    private static final int PROGRESS_DIALOG_ID = 9;
    private static final int UNLOCK_DIALOG_ID = 10;

    /** Shared preferences */
    private NotePreferences prefs;

    /** The URI by which we were started for the notes */
    private Uri noteUri = NoteItemColumns.CONTENT_URI;

    /** Category filter spinner */
    Spinner categoryList = null;

    /** The Note Pad database */
    NoteRepository repository = null;

    // Used to map note categories from the database to a filter list
    CategoryFilterAdapter categoryAdapter = null;

    // Used to map Note entries from the database to views
    NoteCursorAdapter itemAdapter = null;

    /**
     * Local copy of whether a password has been set.
     * This needs to be updated whenever the password hash
     * metadata is added or removed.
     */
    boolean hasPassword = false;

    /** Our main menu */
    Menu menu = null;

    /** &ldquo;Unlock Encrypted Notes&rdquo; dialog */
    Dialog unlockDialog = null;

    /** Text field in the unlock dialog */
    EditText unlockPasswordEditText = null;

    /** Password change dialog */
    Dialog passwordChangeDialog = null;

    /**
     * Text fields in the password change dialog.
     * Field [0] is the old password, [1] and [2] are the new.
     */
    EditText[] passwordChangeEditText = new EditText[3];

    /** Progress reporting service */
    ProgressReportingService progressService = null;

    /** Progress dialog */
    ProgressDialog progressDialog = null;

    /** Encryption for private records */
    StringEncryption encryptor;

    /** Index of the currently selected sort order */
    int selectedSortOrder;

    /**
     * Item Loader callbacks for API ≥ 11.
     */
    private ItemLoaderCallbacks itemLoaderCallbacks = null;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

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
    //@SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // If no data was given in the intent (because we were started
        // as a MAIN activity), then use our default content provider.
        Intent intent = getIntent();
        if (intent.getData() == null) {
            intent.setData(NoteItemColumns.CONTENT_URI);
            noteUri = NoteItemColumns.CONTENT_URI;
        } else {
            noteUri = intent.getData();
        }

        encryptor = StringEncryption.holdGlobalEncryption();
        prefs = NotePreferences.getInstance(this);
        prefs.registerOnNotePreferenceChangeListener(
                new NotePreferences.OnNotePreferenceChangeListener() {
                    @Override
                    public void onNotePreferenceChanged(NotePreferences prefs) {
                        updateListFilter();
                        if (menu != null) {
                            MenuItem unlockItem =
                                    menu.findItem(R.id.menuUnlock);
                            unlockItem.setVisible(
                                    hasPassword && prefs.showPrivate());
                            unlockItem.setTitle(encryptor.hasKey()
                                    ? R.string.MenuLock
                                    : R.string.MenuUnlock);
                        }
                    }
                },
                NPREF_SELECTED_CATEGORY, NPREF_SHOW_ENCRYPTED,
                NPREF_SHOW_PRIVATE, NPREF_SORT_ORDER);
        prefs.registerOnNotePreferenceChangeListener(
                new NotePreferences.OnNotePreferenceChangeListener() {
                    @Override
                    public void onNotePreferenceChanged(NotePreferences prefs) {
                        updateListView();
                    }
                },
                NPREF_SHOW_CATEGORY);

        selectedSortOrder = prefs.getSortOrder();
        if ((selectedSortOrder < 0) ||
                (selectedSortOrder >= NoteItemColumns.USER_SORT_ORDERS.length)) {
            prefs.setSortOrder(0);
        }

        if (repository == null)
            repository = NoteRepositoryImpl.getInstance();
        // Establish a connection to the database
        // (on a non-UI thread)
        Runnable openRepo = new OpenRepositoryRunner();
        updatePasswordVisibility.run();

        categoryAdapter = new CategoryFilterAdapter(this, repository);
        itemAdapter = new NoteCursorAdapter(this, null,
                noteUri, encryptor);
        executor.submit(openRepo);
        itemLoaderCallbacks = new ItemLoaderCallbacks(this,
                prefs, itemAdapter, repository);
        getLoaderManager().initLoader(NoteItemColumns.CONTENT_TYPE.hashCode(),
                null, itemLoaderCallbacks);

        repository.registerDataSetObserver(dataObserver);

        // Inflate our view so we can find our lists
        setContentView(R.layout.list);

        categoryList = (Spinner) findViewById(R.id.ListSpinnerCategory);
        categoryList.setAdapter(categoryAdapter);

        //itemAdapter.setViewResource(R.layout.list_item);
        ListView listView = getListView();
        listView.setAdapter(itemAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent,
                    View view, int position, long id) {
                Log.d(TAG, ".onItemClick(parent,view," + position + "," + id + ")");
            }
        });
        listView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                Log.d(TAG, ".onFocusChange(view," + hasFocus + ")");
            }
        });
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent,
                    View v, int position, long id) {
                Log.d(TAG, ".onItemSelected(parent,view," + position + "," + id + ")");
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.d(TAG, ".onNothingSelected(parent)");
            }
        });

        // Set a callback for the New button
        Button newButton = (Button) findViewById(R.id.ListButtonNew);
        newButton.setOnClickListener(new NewButtonListener());

        // Set a callback for the category filter
        categoryList.setOnItemSelectedListener(new CategorySpinnerListener());
        // categoryAdapter.registerDataSetObserver(dataObserver);

        Log.d(TAG, ".onCreate finished.");
    }

    /**
     * A runner to open the database on a non-UI thread
     * (if on Honeycomb or later) and then load the note into the UI.
     */
    private class OpenRepositoryRunner implements Runnable {
        @Override
        public void run() {
            repository.open(NoteListActivity.this);
            checkForPassword.run();
        }
    }

    /**
     * An observer which is notified when any data changes changes
     * in the repository.  This may be categories in the category list
     * (which may be deleted, affecting the category filter) or notes.
     */
    private final DataSetObserver dataObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            Log.d(TAG, ".DataSetObserver.onChanged");
            long selectedCategory = prefs.getSelectedCategory();
            if (categoryList.getSelectedItemId() != selectedCategory)
                Log.w(TAG, "The category ID at the selected position has changed!");
            checkForPassword.run();
        }

        @Override
        public void onInvalidated() {
            Log.d(TAG, ".DataSetObserver.onInvalidated");
            categoryList.setSelection(0);
        }
    };

    /** Called when the activity is about to be started after having been stopped */
    //@SuppressWarnings("unchecked")
    @Override
    public void onRestart() {
        Log.d(TAG, ".onRestart");
        super.onRestart();
    }

    /** Called when the activity is about to be started */
    @Override
    public void onStart() {
        Log.d(TAG, ".onStart");
        super.onStart();
    }

    /** Called when the activity is ready for user interaction */
    @Override
    public void onResume() {
        Log.d(TAG, ".onResume");
        super.onResume();
        /*
         * Ensure the list of notes is up to date.
         * We use the LoaderManager to reload the list.
         */
        if (itemLoaderCallbacks != null)
            getLoaderManager().restartLoader(
                    NoteItemColumns.CONTENT_TYPE.hashCode(),
                    null, itemLoaderCallbacks);
    }

    /** Called when the activity has lost focus. */
    @Override
    public void onPause() {
        Log.d(TAG, ".onPause");
        super.onPause();
    }

    /** Called when the activity is obscured by another activity. */
    @Override
    public void onStop() {
        Log.d(TAG, ".onStop");
        super.onStop();
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
        StringEncryption.releaseGlobalEncryption(this);
        repository.release(this);
        super.onDestroy();
    }

    /**
     * Generate the WHERE clause for the list query.
     * This is used in both onCreate and updateListFilter
     */
    String generateWhereClause() {
        StringBuilder whereClause = new StringBuilder();
        if (!prefs.showPrivate()) {
            if (whereClause.length() > 0)
                whereClause.append(" AND ");
            whereClause.append(NoteItemColumns.PRIVATE).append(" = 0");
        }
        long selectedCategory = prefs.getSelectedCategory();
        if (selectedCategory >= 0) {
            if (whereClause.length() > 0)
                whereClause.append(" AND ");
            whereClause.append(NoteItemColumns.CATEGORY_ID).append(" = ")
                .append(selectedCategory);
        }
        return whereClause.toString();
    }

    /** Event listener for the New button */
    class NewButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            Log.d(TAG, ".NewButtonListener.onClick");
            long selectedCategory = prefs.getSelectedCategory();
            if (selectedCategory < NoteCategoryColumns.UNFILED)
                selectedCategory = NoteCategoryColumns.UNFILED;

            // Immediately bring up the note edit dialog
            Intent intent = new Intent(v.getContext(),
                    NoteEditorActivity.class);
            intent.putExtra(EXTRA_CATEGORY_ID, selectedCategory);
            v.getContext().startActivity(intent);
        }
    }

    /** Event listener for the category filter */
    class CategorySpinnerListener
        implements AdapterView.OnItemSelectedListener {

        private int lastSelectedPosition = 0;

        /**
         * Called when a category filter is selected.
         *
         * @param parent the Spinner containing the selected item
         * @param v the drop-down item which was selected
         * @param position the position of the selected item
         * @param rowID the ID of the data shown in the selected item
         */
        @Override
        public void onItemSelected(AdapterView<?> parent, View v,
                int position, long rowID) {
            Log.d(TAG, ".CategorySpinnerListener.onItemSelected(p="
                    + position + ",id=" + rowID + ")");
            if (position == 0) {
                prefs.setSelectedCategory(NotePreferences.ALL_CATEGORIES);
            }
            else if (position == parent.getCount() - 1) {
                // This must be the "Edit categories..." button.
                // We don't keep this selection; instead, start
                // the EditCategoriesActivity and revert the selection.
                position = lastSelectedPosition;
                parent.setSelection(lastSelectedPosition);
                // To do: Dismiss the spinner
                Intent intent = new Intent(parent.getContext(),
                        CategoryListActivity.class);
                // To do: find out why this doesn't do anything.
                parent.getContext().startActivity(intent);
            }
            else {
                prefs.setSelectedCategory(rowID);
            }
            lastSelectedPosition = position;
        }

        /** Called when the current selection disappears */
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            Log.d(TAG, ".CategorySpinnerListener.onNothingSelected()");
            /* // Remove the filter
            lastSelectedPosition = 0;
            parent.setSelection(0);
            prefs.setSelectedCategory(NotePreferences.ALL_CATEGORIES);
             */
        }
    }

    /** Look up the spinner item corresponding to a category ID and select it. */
    void setCategorySpinnerByID(long id) {
        Log.w(TAG, "Changing category spinner to item " + id
                + " of " + categoryList.getCount());
        for (int position = 0; position < categoryList.getCount(); position++) {
            if (categoryList.getItemIdAtPosition(position) == id) {
                categoryList.setSelection(position);
                return;
            }
        }
        Log.w(TAG, "No spinner item found for category ID " + id);
        categoryList.setSelection(0);
    }

    /**
     * Called when the settings dialog changes a preference related to
     * filtering the note list
     */
    public void updateListFilter() {
        String whereClause = generateWhereClause();

        selectedSortOrder = prefs.getSortOrder();
        if ((selectedSortOrder < 0) ||
                (selectedSortOrder >= NoteItemColumns.USER_SORT_ORDERS.length))
            selectedSortOrder = 0;

        Log.d(TAG, ".updateListFilter: requerying the data where "
                + whereClause + " ordered by "
                + NoteItemColumns.USER_SORT_ORDERS[selectedSortOrder]);
        getLoaderManager().restartLoader(NoteItemColumns.CONTENT_TYPE.hashCode(),
                null, itemLoaderCallbacks);
        itemAdapter.notifyDataSetChanged();
    }

    /**
     * Called when the settings dialog changes whether categories
     * are displayed alongside the notes
     */
    public void updateListView() {
        // To do: is there another way to do this?
        // The data has not actually changed, just the widget visibility.
        Log.d(TAG, ".updateListView: signaling a data change");
        itemAdapter.notifyDataSetChanged();
    }

    /** Called when the user presses the Menu button. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        Log.d(TAG, "onCreateOptionsMenu");
        getMenuInflater().inflate(R.menu.main_menu, menu);
        MenuItem item = menu.findItem(R.id.menuUnlock);
        item.setVisible(hasPassword && prefs.showPrivate());
        item.setTitle(encryptor.hasKey()
                ? R.string.MenuLock : R.string.MenuUnlock);
        menu.findItem(R.id.menuPassword).setTitle(hasPassword
                ? R.string.MenuPasswordChange : R.string.MenuPasswordSet);
        menu.findItem(R.id.menuSettings).setIntent(
                new Intent(this, PreferencesActivity.class));
        this.menu = menu;
        return true;
    }

    /** Called when the user selects a menu item. */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menuInfo) {
            showDialog(ABOUT_DIALOG_ID);
            return true;
        }
        if (item.getItemId() == R.id.menuUnlock) {
            if (encryptor.hasKey()) {
                prefs.setShowEncrypted(false);
                encryptor.forgetPassword();
                if (unlockPasswordEditText != null)
                    unlockPasswordEditText.setText("");
                menu.findItem(R.id.menuUnlock).setTitle(R.string.MenuUnlock);
            } else {
                showDialog(UNLOCK_DIALOG_ID);
            }
            return true;
        }
        if (item.getItemId() == R.id.menuExport) {
            Intent intent = new Intent(this, ExportActivity.class);
            startActivity(intent);
            return true;
        }
        if (item.getItemId() == R.id.menuImport) {
            Intent intent = new Intent(this, ImportActivity.class);
            startActivity(intent);
            return true;
        }
        if (item.getItemId() == R.id.menuPassword) {
            showDialog(PASSWORD_DIALOG_ID);
            return true;
        }
        Log.w(TAG, "onOptionsItemSelected(" + item.getItemId()
                + "): Not handled");
        return false;
    }

    private final CompoundButton.OnCheckedChangeListener unlockShowPasswordListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button,
                        boolean state) {
                    int inputType = InputType.TYPE_CLASS_TEXT
                        + (state ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    unlockPasswordEditText.setInputType(inputType);
                }
    };

    private final CompoundButton.OnCheckedChangeListener changeShowPasswordListener =
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button,
                        boolean state) {
                    int inputType = InputType.TYPE_CLASS_TEXT
                        + (state ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                 : InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    passwordChangeEditText[0].setInputType(inputType);
                    passwordChangeEditText[1].setInputType(inputType);
                    passwordChangeEditText[2].setInputType(inputType);
                }
    };

    /** Called when opening a dialog for the first time */
    @Override
    public Dialog onCreateDialog(int id) {
        AlertDialog.Builder builder;
        switch (id) {
        case ABOUT_DIALOG_ID:
            builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.about);
            builder.setMessage(getText(R.string.InfoPopupText));
            builder.setCancelable(true);
            builder.setNeutralButton(R.string.InfoButtonOK,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int i) {
                    dialog.dismiss();
                }
            });
            return builder.create();

        case CATEGORY_DIALOG_ID:
            categoryAdapter =
                new CategoryFilterAdapter(this, repository);
            categoryAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    Log.d(TAG, ".DataSetObserver.onChanged");
                    // To do: change the current category filter
                    // if the category has been removed
                }
                @Override
                public void onInvalidated() {
                    Log.d(TAG, ".DataSetObserver.onInvalidated");
                    // To do: change the current category filter
                    // if the category has been removed
                }
            });
            builder = new AlertDialog.Builder(this);
            builder.setAdapter(categoryAdapter,
                    new CategoryDialogSelectionListener());
            return builder.create();

        case UNLOCK_DIALOG_ID:
            builder = new AlertDialog.Builder(this);
            builder.setIcon(R.drawable.ic_menu_login);
            builder.setTitle(R.string.MenuUnlock);
            View unlockLayout =
                ((LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE))
                .inflate(R.layout.unlock,
                        (ScrollView) findViewById(R.id.UnlockLayoutRoot));
            builder.setView(unlockLayout);
            final DialogInterface.OnClickListener listener1 =
                    new UnlockOnClickListener();
            builder.setPositiveButton(R.string.ConfirmationButtonOK, listener1);
            builder.setNegativeButton(R.string.ConfirmationButtonCancel, listener1);
            unlockDialog = builder.create();
            CheckBox showPasswordCheckBox1 =
                (CheckBox) unlockLayout.findViewById(R.id.CheckBoxShowPassword);
            unlockPasswordEditText =
                    (EditText) unlockLayout.findViewById(R.id.EditTextPassword);
            showPasswordCheckBox1.setOnCheckedChangeListener(unlockShowPasswordListener);
            return unlockDialog;

        case PASSWORD_DIALOG_ID:
            builder = new AlertDialog.Builder(this);
            builder.setIcon(R.drawable.ic_menu_login);
            builder.setTitle(hasPassword ? R.string.MenuPasswordChange
                    : R.string.MenuPasswordSet);
            View passwordLayout =
                ((LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE))
                .inflate(R.layout.password,
                        (ScrollView) findViewById(R.id.PasswordLayoutRoot));
            builder.setView(passwordLayout);
            DialogInterface.OnClickListener listener2 =
                new PasswordChangeOnClickListener();
            builder.setPositiveButton(R.string.ConfirmationButtonOK, listener2);
            builder.setNegativeButton(R.string.ConfirmationButtonCancel, listener2);
            passwordChangeDialog = builder.create();
            CheckBox showPasswordCheckBox2 =
                (CheckBox) passwordLayout.findViewById(R.id.CheckBoxShowPassword);
            passwordChangeEditText[0] =
                (EditText) passwordLayout.findViewById(R.id.EditTextOldPassword);
            passwordChangeEditText[1] =
                (EditText) passwordLayout.findViewById(R.id.EditTextNewPassword);
            passwordChangeEditText[2] =
                (EditText) passwordLayout.findViewById(R.id.EditTextConfirmPassword);
            showPasswordCheckBox2.setOnCheckedChangeListener(changeShowPasswordListener);
            return passwordChangeDialog;

        case PROGRESS_DIALOG_ID:
            progressDialog = new ProgressDialog(this);
            progressDialog.setCancelable(false);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMessage("...");
            progressDialog.setMax(100);
            progressDialog.setProgress(0);
            return progressDialog;

        default:
            Log.d(TAG, ".onCreateDialog(" + id + "): undefined dialog ID");
            return null;
        }
    }

    /** Called each time a dialog is shown */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
        case UNLOCK_DIALOG_ID:
            CheckBox showPasswordCheckBox1 =
                    (CheckBox) unlockDialog.findViewById(
                            R.id.CheckBoxShowPassword);
            showPasswordCheckBox1.setChecked(false);
            unlockShowPasswordListener.onCheckedChanged(
                    showPasswordCheckBox1, false);
            if (encryptor.hasKey())
                unlockPasswordEditText.setText(encryptor.getPassword(), 0,
                        encryptor.getPassword().length);
            else
                unlockPasswordEditText.setText("");
            return;

        case PASSWORD_DIALOG_ID:
            passwordChangeDialog.setTitle(hasPassword
                    ? R.string.MenuPasswordChange : R.string.MenuPasswordSet);
            TableRow tr = (TableRow) passwordChangeDialog.findViewById(
                    R.id.TableRowOldPassword);
            tr.setVisibility(hasPassword ? View.VISIBLE : View.GONE);
            CheckBox showPasswordCheckBox2 =
                (CheckBox) passwordChangeDialog.findViewById(
                        R.id.CheckBoxShowPassword);
            showPasswordCheckBox2.setChecked(false);
            changeShowPasswordListener.onCheckedChanged(showPasswordCheckBox2,
                    false);
            passwordChangeEditText[0].setText("");
            passwordChangeEditText[1].setText("");
            passwordChangeEditText[2].setText("");
            return;

        case PROGRESS_DIALOG_ID:
            if (progressService != null) {
                Log.d(TAG, ".onPrepareDialog(PROGRESS_DIALOG_ID):"
                        + " Initializing the progress dialog at "
                        + progressService.getChangedCount() + "/"
                        + progressService.getMaxCount());
                progressDialog.setMessage(progressService.getCurrentMode());
                progressDialog.setMax(progressService.getMaxCount());
                progressDialog.setProgress(progressService.getChangedCount());
            } else {
                Log.d(TAG, ".onPrepareDialog(PROGRESS_DIALOG_ID):"
                        + " Password service has disappeared;"
                        + " dismissing the progress dialog");
                progressDialog.dismiss();
            }
            // Set up a callback to update the dialog
            final Handler progressHandler = new Handler();
            progressHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (progressService != null) {
                        Log.d(TAG, ".onPrepareDialog(PROGRESS_DIALOG_ID).Runnable:"
                                + " Updating the progress dialog to "
                                + progressService.getChangedCount() + "/"
                                + progressService.getMaxCount());
                        progressDialog.setMessage(progressService.getCurrentMode());
                        progressDialog.setMax(progressService.getMaxCount());
                        progressDialog.setProgress(
                                progressService.getChangedCount());
                        progressHandler.postDelayed(this, 100);
                    }
                }
            }, 250);
            return;
        }
    }

    /**
     * A runner which updates the visibility of the old password row
     * on the Set/Change Password dialog and the Lock/Unlock menu entry.
     */
    private final Runnable updatePasswordVisibility = new Runnable() {
        @Override
        public void run() {
            if (menu != null) {
                menu.findItem(R.id.menuUnlock).setVisible(hasPassword);
                menu.findItem(R.id.menuPassword).setTitle(hasPassword
                        ? R.string.MenuPasswordChange
                        : R.string.MenuPasswordSet);
            }
            if (passwordChangeDialog != null) {
                TableRow tr = (TableRow) passwordChangeDialog.findViewById(
                        R.id.TableRowOldPassword);
                tr.setVisibility(hasPassword ? View.VISIBLE : View.GONE);
            }
        }
    };

    /**
     * A runner which checks the repository for a password hash,
     * updating the visibility of the &ldquo;Old Password&rdquo;
     * row on the Set/Change Password dialog and the Lock/Unlock
     * menu entry accordingly.  If the value has changed since
     * last checked, update the UI elements on the UI thread.
     */
    private final Runnable checkForPassword = new Runnable() {
        @Override
        public void run() {
            boolean oldHasPassword = hasPassword;
            hasPassword = encryptor.hasPassword(repository);
            if (hasPassword != oldHasPassword)
                runOnUiThread(updatePasswordVisibility);
        }
    };

    class CategoryDialogSelectionListener
                implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            Log.d(TAG, "CategoryDialogSelectionListener.onClick(" + which + ")");
            if (which == 0) {
                // All
                prefs.setSelectedCategory(NotePreferences.ALL_CATEGORIES);
                setCategorySpinnerByID(NotePreferences.ALL_CATEGORIES);
            } else if (which == categoryAdapter.getCount() - 1) {
                // Edit categories
                Intent intent = new Intent(NoteListActivity.this,
                        CategoryListActivity.class);
                startActivity(intent);
            } else {
                long id = categoryAdapter.getItemId(which);
                prefs.setSelectedCategory(id);
                setCategorySpinnerByID(id);
            }
        }
    }

    class UnlockOnClickListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            Log.d(TAG, "UnlockOnClickListener.onClick(" + which + ")");
            switch (which) {
                case DialogInterface.BUTTON_NEGATIVE:
                    dialog.dismiss();
                    return;

                case DialogInterface.BUTTON_POSITIVE:
                    if (unlockPasswordEditText.length() > 0) {
                        char[] password = new char[unlockPasswordEditText.length()];
                        unlockPasswordEditText.getText().getChars(
                                0, password.length, password, 0);
                        encryptor.setPassword(password);
                        executor.submit(checkPasswordForUnlock);
                    } else {
                        encryptor.forgetPassword();
                        prefs.setShowEncrypted(false);
                        menu.findItem(R.id.menuUnlock)
                                .setTitle(R.string.MenuUnlock);
                    }
                    return;
            }
        }
    }

    /**
     * Check the password that the user entered in
     * {@link UnlockOnClickListener} against the database.
     * This must be done on the non-UI thread.
     */
    private final Runnable checkPasswordForUnlock = new Runnable() {
        @Override
        public void run() {
            try {
                if (encryptor.checkPassword(repository)) {
                    prefs.setShowEncrypted(true);
                    menu.findItem(R.id.menuUnlock)
                            .setTitle(R.string.MenuLock);
                    unlockDialog.dismiss();
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(NoteListActivity.this,
                                    R.string.ToastBadPassword,
                                    Toast.LENGTH_LONG).show();
                            unlockDialog.show();
                        }
                    });
                }
            } catch (GeneralSecurityException gsx) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(NoteListActivity.this,
                                R.string.ToastBadPassword,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };

    class PasswordChangeOnClickListener
                implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            Log.d(TAG, "PasswordChangeOnClickListener.onClick(" + which + ")");
            switch (which) {
            case DialogInterface.BUTTON_NEGATIVE:
                dialog.dismiss();
                return;

            case DialogInterface.BUTTON_POSITIVE:
                Intent passwordChangeIntent = new Intent(NoteListActivity.this,
                        PasswordChangeService.class);
                char[] newPassword =
                    new char[passwordChangeEditText[1].length()];
                passwordChangeEditText[1].getText().getChars(
                        0, newPassword.length, newPassword, 0);
                char[] confirmedPassword =
                    new char[passwordChangeEditText[2].length()];
                passwordChangeEditText[2].getText().getChars(
                        0, confirmedPassword.length, confirmedPassword, 0);
                if (!Arrays.equals(newPassword, confirmedPassword)) {
                    Arrays.fill(confirmedPassword, (char) 0);
                    Arrays.fill(newPassword, (char) 0);
                    AlertDialog.Builder builder =
                        new AlertDialog.Builder(NoteListActivity.this);
                    builder.setIcon(android.R.drawable.ic_dialog_alert);
                    builder.setMessage(R.string.ErrorPasswordMismatch);
                    builder.setNeutralButton(R.string.ConfirmationButtonOK,
                            new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            passwordChangeDialog.show();
                        }
                    });
                    builder.show();
                    return;
                }
                Arrays.fill(confirmedPassword, (char) 0);
                if (newPassword.length > 0)
                    passwordChangeIntent.putExtra(
                            PasswordChangeService.EXTRA_NEW_PASSWORD,
                            newPassword);

                if (encryptor.hasPassword(repository)) {
                    char[] oldPassword =
                        new char[passwordChangeEditText[0].length()];
                    passwordChangeEditText[0].getText().getChars(
                            0, oldPassword.length, oldPassword, 0);
                    StringEncryption oldEncryptor = new StringEncryption();
                    oldEncryptor.setPassword(oldPassword);
                    try {
                        if (!oldEncryptor.checkPassword(repository)) {
                            Arrays.fill(newPassword, (char) 0);
                            Arrays.fill(oldPassword, (char) 0);
                            AlertDialog.Builder builder =
                                new AlertDialog.Builder(NoteListActivity.this);
                            builder.setIcon(android.R.drawable.ic_dialog_alert);
                            builder.setMessage(R.string.ToastBadPassword);
                            builder.setNeutralButton(R.string.ConfirmationButtonCancel,
                                    new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            });
                            builder.show();
                            return;
                        }
                    } catch (GeneralSecurityException gsx) {
                        Arrays.fill(newPassword, (char) 0);
                        Arrays.fill(oldPassword, (char) 0);
                        new AlertDialog.Builder(NoteListActivity.this)
                        .setMessage(gsx.getMessage())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setNeutralButton(R.string.ConfirmationButtonCancel,
                                new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        }).create().show();
                        return;
                    }
                    passwordChangeIntent.putExtra(
                            PasswordChangeService.EXTRA_OLD_PASSWORD, oldPassword);
                }

                passwordChangeIntent.setAction(
                        PasswordChangeService.ACTION_CHANGE_PASSWORD);
                dialog.dismiss();
                Log.d(TAG, "PasswordChangeOnClickListener.onClick:"
                        + " starting the password change service");
                startService(passwordChangeIntent);
                // Bind to the service
                Log.d(TAG, "PasswordChangeOnClickListener.onClick:"
                        + " binding to the password change service");
                bindService(passwordChangeIntent,
                        new PasswordChangeServiceConnection(), 0);
            }
        }
    }

    class PasswordChangeServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            String interfaceDescriptor;
            try {
                interfaceDescriptor = service.getInterfaceDescriptor();
            } catch (RemoteException rx) {
                interfaceDescriptor = rx.getMessage();
            }
            Log.d(TAG, String.format(".onServiceConnected(%s, %s)",
                    name.getShortClassName(), interfaceDescriptor));
            PasswordChangeService.PasswordBinder pBinder =
                (PasswordChangeService.PasswordBinder) service;
            progressService = pBinder.getService();
            showDialog(PROGRESS_DIALOG_ID);
        }

        /** Called when a connection to the service has been lost */
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, ".onServiceDisconnected(" + name.getShortClassName() + ")");
            if (progressDialog != null)
                progressDialog.dismiss();
            progressService = null;
            unbindService(this);
        }
    }
}
