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

import static com.xmission.trevin.android.notes.data.NotePreferences.*;
import static com.xmission.trevin.android.notes.util.LaunchUtils.*;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.*;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
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
import com.xmission.trevin.android.notes.service.ZIPExporter.EncryptionType;
import com.xmission.trevin.android.notes.util.FileUtils;
import com.xmission.trevin.android.notes.util.StringEncryption;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Tests the behavior of the {@link ExportActivity}.
 * For now this is generally limited to the setup;
 * we don&rsquo;s initiate an actual export from here,
 * which would involve the export workers.
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ExportActivityTests {

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

    /**
     * Run a test on the state of several form elements related to
     * passwords and ZIP export settings.  These elements may either
     * be enabled, disabled, or hidden depending on a combination
     * of several state variables.  The variables in question are:
     * <ul>
     *     <li>Whether the &ldquo;Include Private&rdquo; checkbox is
     *     checked</li>
     *     <li>Whether a password has been set in the repository</li>
     *     <li>Whether the user has unlocked encrypted records</li>
     *     <li>The type of file selected for export (XML or ZIP)</li>
     *     <li>For ZIP, what type of encryption is selected</li>
     *     <li>For ZIP with ZIP encryption, whether a ZIP password
     *     has been entered</li>
     * </ul>
     * The UI elements impacted by the state are:
     * <ul>
     *     <li>The &lqduo;No password has been set&rdquo; warning
     *     (may either be visible or hidden)</li>
     *     <li>The &ldquo;Encrypted records are locked&rdquo; warning
     *     (may either be visible or hidden)</li>
     *     <li>Three classes of radio buttons for selecting the ZIP
     *     encryption type:
     *     <ul>
     *         <li>No encryption</li>
     *         <li>Bundled encryption</li>
     *         <li>ZIP encryption</li>
     *     </ul></li>
     *     <li>The ZIP password field (along with the &ldquo;Show
     *     Password&rdquo; checkbox)</li>
     * </ul>
     *
     * @param exportPrivate whether to set the &ldquo;Export Private&rdquo;
     * preference
     * @param setDBPassword whether to set a password in the repository
     * @param unlock whether to unlock encrypted records after setting
     * the DB password; ignored if {@code setDBPassword} is {@code false}
     * @param fileZip whether to initialize the export file as a
     * &ldquo;{@code .zip}&rdquo; file ({@code true}) or
     * &ldquo;{@code .xml}&rdquo; / unset ({@code false}).  If {@code true},
     * we automatically expect the category filter line to be visible;
     * the caller doesn&rsquo;t need to pass this explicitly.
     * @param encryptionType the type of ZIP encryption to set in the
     * preferences
     * @param zipPassword whether to enter a password in the ZIP Password
     * field.  This is the only part of the state which is set <i>after</i>
     * the activity starts, because the password is not retained between
     * launches of the {@link ExportActivity}.
     * @param expectNoPasswordWarning whether the &ldquo;No password has
     * been set&rdquo; message should be visible; if this is set,
     * {@code expectEncryptedLockedWarning} must be {@code false}.
     * @param expectEncryptedLockedWarning whether the &ldquo;Encrypted
     * records are locked&rdquo; message should be visible; if this is set,
     * {@code expectNoPasswordWarning} must be {@code false}.
     * @param expectedNoEncryptionButtonState a tri-state value: if
     * {@code null}, the &ldquo;No Encryption&rdquo; radio button should be
     * hidden.  Otherwise the button should be visible and this indicates
     * whether the button should be enabled.
     * @param expectedBundledEncryptionButtonState a tri-state value: if
     * {@code null}, the &ldquo;Bundled Encryption&rdquo; radio button
     * should be hidden.  Otherwise the button should be visible and this
     * indicates whether the button should be enabled.
     * @param expectedZipEncryptionButtonState a tri-state value: if
     * {@code null}, the &ldquo;ZIP AES&rdquo; radio buttons should be
     * hidden.  Otherwise the buttons should be visible and this indicates
     * whether the buttons should be enabled.
     * @param expectZipPasswordField whether the &ldquo;ZIP Password&rdquo;
     * field and &ldquo;Show Password&rdquo; checkbox should be visible.
     */
    private void runZipOptionsTest(
            boolean exportPrivate,
            boolean setDBPassword, boolean unlock,
            boolean fileZip, EncryptionType encryptionType,
            boolean zipPassword,
            boolean expectNoPasswordWarning,
            boolean expectEncryptedLockedWarning,
            Boolean expectedNoEncryptionButtonState,
            Boolean expectedBundledEncryptionButtonState,
            Boolean expectedZipEncryptionButtonState,
            boolean expectZipPasswordField) {
        mockPrefs.initializePreference(
                NotePreferences.NPREF_EXPORT_PRIVATE, exportPrivate);
        if (setDBPassword) {
            String localPassword = RandomStringUtils.randomAlphanumeric(12);
            globalEncryption.setPassword(localPassword.toCharArray());
            globalEncryption.addSalt();
            globalEncryption.storePassword(mockRepo);
            if (!unlock)
                globalEncryption.forgetPassword();
        }
        // Setting up the file type depends on which platform we're running on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri fileUri = MockExportFileProvider.getMockUri(
                    String.format(Locale.US, "options-test.%s",
                            fileZip ? "zip" : "xml"));
            mockPrefs.initializePreference(NPREF_EXPORT_FILE,
                    fileUri.toString());
        } else {
            String fileName = String.format(Locale.US, "%s/options-test.%s",
                    FileUtils.getDefaultStorageDirectory(testContext),
                    fileZip ? "zip" : "xml");
            mockPrefs.initializePreference(NPREF_EXPORT_FILE, fileName);
        }
        mockPrefs.initializePreference(NPREF_EXPORT_ZIP_ENCRYPTION,
                encryptionType.name());

        List<String> errors = new ArrayList<>();
        try (ActivityScenarioResultsWrapper<ExportActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(ExportActivity.class)) {
            // First check the visibility of the ZIP encryption radio group.
            boolean expectGroupVisible =
                    (expectedNoEncryptionButtonState != null) ||
                            (expectedBundledEncryptionButtonState != null) ||
                            (expectedZipEncryptionButtonState != null);
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "ZIP Encryption radio group",
                        R.id.TableRowExportZipEncryption, expectGroupVisible);
            } catch (Throwable t) {
                // Espresso errors don't include any custom message
                // so we need to provide it ourself.
                if (!t.getMessage().contains("rodio group"))
                    errors.add("ZIP Encryption radio group " + t.getMessage());
                else
                    errors.add(t.getMessage());
            }
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "ZIP Password line",
                        R.id.TableRowExportZipPassword,
                        expectZipPasswordField);
            } catch (Throwable t) {
                if (!t.getMessage().contains("ZIP Password"))
                    errors.add("ZIP Password line " + t.getMessage());
                else
                    errors.add(t.getMessage());
            }
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "Show ZIP Password line",
                        R.id.TableRowExportShowPassword,
                        expectZipPasswordField);
            } catch (Throwable t) {
                if (!t.getMessage().contains("ZIP Password"))
                    errors.add("Show ZIP Password line " + t.getMessage());
                else
                    errors.add(t.getMessage());
            }

            // Now, if the ZIP Password field is visible, set the
            // pre-condition of the password entry.
            if (expectZipPasswordField) {
                setEditText(wrapper.getScenario(),
                        "ZIP Password", R.id.ExportEditTextZipPassword,
                        zipPassword ? "password" : "");
            }

            // Now check the state of the ZIP encryption type buttons
            try {
                if (expectedNoEncryptionButtonState == null) {
                    assertViewVisibility(wrapper.getScenario(),
                            "No Encryption button",
                            R.id.ExportZipEncryptionRadioButtonNone,
                            false);
                } else {
                    assertButtonState(wrapper.getScenario(),
                            "No Encryption",
                            R.id.ExportZipEncryptionRadioButtonNone,
                            expectedNoEncryptionButtonState);
                }
            } catch (AssertionError e) {
                if (!e.getMessage().contains("No Encryption"))
                    errors.add("No Encryption button " + e.getMessage());
                else
                    errors.add(e.getMessage());
            }
            try {
                if (expectedBundledEncryptionButtonState == null) {
                    assertViewVisibility(wrapper.getScenario(),
                            "Bundled Encryption button",
                            R.id.ExportZipEncryptionRadioButtonInternal,
                            false);
            } else {
                    assertButtonState(wrapper.getScenario(),
                            "Bundled Encryption",
                            R.id.ExportZipEncryptionRadioButtonInternal,
                            expectedBundledEncryptionButtonState);
                }
            } catch (AssertionError e) {
                if (!e.getMessage().contains("Bundled Encryption"))
                    errors.add("Bundled Encryption button " + e.getMessage());
                else
                    errors.add(e.getMessage());
            }
            try {
                if (expectedZipEncryptionButtonState == null) {
                    assertViewVisibility(wrapper.getScenario(),
                            "ZIP AES-128 Encryption button",
                            R.id.ExportZipEncryptionRadioButtonAES128,
                            false);
                    assertViewVisibility(wrapper.getScenario(),
                            "ZIP AES-256 Encryption button",
                            R.id.ExportZipEncryptionRadioButtonAES256,
                            false);
                } else {
                    assertButtonState(wrapper.getScenario(),
                            "ZIP AES-128 Encryption",
                            R.id.ExportZipEncryptionRadioButtonAES128,
                            expectedZipEncryptionButtonState);
                    assertButtonState(wrapper.getScenario(),
                            "ZIP AES-256 Encryption",
                            R.id.ExportZipEncryptionRadioButtonAES256,
                            expectedZipEncryptionButtonState);
                }
            } catch (AssertionError e) {
                if (!e.getMessage().contains("ZIP AES"))
                    errors.add("ZIP AES Encryption button " + e.getMessage());
                else
                    errors.add(e.getMessage());
            }

            // Check the category filter
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "Category filter line",
                        R.id.TableRowExportZipCategory, fileZip);
            } catch (AssertionError e) {
                if (!e.getMessage().contains("Category filter"))
                    errors.add("Category filter line " + e.getMessage());
                else
                    errors.add(e.getMessage());
            }

            // Finally, check the warning texts
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "\"No password has been set\" warning",
                        R.id.ExportTextPasswordNotSetWarning,
                        expectNoPasswordWarning);
            } catch (AssertionError e) {
                if (!e.getMessage().contains("has been set\" warning"))
                    errors.add("\"No password has been set\" warning " +
                            e.getMessage());
                else
                    errors.add(e.getMessage());
            }
            try {
                assertViewVisibility(wrapper.getScenario(),
                        "\"Encrypted records are locked\" warning",
                        R.id.ExportTextPasswordRequiredWarning,
                        expectEncryptedLockedWarning);
            } catch (AssertionError e) {
                if (!e.getMessage().contains("Encrypted records"))
                    errors.add("\"Encrypted records are locked\" warning "
                            + e.getMessage());
                else
                    errors.add(e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder heading = new StringBuilder("Errors for conditions");
            heading.append(" Include Private ")
                    .append(exportPrivate ? "\u2611" : "\u2610");
            heading.append(", DB password is ")
                    .append(setDBPassword ?
                            (unlock ? "unlocked" : "locked")
                            : "not set");
            heading.append(", export file is ")
                    .append(fileZip ? "ZIP" : "XML");
            heading.append(", ZIP encryption = ").append(encryptionType);
            heading.append(", ZIP password ")
                    .append(zipPassword ? "= \"password\"" : "is empty");
            heading.append(':');
            errors.add(0, heading.toString());
            fail(StringUtils.join(errors, "\n"));
        }
    }

    static final boolean[] BOTH_BOOLEANS = { false, true };
    static final Boolean[] ALL_BOOLEANS = { null, false, true };
    static final Set<EncryptionType> ZIP_ENCRYPTION_TYPES;
    static {
        Set<EncryptionType> set = new HashSet<>();
        for (EncryptionType type : EncryptionType.values()) {
            switch (type) {
                case NO_ENCRYPTION:
                case BUNDLED_ENCRYPTION:
                    continue;
                default:
                    set.add(type);
            }
        }
        ZIP_ENCRYPTION_TYPES = Collections.unmodifiableSet(set);
    }

    /**
     * When the &ldquo;Include Private&rdquo; checkbox is clear,
     * none of the password warnings or ZIP options should be displayed
     * regardless of the other settings.  This goes through many loops
     * to check all of the other state combinations.
     */
    @Test
    public void testZipOptionsPublicExport() {
        for (Boolean unlockState : ALL_BOOLEANS) {
            boolean setDBPassword = (unlockState != null);
            boolean unlock = Boolean.TRUE.equals(unlockState);
            for (boolean fileZip : BOTH_BOOLEANS) {
                for (EncryptionType encryptionType : EncryptionType.values()) {
                    for (boolean zipPassword : BOTH_BOOLEANS) {
                        runZipOptionsTest(false, setDBPassword, unlock,
                                fileZip, encryptionType, zipPassword,
                                false, false, null, null, null, false);
                    }
                }
            }
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is not
     * ZIP, and a password has not been set, the &ldquo;No password has been
     * set&rdquo; warning should be shown and none of the ZIP options
     * regardless of the ZIP preferences.
     */
    @Test
    public void testZipOptionsPrivateXMLNoPassword() {
        for (EncryptionType encryptionType : EncryptionType.values()) {
            for (boolean zipPassword : BOTH_BOOLEANS) {
                runZipOptionsTest(true, false, false,
                        false, encryptionType, zipPassword,
                        true, false, null, null, null, false);
            }
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is not
     * ZIP, and a password has been set but encrypted records are locked,
     * no password warning should be shown and none of the ZIP options
     * regardless of the ZIP preferences.
     */
    @Test
    public void testZipOptionsPrivateXMLLocked() {
        for (EncryptionType encryptionType : EncryptionType.values()) {
            for (boolean zipPassword : BOTH_BOOLEANS) {
                runZipOptionsTest(true, true, false,
                        false, encryptionType, zipPassword,
                        false, false, null, null, null, false);
            }
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is not
     * ZIP, a password has been set and encrypted records unlocked, none of
     * the password warnings or ZIP options should be displayed.
     */
    @Test
    public void testZipOptionsPrivateXMLUnlocked() {
        for (EncryptionType encryptionType : EncryptionType.values()) {
            for (boolean zipPassword : BOTH_BOOLEANS) {
                runZipOptionsTest(true, true, true,
                        false, encryptionType, zipPassword,
                        false, false, null, null, null, false);
            }
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * a password has not been set in the database, and the encryption type
     * is &ldquo;No Encryption&rdquo;, the &ldquo;No password has been
     * set&rdquo; warning should be shown, the &ldquo;Bundled
     * Encryption&rdquo; option should <i>not</i> be shown, and the ZIP
     * password field should not be shown.
     */
    @Test
    public void testZipOptionsPrivateZIPNoPasswordNoEncryption() {
        for (boolean zipPassword : BOTH_BOOLEANS) {
            runZipOptionsTest(true, false, false,
                    true, EncryptionType.NO_ENCRYPTION, zipPassword,
                    true, false, true, null, true, false);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * a password has not been set in the database, the encryption type is
     * either of the ZIP encryption options, and no ZIP Password has been
     * entered, the &ldquo;No password has been set&rdquo; warning should
     * be shown, the &ldquo;Bundled Encryption&rdquo; option should
     * <i>not</i> be shown, and the ZIP password field should be shown.
     */
    @Test
    public void testZipOptionsPrivateZipNoPasswordZipEncryption() {
        for (EncryptionType encryptionType : ZIP_ENCRYPTION_TYPES) {
            runZipOptionsTest(true, false, false,
                    true, encryptionType, false,
                    true, false, true, null, true, true);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * a password has not been set in the database, the encryption type is
     * either of the ZIP encryption options, and a ZIP Password is entered,
     * neither of the password warnings should be shown and the
     * &ldquo;Bundled Encryption&rdquo; option should <i>not</i> be shown.
     */
    @Test
    public void testZipOptionsPrivateZipEncryptionWithPassword() {
        for (EncryptionType encryptionType : ZIP_ENCRYPTION_TYPES) {
            runZipOptionsTest(true, false, false,
                    true, encryptionType, true,
                    false, false, true, null, true, true);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * encrypted records are locked, and the encryption type is
     * &ldquo;Bundled&rdquo;, the &ldquo;Encrypted records are locked&rdquo;
     * warning should still be shown because all other encryption options
     * are disabled, and the ZIP Password field should not be shown.
     */
    @Test
    public void testZipOptionsPrivateZIPLockedBundledEncryption() {
        for (boolean zipPassword : BOTH_BOOLEANS) {
            runZipOptionsTest(true, true, false,
                    true, EncryptionType.BUNDLED_ENCRYPTION, zipPassword,
                    false, true, false, true, false, false);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * encrypted records are unlocked, and the encryption type is
     * &ldquo;No encryption&rdquo;, the &ldquo;No password has been
     * set&rdquo; warning should be shown, all encryption type buttons
     * should be enabled, and the ZIP Password field should not be shown.
     */
    @Test
    public void testZipOptionsPrivateZipUnlockedNoEncryption() {
        for (boolean zipPassword : BOTH_BOOLEANS) {
            runZipOptionsTest(true, true, true,
                    true, EncryptionType.NO_ENCRYPTION, zipPassword,
                    true, false, true, true, true, false);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * encrypted records are unlocked, and the encryption type is
     * &ldquo;Bundled&rdquo;, no password warning should be shown, all
     * encryption type buttons should be enabled, and the ZIP Password
     * field should not be shown.
     */
    @Test
    public void testZipOptionsPrivateZipUnlockedBundledEncryption() {
        for (boolean zipPassword : BOTH_BOOLEANS) {
            runZipOptionsTest(true, true, true,
                    true, EncryptionType.BUNDLED_ENCRYPTION, zipPassword,
                    false, false, true, true, true, false);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * encrypted records are unlocked, the encryption type is ZIP encryption,
     * and a ZIP Password has not been entered, the &ldquo;No password has
     * been set&rdquo; warning should be shown and all encryption type
     * buttons and the ZIP Password field should be shown.
     */
    @Test
    public void testZipOptionsPrivateZipUnlockedZipEncryptionNoPassword() {
        for (EncryptionType encryptionType : ZIP_ENCRYPTION_TYPES) {
            runZipOptionsTest(true, true, true,
                    true, encryptionType, false,
                    true, false, true, true, true, true);
        }
    }

    /**
     * When &ldquo;Include Private&rdquo; is checked, the file type is ZIP,
     * encrypted records are unlocked, the encryption type is ZIP encryption,
     * and a ZIP Password has been entered, no password  warning should be
     * shown and all encryption type buttons and the ZIP Password field
     * should be shown.
     */
    @Test
    public void testZipOptionsPrivateZipUnlockedZipEncryptionZipPassword() {
        for (EncryptionType encryptionType : ZIP_ENCRYPTION_TYPES) {
            runZipOptionsTest(true, true, true,
                    true, encryptionType, true,
                    false, false, true, true, true, true);
        }
    }

    /**
     * Test exporting an XML file.  We don&rsquo;t need to check
     * the output, as that&rsquo;s covered by the XMLExporter tests.
     */
    @Test
    public void testExportXML() throws IOException {
        File testFile = File.createTempFile("notes-empty-", ".xml",
                testContext.getCacheDir());
        mockPrefs.initializePreference(NPREF_EXPORT_FILE,
                testFile.getAbsolutePath());

        try (ActivityScenarioResultsWrapper<ExportActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(ExportActivity.class)) {
            assertButtonShown(wrapper.getScenario(), "Export",
                    R.id.ExportButtonOK);
            pressButton(wrapper.getScenario(), R.id.ExportButtonOK);
        }
    }

    /**
     * Test exporting a ZIP file.  We don&rsquo;t need to check
     * the output, as that&rsquo;s covered by the ZIPImporter tests.
     */
    @Test
    public void testExportZIP() throws IOException {
        File testFile = File.createTempFile("notes-empty-", ".zip",
                testContext.getCacheDir());
        mockPrefs.initializePreference(NPREF_EXPORT_FILE,
                testFile.getAbsolutePath());

        try (ActivityScenarioResultsWrapper<ExportActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(ExportActivity.class)) {
            assertButtonShown(wrapper.getScenario(), "Export",
                    R.id.ExportButtonOK);
            pressButton(wrapper.getScenario(), R.id.ExportButtonOK);
        }
    }

}
