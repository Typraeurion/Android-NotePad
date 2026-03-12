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

import android.database.DataSetObserver;
import android.util.Log;
import android.widget.Adapter;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.Closeable;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test observer which records which observer method was called.
 * Since observers run on the UI thread while repository calls
 * must be done on a non-UI thread, the observer includes a
 * synchronization mechanism: a {@link CountDownLatch} with a
 * count of 1, on the presumption that the observer will be
 * called just once before the test case checks its status.
 */
public class TestObserver extends DataSetObserver
        implements AutoCloseable, Closeable {

    private static final String LOG_TAG = "TestObserver";

    private Adapter attachedAdapter = null;
    private NoteRepository attachedRepository = null;
    private CountDownLatch latch;
    private boolean changed = false;
    private boolean invalidated = false;

    /**
     * Construct a new observer with an initial count of 1,
     * attached to a given {@link Adapter}.  Use this in a
     * try-with-resources block when the test is only doing
     * a single change to the data set.  The observer will
     * remove itself from the adapter when closed.
     *
     * @param adapter the {@link Adapter} to observe
     */
    public TestObserver(Adapter adapter) {
        attachedAdapter = adapter;
        latch = new CountDownLatch(1);
        adapter.registerDataSetObserver(this);
    }

    /**
     * Construct a new observer with a specified latch count,
     * attached to a given {@link Adapter}.  Use this in a
     * try-with-resources block when the test is doing multiple
     * changes to the data set.  The observer will
     * remove itself from the adapter when closed.
     *
     * @param adapter the {@link Adapter} to observe
     * @param initialCount the number of times this observer is expected
     * to be called.
     */
    public TestObserver(Adapter adapter, int initialCount) {
        attachedAdapter = adapter;
        latch = new CountDownLatch(initialCount);
        adapter.registerDataSetObserver(this);
    }

    /**
     * Construct a new observer with an initial count of 1,
     * attached to a given {@link NoteRepository}.  Use this in a
     * try-with-resources block when the test is only doing
     * a single change to the data set.  The observer will
     * remove itself from the adapter when closed.
     *
     * @param repository the {@link NoteRepository} to observe
     */
    public TestObserver(NoteRepository repository) {
        attachedRepository = repository;
        latch = new CountDownLatch(1);
        repository.registerDataSetObserver(this);
    }

    /**
     * Construct a new observer with a specified latch count,
     * attached to a given {@link NoteRepository}.  Use this in a
     * try-with-resources block when the test is doing multiple
     * changes to the data set.  The observer will
     * remove itself from the adapter when closed.
     *
     * @param repository the {@link NoteRepository} to observe
     * @param initialCount the number of times this observer is expected
     * to be called.
     */
    public TestObserver(NoteRepository repository, int initialCount) {
        attachedRepository = repository;
        latch = new CountDownLatch(initialCount);
        repository.registerDataSetObserver(this);
    }

    @Override
    public void onChanged() {
        Log.d(LOG_TAG, ".onChanged()");
        changed = true;
        latch.countDown();
    }
    @Override
    public void onInvalidated() {
        Log.d(LOG_TAG, ".onInvalidated()");
        invalidated = true;
        latch.countDown();
    }

    /**
     * Reset this observer.  This uses the {@link InstrumentationRegistry}
     * to wait for the application to idle then starts a new countdown latch
     * with a count of 1.
     */
    public void reset() {
        reset(1);
    }

    /**
     * Reset this observer.  This uses the {@link InstrumentationRegistry}
     * to wait for the application to idle then starts a new countdown latch.
     *
     * @param newCount the number of times this observer is expected
     * to be called.
     */
    public void reset(int newCount) {
        Log.d(LOG_TAG, String.format(Locale.US, ".reset(%d)", newCount));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        changed = false;
        invalidated = false;
        latch = new CountDownLatch(newCount);
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
                ".await(1 second): latch count=%d, changed=%s, invalidated=%s",
                latch.getCount(), changed, invalidated));
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
     * {@link #onChanged()} was called.  This uses a default
     * assertion message.
     */
    public void assertChanged() {
        assertChanged("Data observer onChanged was not called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onChanged()} was called using the given
     * assertion message.
     *
     * @param message the message to show if {@link #onChanged()}
     * was not called.
     */
    public void assertChanged(String message) {
        await();
        assertTrue(message, changed);
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onChanged()} was <i>not</i> called.  This uses
     * a default assertion message.
     */
    public void assertNotChanged() {
        assertNotChanged("Data observer onChanged was called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onChanged()} was <i>not</i> called using the given
     * assertion message.
     *
     * @param message the message to show if {@link #onChanged()}
     * was called.
     */
    public void assertNotChanged(String message) {
        waitToClear();
        assertFalse(message, changed);
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onInvalidated()} was called.  This uses a default
     * assertion message.
     */
    public void assertInvalidated() {
        assertInvalidated("Data observer onInvalidated was not called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onInvalidated()} was called using the given
     * assertion message.
     *
     * @param message the message to show if {@link #onInvalidated()}
     * was not called.
     */
    public void assertInvalidated(String message) {
        await();
        assertTrue(message, invalidated);
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onInvalidated()} was <i>not</i> called.  This uses
     * a default assertion message.
     */
    public void assertNotInvalidated() {
        assertNotInvalidated("Data observer onInvalidated was called");
    }

    /**
     * Wait for this observer to be notified, then verify that
     * {@link #onInvalidated()} was <i>not</i> called using the given
     * assertion message.
     *
     * @param message the message to show if {@link #onInvalidated()}
     * was called.
     */
    public void assertNotInvalidated(String message) {
        waitToClear();
        assertFalse(message, invalidated);
    }

    /**
     * Close this observer, which detaches it from any attached
     * {@link Adapter} or {@link NoteRepository}.
     */
    public void close() {
        waitToClear();
        if (attachedAdapter != null) {
            attachedAdapter.unregisterDataSetObserver(this);
            attachedAdapter = null;
        }
        if (attachedRepository != null) {
            attachedRepository.unregisterDataSetObserver(this);
            attachedRepository = null;
        }
    }

}
