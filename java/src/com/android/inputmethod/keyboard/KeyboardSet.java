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

package com.android.inputmethod.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.Log;
import android.util.Xml;
import android.view.inputmethod.EditorInfo;

import com.android.inputmethod.keyboard.internal.KeyboardBuilder;
import com.android.inputmethod.keyboard.internal.KeyboardParams;
import com.android.inputmethod.keyboard.internal.XmlParseUtils;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.LatinImeLogger;
import com.android.inputmethod.latin.LocaleUtils;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.SettingsValues;
import com.android.inputmethod.latin.SubtypeSwitcher;
import com.android.inputmethod.latin.Utils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Locale;

/**
 * This class represents a set of keyboards. Each of them represents a different keyboard
 * specific to a keyboard state, such as alphabet, symbols, and so on.  Layouts in the same
 * {@link KeyboardSet} are related to each other.
 * A {@link KeyboardSet} needs to be created for each {@link android.view.inputmethod.EditorInfo}.
 */
public class KeyboardSet {
    private static final String TAG = KeyboardSet.class.getSimpleName();
    private static final boolean DEBUG_CACHE = LatinImeLogger.sDBG;

    private static final String TAG_KEYBOARD_SET = TAG;
    private static final String TAG_ELEMENT = "Element";

    private final Context mContext;
    private final Params mParams;

    private static class Params {
        int mMode;
        int mInputType;
        int mImeOptions;
        boolean mSettingsKeyEnabled;
        boolean mVoiceKeyEnabled;
        boolean mVoiceKeyOnMain;
        boolean mNoSettingsKey;
        Locale mLocale;
        int mOrientation;
        int mWidth;
        final HashMap<Integer, Integer> mElementKeyboards = new HashMap<Integer, Integer>();
        Params() {}
    }

    private static final HashMap<KeyboardId, SoftReference<Keyboard>> sKeyboardCache =
            new HashMap<KeyboardId, SoftReference<Keyboard>>();

    public static void clearKeyboardCache() {
        sKeyboardCache.clear();
    }

    private KeyboardSet(Context context, Params params) {
        mContext = context;
        mParams = params;
    }

    public Keyboard getMainKeyboard() {
        return getKeyboard(false, false);
    }

    public Keyboard getSymbolsKeyboard() {
        return getKeyboard(true, false);
    }

    public Keyboard getSymbolsShiftedKeyboard() {
        final Keyboard keyboard = getKeyboard(true, true);
        // TODO: Remove this logic once we introduce initial keyboard shift state attribute.
        // Symbol shift keyboard may have a shift key that has a caps lock style indicator (a.k.a.
        // sticky shift key). To show or dismiss the indicator, we need to call setShiftLocked()
        // that takes care of the current keyboard having such shift key or not.
        keyboard.setShiftLocked(keyboard.hasShiftLockKey());
        return keyboard;
    }

    private Keyboard getKeyboard(boolean isSymbols, boolean isShift) {
        final int elementState = Builder.getElementState(mParams.mMode, isSymbols, isShift);
        final int xmlId = mParams.mElementKeyboards.get(elementState);
        final KeyboardId id = Builder.getKeyboardId(elementState, isSymbols, mParams);
        final Keyboard keyboard = getKeyboard(mContext, xmlId, id);
        return keyboard;
    }

    public KeyboardId getMainKeyboardId() {
        final int elementState = Builder.getElementState(mParams.mMode, false, false);
        return Builder.getKeyboardId(elementState, false, mParams);
    }

    private static Keyboard getKeyboard(Context context, int xmlId, KeyboardId id) {
        final Resources res = context.getResources();
        final SubtypeSwitcher subtypeSwitcher = SubtypeSwitcher.getInstance();
        final SoftReference<Keyboard> ref = sKeyboardCache.get(id);
        Keyboard keyboard = (ref == null) ? null : ref.get();
        if (keyboard == null) {
            final Locale savedLocale = LocaleUtils.setSystemLocale(res, id.mLocale);
            try {
                final KeyboardBuilder<KeyboardParams> builder =
                        new KeyboardBuilder<KeyboardParams>(context, new KeyboardParams());
                builder.load(xmlId, id);
                builder.setTouchPositionCorrectionEnabled(
                        subtypeSwitcher.currentSubtypeContainsExtraValueKey(
                                LatinIME.SUBTYPE_EXTRA_VALUE_SUPPORT_TOUCH_POSITION_CORRECTION));
                keyboard = builder.build();
            } finally {
                LocaleUtils.setSystemLocale(res, savedLocale);
            }
            sKeyboardCache.put(id, new SoftReference<Keyboard>(keyboard));

            if (DEBUG_CACHE) {
                Log.d(TAG, "keyboard cache size=" + sKeyboardCache.size() + ": "
                        + ((ref == null) ? "LOAD" : "GCed") + " id=" + id);
            }
        } else if (DEBUG_CACHE) {
            Log.d(TAG, "keyboard cache size=" + sKeyboardCache.size() + ": HIT  id=" + id);
        }

        // TODO: Remove setShiftLocked and setShift calls.
        keyboard.setShiftLocked(false);
        keyboard.setShifted(false);
        return keyboard;
    }

    public static class Builder {
        private final Context mContext;
        private final Resources mResources;

        private final Params mParams = new Params();

        public Builder(Context context, EditorInfo editorInfo, SettingsValues settingsValues) {
            mContext = context;
            mResources = context.getResources();
            final SubtypeSwitcher subtypeSwitcher = SubtypeSwitcher.getInstance();
            final String packageName = context.getPackageName();
            final Params params = mParams;

            params.mMode = Utils.getKeyboardMode(editorInfo);
            if (editorInfo != null) {
                params.mInputType = editorInfo.inputType;
                params.mImeOptions = editorInfo.imeOptions;
            }
            params.mSettingsKeyEnabled = settingsValues.isSettingsKeyEnabled();
            @SuppressWarnings("deprecation")
            final boolean noMicrophone = Utils.inPrivateImeOptions(
                    packageName, LatinIME.IME_OPTION_NO_MICROPHONE, editorInfo)
                    || Utils.inPrivateImeOptions(
                            null, LatinIME.IME_OPTION_NO_MICROPHONE_COMPAT, editorInfo);
            params.mVoiceKeyEnabled = settingsValues.isVoiceKeyEnabled(editorInfo) && !noMicrophone;
            params.mVoiceKeyOnMain = settingsValues.isVoiceKeyOnMain();
            params.mNoSettingsKey = Utils.inPrivateImeOptions(
                    packageName, LatinIME.IME_OPTION_NO_SETTINGS_KEY, editorInfo);
            final boolean forceAscii = Utils.inPrivateImeOptions(
                    packageName, LatinIME.IME_OPTION_FORCE_ASCII, editorInfo);
            final boolean asciiCapable = subtypeSwitcher.currentSubtypeContainsExtraValueKey(
                    LatinIME.SUBTYPE_EXTRA_VALUE_ASCII_CAPABLE);
            params.mLocale = (forceAscii && !asciiCapable)
                    ? Locale.US : subtypeSwitcher.getInputLocale();
            params.mOrientation = mResources.getConfiguration().orientation;
            params.mWidth = mResources.getDisplayMetrics().widthPixels;
        }

        public KeyboardSet build() {
            final Locale savedLocale = LocaleUtils.setSystemLocale(mResources, mParams.mLocale);
            try {
                parseKeyboardSet(mResources, R.xml.keyboard_set);
            } catch (Exception e) {
                //
            } finally {
                LocaleUtils.setSystemLocale(mResources, savedLocale);
            }
            return new KeyboardSet(mContext, mParams);
        }

        // TODO: Move this method to KeyboardSet
        static KeyboardId getKeyboardId(int elementState, boolean isSymbols, Params params) {
            final boolean hasShortcutKey = params.mVoiceKeyEnabled
                    && (isSymbols != params.mVoiceKeyOnMain);
            return new KeyboardId(elementState, params.mLocale, params.mOrientation, params.mWidth,
                    params.mMode, params.mInputType, params.mImeOptions, params.mSettingsKeyEnabled,
                    params.mNoSettingsKey, params.mVoiceKeyEnabled, hasShortcutKey);
        }

        // TODO: Move this method to KeyboardSet
        static int getElementState(int mode, boolean isSymbols, boolean isShift) {
            switch (mode) {
            case KeyboardId.MODE_PHONE:
                return (isSymbols && isShift)
                        ? KeyboardId.ELEMENT_PHONE_SHIFT : KeyboardId.ELEMENT_PHONE;
            case KeyboardId.MODE_NUMBER:
                return KeyboardId.ELEMENT_NUMBER;
            default:
                if (isSymbols) {
                    return isShift ? KeyboardId.ELEMENT_SYMBOLS_SHIFT : KeyboardId.ELEMENT_SYMBOLS;
                }
                return KeyboardId.ELEMENT_ALPHABET;
            }
        }

        private void parseKeyboardSet(Resources res, int resId) throws XmlPullParserException,
                IOException {
            final XmlResourceParser parser = res.getXml(resId);
            try {
                int event;
                while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        final String tag = parser.getName();
                        if (TAG_KEYBOARD_SET.equals(tag)) {
                            parseKeyboardSetContent(parser);
                        } else {
                            throw new XmlParseUtils.IllegalStartTag(parser, TAG_KEYBOARD_SET);
                        }
                    }
                }
            } finally {
                parser.close();
            }
        }

        private void parseKeyboardSetContent(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String tag = parser.getName();
                    if (TAG_ELEMENT.equals(tag)) {
                        parseKeyboardSetElement(parser);
                    } else {
                        throw new XmlParseUtils.IllegalStartTag(parser, TAG_KEYBOARD_SET);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String tag = parser.getName();
                    if (TAG_KEYBOARD_SET.equals(tag)) {
                        break;
                    } else {
                        throw new XmlParseUtils.IllegalEndTag(parser, TAG_KEYBOARD_SET);
                    }
                }
            }
        }

        private void parseKeyboardSetElement(XmlPullParser parser) throws XmlPullParserException,
                IOException {
            final TypedArray a = mResources.obtainAttributes(Xml.asAttributeSet(parser),
                    R.styleable.KeyboardSet_Element);
            try {
                XmlParseUtils.checkAttributeExists(a,
                        R.styleable.KeyboardSet_Element_elementName, "elementName",
                        TAG_ELEMENT, parser);
                XmlParseUtils.checkAttributeExists(a,
                        R.styleable.KeyboardSet_Element_elementKeyboard, "elementKeyboard",
                        TAG_ELEMENT, parser);
                XmlParseUtils.checkEndTag(TAG_ELEMENT, parser);

                final int elementName = a.getInt(
                        R.styleable.KeyboardSet_Element_elementName, 0);
                final int elementKeyboard = a.getResourceId(
                        R.styleable.KeyboardSet_Element_elementKeyboard, 0);
                mParams.mElementKeyboards.put(elementName, elementKeyboard);
            } finally {
                a.recycle();
            }
        }
    }

    public static String parseKeyboardLocale(Resources res, int resId)
            throws XmlPullParserException, IOException {
        final XmlPullParser parser = res.getXml(resId);
        if (parser == null)
            return "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String tag = parser.getName();
                if (TAG_KEYBOARD_SET.equals(tag)) {
                    final TypedArray keyboardSetAttr = res.obtainAttributes(
                            Xml.asAttributeSet(parser), R.styleable.KeyboardSet);
                    final String locale = keyboardSetAttr.getString(
                            R.styleable.KeyboardSet_keyboardLocale);
                    keyboardSetAttr.recycle();
                    return locale;
                } else {
                    throw new XmlParseUtils.IllegalStartTag(parser, TAG_KEYBOARD_SET);
                }
            }
        }
        return "";
    }
}
