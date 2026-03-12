/*
 * Copyright © 2026 Trevin Beattie
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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.intent.matcher.IntentMatchers.doesNotHaveExtraWithKey;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey;
import static androidx.test.espresso.matcher.ViewMatchers.hasFocus;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static org.hamcrest.core.AllOf.allOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.util.Collection;
import java.util.Locale;

/**
 * Common methods for UI tests for launching activities and
 * (where applicable) obtaining their results.
 */
public class LaunchUtils {

    private static final String LOG_TAG = "LaunchUtils";

    /**
     * Verify that an activity has been launched.  For versions of
     * Android up to (35), this uses Espresso&rsquo;s {@link Intents}
     * framework; for Baklava and up, we have to look at the current
     * activity so the caller <i>must</i> ensure it has called the
     * instrumentation&rsquo;s {@link Instrumentation#waitForIdleSync()}
     * before this.  (The {@link ViewActionUtils#pressDialogButton} methods
     * do this before returning.)
     *
     * @param expectedActivityClass the class of the activity that should
     * have been launched.
     */
    public static void assertActivityLaunched(
            Class<? extends Activity> expectedActivityClass) {
        long timeLimit = System.nanoTime() + 5000000000L;
        AssertionError caughtError = null;
        while (System.nanoTime() < timeLimit) try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
                Intents.intended(hasComponent(expectedActivityClass.getName()));
            } else {
                Activity currentActivity = getCurrentActivity();
                assertNotNull("No activity is running", currentActivity);
                assertEquals("Running activity", expectedActivityClass,
                        currentActivity.getClass());
            }
            return;
        } catch (AssertionError ae) {
            caughtError = ae;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                // Ignore
            }
        }
        assertNotNull(String.format(Locale.US,
                "Failed to check for %s within 5 seconds",
                expectedActivityClass.getSimpleName()), caughtError);
        throw caughtError;
    }

    /**
     * If running on Vanilla Ice Cream or lower, initialize Espresso&rsquo;s
     * Intents hooks.  This call is <i>required</i> before starting a test
     * that uses any of the {@code assertIntent*Extra} methods.  Tests
     * that call this <i>must</i> also call {@link #releaseIntents()}
     * when finished.
     */
    public static void initializeIntents() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA)
            Intents.init();
    }

    /**
     * If running on Vanilla Ice Cream or lower, release Espresso&rsquo;s
     * Intents hooks.  This <i>must</i> be called at the end of all tests
     * that begin with calling {@link #initializeIntents()}.
     */
    public static void releaseIntents() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA)
            Intents.release();
    }

    /**
     * Verify that the activity&rsquo;s {@link Intent} has extra
     * data with the given key.
     *
     * @param expectedActivityClass the class of the activity that should
     * have been launched.
     * @param key the extra key that should be in the {@link Intent}
     */
    public static void assertIntentHasExtra(
            Class<? extends Activity> expectedActivityClass, String key) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Intents.intended(allOf(
                    hasComponent(expectedActivityClass.getName()),
                    hasExtraWithKey(key)));
        } else {
            Activity currentActivity = getCurrentActivity();
            assertNotNull("No activity is running", currentActivity);
            assertHasExtra(currentActivity.getIntent(), key);
        }
    }

    /**
     * Verify that the given {@link Intent} has extra data with the
     * given key.
     *
     * @param intent the {@link Intent} to check
     * @param key the extra key that should be in the {@link Intent}
     */
    public static void assertHasExtra(Intent intent, String key) {
        assertTrue(String.format(Locale.US,
                        "Intent has no extra with key %s", key),
                intent.hasExtra(key));
    }

    /**
     * Verify that the activity&rsquo;s {@link Intent} does
     * <i>not</i> have extra data with the given key.
     *
     * @param expectedActivityClass the class of the activity that should
     * have been launched.
     * @param key the extra key that should be in the {@link Intent}
     */
    public static void assertIntentDoesNotHaveExtra(
            Class<? extends Activity> expectedActivityClass, String key) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Intents.intended(allOf(
                    hasComponent(expectedActivityClass.getName()),
                    doesNotHaveExtraWithKey(key)));
        } else {
            Activity currentActivity = getCurrentActivity();
            assertNotNull("No activity is running", currentActivity);
            assertDoesNotHaveExtra(currentActivity.getIntent(), key);
        }
    }

    /**
     * Verify that the given {@link Intent} does <i>not</i> have extra
     * data with the given key.
     *
     * @param intent the {@link Intent} to check
     * @param key the extra key that should be in the {@link Intent}
     */
    public static void assertDoesNotHaveExtra(Intent intent, String key) {
        if (intent.hasExtra(key)) {
            Bundle extras = intent.getExtras();
            Object value = extras.get(key);
            String type = (value == null) ? "null"
                    : value.getClass().getSimpleName();
            fail(String.format(Locale.US,
                    "Intent has %s extra with key %s and value:%s",
                    type, key, value));
        }
    }

    /**
     * Verify that the activity&rsquo;s {@link Intent} has an extra
     * long data with the given key and value.
     *
     * @param expectedActivityClass the class of the activity that should
     * have been launched.
     * @param key the extra key that should be in the {@link Intent}
     * @param expectedValue the value that this extra should have
     */
    public static void assertIntentHasLongExtra(
            Class<? extends Activity> expectedActivityClass,
            String key, long expectedValue) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Intents.intended(allOf(
                    hasComponent(expectedActivityClass.getName()),
                    hasExtra(key, expectedValue)));
        } else {
            Activity currentActivity = getCurrentActivity();
            assertNotNull("No activity is running", currentActivity);
            assertHasLongExtra(currentActivity.getIntent(),
                    key, expectedValue);
        }
    }

    /**
     * Verify that the given {@link Intent} has an extra long data with
     * the given key and value.
     *
     * @param intent the {@link Intent} to check
     * @param key the extra key that should be in the {@link Intent}
     * @param expectedValue the value that this extra should have
     */
    public static void assertHasLongExtra(
            Intent intent, String key, long expectedValue) {
        assertTrue(String.format(Locale.US,
                        "Intent has no extra with key %s", key),
                intent.hasExtra(key));
        Long actualValue = intent.getLongExtra(key, Long.MIN_VALUE);
        if (actualValue == Long.MIN_VALUE)
            actualValue = null;
        assertEquals(String.format(Locale.US,
                        "Intent extra %s value", key),
                Long.valueOf(expectedValue), actualValue);
    }

    /**
     * Verify that the activity&rsquo;s {@link Intent} has an extra
     * string data with the given key and value.
     *
     * @param expectedActivityClass the class of the activity that should
     * have been launched.
     * @param key the extra key that should be in the {@link Intent}
     * @param expectedValue the value that this extra should have
     */
    public static void assertIntentHasStringExtra(
            Class<? extends Activity> expectedActivityClass,
            String key, String expectedValue) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Intents.intended(allOf(
                    hasComponent(expectedActivityClass.getName()),
                    hasExtra(key, expectedValue)));
        } else {
            Activity currentActivity = getCurrentActivity();
            assertNotNull("No activity is running", currentActivity);
            assertHasStringExtra(currentActivity.getIntent(),
                    key, expectedValue);
        }
    }

    /**
     * Verify that the given {@link Intent} has an extra string data with
     * the given key and value.
     *
     * @param intent the {@link Intent} to check
     * @param key the extra key that should be in the {@link Intent}
     * @param expectedValue the value that this extra should have
     */
    public static void assertHasStringExtra(
            Intent intent, String key, String expectedValue) {
        assertTrue(String.format(Locale.US,
                        "Intent has no extra with key %s", key),
                intent.hasExtra(key));
        assertEquals(String.format(Locale.US,
                        "Intent extra %s value", key),
                expectedValue, intent.getStringExtra(key));
    }

    /**
     * Prevent the soft keyboard from appearing and hide it if already
     * shown, since this may obscure buttons during the test.  This needs
     * to be called at the beginning of the scenario block.
     *
     * @param scenario the scenario in which the test is running
     */
    public static <T extends Activity> void hideKeyboard(
            ActivityScenario<T> scenario) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            scenario.onActivity(activity -> {
                activity.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            });
            try {
                onView(allOf(isAssignableFrom(EditText.class), hasFocus()))
                        .perform(closeSoftKeyboard());
            } catch (NoMatchingViewException nx) {
                Log.w("LaunchUtils", "No EditText view has focus");
                // Ignore if no view has an EditText with focus
            }
        } else {
            scenario.onActivity(LaunchUtils::hideKeyboard);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * Prevent the soft keyboard from appearing and hide it if already
     * shown, since this may obscure buttons during the test.  This form
     * uses the given {@link Activity}, so it <i>must</i> be called
     * on the {@link Activity}&rsquo;s thread.  Additionally, it is the
     * caller&rsquo;s responsibility to call
     * {@link Instrumentation#waitForIdleSync()} to wait for the soft
     * keyboard to hide away.
     *
     * @param activity the activity on which to hide the keyboard
     */
    public static void hideKeyboard(Activity activity) {
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        View focusView = activity.getCurrentFocus();
        if (focusView == null)
            focusView = new View(activity);
        imm.hideSoftInputFromWindow(focusView.getWindowToken(), 0);
    }

    /**
     * Get the current activity.  This is meant for tests that have
     * one activity launch another and need to interact with the
     * child activity.  Be aware that any interactions with this
     * activity <i>must</i> be done on the activity&rsquo;s UI
     * thread, not the test thread!
     *
     * @return the current activity.  May be {@code null} if no
     * activity has been found in the &ldquo;resumed&rdquo; state.
     */
    @Nullable
    public static Activity getCurrentActivity() {
        final Activity[] currentActivity = new Activity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                new Runnable() {
                    @Override
                    public void run() {
                        Collection<Activity> activities =
                                ActivityLifecycleMonitorRegistry.getInstance()
                                        .getActivitiesInStage(Stage.RESUMED);
                        if (!activities.isEmpty()) {
                            currentActivity[0] = activities.iterator().next();
                        }
                    }
                });
        return currentActivity[0];
    }

    /**
     * Wait to ensure the current activity has focus.  Some tests may
     * require this after a call to {@code recreate()} because this runs
     * slower on older Android versions.
     */
    public static void waitForFocus() {
        long timeLimit = System.nanoTime() + 5000000000L;
        while (System.nanoTime() < timeLimit) {
            Activity activity = getCurrentActivity();
            if (activity != null) {
                if (activity.hasWindowFocus())
                    return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ix) {
                // Ignore...
            }
        }
        Log.w(LOG_TAG, "Timed out waiting for activity to gain focus");
    }

}
