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
package com.xmission.trevin.android.notes.service;

/**
 * Interface for classes that can be called to update a progress bar.
 */
public interface ProgressBarUpdater {

    /**
     * The key of the progress data that holds the current mode
     * of operation
     */
    public static final String PROGRESS_CURRENT_MODE = "ProgressCurrentMode";
    /**
     * The key of the progress data that holds the
     * total number of categories or items to be imported
     */
    public static final String PROGRESS_MAX_COUNT = "ProgressMaxCount";
    /**
     * The key of the progress data that holds the number of
     * categories or items that have been imported so far
     */
    public static final String PROGRESS_CURRENT_COUNT = "ProgressCurrentCount";

    /**
     * Update the progress indicator.
     *
     * @param modeString the current mode of operation, which
     *                  may be displayed as text on the meter.
     * @param currentCount the number of items that have been processed
     *                  so far.  Must not exceed {@code totalCount}.
     * @param totalCount the total number of items to be processed.
     *                  Set to 0 if the progress is indeterminate.
     * @param throttle if {@code true}, skip updating the progress
     *                 if it&rsquo;s been less than 250 ms since
     *                 we last posted our progress.
     */
    void updateProgress(String modeString,
                        int currentCount, int totalCount,
                        boolean throttle);

}
