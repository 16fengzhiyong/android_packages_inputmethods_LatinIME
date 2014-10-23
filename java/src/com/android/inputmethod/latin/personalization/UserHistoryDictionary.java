/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.inputmethod.latin.personalization;

import android.content.Context;

import com.android.inputmethod.annotations.ExternallyReferenced;
import com.android.inputmethod.latin.Dictionary;
import com.android.inputmethod.latin.ExpandableBinaryDictionary;
import com.android.inputmethod.latin.NgramContext;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.utils.DistracterFilter;

import java.io.File;
import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * Locally gathers stats about the words user types and various other signals like auto-correction
 * cancellation or manual picks. This allows the keyboard to adapt to the typist over time.
 */
public class UserHistoryDictionary extends DecayingExpandableBinaryDictionaryBase {
    /* package */ static final String NAME = UserHistoryDictionary.class.getSimpleName();

    // TODO: Make this constructor private
    /* package */ UserHistoryDictionary(final Context context, final Locale locale) {
        super(context, getDictName(NAME, locale, null /* dictFile */), locale,
                Dictionary.TYPE_USER_HISTORY, null /* dictFile */);
    }

    // Note: This method is called by {@link DictionaryFacilitator} using Java reflection.
    @SuppressWarnings("unused")
    @ExternallyReferenced
    public static UserHistoryDictionary getDictionary(final Context context, final Locale locale,
            final File dictFile, final String dictNamePrefix) {
        return PersonalizationHelper.getUserHistoryDictionary(context, locale);
    }

    /**
     * Add a word to the user history dictionary.
     *
     * @param userHistoryDictionary the user history dictionary
     * @param ngramContext the n-gram context
     * @param word the word the user inputted
     * @param isValid whether the word is valid or not
     * @param timestamp the timestamp when the word has been inputted
     * @param distracterFilter the filter to check whether the word is a distracter
     */
    public static void addToDictionary(final ExpandableBinaryDictionary userHistoryDictionary,
            @Nonnull final NgramContext ngramContext, final String word, final boolean isValid,
            final int timestamp, @Nonnull final DistracterFilter distracterFilter) {
        if (word.length() > Constants.DICTIONARY_MAX_WORD_LENGTH) {
            return;
        }
        userHistoryDictionary.updateEntriesForWordWithCheckingDistracter(ngramContext, word,
                isValid, 1 /* count */, timestamp, distracterFilter);
    }
}
