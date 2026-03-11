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
package com.xmission.trevin.android.notes.ui;

import static com.xmission.trevin.android.notes.ui.CategoryFilterTests.randomCategoryName;
import static com.xmission.trevin.android.notes.util.ViewActionUtils.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.widget.Spinner;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.MockSharedPreferences;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NoteItem;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryImpl;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class NoteListCategoryFilterDropDownTest {

    private static Instrumentation instrument = null;
    private Context testContext = null;
    private static MockSharedPreferences mockPrefs = null;
    private static MockNoteRepository mockRepo = null;

    static final Random RAND = new Random();

    @BeforeClass
    public static void initializeMocks() {
        if (mockRepo == null) {
            mockRepo = MockNoteRepository.getInstance();
            NoteRepositoryImpl.setInstance(mockRepo);
        }
        mockPrefs = new MockSharedPreferences();
        NotePreferences.setSharedPreferences(mockPrefs);
        instrument = InstrumentationRegistry.getInstrumentation();
    }

    @Before
    public void initializeRepository() {
        testContext = instrument.getTargetContext();
        mockPrefs.resetMock();
        mockRepo.open(testContext);
        mockRepo.clear();
    }

    @After
    public void releaseRepository() {
        mockRepo.release(testContext);
    }

    @Test
    public void noteListCategoryFilterDropDownTest() throws Throwable {
        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            CategoryFilterAdapter[] adapter = new CategoryFilterAdapter[1];
            wrapper.onActivity(activity -> {
                adapter[0] = activity.categoryAdapter;
            });
            assertNotNull("Category filter adapter has not been set",
                    adapter[0]);
            pressButton(wrapper.getScenario(), R.id.ListSpinnerCategory);

            // Verify the data in the adapter
            assertTrue(String.format(Locale.US,
                    "Spinner should have at least 3 entries; found %d",
                    adapter[0].getCount()), adapter[0].getCount() >= 3);

            // Check items by name in the adapter
            assertEquals("All",
                    adapter[0].getItem(0).getName());
            assertEquals("Unfiled",
                    adapter[0].getItem(adapter[0].getCount() - 2).getName());

            String lastItemName =
                    adapter[0].getItem(adapter[0].getCount() - 1).getName();
            assertTrue("Last item should be Edit categories",
                    lastItemName.startsWith("Edit categories"));
        }
    }

    /**
     * When the note list activity is starting up from scratch,
     * verify that the selected category from the preferences is
     * retained.  The activity needs to set this after the adapter
     * has been loaded from the database on a non-UI thread.
     */
    @Test
    public void testPreserveSelectedCategory() {
        List<NoteCategory> testCategories = new ArrayList<>();
        for (int i = RAND.nextInt(3) + 7; i >= 0; --i) {
            NoteCategory category = mockRepo.insertCategory(
                    randomCategoryName('A', 'Z'));
            testCategories.add(category);
            NoteItem note = new NoteItem();
            note.setCategoryId(category.getId());
            note.setNote(category.getName());
            mockRepo.insertNote(note);
        }
        NoteCategory targetCategory = testCategories.get(
                RAND.nextInt(testCategories.size()));
        NotePreferences prefs = NotePreferences.getInstance(testContext);
        prefs.setSelectedCategory(targetCategory.getId());

        try (ActivityScenarioResultsWrapper<NoteListActivity> wrapper =
                ActivityScenarioResultsWrapper.launch(NoteListActivity.class)) {
            final Spinner[] spinner = new Spinner[1];
            CategoryFilterAdapter[] adapter = new CategoryFilterAdapter[1];
            wrapper.onActivity(activity -> {
                spinner[0] = activity.categoryList;
                adapter[0] = activity.categoryAdapter;
            });
            assertNotNull("Category filter adapter has not been set",
                    adapter[0]);
            long timeLimit = System.nanoTime() + 5000000000L;
            while (adapter[0].getCount() < 3) {
                // The adapter always has its "All Categories" and
                // "Edit Categories" items; we need to wait for more...
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertFalse("Timed out waiting for the adapter to be populated",
                        System.nanoTime() > timeLimit);
                try {
                    Thread.sleep(128);
                } catch (InterruptedException ix) {
                    // Ignore
                }
            }
            /*
             * Once the adapter has been loaded, the activity then needs to
             * update the list query which will result in another call to
             * the repository.  But we don't need to wait for all of this
             * to make its way back to the main list.
             */
            assertEquals("Selected category ID",
                    targetCategory.getId().longValue(),
                    spinner[0].getSelectedItemId());
        }
    }

}
