/*
 * Copyright © 2011 Trevin Beattie
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

import java.util.Collections;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

import android.app.*;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CompoundButton.OnCheckedChangeListener;

import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.data.NotePreferences;
import com.xmission.trevin.android.notes.provider.NoteSchema.*;
import com.xmission.trevin.android.notes.R;
import com.xmission.trevin.android.notes.util.StringEncryption;

/**
 * The preferences activity manages the user options dialog.
 */
public class PreferencesActivity extends Activity {

    public static final String LOG_TAG = "PreferencesActivity";

    // Constants for converting between view ratio and thumb position,
    // rounded to 8 bits after the decimal.
    /** ln(1/100) &mdash; the smallest supported ratio */
    public static final double LOG_HUNDREDTH = - 4.60546875;
    /** ln(2) &mdash; the largest supported ratio */
    public static final double LOG_TWO = 0.6953125;

    private NotePreferences prefs;

    TextView scrollThresholdText = null;

    /** The global encryption object */
    StringEncryption encryptor;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(LOG_TAG, ".onCreate");

        setContentView(R.layout.preferences);

        prefs = NotePreferences.getInstance(this);

        Spinner spinner = findViewById(R.id.PrefsSpinnerSortBy);
        setSpinnerByID(spinner, prefs.getSortOrder());
        spinner.setOnItemSelectedListener(new SortOrderSpinnerListener());

        CheckBox checkBox = findViewById(R.id.PrefsCheckBoxShowCategory);
        checkBox.setChecked(prefs.showCategory());
        checkBox.setOnCheckedChangeListener(new ShowCategoryChangeListener());

        encryptor = StringEncryption.holdGlobalEncryption();
        checkBox = findViewById(R.id.PrefsCheckBoxShowPrivate);
        checkBox.setChecked(prefs.showPrivate());
        checkBox.setOnCheckedChangeListener(new ShowPrivateChangeListener());

        ScrollBar scrollThresholdScrollBar = findViewById(R.id.PrefsScrollBar);
        // Compute the inverse of the exponential
        // used in the ScrollBarChangeListener
        double ratio = prefs.getScrollBarThreshold();
        double position;
        if (ratio < 0.01)
            position = 0.0;
        else if (ratio > 2.0)
            position = 1.0;
        else
            position = Math.max(0, Math.min(1.0,
                    (Math.log(ratio) - LOG_HUNDREDTH) / (LOG_TWO - LOG_HUNDREDTH)));
        scrollThresholdScrollBar.setPosition(position);
        scrollThresholdScrollBar.registerOnScrollChangeListener(
                new ScrollBarChangeListener());
        scrollThresholdText = findViewById(R.id.PrefsTextScrollbarPages);
        updateScrollThreshold(ratio);
    }

    /** Called when the user selects a sort order */
    private class SortOrderSpinnerListener implements OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Do nothing
        }
        @Override
        public void onItemSelected(AdapterView<?> parent, View child,
                                   int position, long id) {
            Log.d(LOG_TAG, "spinnerSortBy.onItemSelected("
                    + position + "," + id + ")");
            if (position >= NoteItemColumns.USER_SORT_ORDERS.length) {
                Log.e(LOG_TAG, "Unknown sort order selected");
            } else if (position >= 0) {
                prefs.setSortOrder(position);
            }
        }
    }

    /**
     * Called when the user toggles the &ldquo;Show Category&rdquo; checkbox
     */
    private class ShowCategoryChangeListener
            implements OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                @NonNull CompoundButton button, boolean isChecked) {
            Log.d(LOG_TAG, "ShowCategoryChangeListener.onCheckedChanged("
                    + isChecked + ")");
            prefs.setShowCategory(isChecked);
        }
    }

    /**
     * Called when the user toggles the &ldquo;Show Private&rdquo; checkbox
     */
    private class ShowPrivateChangeListener
            implements OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(
                @NonNull CompoundButton button, boolean isChecked) {
            Log.d(LOG_TAG, "ShowPrivateChangeListener.onCheckedChanged("
                    + isChecked + ")");
            prefs.setShowPrivate(isChecked);
        }
    }

    /**
     * Fraction characters.  The n/5 and n/6 characters were not supported
     * in the default font until Lollipop (API 21); on Ice Cream Sandwich
     * (API <= 15) nothing was rendered for them at all, while on Jelly
     * Bean through Kit Kat (API 16-20) there were substituted with
     * full-size "n", "/", and "d" characters.
     */
    private static final SortedMap<Float,String> FRACTION_CHARS;
    static {
        SortedMap<Float,String> m = new TreeMap<>();
        m.put(0.125f, "\u215b");  // 1/8
        m.put(0.25f, "\u00bc");   // 1/4
        m.put(1/3.0f, "\u2153");  // 1/3
        m.put(0.375f,  "\u215c"); // 3/8
        m.put(0.5f, "\u00bd");    // 1/2
        m.put(0.625f, "\u215d");  // 5/8
        m.put(2/3.0f, "\u2154");  // 2/3
        m.put(0.75f, "\u00be");   // 3/4
        m.put(0.875f, "\u215e");  // 7/8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            m.put(1/6.0f, "\u2159"); // 1/6
            m.put(0.2f, "\u2155");   // 1/5
            m.put(0.4f, "\u2156");   // 2/5
            m.put(0.6f, "\u2157");   // 3/5
            m.put(0.8f, "\u2158");   // 4/5
            m.put(5/6.0f, "\u215a"); // 5/6
        }
        FRACTION_CHARS = Collections.unmodifiableSortedMap(m);
    }

    /**
     * Set the text of the scrollbar threshold according to the
     * ratio between the view size and content size.  We try to
     * use rational fractions where practical.
     *
     * @param ratio the ratio between the view size and content size
     */
    private void updateScrollThreshold(double ratio) {
        if (ratio <= 0.0) {
            scrollThresholdText.setText(R.string.PrefTextScrollbarNever);
            return;
        }
        if (ratio > Integer.MAX_VALUE) {
            scrollThresholdText.setText(R.string.PrefTextScrollbarAlways);
            return;
        }
        double pages = 1.0 / ratio;
        int pagesInt = (int) pages;
        float pagesFrac = (float) (pages - pagesInt);
        String rationalCount = (pagesInt == 0) ? ""
                : Integer.toString(pagesInt);
        if (pagesInt < 10) {
            if (pagesFrac < FRACTION_CHARS.firstKey() / 2) {
                if (pagesInt == 0)
                    rationalCount = String.format(
                            Locale.getDefault(), "%.4f", pages);
            } else if (pagesFrac >= (FRACTION_CHARS.lastKey() + 1) / 2) {
                rationalCount = Integer.toString(++pagesInt);
            } else {
                float closestKey = pagesFrac;
                if (!FRACTION_CHARS.containsKey(closestKey)) {
                    if (pagesFrac <= FRACTION_CHARS.firstKey()) {
                        closestKey = FRACTION_CHARS.firstKey();
                    } else if (pagesFrac > FRACTION_CHARS.lastKey()) {
                        closestKey = FRACTION_CHARS.lastKey();
                    } else {
                        float minBound = FRACTION_CHARS.subMap(
                                0.0f, pagesFrac).lastKey();
                        float maxBound = FRACTION_CHARS.subMap(
                                pagesFrac, 1.0f).firstKey();
                        if (pagesFrac - minBound < maxBound - pagesFrac)
                            closestKey = minBound;
                        else
                            closestKey = maxBound;
                    }
                }
                rationalCount += FRACTION_CHARS.get(closestKey);
            }
        }
        String text = getResources().getQuantityString(
                R.plurals.PrefTextScrollbarPages,
                (pages < 1.0625) ? 1 : 2, rationalCount);
        scrollThresholdText.setText(text);
    }

    /**
     * Called when the user moves the scrollbar threshold
     */
    private class ScrollBarChangeListener
            implements ScrollBar.OnScrollBarChangeListener {
        @Override
        public void onScrollBarChange(
                ScrollBar scrollBar, float position, boolean isInFlux) {
//            Log.d(LOG_TAG, String.format(Locale.US,
//                    "ScrollBarChangListener.onScrollBarChange(%f,%s)",
//                    position, isInFlux));
            // Map the position to a view:content ratio
            double ratio = Math.exp(position * (LOG_TWO - LOG_HUNDREDTH)
                    + LOG_HUNDREDTH);
            if (ratio < 0.01)
                // Clip to "Never"
                ratio = 0;
            else if (ratio > 2.0)
                // Clip to "Always"
                ratio = Double.POSITIVE_INFINITY;
            if (!isInFlux)
                prefs.setScrollBarThreshold((float) ratio);
            updateScrollThreshold(ratio);
        }
    }

    /** Called when the user presses the Back button */
    @Override
    public void onBackPressed() {
        Log.d(LOG_TAG, ".onBackPressed()");
        super.onBackPressed();
    }

    /** Called when the activity is about to be destroyed */
    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, ".onDestroy()");
        StringEncryption.releaseGlobalEncryption(this);
        super.onDestroy();
    }

    /** Look up the spinner item corresponding to a category ID and select it. */
    void setSpinnerByID(Spinner spinner, long id) {
        for (int position = 0; position < spinner.getCount(); position++) {
            if (spinner.getItemIdAtPosition(position) == id) {
                spinner.setSelection(position);
                return;
            }
        }
        Log.w(LOG_TAG, "No spinner item found for ID " + id);
        spinner.setSelection(0);
    }

}
