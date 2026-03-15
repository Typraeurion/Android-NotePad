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

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.EditText;
import android.view.ViewTreeObserver;

import androidx.annotation.RequiresApi;

/**
 * Override the {@link #onScrollChanged} method of the standard
 * {@link EditText} widget since older versions of Android did
 * not call the {@link ViewTreeObserver.OnScrollChangedListener}
 * unless the entire view tree scrolls.
 */
public class ObservableEditText extends EditText {

    /** Capture the scroll changed listener */
    private ViewTreeObserver.OnScrollChangedListener listener = null;

    public ObservableEditText(Context context) {
        super(context);
    }

    public ObservableEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ObservableEditText(Context context, AttributeSet attrs,
                              int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @RequiresApi(21)
    public ObservableEditText(Context context, AttributeSet attrs,
                              int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Set the listener for scroll events.  On older versions of
     * Android (prior to Jellybean), we use this listener directly
     * in {@link #onScrollChanged(int, int, int, int)} calls.
     * On newer versions we pass the listener along to the
     * {@link android.view.ViewTreeObserver}.
     *
     * @param listener the listener to set
     */
    public void setOnScrollChangedListener(
            ViewTreeObserver.OnScrollChangedListener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
            this.listener = listener;
        else
            getViewTreeObserver().addOnScrollChangedListener(listener);
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if ((listener != null) && ((x != oldX) || (y != oldY)))
            listener.onScrollChanged();
    }

}
