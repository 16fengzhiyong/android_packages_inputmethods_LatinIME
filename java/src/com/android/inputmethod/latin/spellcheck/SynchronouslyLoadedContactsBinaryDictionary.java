/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.inputmethod.latin.spellcheck;

import android.content.Context;

import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.ContactsBinaryDictionary;
import com.android.inputmethod.latin.PrevWordsInfo;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.settings.SettingsValuesForSuggestion;
import com.android.inputmethod.latin.WordComposer;

import java.util.ArrayList;
import java.util.Locale;

public final class SynchronouslyLoadedContactsBinaryDictionary extends ContactsBinaryDictionary {
    private static final String NAME = "spellcheck_contacts";
    private final Object mLock = new Object();

    public SynchronouslyLoadedContactsBinaryDictionary(final Context context, final Locale locale) {
        super(context, locale, null /* dictFile */, NAME);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final WordComposer codes,
            final PrevWordsInfo prevWordsInfo, final ProximityInfo proximityInfo,
            final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final int sessionId, final float[] inOutLanguageWeight) {
        synchronized (mLock) {
            return super.getSuggestions(codes, prevWordsInfo, proximityInfo,
                    settingsValuesForSuggestion, sessionId, inOutLanguageWeight);
        }
    }

    @Override
    public boolean isInDictionary(final String word) {
        synchronized (mLock) {
            return super.isInDictionary(word);
        }
    }
}
