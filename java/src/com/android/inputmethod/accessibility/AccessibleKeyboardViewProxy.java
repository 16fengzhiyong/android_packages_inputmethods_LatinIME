/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.accessibility;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import com.android.inputmethod.compat.AccessibilityEventCompatUtils;
import com.android.inputmethod.compat.MotionEventCompatUtils;
import com.android.inputmethod.keyboard.Key;
import com.android.inputmethod.keyboard.KeyDetector;
import com.android.inputmethod.keyboard.KeyboardView;
import com.android.inputmethod.keyboard.PointerTracker;

public class AccessibleKeyboardViewProxy {
    private static final String TAG = AccessibleKeyboardViewProxy.class.getSimpleName();
    private static final AccessibleKeyboardViewProxy sInstance = new AccessibleKeyboardViewProxy();

    // Delay in milliseconds between key press DOWN and UP events
    private static final long DELAY_KEY_PRESS = 10;

    private int mScaledEdgeSlop;
    private KeyboardView mView;
    private AccessibleKeyboardActionListener mListener;

    private int mLastHoverKeyIndex = KeyDetector.NOT_A_KEY;
    private int mLastX = -1;
    private int mLastY = -1;

    public static void init(Context context, SharedPreferences prefs) {
        sInstance.initInternal(context, prefs);
        sInstance.mListener = AccessibleInputMethodServiceProxy.getInstance();
    }

    public static AccessibleKeyboardViewProxy getInstance() {
        return sInstance;
    }

    public static void setView(KeyboardView view) {
        sInstance.mView = view;
    }

    private AccessibleKeyboardViewProxy() {
        // Not publicly instantiable.
    }

    private void initInternal(Context context, SharedPreferences prefs) {
        final Paint paint = new Paint();
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(14.0f);
        paint.setAntiAlias(true);
        paint.setColor(Color.YELLOW);

        mScaledEdgeSlop = ViewConfiguration.get(context).getScaledEdgeSlop();
    }

    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event,
            PointerTracker tracker) {
        if (mView == null) {
            Log.e(TAG, "No keyboard view set!");
            return false;
        }

        switch (event.getEventType()) {
        case AccessibilityEventCompatUtils.TYPE_VIEW_HOVER_ENTER:
            final Key key = tracker.getKey(mLastHoverKeyIndex);

            if (key == null)
                break;

            final CharSequence description = KeyCodeDescriptionMapper.getInstance()
                    .getDescriptionForKey(mView.getContext(), mView.getKeyboard(), key);

            if (description == null)
                return false;

            event.getText().add(description);

            break;
        }

        return true;
    }

    /**
     * Receives hover events when accessibility is turned on in API > 11. In
     * earlier API levels, events are manually routed from onTouchEvent.
     *
     * @param event The hover event.
     * @return {@code true} if the event is handled
     */
    public boolean onHoverEvent(MotionEvent event, PointerTracker tracker) {
        return onTouchExplorationEvent(event, tracker);
    }

    public boolean dispatchTouchEvent(MotionEvent event) {
        // Since touch exploration translates hover double-tap to a regular
        // single-tap, we're going to drop non-touch exploration events.
        if (!AccessibilityUtils.getInstance().isTouchExplorationEvent(event))
            return true;

        return false;
    }

    /**
     * Handles touch exploration events when Accessibility is turned on.
     *
     * @param event The touch exploration hover event.
     * @return {@code true} if the event was handled
     */
    private boolean onTouchExplorationEvent(MotionEvent event, PointerTracker tracker) {
        final int x = (int) event.getX();
        final int y = (int) event.getY();

        switch (event.getAction()) {
        case MotionEventCompatUtils.ACTION_HOVER_ENTER:
        case MotionEventCompatUtils.ACTION_HOVER_MOVE:
            final int keyIndex = tracker.getKeyIndexOn(x, y);

            if (keyIndex != mLastHoverKeyIndex) {
                fireKeyHoverEvent(tracker, mLastHoverKeyIndex, false);
                mLastHoverKeyIndex = keyIndex;
                mLastX = x;
                mLastY = y;
                fireKeyHoverEvent(tracker, mLastHoverKeyIndex, true);
            }

            return true;
        case MotionEventCompatUtils.ACTION_HOVER_EXIT:
            final int width = mView.getWidth();
            final int height = mView.getHeight();

            if (x < mScaledEdgeSlop || y < mScaledEdgeSlop || x >= (width - mScaledEdgeSlop)
                    || y >= (height - mScaledEdgeSlop)) {
                fireKeyHoverEvent(tracker, mLastHoverKeyIndex, false);
                mLastHoverKeyIndex = KeyDetector.NOT_A_KEY;
                mLastX = -1;
                mLastY = -1;
            } else if (mLastHoverKeyIndex != KeyDetector.NOT_A_KEY) {
                fireKeyPressEvent(tracker, mLastX, mLastY, event.getEventTime());
            }

            return true;
        }

        return false;
    }

    private void fireKeyHoverEvent(PointerTracker tracker, int keyIndex, boolean entering) {
        if (mListener == null) {
            Log.e(TAG, "No accessible keyboard action listener set!");
            return;
        }

        if (mView == null) {
            Log.e(TAG, "No keyboard view set!");
            return;
        }

        if (keyIndex == KeyDetector.NOT_A_KEY)
            return;

        final Key key = tracker.getKey(keyIndex);

        if (key == null)
            return;

        if (entering) {
            mListener.onHoverEnter(key.mCode);
            mView.sendAccessibilityEvent(AccessibilityEventCompatUtils.TYPE_VIEW_HOVER_ENTER);
        } else {
            mListener.onHoverExit(key.mCode);
            mView.sendAccessibilityEvent(AccessibilityEventCompatUtils.TYPE_VIEW_HOVER_EXIT);
        }
    }

    private void fireKeyPressEvent(PointerTracker tracker, int x, int y, long eventTime) {
        tracker.onDownEvent(x, y, eventTime, null);
        tracker.onUpEvent(x, y, eventTime + DELAY_KEY_PRESS, null);
    }
}
