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
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static com.xmission.trevin.android.notes.ui.FocusAction.requestFocus;
import static org.hamcrest.core.AllOf.allOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class ViewActionUtils {

    private static final String LOG_TAG = "ViewActionUtils";

    /**
     * Wait for a {@link Dialog} with specified text to be shown.
     * Normally this text would be the title, but this will work
     * with text anywhere in the view hierarchy so it should be unique.
     * The dialog must exist within the {@code scenario}&rsquo;s activity.
     *
     * @param scenario the scenario in which the test is running
     * @param expectedText the text within the dialog to look for
     *
     * @throws AssertionError if no dialog with the given title is found
     * within 5 seconds
     */
    public static <T extends Activity> void assertDialogShown(
            @NonNull ActivityScenario<T> scenario,
            @NonNull String expectedText) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            onView(withText(expectedText))
                    .inRoot(isDialog())
                    .check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
        } else {
            final AtomicReference<TextView> title = new AtomicReference<>();
            long timeout = System.nanoTime() + 5000000000L;
            while ((title.get() == null) &&
                    (System.nanoTime() < timeout)) {
                scenario.onActivity(activity -> {
                    List<View> roots = getWindowRoots();
                    for (View root : roots) {
                        TextView found = findViewRecursive(root, expectedText);
                        if (found != null) {
                            title.set(found);
                            return;
                        }
                    }
                });
                if (title.get() == null) try {
                    Thread.sleep(50);
                } catch (InterruptedException ix) {
                    // Ignore
                }
            }
            assertNotNull(String.format(Locale.US,
                            "Dialog \"%s\" was not found", expectedText),
                    title.get());
        }
    }

    /**
     * Find all root views from WindowManagerGlobal.  This is needed
     * to find views in Dialogs and pop-ups without Espresso.
     *
     * @return a {@link List} of root {@link View}s
     */
    @SuppressWarnings("unchecked")
    private static List<View> getWindowRoots() {
        try {
            Class<?> wmClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmInstance = wmClass.getMethod("getInstance").invoke(null);
            Field viewsField = wmClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object views = viewsField.get(wmInstance);
            if (views instanceof List) {
                return (List<View>) views;
            } else if (views instanceof View[]) {
                return Arrays.asList((View[]) views);
            } else if (views != null) {
                Log.w(LOG_TAG, "Window manager views is neither a List nor"
                        + " an Array; got " + views.getClass().getName());
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to get window manager views", e);
        }
        return Collections.emptyList();
    }

    /**
     * Find the View within a given root that contains specific text
     *
     * @param root the root {@link View}
     * @param hasText the text to look for
     *
     * @return the view if found, or {@code null} if the text was not found
     */
    private static TextView findViewRecursive(View root, String hasText) {
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            if ((cs != null) && hasText.contentEquals(cs))
                return (TextView) root;
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView tv = findViewRecursive(vg.getChildAt(i), hasText);
                if (tv != null)
                    return tv;
            }
        }

        return null;
    }

    /**
     * Verify that a given button is present (and visible).
     * The button must exist within the activity content.
     * This is the Espresso version.
     *
     * @param buttonId the resource ID of the button to check
     */
    private static void esAssertButtonShown(int buttonId) {
        onView(withId(buttonId))
                .check(matches(allOf(
                        withEffectiveVisibility(Visibility.VISIBLE),
                        isEnabled())));
    }

    /**
     * Verify that a given view is a button that is visible.
     * It may be a text {@link Button} or an {@link ImageButton}.
     * The caller is responsible for finding the view.
     *
     * @param view the {@link View} to check
     * @param buttonName the name of the button for any assertion message
     * @param buttonId the resource ID of the button for any assertion message
     *
     * @throws AssertionError if the button is missing, not visible,
     * or is neither a {@link Button} nor {@link ImageButton}.
     */
    private static void assertViewIsVisibleButton(
            View view, String buttonName, int buttonId) {
            assertNotNull(buttonName + " button is missing", view);
            if (!(view instanceof Button) &&
                    !(view instanceof ImageButton)) {
                fail(String.format(Locale.US,
                        "%s view with ID %d is neither a Button"
                                + " nor an ImageButton",
                        buttonName, buttonId));
            }
            assertTrue(buttonName + " button is not visible",
                    view.isShown());
            assertTrue(buttonName + " button is disabled",
                    view.isEnabled());
    }

    /**
     * Verify that a given button is present (and visible).
     * The button must exist within the activity content.
     * It may be a text {@link Button} or an {@link ImageButton}.
     *
     * @param scenario the scenario in which the test is running
     * @param buttonName the name of the button
     * @param buttonId the resource ID of the button to check
     *
     * @throws AssertionError if the button is missing or not visible
     */
    public static <T extends Activity> void assertButtonShown(
            ActivityScenario<T> scenario,
            String buttonName, int buttonId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            esAssertButtonShown(buttonId);
        } else {
            final View[] buttonRef = new View[1];
            scenario.onActivity(activity -> {
                buttonRef[0] = activity.findViewById(buttonId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertViewIsVisibleButton(buttonRef[0], buttonName, buttonId);
        }
    }

    /**
     * Verify that a given dialog button is present (and visible).
     *
     * @param scenario the scenario in which the test is running
     * @param dialog the {@link Dialog} containing the button.
     * @param buttonName the name of the button
     * @param buttonId the resource ID of the button to check
     *
     * @throws AssertionError if the button is missing or not visible
     */
    public static <T extends Activity> void assertButtonShown(
            ActivityScenario<T> scenario, @NonNull final Dialog dialog,
            String buttonName, int buttonId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            esAssertButtonShown(buttonId);
        } else {
            final View[] buttonRef = new View[1];
            scenario.onActivity(activity -> {
                buttonRef[0] = dialog.findViewById(buttonId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertViewIsVisibleButton(buttonRef[0], buttonName, buttonId);
        }
    }

    /**
     * Click the designated button.  This is the Espresso-only version.
     *
     * @param buttonId the resource ID of the button to click
     *
     * @throws AssertionError if the button is not visible or enabled
     */
    private static void esPressButton(int buttonId) {
        // Check whether the button is visible; otherwise click() will fail.
        boolean isVisible;
        try {
            onView(withId(buttonId))
                    .check(matches(isCompletelyDisplayed()));
            isVisible = true;
        } catch (AssertionError | Exception e) {
            isVisible = false;
        }
        // Try scrolling to the button if necessary
        if (!isVisible) try {
            onView(withId(buttonId))
                    .perform(scrollTo());
        } catch (AssertionError | Exception e) {
            // Ignore
            Log.w(LOG_TAG, String.format(Locale.US,
                    "Button %d is not completely visible"
                            + " and we failed to scroll to it.",
                    buttonId));
        }
        onView(withId(buttonId))
                .check(matches(allOf(isEnabled(),
                        isDisplayingAtLeast(90))))
                .perform(click());
    }

    /**
     * Click the designated button in a dialog.
     * This is the Espresso-only version.
     *
     * @param buttonId the resource ID of the button to click
     *
     * @throws AssertionError if the button is not visible or enabled
     */
    private static void esPressDialogButton(int buttonId) {
        // Check whether the button is visible; otherwise click() will fail.
        boolean isVisible;
        try {
            onView(withId(buttonId))
                    .inRoot(isDialog())
                    .check(matches(isCompletelyDisplayed()));
            isVisible = true;
        } catch (AssertionError | Exception e) {
            isVisible = false;
        }
        // Try scrolling to the button if necessary
        if (!isVisible) try {
            onView(withId(buttonId))
                    .inRoot(isDialog())
                    .perform(scrollTo());
        } catch (AssertionError | Exception e) {
            // Ignore
            Log.w(LOG_TAG, String.format(Locale.US,
                    "Button %d is not completely visible"
                            + " and we failed to scroll to it.",
                    buttonId));
        }
        onView(withId(buttonId))
                .inRoot(isDialog())
                .check(matches(allOf(isEnabled(),
                        isDisplayingAtLeast(90))))
                .perform(click());
    }

    /**
     * Click the designated button.  The button must exist within the
     * activity content.  The test should have already verified that
     * the button exists and is enabled.  It may be either a text
     * {@link Button} or an {@link ImageButton}.
     *
     * @param scenario the scenario in which the test is running
     * @param buttonId the resource ID of the button to click
     */
    public static <T extends Activity> void pressButton(
            ActivityScenario<T> scenario,
            int buttonId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            esPressButton(buttonId);
        } else {
            final View[] buttonRef = new View[1];
            scenario.onActivity(activity -> {
                buttonRef[0] = activity.findViewById(buttonId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNotNull(String.format(Locale.US,
                    "No button found with ID %d", buttonId), buttonRef[0]);
            if (!(buttonRef[0] instanceof Button) &&
                    !(buttonRef[0] instanceof ImageButton))
                fail(String.format(Locale.US,
                        "View with ID %d is neither a Button nor ImageButton",
                        buttonId));
            scenario.onActivity(activity -> {
                buttonRef[0].performClick();
            });
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * Click the designated dialog button.
     *
     * @param scenario the scenario in which the test is running
     * @param dialog the {@link Dialog} containing the button.
     * @param buttonId the resource ID of the button to click
     */
    public static <T extends Activity> void pressDialogButton(
            ActivityScenario<T> scenario, @NonNull final Dialog dialog,
            int buttonId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            esPressDialogButton(buttonId);
        } else {
            View[] buttonRef = new View[1];
            scenario.onActivity(activity -> {
                buttonRef[0] = dialog.findViewById(buttonId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNotNull(String.format(Locale.US,
                    "No button found with ID %d", buttonId), buttonRef[0]);
            assertTrue(String.format(Locale.US,
                            "View with ID %d is not a Button", buttonId),
                    buttonRef[0] instanceof Button);
            scenario.onActivity(activity -> {
                buttonRef[0].performClick();
            });
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * Press the system &ldquo;Back&rdquo; button.
     */
    public static void pressBackButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pressBack();
        } else {
            InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * Return the content of a text view.  The text view must exist
     * within the activity content.
     *
     * @param scenario the scenario in which the test is running
     * @param viewName the name of the text view to use for any assertion error
     * @param fieldId the resource ID of the text view
     *
     * @return the text of the UI element
     *
     * @throws AssertionError if the given field is missing
     */
    public static <T extends Activity> String getElementText(
            ActivityScenario<T> scenario,
            String viewName, int fieldId) {
        final View[] viewRef = new View[1];
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            onView(withId(fieldId))
                    .check((view, noViewFoundException) -> {
                        viewRef[0] = view;
                    });
        } else {
            scenario.onActivity(activity -> {
                viewRef[0] = activity.findViewById(fieldId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        assertNotNull(String.format(Locale.US,
                "%s is missing", viewName), viewRef[0]);
        assertTrue(String.format(Locale.US,
                        "%s with ID %d is not a TextView", viewName, fieldId),
                viewRef[0] instanceof TextView);
        return ((TextView) viewRef[0]).getText().toString();
    }

    /**
     * Change the content of an edit text field.  The field must exist
     * within the activity content.  This is the Espresso version.
     *
     * @param fieldId the resource ID of the edit text field
     * @param newText the text to set in the edit text field
     */
    private static void esSetEditText(int fieldId, final String newText) {
        onView(withId(fieldId))
                .check(matches(isAssignableFrom(EditText.class)))
                .perform(scrollTo(),
                        requestFocus(),
                        replaceText(newText),
                        closeSoftKeyboard());
    }

    /**
     * Change the content of an edit text field.  The field must exist
     * within the activity content.
     *
     * @param scenario the scenario in which the test is running
     * @param fieldName the name of the edit text field to use
     * for any assertion error
     * @param fieldId the resource ID of the edit text field
     * @param newText the text to set in the edit text field
     *
     * @throws AssertionError if the given field is missing
     */
    public static <T extends Activity> void setEditText(
            ActivityScenario<T> scenario,
            String fieldName, int fieldId, final String newText) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            esSetEditText(fieldId, newText);
        } else {
            final View[] viewRef = new View[1];
            scenario.onActivity(activity -> {
                viewRef[0] = activity.findViewById(fieldId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNotNull(String.format(Locale.US,
                    "%s is missing", fieldName), viewRef[0]);
            assertTrue(String.format(Locale.US,
                "%s with ID %d is not an EditText", fieldName, fieldId),
                    viewRef[0] instanceof EditText);
            scenario.onActivity(activity -> {
                viewRef[0].requestFocus();
                ((EditText) viewRef[0]).setText(newText);
            });
        }
    }

    /**
     * Return the state of a check box.  The check box must exist
     * within the activity content.  This is the Espresso-only version.
     *
     * @param fieldId the resource ID of the check box
     *
     * @return whether the box is checked
     *
     * @throws AssertionError if the given field is missing or is not a
     * {@link CheckBox}
     */
    private static boolean esGetCheckboxState(int fieldId) {
        final boolean[] state = new boolean[1];
        onView(withId(fieldId))
                .check(matches(isAssignableFrom(CheckBox.class)))
                .check((view, noViewFoundException) -> {
                    state[0] = ((CheckBox) view).isChecked();
                });
        return state[0];
    }

    /**
     * Return the state of the given check box.  This is the
     * non-Espresso version; the caller is responsible for obtaining
     * the check box&rsquo; {@link View}.
     *
     * @param view the view of the check box (will be checked for {@code null}
     * @param viewName the name of the check box to use for any assertion error
     * @param fieldId the resource ID of the check box for any assertion error
     *
     * @return whether the box is checked
     *
     * @throws AssertionError if the {@code view} is {@code null} or
     * is not a {@link CheckBox}.
     */
    private static boolean getCheckboxState(
            View view, String viewName, int fieldId) {
        assertNotNull(String.format(Locale.US,
                "Checkbox %s with resource ID %d was not found",
                viewName, fieldId), view);
        assertTrue(String.format(Locale.US,
                "%s with ID %d is not a CheckBox", viewName, fieldId),
                view instanceof CheckBox);
        return ((CheckBox) view).isChecked();
    }

    /**
     * Return the state of a check box.  The check box must exist
     * within the activity content.
     *
     * @param scenario the scenario in which the test is running
     * @param viewName the name of the check box to use for any assertion error
     * @param fieldId the resource ID of the check box
     *
     * @return whether the box is checked
     *
     * @throws AssertionError if the given field is missing
     */
    public static <T extends Activity> boolean getCheckboxState(
            ActivityScenario<T> scenario,
            String viewName, int fieldId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return esGetCheckboxState(fieldId);
        } else {
            final View[] viewRef = new View[1];
            scenario.onActivity(activity -> {
                viewRef[0] = activity.findViewById(fieldId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            return getCheckboxState(viewRef[0], viewName, fieldId);
        }
    }

    /**
     * Return the state of a check box within a dialog.
     *
     * @param scenario the scenario in which the test is running
     * @param dialog the {@link Dialog} containing the check box.
     * @param viewName the name of the check box to use for any assertion error
     * @param fieldId the resource ID of the check box
     *
     * @return the text of the UI element
     *
     * @throws AssertionError if the given field is missing
     */
    public static <T extends Activity> boolean getCheckboxState(
            ActivityScenario<T> scenario,
            @NonNull final Dialog dialog,
            String viewName, int fieldId) {
        final boolean[] state = new boolean[1];
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return esGetCheckboxState(fieldId);
        } else {
            final View[] viewRef = new View[1];
            scenario.onActivity(activity -> {
                viewRef[0] = dialog.findViewById(fieldId);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            return getCheckboxState(viewRef[0], viewName, fieldId);
        }
    }

}
