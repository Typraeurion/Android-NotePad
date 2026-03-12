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
package com.xmission.trevin.android.notes.provider;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.xmission.trevin.android.notes.data.NotePreferences;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestPreferencesObserver implements AutoCloseable,
        NotePreferences.OnNotePreferenceChangeListener {

    private static final String LOG_TAG = "TestPrefsObserver";

    private final NotePreferences prefs;
    private CountDownLatch latch;
    private boolean changed = false;

    /**
     * Construct a new observer attached to the singleton
     * {@link NotePreferences} instance for the given preference item.
     * This expects a single change.  Use this in a
     * try-with-resources block.  The observer will remove itself
     * from the {@link NotePreferences} when closed.
     *
     * @param context the context in which the test is running
     * @param preference the preference to observe
     */
    public TestPreferencesObserver(
            @NonNull Context context, String preference) {
        this(context, 1, preference);
    }

    /**
     * Construct a new observer with a specified latch count
     * attached to the singleton {@link NotePreferences} instance
     * for the given set of preference items.  Use this in a
     * try-with-resources block when the test is doing an expected
     * number of changes to the shared preferences.  The observer
     * will remove itself from the {@link NotePreferences} when closed.
     *
     * @param context the context in which the test is running
     * @param initialCount the number of times this observer is expected
     * to be called.
     * @param preferences the preferences to observe
     */
    public TestPreferencesObserver(
            @NonNull Context context, int initialCount,
            String... preferences) {
        prefs = NotePreferences.getInstance(context);
        latch = new CountDownLatch(initialCount);
        prefs.registerOnNotePreferenceChangeListener(this, preferences);
    }

    @Override
    public void onNotePreferenceChanged(NotePreferences prefs) {
        changed = true;
        latch.countDown();
    }

    /**
     * Wait for the latch to show the observer was notified.
     * Times out after a second.  This is called internally
     * before every {@code assert}&hellip; method; test cases
     * only need to call it if an exception prevents them
     * from checking the observer before unregistering it.
     *
     * @throws AssertionError if the latch times out
     * @throws RuntimeException (wrapping an {@link InterruptedException})
     * if the wait was interrupted
     */
    private void await() throws AssertionError {
        Log.d(LOG_TAG, String.format(Locale.US,
                ".await(1 second): latch count=%d, changed=%s",
                latch.getCount(), changed));
        try {
            assertTrue("Timed out waiting for the observer to be notified",
                    latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting"
                    + " for the observer to be notified", e);
        }
    }

    /**
     * Wait for the latch; times out in a quarter second.
     * This call does not throw any exception if the latch
     * times out.  It&rsquo;s intended to ensure synchronization
     * of any outstanding notifications prior to unregistering
     * the observer during test case clean-up.
     */
    public void waitToClear() {
        if (latch.getCount() > 0)
            Log.d(LOG_TAG, "Waiting 250ms to clear the latch");
        try {
            latch.await(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onNotePreferenceChanged(NotePreferences)} was called.
     * This uses a default assertion message.
     */
    public void assertChanged() {
        assertChanged("Preferences observer onChanged was not called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onNotePreferenceChanged(NotePreferences)} was called
     * using the given assertion message.
     *
     * @param message the message to show if
     * {@link #onNotePreferenceChanged(NotePreferences)} was not called.
     */
    public void assertChanged(String message) {
        await();
        assertTrue(message, changed);
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onNotePreferenceChanged(NotePreferences)} was <i>not</i>
     * called.  This uses a default assertion message.
     */
    public void assertNotChanged() {
        assertNotChanged("Preferences observer onChanged was called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onNotePreferenceChanged(NotePreferences)} was <i>not</i>
     * called using the given assertion message.
     *
     * @param message the message to show if
     * {@link #onNotePreferenceChanged(NotePreferences)} was called.
     */
    public void assertNotChanged(String message) {
        waitToClear();
        assertFalse(message, changed);
    }

    /**
     * Close this observer, which detaches it from the
     * {@link NotePreferences}.
     */
    public void close() {
        waitToClear();
        prefs.unregisterOnNotePreferenceChangeListener(this);
    }

}
