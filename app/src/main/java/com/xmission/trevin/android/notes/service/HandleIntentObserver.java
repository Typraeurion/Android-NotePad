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
package com.xmission.trevin.android.notes.service;

/**
 * Interface for observers which should be called when a service@rsquo;s
 * {@code handleIntent} method has finished.  This is meant as a temporary
 * workaround for synchronization issues with test code since the service
 * handles intents on an asynchronous worker thread.
 * <p>
 * Ultimately the &ldquo;service&rdquo; classes ought to be replaced
 * with simple {@code Runnable}s that can be called without going through
 * Android&rsquo;s services layer.  Services are meant for long-running
 * operations without a user interface but which may have inter-process
 * communication, not for one-shot asynchronous work.
 * </p>
 */
public interface HandleIntentObserver {

    /** Called when the intent completes successfully */
    void onComplete();

    /**
     * Called if the intent refuses to process the work
     * or otherwise terminates before finishing the job.
     * This is still a normal return; the service does
     * not throw an exception.
     */
    void onRejected();

    /**
     * Called when the service shows a {@code Toast} on the UI thread
     *
     * @param message the message of the {@code Toast}
     */
    void onToast(String message);

    /**
     * Called when {@code handleIntent} throws an exception
     *
     * @param e the exception that was thrown
     */
    void onError(Exception e);

}
