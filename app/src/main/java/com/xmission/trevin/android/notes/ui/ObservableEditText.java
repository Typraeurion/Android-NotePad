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

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;
import android.view.ViewTreeObserver;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * Override the {@link #onScrollChanged} method of the standard
 * {@link EditText} widget to deliver scroll callbacks synchronously
 * and uniformly on all API levels.
 * <p>
 * {@link android.view.ViewTreeObserver.OnScrollChangedListener} is not
 * used here even on Jelly Bean and later, because the
 * {@link android.view.ViewTreeObserver} dispatches its callbacks
 * asynchronously on the next traversal pass rather than synchronously
 * during a {@link #scrollTo} call.  Calling the listener directly from
 * {@link #onScrollChanged} avoids that latency and ensures that any
 * downstream updates (e.g. a scroll bar thumb) are always in sync with
 * the view&rsquo;s actual scroll position.
 * </p>
 */
public class ObservableEditText extends AppCompatEditText {

    /** The registered scroll changed listener, if any */
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

    /**
     * Set the listener for scroll events.  The listener is called
     * directly from {@link #onScrollChanged(int, int, int, int)}
     * so that callbacks are synchronous on all API levels.
     *
     * @param listener the listener to set
     */
    public void setOnScrollChangedListener(
            ViewTreeObserver.OnScrollChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if ((listener != null) && ((x != oldX) || (y != oldY)))
            listener.onScrollChanged();
    }

}
