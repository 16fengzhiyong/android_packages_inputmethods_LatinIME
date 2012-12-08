/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;

import com.android.inputmethod.keyboard.PointerTracker.DrawingProxy;
import com.android.inputmethod.keyboard.PointerTracker.TimerProxy;
import com.android.inputmethod.latin.Constants;
import com.android.inputmethod.latin.CoordinateUtils;
import com.android.inputmethod.latin.InputPointers;
import com.android.inputmethod.latin.R;

/**
 * A view that renders a virtual {@link MoreKeysKeyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 */
public final class MoreKeysKeyboardView extends KeyboardView implements MoreKeysPanel {
    private final int[] mCoordinates = CoordinateUtils.newInstance();

    private final KeyDetector mKeyDetector;

    private Controller mController;
    private KeyboardActionListener mListener;
    private int mOriginX;
    private int mOriginY;

    private static final TimerProxy EMPTY_TIMER_PROXY = new TimerProxy.Adapter();

    private final KeyboardActionListener mMoreKeysKeyboardListener =
            new KeyboardActionListener.Adapter() {
        @Override
        public void onCodeInput(final int primaryCode, final int x, final int y) {
            // Because a more keys keyboard doesn't need proximity characters correction, we don't
            // send touch event coordinates.
            mListener.onCodeInput(
                    primaryCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }

        @Override
        public void onTextInput(final String text) {
            mListener.onTextInput(text);
        }

        @Override
        public void onStartBatchInput() {
            mListener.onStartBatchInput();
        }

        @Override
        public void onUpdateBatchInput(final InputPointers batchPointers) {
            mListener.onUpdateBatchInput(batchPointers);
        }

        @Override
        public void onEndBatchInput(final InputPointers batchPointers) {
            mListener.onEndBatchInput(batchPointers);
        }

        @Override
        public void onCancelInput() {
            mListener.onCancelInput();
        }

        @Override
        public void onPressKey(final int primaryCode) {
            mListener.onPressKey(primaryCode);
        }

        @Override
        public void onReleaseKey(final int primaryCode, final boolean withSliding) {
            mListener.onReleaseKey(primaryCode, withSliding);
        }
    };

    public MoreKeysKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.moreKeysKeyboardViewStyle);
    }

    public MoreKeysKeyboardView(final Context context, final AttributeSet attrs,
            final int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();
        mKeyDetector = new MoreKeysDetector(
                res.getDimension(R.dimen.more_keys_keyboard_slide_allowance));
        setKeyPreviewPopupEnabled(false, 0);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
            final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public void setKeyboard(final Keyboard keyboard) {
        super.setKeyboard(keyboard);
        mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(),
                -getPaddingTop() + mVerticalCorrection);
    }

    @Override
    public KeyDetector getKeyDetector() {
        return mKeyDetector;
    }

    @Override
    public KeyboardActionListener getKeyboardActionListener() {
        return mMoreKeysKeyboardListener;
    }

    @Override
    public DrawingProxy getDrawingProxy() {
        return this;
    }

    @Override
    public TimerProxy getTimerProxy() {
        return EMPTY_TIMER_PROXY;
    }

    @Override
    public void setKeyPreviewPopupEnabled(final boolean previewEnabled, final int delay) {
        // More keys keyboard needs no pop-up key preview displayed, so we pass always false with a
        // delay of 0. The delay does not matter actually since the popup is not shown anyway.
        super.setKeyPreviewPopupEnabled(false, 0);
    }

    @Override
    public void showMoreKeysPanel(final View parentView, final Controller controller,
            final int pointX, final int pointY, final KeyboardActionListener listener) {
        mController = controller;
        mListener = listener;
        final View container = getContainerView();
        final MoreKeysKeyboard pane = (MoreKeysKeyboard)getKeyboard();
        final int defaultCoordX = pane.getDefaultCoordX();
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        final int x = pointX - defaultCoordX - container.getPaddingLeft();
        final int y = pointY - container.getMeasuredHeight() + container.getPaddingBottom();

        parentView.getLocationInWindow(mCoordinates);
        // Ensure the horizontal position of the panel does not extend past the screen edges.
        final int maxX = parentView.getMeasuredWidth() - container.getMeasuredWidth();
        final int panelX = Math.max(0, Math.min(maxX, x + CoordinateUtils.x(mCoordinates)));
        final int panelY = y + CoordinateUtils.y(mCoordinates);
        container.setX(panelX);
        container.setY(panelY);

        mOriginX = x + container.getPaddingLeft();
        mOriginY = y + container.getPaddingTop();
        controller.onShowMoreKeysPanel(this);
    }

    @Override
    public boolean dismissMoreKeysPanel() {
        if (mController == null) return false;
        return mController.onDismissMoreKeysPanel();
    }

    @Override
    public int translateX(final int x) {
        return x - mOriginX;
    }

    @Override
    public int translateY(final int y) {
        return y - mOriginY;
    }

    @Override
    public View getContainerView() {
        return (View)getParent();
    }

    @Override
    public boolean isShowingInParent() {
        return (getContainerView().getParent() != null);
    }
}
