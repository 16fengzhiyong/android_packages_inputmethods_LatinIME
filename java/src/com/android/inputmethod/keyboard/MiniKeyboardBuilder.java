/*
 * Copyright (C) 2010 Google Inc.
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

package com.android.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;

import java.util.List;

public class MiniKeyboardBuilder {
    private final Resources mRes;
    private final Keyboard mKeyboard;
    private final CharSequence[] mPopupCharacters;
    private final int mMaxColumns;
    private final int mNumRows;
    private int mColPos;
    private int mRowPos;
    private Row mRow;
    private int mX;
    private int mY;

    public MiniKeyboardBuilder(Context context, int layoutTemplateResId, Key popupKey) {
        mRes = context.getResources();
        mKeyboard = new Keyboard(context, layoutTemplateResId, null);
        mPopupCharacters = popupKey.mPopupCharacters;
        final int numKeys = mPopupCharacters.length;
        final int maxColumns = popupKey.mMaxPopupColumn;
        int numRows = numKeys / maxColumns;
        if (numKeys % maxColumns != 0) numRows++;
        mMaxColumns = maxColumns;
        mNumRows = numRows;
        // TODO: To determine key width we should pay attention to key label length.
        mRow = new Row(mKeyboard, getRowFlags());
        if (numRows > 1) {
            mColPos = numKeys % maxColumns;
            if (mColPos > 0) mColPos = maxColumns - mColPos;
            // Centering top-row keys.
            mX = mColPos * (mRow.mDefaultWidth + mRow.mDefaultHorizontalGap) / 2;
        }
        mKeyboard.setMinWidth(0);
    }

    public Keyboard build() {
        List<Key> keys = mKeyboard.getKeys();
        for (CharSequence label : mPopupCharacters) {
            refresh();
            final Key key = new Key(mRes, mRow, label, mX, mY);
            keys.add(key);
            advance();
        }
        finish();
        return mKeyboard;
    }

    private int getRowFlags() {
        final int rowPos = mRowPos;
        int rowFlags = 0;
        if (rowPos == 0) rowFlags |= Keyboard.EDGE_TOP;
        if (rowPos == mNumRows - 1) rowFlags |= Keyboard.EDGE_BOTTOM;
        return rowFlags;
    }

    private void refresh() {
        if (mColPos >= mMaxColumns) {
            final Row row = mRow;
            // TODO: Allocate key position depending the precedence of popup characters.
            mX = 0;
            mY += row.mDefaultHeight + row.mVerticalGap;
            mColPos = 0;
            // TODO: To determine key width we should pay attention to key label length from
            // bottom to up for rows.
            mRow = new Row(mKeyboard, getRowFlags());
            mRowPos++;
        }
    }

    private void advance() {
        final Row row = mRow;
        final Keyboard keyboard = mKeyboard;
        // TODO: Allocate key position depending the precedence of popup characters.
        mX += row.mDefaultWidth + row.mDefaultHorizontalGap;
        if (mX > keyboard.getMinWidth())
            keyboard.setMinWidth(mX);
        mColPos++;
    }

    private void finish() {
        mKeyboard.setHeight(mY + mRow.mDefaultHeight);
    }
}