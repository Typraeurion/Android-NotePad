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

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Environment;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/**
 * Tests for getting the external storage directory, checking whether it's
 * mounted, and checking our permissions to use it.  These methods all vary
 * their implementation depending on the Android SDK version and require
 * Android's Environment and Context, so they must be run on emulated devices.
 *
 * @author Trevin Beattie
 */
@RunWith(AndroidJUnit4.class)

public class FileUtilsTests {

    Context testContext = null;

    @Before
    public void initialize() {
        if (testContext == null)
            testContext = InstrumentationRegistry.getTargetContext();
    }

    /** Test getting the default storage directory */
    @Test
    public void testGetDefaultStorageDirectory() {

        String directoryName = FileUtils.getDefaultStorageDirectory(testContext);
        assertNotNull("No directory returned", directoryName);
        File f = new File(directoryName);
        assertTrue(directoryName + " is not a directory", f.isDirectory());

    }

    /**
     * Run a test on whether storage is available.
     *
     * @param testExternalStorage whether to test a file within the
     *        external storage directory for the device (true)
     *        or on internal storage (false).
     * @param testWriteAccess whether the test should expect write access
     * @param externalStorageStatus whether and how external storage
     *        should be mounted for this test; <b>must</b> be one of
     *        the Environment.MEDIA_* constants.
     * @param expectedAnswer the expected result of the call
     *
     * @throws IOException if an I/O error occurs
     */
    private void runStorageAvailabilityTest(
            boolean testExternalStorage, boolean testWriteAccess,
            String externalStorageStatus, boolean expectedAnswer)
            throws IOException {

        File externalStorageDirectory =
                Environment.getExternalStorageDirectory();
        String customPath = File.separator + "subdirectory"
                + File.separator + "filename.test";
        File testFile = testExternalStorage ?
                new File(externalStorageDirectory.getPath() + customPath)
                : new File(customPath);

        switch (externalStorageStatus) {
            case Environment.MEDIA_MOUNTED:
                // Assuming the media is already mounted
                break;
            case Environment.MEDIA_BAD_REMOVAL:
            case Environment.MEDIA_CHECKING:
            case Environment.MEDIA_EJECTING:
            case Environment.MEDIA_MOUNTED_READ_ONLY:
            case Environment.MEDIA_NOFS:
            case Environment.MEDIA_REMOVED:
            case Environment.MEDIA_SHARED:
            case Environment.MEDIA_UNKNOWN:
            case Environment.MEDIA_UNMOUNTABLE:
            case Environment.MEDIA_UNMOUNTED:
                // Fix Me: there ought to be a way to do this in the emulator
                fail("Cannot change media state; cannot test "
                        + externalStorageStatus);
                // Unreachable
                return;
            default:
                fail("Invalid media state provided to test: "
                        + externalStorageStatus);
                // Unreachable
                return;
        }

        boolean actualAnswer = FileUtils.isStorageAvailable(
                testFile, testWriteAccess);

        assertEquals("isStorageAvailable", expectedAnswer, actualAnswer);

    }

    /**
     * Test checking whether external storage is available for read access
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testIsStorageAvailableExternalRead() throws IOException {
        runStorageAvailabilityTest(true, false, Environment.MEDIA_MOUNTED, true);
    }

    /**
     * Test checking whether external storage is available for write access
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testIsStorageAvailableExternalWrite() throws IOException {
        runStorageAvailabilityTest(true, true, Environment.MEDIA_MOUNTED, true);
    }

    /**
     * Test checking whether internal storage is available
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testIsStorageAvailableInternal() throws IOException {
        // The second (write) and third (storage state) parameters
        // should be irrelevant to this case.
        runStorageAvailabilityTest(false, false, Environment.MEDIA_MOUNTED, true);
    }

    /**
     * Run a test on whether we have permission to read or write a file.
     *
     * @param testPrivateDirectory whether to test a file within the
     *        application's private data directory (true)
     *        or in a public directory (false).
     * @param testWriteAccess whether the test should expect write access
     * @param expectedAnswer the expected result of the call
     *
     * @throws IOException if an I/O error occurs
     */
    private void runStoragePermissionTest(
            boolean testPrivateDirectory, boolean testWriteAccess,
            boolean expectedAnswer)
        throws IOException {

        File storageDirectory = testPrivateDirectory ?
                testContext.getExternalFilesDir(null)
                : Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
        File testFile = new File(storageDirectory, "filename.test");

        boolean actualAnswer = FileUtils.checkPermissionForExternalStorage(
                testContext, testFile, testWriteAccess);

        // Fix Me: In SDK 23+, access to public folders depends on
        // whether the user has granted permission.  There ought to
        // be a way to control this permission in the emulator.
        if (testPrivateDirectory)
            assertEquals("checkPermissionForExternalStorage",
                    expectedAnswer, actualAnswer);

    }

    /**
     * Test that we have read permission in our private folder
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testCheckReadPermissionForPrivateStorage() throws IOException {
        runStoragePermissionTest(true, false, true);
    }

    /**
     * Test that we have write permission in our private folder
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testCheckWritePermissionForPrivateStorage() throws IOException {
        runStoragePermissionTest(true, true, true);
    }

    /**
     * Test whether we have read permission in a public folder
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testCheckReadPermissionForPublicStorage() throws IOException {
        runStoragePermissionTest(false, false, false);
    }

    /**
     * Test whether we have write permission in a public folder
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testCheckWritePermissionForPublicStorage() throws IOException {
        runStoragePermissionTest(false, true, false);
    }

    /**
     * Test creating the target directory of a file if it does not already exist
     *
     * @throws IOException if an I/O error occurs
     */
    @Test
    public void testCreateMissingDirectory() throws IOException {

        File baseDirectory = testContext.getExternalFilesDir(null);

        File subDirectory = new File(baseDirectory,
                RandomStringUtils.randomAlphabetic(7, 11));
        while (subDirectory.exists()) {
            subDirectory = new File(baseDirectory,
                    RandomStringUtils.randomAlphabetic(7, 15));
        }

        try {
            FileUtils.ensureParentDirectoryExists(subDirectory);
        } finally {
            subDirectory.delete();
        }

    }

}
