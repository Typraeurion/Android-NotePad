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
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.widget.*;

import androidx.annotation.NonNull;

import com.xmission.trevin.android.notes.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A view for presenting a scroll bar representing the viewable size
 * and position of a {@link ScrollView} relative to its total content.
 * <p>
 * {@link ScrollBar}s support the following attributes:
 * <table>
 *     <thead>
 *         <tr><th>Name</th><th>Description</th></tr>
 *     </thead>
 *     <tbody>
 *         <tr>
 *             <th style="width: 20%"><tt>android:orientation</tt></th>
 *             <td>Either <tt>horizontal</tt> (the default)
 *             or <tt>vertical</tt>.</td>
 *         </tr>
 *         <tr>
 *             <th><tt>thumbType</tt></th>
 *             <td>Either <tt>fixed</tt> or <tt>scaled</tt>
 *             (the default).  Fixed-length thumbs are set
 *             to the minimum length and the scroll position
 *             ranges from 0 through {@code contentSize};
 *             the {@code viewSize} setting is ignored.
 *             Scaled thumbs vary in length according to the
 *             ratio between {@code viewSize} and {@code totalSize}
 *             and the scroll position is limited to {@code totalSize
 *             - viewSize}.</td>
 *         </tr>
 *         <tr>
 *             <th><tt>android:enabled</tt></th>
 *             <td>Whether this scroll bar is interactive; default
 *             is <tt>true</tt>.  If set to <tt>false</tt>,
 *             the user will not be able to move it and it can
 *             only be moved and sized programatically.</td>
 *         </tr>
 *         <tr>
 *             <th><tt>contentSize</tt></th>
 *             <td>The initial size of the content represented
 *             by this scroll bar.</td>
 *         </tr>
 *         <tr>
 *             <th><tt>viewSize</tt></th>
 *             <td>The initial size of the view represented by
 *             the thumb of this scroll bar.</td>
 *         </tr>
 *         <tr>
 *             <th><tt>android:thumbPosition</tt></th>
 *             <td>The initial position of the thumb.  Default is 0.
 *             This will be clipped to a valid range according to
 *             the {@code thumbType}.</td>
 *         </tr>
 *     </tbody>
 * </table>
 * </p>
 *
 * @author Trevin Beattie
 */
public class ScrollBar extends FrameLayout {

    private static final String LOG_TAG = "ScrollBar";

    /** The callback used to indicate the user has moved the scroll bar */
    public interface OnScrollChangeListener {
        /**
         * Handle a change to the scroll bar position
         *
         * @param view the {@link ScrollBar} view whose position has changed
         * @param position the position of the scrollbar thumb in relation
         * to its total length, ranging from 0 (inclusive) to
         * {@code totalSize} (exclusive for a {@link ThumbType#SCALED SCALED}
         * thumb, inclusive for a {@link ThumbType#FIXED FIXED} thumb).
         */
        void onScrollChange(ScrollBar view, float position);
    }

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    public enum ThumbType {
        /**
         * Provide a fixed-size thumb which selects a value from 0
         * at the minimum end of the bar to the total size at the
         * maximum end.
         */
        FIXED,
        /**
         * Provide a scaled thumb whose size is proportional to the
         * view relative to the full content.  It selects a value
         * from 0 to the total size minus the view size at the
         * maximum end.
         */
        SCALED
    }

    /** Container for this widget&rsquo;s state */
    private static class ScrollBarState extends BaseSavedState {
        private final Orientation orientation;
        private final ThumbType thumbType;
        private final double totalSize;
        private final double viewSize;
        private final double position;

        /**
         * Constructor called from {@link ScrollBar#onSaveInstanceState()}
         */
        private ScrollBarState(Parcelable superState, ScrollBar scrollBar) {
            super(superState);
            orientation = scrollBar.orientation;
            thumbType = scrollBar.thumbType;
            totalSize = scrollBar.totalSize;
            viewSize = scrollBar.viewSize;
            position = scrollBar.position;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private ScrollBarState(Parcel source) {
            super(source);
            orientation = Orientation.valueOf(source.readString());
            thumbType = ThumbType.valueOf(source.readString());
            totalSize = source.readDouble();
            viewSize = source.readDouble();
            position = source.readDouble();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeString(orientation.name());
            out.writeString(thumbType.name());
            out.writeDouble(totalSize);
            out.writeDouble(viewSize);
            out.writeDouble(position);
        }

        public static final Parcelable.Creator<ScrollBarState> CREATOR
                = new Creator<ScrollBarState>() {
            @Override
            public ScrollBarState createFromParcel(Parcel source) {
                return new ScrollBarState(source);
            }
            @Override
            public ScrollBarState[] newArray(int size) {
                return new ScrollBarState[size];
            }
        };

        @Override
        @NonNull
        public String toString() {
            return new StringBuilder(getClass().getSimpleName())
                    .append("[orientation=").append(orientation)
                    .append(", thumbType=").append(thumbType)
                    .append(", totalSize=").append(totalSize)
                    .append(", viewSize=").append(viewSize)
                    .append(", position=").append(position)
                    .append("]")
                    .toString();
        }

        @Override
        public int hashCode() {
            int hash = orientation.hashCode() * 31 + Double.hashCode(totalSize);
            hash = hash * 31 + thumbType.hashCode();
            hash = hash * 31 + Double.hashCode(viewSize);
            hash = hash * 31 + Double.hashCode(position);
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ScrollBarState))
                return false;
            ScrollBarState otherState = (ScrollBarState) other;
            return ((orientation == otherState.orientation) &&
                    (thumbType == otherState.thumbType) &&
                    (totalSize == otherState.totalSize) &&
                    (viewSize == otherState.viewSize) &&
                    (position == otherState.position));
        }

    }

    private final Context context;
    /** The orientation of this scroll bar */
    private @NonNull Orientation orientation;
    /**
     * Whether the thumb of the scroll bar has a fixed size
     * or is scaled in proportion to the view size over the total size.
     */
    private @NonNull ThumbType thumbType;

    /** The size of the content this scroll bar represents */
    private double totalSize;
    /**
     * The size of the view over the scrollable content.  This may be
     * greater or less than the total size, but should not be zero
     * if the view has been rendered.
     */
    private double viewSize;
    /**
     * Current offset of the scroll thumb, in the same units as the
     * total size of the content this scroll bar represents.
     */
    private double position = 0;

    /** The scroll thumb view */
    private ImageView thumb;

    /** Registered listeners for thumb movement */
    private final List<OnScrollChangeListener> listeners = new ArrayList<>();

    /**
     * Create a new ScrollBar with default settings:
     * <ul>
     *     <li>Horizontal orientation</li>
     *     <li>Thumb type is scaled</li>
     *     <li>Content size is 0 (empty)</li>
     *     <li>View size is 1 (the unit is unimportant)</li>
     *     <li>The initial position is 0</li>
     * </ul>
     * This sets the scroll thumb to the full length of the bar,
     * rendering it effectively immobile.
     *
     * @param context the context in which the view is being created
     */
    public ScrollBar(Context context) {
        this(context, null);
    }

    /**
     * Create a ScrollBar with a given set of attributes.
     *
     * @param context the context in which the view is being created
     * @param attrs the set of attributes to be applied to the view.
     * This may be {@code null} to use the default settings.
     */
    public ScrollBar(@NonNull Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Create a ScrollBar with a given set of attributes.
     *
     * @param context the context in which the view is being created
     * @param attrs the set of attributes to be applied to the view.
     * This may be {@code null} to use the default settings.
     * @param defStyleAttr a reference to the style resource that
     * provides default attributes to apply, or 0 if there is no
     * default style to apply.
     */
    public ScrollBar(@NonNull Context context, AttributeSet attrs,
                     int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;

        Log.d(LOG_TAG, String.format(Locale.US,
                "Creating ScrollBar(%s,%s,%d)",
                context.getClass().getSimpleName(), attrs, defStyleAttr));

        // Get any overrides to the default settings
        // noinspection resource: TypeArray doesn't support close() until API 31
        TypedArray attrValues = context.obtainStyledAttributes(
                attrs, R.styleable.ScrollBar, defStyleAttr, 0);
        try {
            orientation = Orientation.values()[attrValues.getInt(
                    R.styleable.ScrollBar_android_orientation, 0)];
            thumbType = ThumbType.values()[attrValues.getInt(
                    R.styleable.ScrollBar_thumbType, 1)];
            totalSize = attrValues.getFloat(
                    R.styleable.ScrollBar_contentSize, 0.0f);
            if (totalSize < 0)
                throw new IllegalArgumentException(
                        "contentSize cannot be negative");
            viewSize = attrValues.getFloat(
                    R.styleable.ScrollBar_viewSize, 1.0f);
            if (viewSize <= 0)
                throw new IllegalArgumentException(
                        "viewSize must be positive");
            position = attrValues.getFloat(
                    R.styleable.ScrollBar_android_thumbPosition, 0.0f);
            if (position < 0)
                throw new IllegalArgumentException(
                        "thumbPosition cannot be negative");
        } finally {
            attrValues.recycle();
        }

        LayoutInflater inflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate((orientation == Orientation.HORIZONTAL)
                ? R.layout.scrollbar_horizontal
                : R.layout.scrollbar_vertical, this, true);
        thumb = findViewById(R.id.ScrollThumb);
    }

    /**
     * Check whether this scroll bar can accept touch events according to:
     * <ul>
     *     <li>the {@code enabled} user setting, and</li>
     *     <li>if the type of thumb is scaled, then
     *     <ul>
     *         <li>whether the view size is less than the total size.</li>
     *     </ul>
     *     </li>
     * </ul>
     *
     * @return {@code true} if this scroll bar can accept touch events,
     * {@code false} if it is immobile.
     */
    private boolean canMove() {
        return isEnabled() && ((thumbType == ThumbType.FIXED) ||
                (viewSize < totalSize));
    }

    /**
     * Clip a new tentative position to the limits imposed by the
     * scroll bar settings.  If the {@code thumbType} is
     * {@link ThumbType#FIXED FIXED}, the limit is {@code totalSize}.
     * If {@link ThumbType#SCALED SCALED}, the limit is {@code totalSize
     * - viewSize} if {@code viewSize < totalSize}, else it is fixed to 0.
     *
     * @param tentative the proposed new position
     *
     * @return the clipped position
     */
    private double clipPosition(double tentative) {
        if (thumbType == ThumbType.FIXED)
            return Math.min(Math.max(0, tentative), totalSize);

        if (viewSize >= totalSize)
            return 0;

        return Math.min(Math.max(0, tentative), totalSize - viewSize);
    }

    /**
     * Given the length (and width) of the parent view and our
     * current settings, compute how long the thumb should be.
     *
     * @param parentLength the length of the parent view in the
     * orientation of the scroll bar, in pixels.
     * @param parentGirth the width of the parent view perpendicular
     * to the orientation of the scroll bar, in pixels.
     *
     * @return the new length of the thumb in the orientation of the
     * scroll bar, in pixels
     */
    private int computeThumbLength(int parentLength, int parentGirth) {
        int minimumLength = (int) Math.min(parentGirth, parentLength);
        if (thumbType == ThumbType.FIXED)
            return minimumLength;

        if (viewSize >= totalSize)
            return parentLength;

        // Truncate any fractional part of the length
        return (int) Math.max(minimumLength,
                parentLength * viewSize / totalSize);
    }

    /**
     * Given the amount of room in the parent view to move the scroll bar
     * thumb and our current {@code position}, compute the offset of
     * the thumb relative to the start of the track.
     *
     * @param openLength the length of the parent view minus the length
     * of the thumb, in pixels.
     *
     * @return the new offset of the thumb in pixels
     */
    private int computeThumbOffset(int openLength) {
        if (thumbType == ThumbType.FIXED)
            // Round the position
            return (int) Math.round(openLength * position / totalSize);

        if (viewSize >= totalSize)
            return 0;

        return (int) Math.round(openLength * position /
                (totalSize - viewSize));
    }

    /**
     * Set the size and position of the thumb relative to the current
     * settings when the view is laid out.
     *
     * @param changed {@code true} if the size or position of the view
     * has changed since the previous layout.
     *
     */
    @Override
    protected void onLayout(boolean changed, int left, int top,
                             int right, int bottom) {
        Log.d(LOG_TAG, String.format(Locale.US,
                ".onLayout(%s,%d,%d,%d,%d)", changed,
                left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);

        int parentWidth = right - left;
        int parentHeight = bottom - top;
        int thumbWidth;
        int thumbHeight;
        int thumbX;
        int thumbY;
        if (orientation == Orientation.HORIZONTAL) {
            thumbWidth = computeThumbLength(parentWidth, parentHeight);
            thumbHeight = thumb.getMeasuredHeight();
            thumbX = computeThumbOffset(parentWidth - thumbWidth);
            thumbY = 0;
        } else {
            thumbHeight = computeThumbLength(parentHeight, parentWidth);
            thumbWidth = thumb.getMeasuredWidth();
            thumbX = 0;
            thumbY = computeThumbOffset(parentHeight - thumbHeight);
        }
        Log.d(LOG_TAG, String.format(Locale.US,
                "Computed thumb layout: offset (%d, %d), size %d \u00d7 %d",
                thumbX, thumbY, thumbWidth, thumbHeight));
        thumb.layout(thumbX, thumbY,
                thumbX + thumbWidth, thumbY + thumbHeight);
    }

    /**
     * Set the size of the content represented by this scroll bar.
     * The caller may use any units, but should be consistent.
     *
     * @param size the new size of the content
     *
     * @throws IllegalArgumentException if {@code size} is negative
     */
    public void setContentSize(double size) {
        if (size < 0)
            throw new IllegalArgumentException("Content size cannot be negative");
        totalSize = size;
        position = clipPosition(position);
        Log.d(LOG_TAG, String.format(Locale.US,
                ".setContentSize(%f); position is %f", totalSize, position));
        if (!isInLayout())
            requestLayout();
    }

    /**
     * Set the size of the viewable area represented by the thumb of
     * this scroll bar.  The units for this size must be the same as
     * for {@link #setContentSize(double)}.  This is ignored for a
     * {@link ThumbType#FIXED FIXED}-size thumb.
     *
     * @param size the new size of the viewable area
     *
     * @throws IllegalArgumentException if {@code size} is not positive
     */
    public void setViewSize(double size) {
        if (size <= 0)
            throw new IllegalArgumentException("View size must be positive");
        viewSize = size;
        position = clipPosition(position);
        Log.d(LOG_TAG, String.format(Locale.US,
                ".setViewSize(%f); position is %f", viewSize, position));
        if (!isInLayout())
            requestLayout();
    }

    /**
     * Set the current position of the thumb on the scroll bar.
     * The units for the position must be the same as for
     * {@link #setContentSize(double)}.  If the position exceeds
     * the total content size minus the view size (if the thumb type
     * is {@link ThumbType#SCALED SCALED}) it will be clipped,
     * so this should be set after setting the content and view sizes.
     * This does not trigger any listener callbacks.
     *
     * @param newPosition the new position of the thumb
     *
     * @throws IllegalArgumentException if {@code newPosition} is negative
     */
    public void setPosition(double newPosition) {
        if (newPosition < 0)
            throw new IllegalArgumentException("Thumb position cannot be negative");
        position = clipPosition(newPosition);
        Log.d(LOG_TAG, String.format(Locale.US,
                ".setPosition(%f); new position is %f",
                newPosition, position));
        if (!isInLayout())
            requestLayout();
    }

    /**
     * Get the current position of the thumb on the scroll bar.
     * This represents the start of the thumb relative to the start
     * of the content for a {@link ThumbType#SCALED SCALED} scroll
     * thumb, or a value from 0 to the total content size for a
     * {@link ThumbType#FIXED FIXED} scroll thumb.
     *
     * @return the thumb position
     */
    public double getPosition() {
        return position;
    }

    /**
     * Generate a representation of our internal state that can later
     * be used to create a new instance with that same state.
     *
     * @return the state of this ScrollBar
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        Log.d(LOG_TAG, ".onSaveInstanceState()");
        return new ScrollBarState(super.onSaveInstanceState(), this);
    }

    /**
     * Re-apply the state of a ScrollBar that was previously saved with
     * {@link #onSaveInstanceState()}.
     *
     * @param state The frozen state that had previously been returned by
     * {@link #onSaveInstanceState()}.
     */
    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        Log.d(LOG_TAG, String.format(Locale.US,
                ".onRestoreInstanceState(%s)", state));
        super.onRestoreInstanceState(state);
        if (state instanceof  ScrollBarState) {
            ScrollBarState myState = (ScrollBarState) state;
            if (myState.orientation != orientation) {
                // Need to switch layouts
                LayoutInflater inflater = (LayoutInflater)
                        context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater.inflate((myState.orientation == Orientation.HORIZONTAL)
                        ? R.layout.scrollbar_horizontal
                        : R.layout.scrollbar_vertical, this, true);
                thumb = findViewById(R.id.ScrollThumb);
            }
            orientation = myState.orientation;
            totalSize = myState.totalSize;
            viewSize = myState.viewSize;
            position = myState.position;
            if (!isInLayout())
                requestLayout();
        }
    }

    /**
     * Respond to drag events on the scroll bar thumb
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        double relativePosition;
        int barSize;
        int thumbSize;

        Log.d(LOG_TAG, String.format(Locale.US,
                "onTouchEvent(%s)", event));

        if (!canMove())
            return false;

        if (orientation == Orientation.HORIZONTAL) {
            relativePosition = event.getX();
            barSize = getWidth();
            thumbSize = thumb.getWidth();
        } else {
            relativePosition = event.getY();
            barSize = getHeight();
            thumbSize = thumb.getHeight();
        }

        // Clip the relative position to half of the thumb length
        // away from either end of the scroll region
        int openLength = barSize - thumbSize;
        relativePosition = Math.min(Math.max(0,
                relativePosition - thumbSize / 2.0), openLength);
        double oldPosition = position;

        if (thumbType == ThumbType.FIXED) {
            position = totalSize * relativePosition / openLength;
        } else {
            position = (totalSize - viewSize) * relativePosition / openLength;
        }

        if (position != oldPosition) {
            Log.d(LOG_TAG, String.format(Locale.US,
                    "Position has changed from %.3f to %.3f",
                    oldPosition, position));

            if (!isInLayout())
                requestLayout();

            for (OnScrollChangeListener listener : listeners)
                listener.onScrollChange(this, (float) position);
        }

        return true;
    }

    /**
     * Register a listener to be notified when the scroll thumb
     * changes position.
     *
     * @param listener the listener to register
     */
    public void registerOnScrollChangeListener(
            OnScrollChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a registered scroll change listener.
     *
     * @param listener the listener to remove
     */
    public void unregisterOnScrollChangeListener(
            OnScrollChangeListener listener) {
        listeners.remove(listener);
    }

}
