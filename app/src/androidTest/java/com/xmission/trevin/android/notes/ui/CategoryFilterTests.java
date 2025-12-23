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

import static org.junit.Assert.*;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.data.NoteCategory;
import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.MockNoteRepository;
import com.xmission.trevin.android.notes.provider.NoteRepositoryTests.TestObserver;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;

/**
 * Tests for the CategoryFilterAdapter as used by NoteListActivity.
 *
 * @author Trevin Beattie
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class CategoryFilterTests {

    static Context testContext = null;

    static MockNoteRepository repository = null;

    static final Random RAND = new Random();

    @BeforeClass
    public static void initializeRepository() {
        testContext = InstrumentationRegistry.getTargetContext();
        repository = (MockNoteRepository) MockNoteRepository.getInstance();
        repository.open(testContext);
    }

    @Before
    public void clearData() {
        repository.clear();
    }

    @AfterClass
    public static void releaseRepository() {
        repository.release(testContext);
    }

    /**
     * When using a clean database, the category filter should contain
     * three entries: &ldquo;All&rdquo;, &ldquo;Unfiled&rdquo;, and
     * &ldquo;Edit categories&hellip;&rdquo;.
     */
    @Test
    public void testDefaultFilterSet() {
        CategoryFilterAdapter adapter =
                new CategoryFilterAdapter(testContext, repository);
        assertEquals(3, adapter.getCount());

        NoteCategory expectedCategory = new NoteCategory();
        expectedCategory.setId(NotePreferences.ALL_CATEGORIES);
        expectedCategory.setName(testContext.getString(R.string.Category_All));
        assertEquals("All categories entry", expectedCategory,
                adapter.getItem(0));

        expectedCategory.setId(NoteCategory.UNFILED);
        expectedCategory.setName(testContext.getString(R.string.Category_Unfiled));
        assertEquals("Unfiled category entry", expectedCategory,
                adapter.getItem(1));

        expectedCategory.setId(NotePreferences.ALL_CATEGORIES - 1);
        expectedCategory.setName(testContext.getString(R.string.Category_Edit));
        assertEquals("Edit categories entry", expectedCategory,
                adapter.getItem(2));
    }

    /**
     * Generate a random category name.  The first letter of the name will be
     * in the range specified by the arguments; the rest will be lower case.
     *
     * @param minLetter the earliest the first letter may be
     * @param maxLetter the latest the first letter may be
     *
     * @return the category name
     */
    public static String randomCategoryName(char minLetter, char maxLetter) {
        char firstLetter = (char) (minLetter +
                RAND.nextInt(maxLetter - minLetter + 1));
        int targetLen = RAND.nextInt(6) + 5;
        String rest = RandomStringUtils.randomAlphabetic(targetLen)
                .toLowerCase(Locale.US);
        return firstLetter + rest;
    }

    /**
     * When the database contains user-defined categories, the category
     * filter should contain these categories in alphabetical order
     * between the &ldquo;All&rdquo; and &ldquo;Edit categories&hellip;&rdquo;
     * entries, with &ldquo;Unfiled&rdquo; in the proper order.
     */
    @Test
    public void testUserCategories() {
        CategoryFilterAdapter adapter =
                new CategoryFilterAdapter(testContext, repository);

        List<NoteCategory> testCategories = new ArrayList<>();
        NoteCategory expectedCategory = new NoteCategory();
        expectedCategory.setId(NoteCategory.UNFILED);
        expectedCategory.setName(
                testContext.getString(R.string.Category_Unfiled));
        testCategories.add(expectedCategory);

        expectedCategory = repository.insertCategory(
                randomCategoryName('A', 'T'));
        testCategories.add(expectedCategory);

        expectedCategory =
                repository.insertCategory(randomCategoryName('V', 'Z'));
        testCategories.add(expectedCategory);

        expectedCategory =
                repository.insertCategory(randomCategoryName('A', 'Z'));
        testCategories.add(expectedCategory);

        Collections.sort(testCategories, new Comparator<NoteCategory>() {
            @Override
            public int compare(NoteCategory c1, NoteCategory c2) {
                String s1 = c1.getName() == null ? "" : c1.getName();
                String s2 = c2.getName() == null ? "" : c2.getName();
                return s1.compareTo(s2);
            }
        });

        assertEquals(testCategories.size() + 2, adapter.getCount());

        expectedCategory = new NoteCategory();
        expectedCategory.setId(NotePreferences.ALL_CATEGORIES);
        expectedCategory.setName(testContext.getString(R.string.Category_All));
        assertEquals("All categories entry", expectedCategory,
                adapter.getItem(0));

        for (int i = 0; i < testCategories.size(); i++) {
            expectedCategory.setId(testCategories.get(i).getId());
            expectedCategory.setName(testCategories.get(i).getName());
            assertEquals("Database category entry for position " + (i + 1),
                    testCategories.get(i), adapter.getItem(i + 1));
        }

        expectedCategory.setId(NotePreferences.ALL_CATEGORIES - 1);
        expectedCategory.setName(testContext.getString(R.string.Category_Edit));
        assertEquals("Edit category entry", expectedCategory,
                adapter.getItem(testCategories.size() + 1));
    }

    /**
     * When a new category is added to the database, any
     * data observers on the adapter should be notified.
     */
    @Test
    public void testObserveInsertCategory() {
        CategoryFilterAdapter adapter =
                new CategoryFilterAdapter(testContext, repository);
        TestObserver observer = new TestObserver();
        adapter.registerDataSetObserver(observer);
        repository.insertCategory(randomCategoryName('A', 'Z'));
        observer.assertChanged();
    }

    /**
     * When an existing category is modified in the database,
     * any data observers on the adapter should be notified.
     */
    @Test
    public void testObserveUpdateCategory() {
        CategoryFilterAdapter adapter =
                new CategoryFilterAdapter(testContext, repository);
        NoteCategory userCategory = repository.insertCategory(
                randomCategoryName('A', 'Z'));
        TestObserver observer = new TestObserver();
        adapter.registerDataSetObserver(observer);
        repository.updateCategory(userCategory.getId(),
                randomCategoryName('A', 'Z'));
        observer.assertChanged();
    }

    /**
     * When a category is deleted from the database, any
     * data observers on the adapter should be notified.
     */
    @Test
    public void testObserveDeleteCategory() {
        CategoryFilterAdapter adapter =
                new CategoryFilterAdapter(testContext, repository);
        NoteCategory userCategory = repository.insertCategory(
                randomCategoryName('A', 'Z'));
        TestObserver observer = new TestObserver();
        adapter.registerDataSetObserver(observer);
        repository.deleteCategory(userCategory.getId());
        observer.assertChanged();
    }

}
