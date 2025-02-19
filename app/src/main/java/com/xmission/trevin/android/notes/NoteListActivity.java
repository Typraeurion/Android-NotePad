/*
 * $Id: NoteListActivity.java,v 1.3 2015/03/28 20:52:43 trevin Exp trevin $
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
 *
 * $Log: NoteListActivity.java,v $
 * Revision 1.3  2015/03/28 20:52:43  trevin
 * Added a sort order preference.
 * Added category loader callbacks and item loader callbacks
 *   for compatibility with API 11 (Honeycomb.)
 * Added debug messages for onStart, onResume, onPause, and onStop calls.
 *
 * Revision 1.2  2014/05/15 01:07:40  trevin
 * Added the copyright notice.
 * Added a password change dialog and progress bar dialog.
 * Added a second managed query to handle categories.
 * Replaced the Export menu with ExportActivity.
 * Added ImportActivity.
 *
 * Revision 1.1  2011/03/23 03:10:53  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.notes;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import com.xmission.trevin.android.notes.Note.*;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.widget.*;

/**
 * Displays a list of Notes.  Will display items from the {@link Uri}
 * provided in the intent if there is one, otherwise defaults to displaying the
 * contents of the {@link NoteProvider}.
 *
 * @author Trevin Beattie
 */
@SuppressLint("NewApi")
@SuppressWarnings("deprecation")
public class NoteListActivity extends ListActivity
implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "NoteListActivity";

    private static final int ABOUT_DIALOG_ID = 1;
    private static final int CATEGORY_DIALOG_ID = 2;
    private static final int PASSWORD_DIALOG_ID = 8;
    private static final int PROGRESS_DIALOG_ID = 9;

    /**
     * The columns we are interested in from the category table
     */
    static final String[] CATEGORY_PROJECTION = new String[] {
            NoteCategory._ID,
            NoteCategory.NAME,
    };

    /**
     * The columns we are interested in from the item table
     */
    static final String[] ITEM_PROJECTION = new String[] {
	    NoteItem._ID,
	    NoteItem.NOTE,
	    NoteItem.CATEGORY_NAME,
	    NoteItem.PRIVATE,
    };

    /** Shared preferences */
    private SharedPreferences prefs;

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

    /** Label for the currently selected category */
    public static final String NPREF_SELECTED_CATEGORY = "SelectedCategory";

    /** The URI by which we were started for the To-Do items */
    private Uri noteUri = NoteItem.CONTENT_URI;

    /** The corresponding URI for the categories */
    private Uri categoryUri = NoteCategory.CONTENT_URI;

    /** Category filter spinner */
    Spinner categoryList = null;

    // Used to map note categories from the database to a filter list
    CategoryFilterCursorAdapter categoryAdapter = null;

    // Used to map Note entries from the database to views
    NoteCursorAdapter itemAdapter = null;

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

    /**
     * Category Loader callbacks for API ≥ 11.
     * This <b>must</b> be stored in an Object reference
     * because we need to support API’s 8–10 as well.
     */
    private Object categoryLoaderCallbacks = null;

    /**
     * Item Loader callbacks for API ≥ 11.
     * This <b>must</b> be stored in an Object reference
     * because we need to support API’s 8–10 as well.
     */
    private Object itemLoaderCallbacks = null;

    /** Called when the activity is first created. */
    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, ".onCreate");

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        // If no data was given in the intent (because we were started
	// as a MAIN activity), then use our default content provider.
	Intent intent = getIntent();
	if (intent.getData() == null) {
	    intent.setData(NoteItem.CONTENT_URI);
	    noteUri = NoteItem.CONTENT_URI;
	    categoryUri = NoteCategory.CONTENT_URI;
	} else {
	    noteUri = intent.getData();
	    categoryUri = noteUri.buildUpon().encodedPath("/categories").build();
	}

	encryptor = StringEncryption.holdGlobalEncryption();
	prefs = getSharedPreferences(NOTE_PREFERENCES, MODE_PRIVATE);
	prefs.registerOnSharedPreferenceChangeListener(this);
	String whereClause = generateWhereClause();

        int selectedSortOrder = prefs.getInt(NPREF_SORT_ORDER, 0);
        if ((selectedSortOrder < 0) ||
        	(selectedSortOrder >= NoteItem.USER_SORT_ORDERS.length)) {
            prefs.edit().putInt(NPREF_SORT_ORDER, 0).apply();
	    selectedSortOrder = 0;
	}

        /*
         * Perform two managed queries. The Activity will handle closing and
         * requerying the cursor when needed ... on Android 2.x.
	 *
	 * On API level ≥ 11, you need to find a way to re-initialize
	 * the cursor when the activity is restarted!
	 */
        Cursor categoryCursor = null;
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
	    Log.d(TAG, ".onCreate: selecting categories");
	    categoryCursor = managedQuery(categoryUri,
		    CATEGORY_PROJECTION, null, null,
		    NoteCategory.DEFAULT_SORT_ORDER);
	    categoryAdapter = new CategoryFilterCursorAdapter(this, categoryCursor);
	} else {
	    categoryAdapter = new CategoryFilterCursorAdapter(this, 0);
	    Log.d(TAG, ".onCreate: initializing a category loader manager");
	    if (Log.isLoggable(TAG, Log.DEBUG))
		    LoaderManager.enableDebugLogging(true);
	    categoryLoaderCallbacks = new CategoryLoaderCallbacks(this,
			    prefs, categoryAdapter, categoryUri);
	    getLoaderManager().initLoader(NoteCategory.CONTENT_TYPE.hashCode(),
		    null, (LoaderManager.LoaderCallbacks<Cursor>) categoryLoaderCallbacks);
	}

        Cursor itemCursor = null;
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
	    Log.d(TAG, ".onCreate: selecting Notes where "
		    + whereClause + " ordered by "
		    + NoteItem.USER_SORT_ORDERS[selectedSortOrder]);
	    itemCursor = managedQuery(noteUri,
		    ITEM_PROJECTION, whereClause, null,
		    NoteItem.USER_SORT_ORDERS[selectedSortOrder]);
	    itemAdapter = new NoteCursorAdapter(
		    this, R.layout.list_item, itemCursor,
		    getContentResolver(), noteUri, this, encryptor);
	} else {
	    itemAdapter = new NoteCursorAdapter(
		    this, R.layout.list_item, null,
		    getContentResolver(), noteUri, this, encryptor);
	    Log.d(TAG, ".onCreate: initializing a To Do item loader manager");
	    itemLoaderCallbacks = new ItemLoaderCallbacks(this,
			    prefs, itemAdapter, noteUri);
	    getLoaderManager().initLoader(NoteItem.CONTENT_TYPE.hashCode(),
		    null, (LoaderManager.LoaderCallbacks<Cursor>) itemLoaderCallbacks);
	}

        // Inflate our view so we can find our lists
	setContentView(R.layout.list);

        categoryAdapter.setDropDownViewResource(
        	R.layout.simple_spinner_dropdown_item);
        categoryList = (Spinner) findViewById(R.id.ListSpinnerCategory);
        categoryList.setAdapter(categoryAdapter);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            setCategorySpinnerByID(prefs.getLong(NPREF_SELECTED_CATEGORY, -1));

	itemAdapter.setViewResource(R.layout.list_item);
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
	categoryAdapter.registerDataSetObserver(new DataSetObserver() {
		    @Override
		    public void onChanged() {
			Log.d(TAG, ".DataSetObserver.onChanged");
			long selectedCategory =
			    prefs.getLong(NPREF_SELECTED_CATEGORY, -1);
			if (categoryList.getSelectedItemId() != selectedCategory) {
			    Log.w(TAG, "The category ID at the selected position has changed!");
			    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
				NoteListActivity.this.setCategorySpinnerByID(selectedCategory);
			}
		    }
		    @Override
		    public void onInvalidated() {
			Log.d(TAG, ".DataSetObserver.onInvalidated");
			categoryList.setSelection(0);
		    }
	});

	Log.d(TAG, ".onCreate finished.");
    }

    /** Called when the activity is about to be started after having been stopped */
    @SuppressWarnings("unchecked")
    @Override
    public void onRestart() {
	Log.d(TAG, ".onRestart");
	if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) &&
		(categoryLoaderCallbacks instanceof LoaderManager.LoaderCallbacks)) {
	    getLoaderManager().restartLoader(NoteCategory.CONTENT_TYPE.hashCode(),
		    null, (LoaderManager.LoaderCallbacks<Cursor>) categoryLoaderCallbacks);
	    getLoaderManager().restartLoader(NoteItem.CONTENT_TYPE.hashCode(),
		    null, (LoaderManager.LoaderCallbacks<Cursor>) itemLoaderCallbacks);
	}
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
	super.onDestroy();
    }

    /**
     * Generate the WHERE clause for the list query.
     * This is used in both onCreate and onSharedPreferencesChanged.
     */
    String generateWhereClause() {
	StringBuilder whereClause = new StringBuilder();
	if (!prefs.getBoolean(NPREF_SHOW_PRIVATE, false)) {
	    if (whereClause.length() > 0)
		whereClause.append(" AND ");
	    whereClause.append(NoteItem.PRIVATE).append(" = 0");
	}
	long selectedCategory = prefs.getLong(NPREF_SELECTED_CATEGORY, -1);
	if (selectedCategory >= 0) {
	    if (whereClause.length() > 0)
		whereClause.append(" AND ");
	    whereClause.append(NoteItem.CATEGORY_ID).append(" = ")
		.append(selectedCategory);
        }
	return whereClause.toString();
    }

    /** Event listener for the New button */
    class NewButtonListener implements View.OnClickListener {
	@Override
	public void onClick(View v) {
	    Log.d(TAG, ".NewButtonListener.onClick");
	    ContentValues values = new ContentValues();
	    // This is the only time an empty note is allowed
	    values.put(NoteItem.NOTE, "");
	    long selectedCategory = prefs.getLong(
		    NPREF_SELECTED_CATEGORY, NoteCategory.UNFILED);
	    if (selectedCategory < 0)
		selectedCategory = NoteCategory.UNFILED;
	    values.put(NoteItem.CATEGORY_ID, selectedCategory);
	    Uri itemUri = getContentResolver().insert(noteUri, values);

	    // Immediately bring up the note edit dialog
	    Intent intent = new Intent(v.getContext(),
		    NoteEditorActivity.class);
	    intent.setData(itemUri);
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
		prefs.edit().putLong(NPREF_SELECTED_CATEGORY, -1).apply();
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
		prefs.edit().putLong(NPREF_SELECTED_CATEGORY, rowID).apply();
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
	    prefs.edit().putLong(TPREF_SELECTED_CATEGORY, -1).apply(); */
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

    /** Called when the settings dialog stores a new user setting */
    @Override
    public void onSharedPreferenceChanged(
	    SharedPreferences prefs, String key) {
        Log.d(TAG, ".onSharedPreferenceChanged(\"" + key + "\")");
	if (key.equals(NPREF_SHOW_PRIVATE) ||
		key.equals(NPREF_SELECTED_CATEGORY) ||
		key.equals(NPREF_SORT_ORDER)) {
	    String whereClause = generateWhereClause();

	    int selectedSortOrder = prefs.getInt(NPREF_SORT_ORDER, 0);
	    if ((selectedSortOrder < 0) ||
		(selectedSortOrder >= NoteItem.USER_SORT_ORDERS.length))
		selectedSortOrder = 0;

	    Log.d(TAG, ".onSharedPreferenceChanged: requerying the data where "
		    + whereClause + " ordered by "
		    + NoteItem.USER_SORT_ORDERS[selectedSortOrder]);
	    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
		Cursor itemCursor = managedQuery(noteUri,
			ITEM_PROJECTION, whereClause, null,
			NoteItem.USER_SORT_ORDERS[selectedSortOrder]);
		// Change the cursor used by this list
		itemAdapter.changeCursor(itemCursor);
	    } else {
		getLoaderManager().restartLoader(NoteItem.CONTENT_TYPE.hashCode(),
			null, (LoaderManager.LoaderCallbacks<Cursor>) itemLoaderCallbacks);
	    }
	}
	else if (key.equals(NPREF_SHOW_CATEGORY)) {
	    // To do: is there another way to do this?
	    // The data has not actually changed, just the widget visibility.
	    Log.d(TAG, ".onSharedPreferenceChanged: signaling a data change");
	    itemAdapter.notifyDataSetChanged();
	}
	// To do: etc...
    }

    /** Called when the user presses the Menu button. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        Log.d(TAG, "onCreateOptionsMenu");
	getMenuInflater().inflate(R.menu.main_menu, menu);
	menu.findItem(R.id.menuSettings).setIntent(
		new Intent(this, PreferencesActivity.class));
        return true;
    }

    /** Called when the user selects a menu item. */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
	switch (item.getItemId()) {
	default:
	    Log.w(TAG, "onOptionsItemSelected(" + item.getItemId()
		    + "): Not handled");
	    return false;

	case R.id.menuInfo:
	    showDialog(ABOUT_DIALOG_ID);
	    return true;

	case R.id.menuExport:
	    Intent intent = new Intent(this, ExportActivity.class);
	    startActivity(intent);
	    return true;

	case R.id.menuImport:
	    intent = new Intent(this, ImportActivity.class);
	    startActivity(intent);
	    return true;

	case R.id.menuPassword:
	    showDialog(PASSWORD_DIALOG_ID);
	    return true;
	}
    }

    /** Called when opening a dialog for the first time */
    @Override
    public Dialog onCreateDialog(int id) {
	switch (id) {
	case ABOUT_DIALOG_ID:
	    AlertDialog.Builder builder = new AlertDialog.Builder(this);
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
	    Cursor categoryCursor = getContentResolver().query(categoryUri,
		    CATEGORY_PROJECTION, null, null,
		    NoteCategory.DEFAULT_SORT_ORDER);
	    categoryAdapter =
		new CategoryFilterCursorAdapter(this, categoryCursor);
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

	case PASSWORD_DIALOG_ID:
	    builder = new AlertDialog.Builder(this);
	    builder.setIcon(R.drawable.ic_menu_login);
	    builder.setTitle(R.string.MenuPasswordSet);
	    View passwordLayout =
		((LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE))
		.inflate(R.layout.password,
			(ScrollView) findViewById(R.id.PasswordLayoutRoot));
	    builder.setView(passwordLayout);
	    DialogInterface.OnClickListener listener =
		new PasswordChangeOnClickListener();
	    builder.setPositiveButton(R.string.ConfirmationButtonOK, listener);
	    builder.setNegativeButton(R.string.ConfirmationButtonCancel, listener);
	    passwordChangeDialog = builder.create();
	    CheckBox showPasswordCheckBox =
		(CheckBox) passwordLayout.findViewById(R.id.CheckBoxShowPassword);
	    passwordChangeEditText[0] =
		(EditText) passwordLayout.findViewById(R.id.EditTextOldPassword);
	    passwordChangeEditText[1] =
		(EditText) passwordLayout.findViewById(R.id.EditTextNewPassword);
	    passwordChangeEditText[2] =
		(EditText) passwordLayout.findViewById(R.id.EditTextConfirmPassword);
	    showPasswordCheckBox.setOnCheckedChangeListener(
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
	    });
	    int inputType = InputType.TYPE_CLASS_TEXT
		+ (showPasswordCheckBox.isChecked()
			? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			: InputType.TYPE_TEXT_VARIATION_PASSWORD);
	    passwordChangeEditText[0].setInputType(inputType);
	    passwordChangeEditText[1].setInputType(inputType);
	    passwordChangeEditText[2].setInputType(inputType);
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
	case PASSWORD_DIALOG_ID:
	    TableRow tr = (TableRow) passwordChangeDialog.findViewById(
		    R.id.TableRowOldPassword);
	    tr.setVisibility(encryptor.hasPassword(getContentResolver())
		    ? View.VISIBLE : View.GONE);
	    CheckBox showPasswordCheckBox =
		(CheckBox) passwordChangeDialog.findViewById(
			R.id.CheckBoxShowPassword);
	    showPasswordCheckBox.setChecked(false);
	    passwordChangeEditText[0].setText("");
	    passwordChangeEditText[1].setText("");
	    passwordChangeEditText[2].setText("");
	    return;

	case PROGRESS_DIALOG_ID:
	    if (progressService != null) {
		Log.d(TAG, ".onPrepareDialog(PROGRESS_DIALOG_ID):"
			+ " Initializing the progress dialog at "
			+ progressService.getCurrentMode() + " "
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
				+ progressService.getCurrentMode() + " "
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

    class CategoryDialogSelectionListener
		implements DialogInterface.OnClickListener {
	@Override
	public void onClick(DialogInterface dialog, int which) {
	    Log.d(TAG, "CategoryDialogSelectionListener.onClick(" + which + ")");
	    if (which == 0) {
		// All
		prefs.edit().putLong(NPREF_SELECTED_CATEGORY, -1).apply();
		setCategorySpinnerByID(-1);
	    } else if (which == categoryAdapter.getCount() - 1) {
		// Edit categories
		Intent intent = new Intent(NoteListActivity.this,
			CategoryListActivity.class);
		startActivity(intent);
	    } else {
		long id = categoryAdapter.getItemId(which);
		prefs.edit().putLong(NPREF_SELECTED_CATEGORY, id).apply();
		setCategorySpinnerByID(id);
	    }
	}
    }

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

		if (encryptor.hasPassword(getContentResolver())) {
		    char[] oldPassword =
			new char[passwordChangeEditText[0].length()];
		    passwordChangeEditText[0].getText().getChars(
			    0, oldPassword.length, oldPassword, 0);
		    StringEncryption oldEncryptor = new StringEncryption();
		    oldEncryptor.setPassword(oldPassword);
		    try {
			if (!oldEncryptor.checkPassword(getContentResolver())) {
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
	    try {
		Log.d(TAG, ".onServiceConnected(" + name.getShortClassName()
			+ "," + service.getInterfaceDescriptor() + ")");
	    } catch (RemoteException rx) {}
	    PasswordChangeService.PasswordBinder pbinder =
		(PasswordChangeService.PasswordBinder) service;
	    progressService = pbinder.getService();
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
