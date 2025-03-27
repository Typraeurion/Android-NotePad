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
package com.xmission.trevin.android.notes.util;

import java.io.File;
import java.io.IOException;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.util.Log;

/**
 * Abstracts access to data directories across different versions of
 * the Android API.
 *
 * @author Trevin Beattie
 */
public class FileUtils {

    private static final String TAG = "FileUtils";

    /**
     * Return the default directory where this app may store its data files.
     * The possible locations are:
     * <dl>
     * <dt>Froyo (2.2) &ndash; Jellybean (4.3)</dt>
     * <dd><i>Environment external storage</i>/Android/Data/com.xmission.trevin.android.notes/<dd>
     * <dt>Kit Kat (4.4) &ndash; <i>(latest)</i></dt>
     * <dd><i>Context external storage</i>/</dd>
     * </dl>
     *
     * @param context the activity requesting the default directory
     */
    public static String getDefaultStorageDirectory(Context context) {
	Log.d(TAG, ".getDefaultStorageDirectory");
	String dirName;
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
	    dirName = Environment.getExternalStorageDirectory().getAbsolutePath()
		+ "/Android/Data/com.xmission.trevin.android.notes";
	} else {
            File filesDir = context.getExternalFilesDir(null);
            if (filesDir == null) {
                Log.w(TAG, "External storage is unavailable!"
                        + "  Cannot determine default storage path.");
                // Fall back to the Jelly Bean path
                dirName = Environment.getExternalStorageDirectory().getAbsolutePath()
		+ "/Android/Data/com.xmission.trevin.android.notes";
            } else {
                dirName = filesDir.getAbsolutePath();
            }
	}
	Log.d(TAG, "Default storage directory is " + dirName);
	return dirName;
    }

    /**
     * Return the default shared directory that the app may use.
     */
    public static String getSharedStorageDirectory() {
        Log.d(TAG, ".getSharedStorageDirectory");
        String dirName = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        Log.d(TAG, "Shared storage directory is " + dirName);
        return dirName;
    }

    /**
     * Check whether the given file&rsquo;s path is in external
     * storage and if so whether the external storage is mounted.
     *
     * @param file the file to check
     * @param checkWriteAccess whether to verify that the storage can
     * be written to
     *
     * @return true if the path is in internal storage or if external
     * storage is mounted, and if @code{checkWriteAccess}
     * is @code{true} it is <i>not</i> mounted read-only.  Returns
     * false if the path is in external storage but that is not
     * mounted or (if @code{checkWriteAccess} is @code{true}) is
     * read-only.
     *
     * @throws IOException if an I/O error occurs
     */
    public static boolean isStorageAvailable(
            File file, boolean checkWriteAccess)
            throws IOException {
	Log.d(TAG, ".isStorageAvailable");
	if (file.getParentFile().getCanonicalPath().startsWith(
		Environment.getExternalStorageDirectory().getCanonicalPath()
                        + File.separator)) {
	    String storageState = Environment.getExternalStorageState();
	    Log.d(TAG, "External storage is " + storageState);
	    if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(storageState))
		return !checkWriteAccess;
	    return Environment.MEDIA_MOUNTED.equals(storageState);
	}
	return true;
    }

    /**
     * On Marshmallow (Android 6), check whether the given
     * file&rsquo;s path is for external storage <i>and is not</i>
     * the application&rsquo;s private directory.  If that&rsquo;s
     * the case, check whether we have permission to read or write
     * to external storage.
     * <p>
     * Granting storage permission has to be handled asynchronously,
     * so the caller will have to act as if permission is denied for
     * the time being and the user must retry the operation after
     * granting permission.
     * </p>
     *
     * @param context the {@link Context} requesting access to the file
     * @param file the file to check
     * @param requestWriteAccess true if we need write access for the
     *        operation, otherwise we just check read access
     *
     * @return true if we are running on Lollipop or earlier (in which case
     * these permissions were granted at install time), if the path is
     * <i>not</i> on external storage, or if external storage access has
     * already been granted.
     *
     * @throws IOException if an I/O error occurs
     */
    public static boolean checkPermissionForExternalStorage(
            Context context, File file, boolean requestWriteAccess)
            throws IOException {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.d(TAG, ".checkPermissionForExternalStorage: assuming"
                    + " storage access is granted on API "
                    + Build.VERSION.SDK_INT);
            return true;
        }
        String canonicalStoragePath = Environment.getExternalStorageDirectory()
                .getCanonicalPath() + File.separator;
        String canonicalFile = file.getCanonicalPath();
        if (!canonicalFile.startsWith(canonicalStoragePath)) {
            Log.d(TAG, ".checkPermissionForExternalStorage: "
                    + file.getPath() + " is not in external storage.");
            return true;
        }

        String canonicalPrivatePath = context.getExternalFilesDir(null)
                .getCanonicalPath() + File.separator;
        if (canonicalFile.startsWith(canonicalPrivatePath)) {
            Log.d(TAG, ".checkPermissionForExternalStorage: "
                    + file.getPath() + " is within package-specific storage.");
            return true;
        }

        String requestedPermission = requestWriteAccess
                ? Manifest.permission.WRITE_EXTERNAL_STORAGE
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (context.checkSelfPermission(requestedPermission)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, ".checkPermissionForExternalStorage: "
                    + requestedPermission + " has been granted");
            return true;
        }

        Log.i(TAG, ".checkPermissionForExternalStorage: "
                + requestedPermission + " is currently denied.");
        return false;
    }

    /**
     * Ensure that the parent directory of the given file exists.
     *
     * @param file the file whose parent directory to check or create
     *
     * @throws SecurityException if there was an error creating the
     * parent directory.
     */
    public static void ensureParentDirectoryExists(File file)
	throws SecurityException {
	if (!file.getParentFile().exists()) {
	    Log.i(TAG, ".ensureParentDirectoryExists: "
		  + file.getParent() + " does not exist; attempting to create it");
	    boolean status = file.getParentFile().mkdirs();
	    Log.d(TAG, file.getParent()
		  + (status ? " was created" : " was NOT created"));
	}
    }

    /**
     * For content URI&rsquo;s returned from the Storage Access Framework
     * (KitKat or higher), find the associated file name.  This might not
     * be in the URI itself; the path may instead be a numeric file ID
     * (inode number or link?).  If we are unable to retrieve it from
     * the content resolver, fall back on the URI path.
     *
     * @param context the context in which the context resolver is found
     * @param uri the URI of the file to query
     */
    @TargetApi(19)
    public static String getFileNameFromUri(Context context, Uri uri) {
        Log.d(TAG, String.format(".getFileNameFromUri(\"%s\")",
                uri.toString()));
        String fileName = null;
        Cursor cursor = context.getContentResolver().query(uri,
                null, null, null, null, null);
        try {
            if ((cursor != null) && cursor.moveToFirst()) {
                fileName = cursor.getString(
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                Log.d(TAG, String.format("Got the display name: \"%s\"",
                        fileName));
            }
        } catch (Exception e) {
            Log.e(TAG, String.format("Failed to query %s from URI \"%s\"",
                    OpenableColumns.DISPLAY_NAME, uri.toString()), e);
        } finally {
            cursor.close();
        }
        if (fileName == null) {
            // Fall back on the URI path, stripping any scheme
            fileName = uri.getPath().replaceFirst(".+://", "");
            Log.w(TAG, String.format(
                    ".getFileNameFromUri: Falling back on URI path \"%s\"",
                    fileName));
        }
        return fileName;
    }

}
