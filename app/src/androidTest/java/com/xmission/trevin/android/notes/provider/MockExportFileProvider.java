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

import static android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE;
import static android.provider.OpenableColumns.DISPLAY_NAME;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extension of the Android {@link MockContentProvider}
 * @author Trevin Beattie
 */
@RequiresApi(Build.VERSION_CODES.N)
public class MockExportFileProvider extends MockContentProvider{

    /** Authority for our mock content provider */
    private static final String MOCK_AUTHORITY =
            "com.xmission.trevin.android.notes.provider.MockExportFileProvider";

    @NonNull
    private final Context context;

    public MockExportFileProvider(@NonNull Context context) {
        this.context = context;
    }

    /**
     * Create a content URI for a file that uses this provider.
     *
     * @param fileName the base file name of the file being tested
     *
     * @return a content URI for this file
     */
    public static Uri getMockUri(String fileName) {
        return Uri.parse(String.format(Locale.US,
                "content://%s/%s", MOCK_AUTHORITY, fileName));
    }

    /**
     * Create a MockContentResolver that uses this mock provider
     *
     * @return a MockContentResolver
     */
    public MockContentResolver getResolver() {
        MockContentResolver resolver = new MockContentResolver(context);
        resolver.addProvider(MOCK_AUTHORITY, this);
        return resolver;
    }

    private static final Pattern EXTENSION =
            Pattern.compile("\\.[0-9a-z]+$", Pattern.CASE_INSENSITIVE);

    /**
     * Handle requests for the MIME type of data at the given URI.
     *
     * @param uri the URI to query
     *
     * @return the implied MIME type of the content at that URI
     */
    @Override
    public String getType(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            Matcher m = EXTENSION.matcher(path);
            if (m.find()) {
                if (m.group().equals(".xml"))
                    return "text/xml";
                if (m.group().equals(".zip"))
                    return "application/zip";
            }
        }
        return "*/*";
    }

    /**
     * Query the given content URI for a display filename and MIME type.
     *
     * @param uri the URI to query
     * @param projection ignored
     * @param selection ignored
     * @param selectionArgs ignored
     * @param sortOrder ignored
     *
     * @return a {@link Cursor} over a single row containing the last
     * path component of the URI and a MIME type obtained from
     * {@link #getType(Uri)}.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {
                DISPLAY_NAME, COLUMN_MIME_TYPE });
        cursor.addRow(new Object[] {
                uri.getLastPathSegment(), getType(uri) });
        return cursor;
    }

    /**
     * Open a file.  Since this is a mock, we ignore whatever is in the
     * content URI and create a temporary test file instead.
     *
     * @param uri the content URI
     * @param mode how to open the file (read, write, append).
     * Can be "r", "w", "wt", "wa", "rw" or "rwt".  Cannot be {@code null}.
     *
     * @return a file descriptor
     */
    @Override
    public ParcelFileDescriptor openFile(
            @NonNull Uri uri, @NonNull String mode) {
        int iMode;
        switch (mode) {
            case "r":
                iMode = ParcelFileDescriptor.MODE_READ_ONLY;
                break;
            case "w":
                iMode = ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_CREATE;
                break;
            case "wt":
                iMode = ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE;
                break;
            case "wa":
                iMode = ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_APPEND;
                break;
            case "rw":
                iMode = ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE;
                break;
            case "rwt":
                iMode = ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE;
                break;
            default:
                throw new IllegalArgumentException("Bad file mode: " + mode);
        }
        String path = uri.getPath();
        if (path == null)
            path = "";
        Matcher m = EXTENSION.matcher(path);
        String extension = m.find() ? m.group() : ".test";
        try {
            File tempFile = File.createTempFile("mock-content-",
                    extension, context.getCacheDir());
            return ParcelFileDescriptor.open(tempFile, iMode);
        } catch (IOException iox) {
            throw new RuntimeException("Failed to create temp file", iox);
        }
    }

}
