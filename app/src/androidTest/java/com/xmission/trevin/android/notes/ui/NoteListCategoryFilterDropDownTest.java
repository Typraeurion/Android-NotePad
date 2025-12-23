package com.xmission.trevin.android.notes.ui;


import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;

import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class NoteListCategoryFilterDropDownTest {

    @Rule
    public ActivityTestRule<NoteListActivity> mActivityTestRule =
            new ActivityTestRule<>(NoteListActivity.class);

    @Test
    public void noteListCategoryFilterDropDownTest() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            /*
             * --- Modern Android Path (API 34+) ---
             * Espresso 2.x/3.x is broken on these versions.
             * We use raw Instrumentation.
             */
            final NoteListActivity activity = mActivityTestRule.getActivity();
            final Spinner spinner = (Spinner)
                    activity.findViewById(R.id.ListSpinnerCategory);

            assertNotNull("Spinner not found", spinner);
            assertTrue("Spinner not visible", spinner.isShown());

            // Verify the data in the adapter
            SpinnerAdapter adapter = spinner.getAdapter();
            assertNotNull("Spinner adapter is null", adapter);

            assertTrue("Spinner should have at least 3 entries; found "
                    + adapter.getCount(), adapter.getCount() >= 3);

            // Check items by name in the adapter
            assertEquals("All", ((NoteCategory)
                    adapter.getItem(0)).getName());
            assertEquals("Unfiled", ((NoteCategory)
                    adapter.getItem(adapter.getCount() - 2)).getName());

            String lastItemName = ((NoteCategory)
                    adapter.getItem(adapter.getCount() - 1)).getName();
            assertTrue("Last item should be Edit categories",
                    lastItemName.startsWith("Edit categories"));

            // Perform the click via the UI thread to ensure it doesn't crash
            mActivityTestRule.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    spinner.performClick();
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        } else {
            /*
             * --- Legacy Android Path (API < 34) ---
             * Use Espresso for older devices where it works correctly.
             */
            onView(allOf(withId(R.id.ListSpinnerCategory), isDisplayed()))
                    .perform(performClick());

            onView(allOf(withId(android.R.id.text1),
                    withText("All"), isDisplayed()))
                    .check(matches(isDisplayed()));

            onView(allOf(withId(android.R.id.text1),
                    withText("Unfiled"), isDisplayed()))
                    .check(matches(isDisplayed()));

            onView(allOf(withId(android.R.id.text1),
                    withText("Edit categories…"), isDisplayed()))
                    .check(matches(withText("Edit categories…")));
        }
    }

    /**
     * A custom click action that calls view.performClick() directly.
     * This avoids using Espresso's default click action.
     * The InputManager.getInstance() hidden API was
     * removed/restricted in newer Android versions (API 34+).
     */
    private static ViewAction performClick() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "perform click";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.performClick();
            }
        };
    }
}
