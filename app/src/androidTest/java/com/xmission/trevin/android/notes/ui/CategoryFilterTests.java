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

import com.xmission.trevin.android.notes.provider.MockNoteRepository;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the CategoryFilterAdapter as used by NoteListActivity.
 *
 * @author Trevin Beattie
 */
@LargeTest
public class CategoryFilterTests {

    static Context testContext = null;

    static MockNoteRepository repository = null;

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
        fail("Not yet implemented");
    }

}
