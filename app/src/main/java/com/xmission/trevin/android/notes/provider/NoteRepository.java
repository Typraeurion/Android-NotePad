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
package com.xmission.trevin.android.notes.provider;

import android.content.Context;
import android.database.DataSetObserver;
import android.database.SQLException;

import android.support.annotation.NonNull;

import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NoteMetadata;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.service.PasswordChangeService;

import java.util.List;

/**
 * Higher-level interface to the SQLite database for NotePad.
 * All methods in this class should <i>not</i> be called from the
 * UI thread of the application.
 *
 * @author Trevin Beattie
 */
public interface NoteRepository {

    /**
     * Open the database for a given context.  The database will be held open
     * until all contexts using it call the {@link #release{}} method.
     *
     * @param context the context in which to connect to the database
     *
     * @throws SQLException if we fail to connect to the database
     */
    void open(@NonNull Context context) throws SQLException;

    /**
     * Release the database for a given context.  If this is the last
     * context holding the database, the database connection is closed.
     *
     * @param context the context which no longer needs this repository
     */
    void release(@NonNull Context context);

    /**
     * Count the number of categories in the category table.
     * This will include the pre-populated &ldquo;Unfiled&rdquo;
     * category, but not the &ldquo;All&rdquo; pseudo-category.
     *
     * @return the number of categories in the database
     */
    int countCategories();

    /**
     * Get all of the categories available from the database.
     * This will include the pre-populated &ldquo;Unfiled&rdquo;
     * category.  The categories will be sorted alphabetically.
     *
     * @return a List of categories
     */
    List<NoteCategory> getCategories();

    /**
     * Get a single category by its ID.
     *
     * @param categoryId the ID of the category to return
     *
     * @return the category if found, or {@code null}
     * if there is no category with that ID.
     */
    NoteCategory getCategoryById(long categoryId);

    /**
     * Add a new category.
     *
     * @param categoryName the name of the category to add
     *
     * @return the newly added category
     *
     * @throws IllegalArgumentException if {@code categoryName} is empty
     * @throws SQLException if we failed to insert the category
     */
    NoteCategory insertCategory(@NonNull String categoryName)
            throws IllegalArgumentException, SQLException;

    /**
     * Add a new category, including its pre-determined ID.
     * This is meant for use by the importer service where
     * category ID's should be preserved.
     *
     * @param category the category to add
     *
     * @return the newly added category
     *
     * @throws IllegalArgumentException if {@code categoryName} is empty
     * @throws SQLException if we failed to insert the category
     */
    NoteCategory insertCategory(@NonNull NoteCategory category)
            throws IllegalArgumentException, SQLException;

    /**
     * Rename a category.  If the &ldquo;Unfiled&rdquo; category ID
     * ({@value NoteCategory#UNFILED}) is given, the {@code newName}
     * parameter is ignored; the name will be taken from the string resources.
     *
     * @param categoryId the ID of the category to change
     * @param newName the new name of the category
     *
     * @return the modified category
     *
     * @throws IllegalArgumentException if {@code newName} is empty
     * @throws SQLException if we failed to update the category
     */
    NoteCategory updateCategory(long categoryId, @NonNull String newName)
        throws IllegalArgumentException, SQLException;

    /**
     * Delete a category.  This will refuse to delete the
     * &ldquo;Unfiled&rdquo; category ({@value NoteCategory#UNFILED}).
     * Any notes which are using the given category will be reassigned
     * to &ldquo;Unfiled&rdquo;.
     *
     * @param categoryId the ID of the category to delete
     *
     * @return {@code true} if the category was deleted,
     * {@code false} if the category did not exist.
     *
     * @throws IllegalArgumentException if {@code categoryId} is
     * {@value NoteCategory#UNFILED}
     * @throws SQLException if we failed to delete the category
     * or update any of its notes
     */
    boolean deleteCategory(long categoryId)
            throws IllegalArgumentException, SQLException;

    /**
     * Delete <i>all</i> categories except &ldquo;Unfiled&rdquo;.
     * All notes will be reassigned to the &ldquo;Unfiled&rdquo; category.
     *
     * @return {@code true} if any categories were deleted, {@code false}
     * if there were no categories other than &ldquo;Unfiled&rdquo;.
     *
     * @throws SQLException if we failed to delete the categories
     * or update the notes
     */
    boolean deleteAllCategories() throws SQLException;

    /**
     * Count the number of metadata in the metadata table.
     *
     * @return the number of metadata in the database
     */
    int countMetadata();

    /**
     * Get all of the metadata from the database.
     * The data will be ordered by name.
     *
     * @return the Note Pad metadata
     */
    List<NoteMetadata> getMetadata();

    /**
     * Get a single metadatum by name.
     *
     * @param key the name of the metadata to return
     *
     * @return the metadata if found, or {@code null} if there is no
     * metadata with the given {@code key}.
     */
    NoteMetadata getMetadataByName(@NonNull String key);

    /**
     * Get a single metadatum by ID.  There shouldn&rsquo;t be a use case
     * for this, but it is provided for completeness.
     *
     * @param id the ID of the metadata to return
     *
     * @return the metadata if found, or {@code null} if there is no
     * metadata with the given {@code id}.
     */
    NoteMetadata getMetadataById(long id);

    /**
     * Add or modify metadata.
     *
     * @param name the name of the metadata to set
     * @param value the value to set for the metadata
     *
     * @return the metadata that was added or changed
     *
     * @throws IllegalArgumentException if {@code name} is empty
     * @throws SQLException if we failed to insert or update the metadata
     */
    NoteMetadata upsertMetadata(@NonNull String name, @NonNull byte[] value)
            throws IllegalArgumentException, SQLException;

    /**
     * Delete metadata.
     *
     * @param name the name of the metadata to remove
     *
     * @return {@code true} if the metadata was deleted,
     * {@code false} if the metedata did not exist.
     *
     * @throws IllegalArgumentException if {@code name} is empty
     * @throws SQLException if we failed to delete the metadata
     */
    boolean deleteMetadata(@NonNull String name)
        throws IllegalArgumentException, SQLException;

    /**
     * Delete metadata by its ID.  There is no use case for this,
     * but it is provided for completeness.
     *
     * @param id the database ID of the metadata
     *
     * @return {@code true} if the metadata was deleted,
     * {@code false} if the metedata did not exist.
     *
     * @throws IllegalArgumentException if {@code name} is empty
     * @throws SQLException if we failed to delete the metadata
     */
    boolean deleteMetadataById(long id)
            throws IllegalArgumentException, SQLException;

    /**
     * Delete <i>all</i> metadata.
     *
     * @return {@code true} if any metadata was deleted, {@code false}
     * if there was no metadata.
     *
     * @throws SQLException if we failed to delete the metadata
     */
    boolean deleteAllMetadata() throws SQLException;

    /**
     * Count the number of notes in the database.
     *
     * @return the number of notes in the database
     */
    int countNotes();

    /**
     * Count the number of notes in the database for a given category.
     *
     * @param categoryId the ID of the category whose notes to count,
     * or {@link NotePreferences#ALL_CATEGORIES} to count all notes.
     *
     * @return the number of notes in the category
     */
    int countNotesInCategory(long categoryId);

    /**
     * Count the number of private notes in the database.
     *
     * @return the number of private notes in the database.
     */
    int countPrivateNotes();

    /**
     * Count the number of encrypted notes in the database.
     *
     * @return the number of encrypted notes in the database.
     */
    int countEncryptedNotes();

    /**
     * Get a cursor over notes matching the given selection criteria.
     *
     * @param categoryId the ID of the category whose notes to count,
     * or {@link NotePreferences#ALL_CATEGORIES} to include all notes.
     * @param includePrivate whether to include private notes.
     * @param includeEncrypted whether to include encrypted notes.
     * If {@code true}, {@code includePrivate} must also be {@code true}.
     * The provider will <i>not</i> decrypt the notes; decryption must be
     * done by the UI.
     * @param sortOrder the order in which to return matching notes,
     *                  expressed as one or more field names optionally
     *                  followed by &ldquo;desc&rdquo; suitable for use
     *                  as the object of an {@code ORDER BY} clause.
     *
     * @return a cursor for retrieving the notes
     */
    NoteCursor getNotes(long categoryId,
                        boolean includePrivate, boolean includeEncrypted,
                        @NonNull String sortOrder);

    /**
     * Get a list of ID&rsquo;s of private notes.  This is exclusively
     * meant for use by the {@link PasswordChangeService} to select notes
     * whose encryption needs changing.
     *
     * @return an array of note ID&rsquo;s.
     */
    long[] getPrivateNoteIds();

    /**
     * Get a single note by its ID.
     *
     * @param noteId the ID of the note to return
     *
     * @return the note if found, or {@code null} if there is no note
     * with that ID.
     */
    NoteItem getNoteById(long noteId);

    /**
     * Add a new note.  The note&rsquo;s category will be set by
     * its {@code categoryId}, <i>not</i> its {@code categoryName}.
     * If the note is encrypted, its {@code encryptedNote} field
     * <i>must</i> already be set to the encrypted value of the
     * {@code note}; any text in {@code note} will be ignored.
     *
     * @param note the note to add
     *
     * @return the newly added note.  This will be the same as the
     * {@code note} parameter that was passed in but will have its
     * {@code id} field set.
     *
     * @throws java.lang.IllegalArgumentException if the note&rsquo;s
     * content is empty
     * @throws SQLException if we failed to insert the note
     */
    NoteItem insertNote(@NonNull NoteItem note)
            throws IllegalArgumentException, SQLException;

    /**
     * Modify an existing note.  The note&rsquo;s category will be set by
     * its {@code categoryId}, <i>not</i> its {@code categoryName}.
     * If the note is encrypted, its {@code encryptedNote} field
     * <i>must</i> already be set to the encrypted value of the
     * {@code note}; any text in {@code note} will be ignored.
     *
     * @param note the note to change
     *
     * @return the updated note.  This will be the same as the
     * {@code note} parameter that was passed in.
     *
     * @throws java.lang.IllegalArgumentException if the note&rsquo;s
     * content is empty
     * @throws SQLException if we failed to insert the note
     */
    NoteItem updateNote(@NonNull NoteItem note)
            throws IllegalArgumentException, SQLException;

    /**
     * Delete a note.
     *
     * @param noteId the ID of the note to delete
     *
     * @return {@code true} if the note was deleted,
     * {@code false} if there was no note with that ID.
     *
     * @throws SQLException if we failed to delete the note
     */
    boolean deleteNote(long noteId) throws SQLException;

    /**
     * Purge <i>all</i> notes.
     *
     * @return {@code true} if any notes were deleted, {@code false}
     * if there were no notes.
     *
     * @throws SQLException if we failed to delete any notes
     */
    boolean deleteAllNotes() throws SQLException;

    /**
     * Run an operation within a database transaction.
     * The repository ensures that no other database operations
     * will be called while the transaction is in progress.
     * The transaction will be committed if the operation returns
     * normally; if it throws an (uncaught) exception, the transaction
     * will be rolled back.
     *
     * @param callback the operation to do.
     */
    void runInTransaction(@NonNull Runnable callback);

    /**
     * Register an observer that is called when Note Pad data changes.
     * Due to the nature of this app, whether the data is included in
     * this particular cursor is not taken into account; <i>any</i>
     * data change will result in a callback.
     *
     * @param observer the object that gets notified when Note Pad
     * data changes.
     */
    void registerDataSetObserver(@NonNull DataSetObserver observer);

    /**
     * Unregister an observer that has previously been registered
     * with this cursor via {@link #registerDataSetObserver}.
     *
     * @param observer the object to unregister
     */
    void unregisterDataSetObserver(@NonNull DataSetObserver observer);

}
