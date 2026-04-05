/*
 * Copyright © 2014–2026 Trevin Beattie
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

import static com.xmission.trevin.android.notes.data.NotePreferences.NPREF_IMPORT_FILE;
import static com.xmission.trevin.android.notes.data.NotePreferences.NPREF_IMPORT_TYPE;
import static com.xmission.trevin.android.notes.util.LaunchUtils.initializeIntents;
import static com.xmission.trevin.android.notes.util.LaunchUtils.releaseIntents;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.assertButtonShown;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.pressButton;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.test.mock.MockContentResolver;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.MockSharedPreferences;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockExportFileProvider;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;
import com.xmission.trevin.android.notes.service.XMLImportWorker;
import com.xmission.trevin.android.notes.service.ZIPImportWorker;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests the behavior of the {@link ImportActivity},
 * {@link XMLImportWorker}, and {@link ZIPImportWorker}.
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ImportActivityTests {

    private static Instrumentation instrument = null;
    private static Context testContext = null;
    private static MockContentResolver mockResolver = null;
    private static MockSharedPreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;
    private StringEncryption globalEncryption = null;

    @BeforeClass
    public static void initializeMocks() {
        mockRepo = MockNoteRepository.getInstance();
        NoteRepositoryImpl.setInstance(mockRepo);
        mockPrefs = MockSharedPreferences.getInstance();
        NotePreferences.setSharedPreferences(mockPrefs);
        instrument = InstrumentationRegistry.getInstrumentation();
        testContext = instrument.getTargetContext();
        // We only need the content resolver on Nougat or higher;
        // on earlier platforms we don't use an export file URI;
        // the user may enter the file location directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mockResolver = new MockExportFileProvider(
                    ApplicationProvider.getApplicationContext())
                    .getResolver();
        }
    }

    @Before
    public void resetMocks() {
        mockPrefs.resetMock();
        mockRepo.clear();
        mockRepo.open(testContext);
        globalEncryption = StringEncryption.holdGlobalEncryption();
        globalEncryption.forgetPassword();
        initializeIntents();
    }

    @After
    public void cleanUp() {
        releaseIntents();
        globalEncryption.forgetPassword();
        StringEncryption.releaseGlobalEncryption(testContext);
        globalEncryption = null;
        mockRepo.release(testContext);
    }

    static final Pattern FILE_PATTERN = Pattern.compile(
            "(.*/)?([^.]+)(\\.[a-z]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Copy an XML test file from our test assets directory into
     * the app&rsquo;s private file storage.  The file will be marked
     * for deletion at the end of the test.
     *
     * @param sourceFileName the name of the test file to copy
     *
     * @return a {@link File} object referencing the copy that the
     * app can use
     *
     * @throws IOException if there is any error reading the source file
     * or copying it to the destination file
     */
    public File copyTestFile(String sourceFileName) throws IOException {
        Context hostContext = InstrumentationRegistry.getInstrumentation().getContext();
        // Generate a random name for the copied file to ensure it
        // doesn't interfere with any prior test or regular app files\
        File destFile;
        Matcher m = FILE_PATTERN.matcher(sourceFileName);
        if (m.matches()) {
            destFile = File.createTempFile(m.group(2), m.group(3),
                    testContext.getCacheDir());
        } else {
            destFile = File.createTempFile("test", ".data",
                    testContext.getCacheDir());
        }
        destFile.deleteOnExit();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                hostContext.getAssets().open(sourceFileName)));
             BufferedWriter writer = new BufferedWriter(
                new FileWriter(destFile))) {
            String line = reader.readLine();
            while (line != null) {
                writer.write(line);
                writer.newLine();
                line = reader.readLine();
            }
            writer.flush();
        }
        return destFile;
    }

    /**
     * Test importing an empty sample XML file.  We don&rsquot need to
     * check the input, as that&rsquo;s covered by the XMLImporter tests.
     */
    @Test
    public void testImportXML() throws IOException {
        File testFile = copyTestFile("notes-empty-2.xml");
        mockPrefs.initializePreference(NPREF_IMPORT_TYPE,
                NotePreferences.ImportType.CLEAN.ordinal());
        mockPrefs.initializePreference(NPREF_IMPORT_FILE,
                testFile.getAbsolutePath());

        try (ActivityScenarioResultsWrapper<ImportActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(ImportActivity.class)) {
            assertButtonShown(wrapper.getScenario(), "Import",
                    R.id.ImportButtonOK);
            pressButton(wrapper.getScenario(), R.id.ImportButtonOK);
        }
    }

    /**
     * Test importing an empty sample ZIP file.  We don&rsquot need to
     * check the input, as that&rsquo;s covered by the ZIPImporter tests.
     */
    @Test
    public void testImportZIP() throws IOException {
        File testFile = copyTestFile("notes-empty.zip");
        mockPrefs.initializePreference(NPREF_IMPORT_TYPE,
                NotePreferences.ImportType.CLEAN.ordinal());
        mockPrefs.initializePreference(NPREF_IMPORT_FILE,
                testFile.getAbsolutePath());

        try (ActivityScenarioResultsWrapper<ImportActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(ImportActivity.class)) {
            assertButtonShown(wrapper.getScenario(), "Import",
                    R.id.ImportButtonOK);
            pressButton(wrapper.getScenario(), R.id.ImportButtonOK);
        }
    }

}
