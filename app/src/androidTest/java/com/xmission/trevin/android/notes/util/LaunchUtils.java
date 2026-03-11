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
import static androidx.test.espresso.matcher.ViewMatchers.hasFocus;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static org.hamcrest.core.AllOf.allOf;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.util.Collection;

/**
 * Common methods for UI tests for launching activities and
 * (where applicable) obtaining their results.
 */
public class LaunchUtils {

    private static final String LOG_TAG = "LaunchUtils";

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
