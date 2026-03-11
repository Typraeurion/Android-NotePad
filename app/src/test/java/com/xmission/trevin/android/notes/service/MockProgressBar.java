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

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A class for collecting progress data while testing services.
 *
 * @author Trevin Beattie
 */
public class MockProgressBar implements ProgressBarUpdater {

    /**
     * A tuplet of progress data
     */
    public static class Progress {
        public final String mode;
        public final int current;
        public final int total;
        public Progress(String mode, int current, int total) {
            this.mode = mode;
            this.current = current;
            this.total = total;
        }
    }

    /**
     * Progress data that has been collected over the lifetime of this
     * updater.  Keyed by system time.
     */
    private final SortedMap<Long,Progress> records = new TreeMap<>();

    /** Start time; set on creation. */
    private final long startTime = System.nanoTime();

    /** The time we last added a progress record; used for throttling */
    private long lastRecordTime = 0;

    /** End time; set on demand. */
    private long endTime;

    @Override
    public void updateProgress(String modeString,
                               int currentCount, int totalCount,
                               boolean throttle) {
        long now = System.nanoTime();
        if (throttle && (now < lastRecordTime + 100000000))
            return;
        records.put(now, new Progress(modeString, currentCount, totalCount));
        lastRecordTime = now;
    }

    /** Set the end time now */
    public void setEndTime() {
        endTime = System.nanoTime();
    }

    /**
     * @return the first progress record, or
     * {@code null} if this updater was never called
     */
    public Progress getStartProgress() {
        if (records.isEmpty())
            return null;
        return records.firstEntry().getValue();
    }

    /**
     * @return the last progress record, or
     * {@code null} if this updater was never called
     */
    public Progress getEndProgress() {
        if (records.isEmpty())
            return null;
        return records.lastEntry().getValue();
    }

    /**
     * @param modeString the mode of operation whose progress to look for
     *
     * @return all progress records for the given mode; may be empty
     */
    public List<Progress> getProgressFor(String modeString) {
        List<Progress> result = new java.util.ArrayList<>();
        for (Progress p : records.values())
            if (p.mode.equals(modeString))
                result.add(p);
        return result;
    }

    /**
     * @return the elapsed time in seconds
     */
    public double getElapsedTime() {
        return (((endTime == 0) ? System.nanoTime()
                : endTime) - startTime) / 1.0e+9;
    }

}
