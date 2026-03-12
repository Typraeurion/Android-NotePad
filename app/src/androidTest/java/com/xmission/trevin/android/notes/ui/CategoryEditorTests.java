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
package com.xmission.trevin.android.notes.ui;

import static com.xmission.trevin.android.notes.ui.CategoryFilterTests.randomCategoryName;
import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.data.NoteCategory;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;

/**
 * Tests for the {@link CategoryEditorAdapter}
 * as used by {@link CategoryListActivity}
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class CategoryEditorTests {

    static Instrumentation instrument = null;

    static Context testContext = null;

    static final Random RAND = new Random();

    /** Compare categories by name (case-insensitive) */
    public static final Comparator<NoteCategory> CATEGORY_NAME_COMPARATOR
            = new Comparator<NoteCategory>() {
        @Override
        public int compare(NoteCategory c1, NoteCategory c2) {
            return c1.getName().compareTo(c2.getName());
        }
    };

    /**
     * Generate a list of random category names.
     *
     * @return the categories in alphabetical order
     */
    public static List<NoteCategory> getRandomCategoryList() {
        List<NoteCategory> categories = new ArrayList<>();
        for (int i = RAND.nextInt(4) + 4; i >= 0; --i) {
            NoteCategory category = new NoteCategory();
            category.setId(categories.size() + 1);
            category.setName(randomCategoryName('A', 'Z'));
            categories.add(category);
        }
        Collections.sort(categories, CATEGORY_NAME_COMPARATOR);
        return categories;
    }

    @BeforeClass
    public static void getTestContext() {
        instrument = InstrumentationRegistry.getInstrumentation();
        testContext = instrument.getTargetContext();
    }

    private static void runOnUiAndWait(Runnable runnable) {
        instrument.runOnMainSync(runnable);
        instrument.waitForIdleSync();
    }

    @Test
    public void testEditCategory() {
        final List<NoteCategory> categories = getRandomCategoryList();
        final CategoryEditorAdapter adapter =
                new CategoryEditorAdapter(testContext, categories);
        final int targetCategory = RAND.nextInt(adapter.getCount());
        final EditText[] textBoxHolder = new EditText[1];
        instrument.runOnMainSync(() -> {
            textBoxHolder[0] = (EditText) adapter.getView(
                    targetCategory, null, null);
        });
        runOnUiAndWait(() -> textBoxHolder[0].requestFocus());
        NoteCategory oldCategory = categories.get(targetCategory).clone();
        String expectedName = randomCategoryName('A', 'Z');
        runOnUiAndWait(() -> textBoxHolder[0].setText(expectedName));

        // Changing the text alone shouldn't change the category ... yet
        NoteCategory actualCategory = adapter.getItem(targetCategory);
        assertEquals("Category after edit; still focused",
                oldCategory, actualCategory);

        // After focus changes, the category should have changed
        runOnUiAndWait(() -> textBoxHolder[0].clearFocus());
        NoteCategory expectedCategory = oldCategory.clone();
        expectedCategory.setName(expectedName);
        actualCategory = adapter.getItem(targetCategory);
        assertEquals("Category after focus change",
                expectedCategory, actualCategory);
    }

}
