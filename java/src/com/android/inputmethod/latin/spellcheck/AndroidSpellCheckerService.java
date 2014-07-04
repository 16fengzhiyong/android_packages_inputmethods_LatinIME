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

package com.android.inputmethod.latin.spellcheck;

import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.service.textservice.SpellCheckerService;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SuggestionsInfo;

import com.android.inputmethod.keyboard.KeyboardLayoutSet;
import com.android.inputmethod.latin.ContactsBinaryDictionary;
import com.android.inputmethod.latin.Dictionary;
import com.android.inputmethod.latin.DictionaryCollection;
import com.android.inputmethod.latin.DictionaryFactory;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.UserBinaryDictionary;
import com.android.inputmethod.latin.utils.AdditionalSubtypeUtils;
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils;
import com.android.inputmethod.latin.utils.CollectionUtils;
import com.android.inputmethod.latin.utils.LocaleUtils;
import com.android.inputmethod.latin.utils.ScriptUtils;
import com.android.inputmethod.latin.utils.StringUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Service for spell checking, using LatinIME's dictionaries and mechanisms.
 */
public final class AndroidSpellCheckerService extends SpellCheckerService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = AndroidSpellCheckerService.class.getSimpleName();
    private static final boolean DBG = false;
    private static final int POOL_SIZE = 2;

    public static final String PREF_USE_CONTACTS_KEY = "pref_spellcheck_use_contacts";

    private static final int SPELLCHECKER_DUMMY_KEYBOARD_WIDTH = 480;
    private static final int SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT = 368;

    private final static String[] EMPTY_STRING_ARRAY = new String[0];
    private Map<String, DictionaryPool> mDictionaryPools = CollectionUtils.newSynchronizedTreeMap();
    private Map<String, UserBinaryDictionary> mUserDictionaries =
            CollectionUtils.newSynchronizedTreeMap();
    private ContactsBinaryDictionary mContactsDictionary;

    // The threshold for a suggestion to be considered "recommended".
    private float mRecommendedThreshold;
    // Whether to use the contacts dictionary
    private boolean mUseContactsDictionary;
    private final Object mUseContactsLock = new Object();

    private final HashSet<WeakReference<DictionaryCollection>> mDictionaryCollectionsList =
            new HashSet<>();

    public static final String SINGLE_QUOTE = "\u0027";
    public static final String APOSTROPHE = "\u2019";

    @Override public void onCreate() {
        super.onCreate();
        mRecommendedThreshold =
                Float.parseFloat(getString(R.string.spellchecker_recommended_threshold_value));
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(prefs, PREF_USE_CONTACTS_KEY);
    }

    private static String getKeyboardLayoutNameForScript(final int script) {
        switch (script) {
        case ScriptUtils.SCRIPT_LATIN:
            return "qwerty";
        case ScriptUtils.SCRIPT_CYRILLIC:
            return "east_slavic";
        case ScriptUtils.SCRIPT_GREEK:
            return "greek";
        default:
            throw new RuntimeException("Wrong script supplied: " + script);
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (!PREF_USE_CONTACTS_KEY.equals(key)) return;
        synchronized(mUseContactsLock) {
            mUseContactsDictionary = prefs.getBoolean(PREF_USE_CONTACTS_KEY, true);
            if (mUseContactsDictionary) {
                startUsingContactsDictionaryLocked();
            } else {
                stopUsingContactsDictionaryLocked();
            }
        }
    }

    private void startUsingContactsDictionaryLocked() {
        if (null == mContactsDictionary) {
            // TODO: use the right locale for each session
            mContactsDictionary =
                    new SynchronouslyLoadedContactsBinaryDictionary(this, Locale.getDefault());
        }
        final Iterator<WeakReference<DictionaryCollection>> iterator =
                mDictionaryCollectionsList.iterator();
        while (iterator.hasNext()) {
            final WeakReference<DictionaryCollection> dictRef = iterator.next();
            final DictionaryCollection dict = dictRef.get();
            if (null == dict) {
                iterator.remove();
            } else {
                dict.addDictionary(mContactsDictionary);
            }
        }
    }

    private void stopUsingContactsDictionaryLocked() {
        if (null == mContactsDictionary) return;
        final Dictionary contactsDict = mContactsDictionary;
        // TODO: revert to the concrete type when USE_BINARY_CONTACTS_DICTIONARY is no longer needed
        mContactsDictionary = null;
        final Iterator<WeakReference<DictionaryCollection>> iterator =
                mDictionaryCollectionsList.iterator();
        while (iterator.hasNext()) {
            final WeakReference<DictionaryCollection> dictRef = iterator.next();
            final DictionaryCollection dict = dictRef.get();
            if (null == dict) {
                iterator.remove();
            } else {
                dict.removeDictionary(contactsDict);
            }
        }
        contactsDict.close();
    }

    @Override
    public Session createSession() {
        // Should not refer to AndroidSpellCheckerSession directly considering
        // that AndroidSpellCheckerSession may be overlaid.
        return AndroidSpellCheckerSessionFactory.newInstance(this);
    }

    /**
     * Returns an empty SuggestionsInfo with flags signaling the word is not in the dictionary.
     * @param reportAsTypo whether this should include the flag LOOKS_LIKE_TYPO, for red underline.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getNotInDictEmptySuggestions(final boolean reportAsTypo) {
        return new SuggestionsInfo(reportAsTypo ? SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO : 0,
                EMPTY_STRING_ARRAY);
    }

    /**
     * Returns an empty suggestionInfo with flags signaling the word is in the dictionary.
     * @return the empty SuggestionsInfo with the appropriate flags set.
     */
    public static SuggestionsInfo getInDictEmptySuggestions() {
        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                EMPTY_STRING_ARRAY);
    }

    public SuggestionsGatherer newSuggestionsGatherer(final String text, int maxLength) {
        return new SuggestionsGatherer(text, mRecommendedThreshold, maxLength);
    }

    // TODO: remove this class and replace it by storage local to the session.
    public static final class SuggestionsGatherer {
        public static final class Result {
            public final String[] mSuggestions;
            public final boolean mHasRecommendedSuggestions;
            public Result(final String[] gatheredSuggestions,
                    final boolean hasRecommendedSuggestions) {
                mSuggestions = gatheredSuggestions;
                mHasRecommendedSuggestions = hasRecommendedSuggestions;
            }
        }

        private final ArrayList<String> mSuggestions;
        private final int[] mScores;
        private final String mOriginalText;
        private final float mRecommendedThreshold;
        private final int mMaxLength;
        private int mLength = 0;

        // The two following attributes are only ever filled if the requested max length
        // is 0 (or less, which is treated the same).
        private String mBestSuggestion = null;
        private int mBestScore = Integer.MIN_VALUE; // As small as possible

        SuggestionsGatherer(final String originalText, final float recommendedThreshold,
                final int maxLength) {
            mOriginalText = originalText;
            mRecommendedThreshold = recommendedThreshold;
            mMaxLength = maxLength;
            mSuggestions = new ArrayList<>(maxLength + 1);
            mScores = new int[mMaxLength];
        }

        synchronized public boolean addWord(char[] word, int[] spaceIndices, int wordOffset,
                int wordLength, int score) {
            final int positionIndex = Arrays.binarySearch(mScores, 0, mLength, score);
            // binarySearch returns the index if the element exists, and -<insertion index> - 1
            // if it doesn't. See documentation for binarySearch.
            final int insertIndex = positionIndex >= 0 ? positionIndex : -positionIndex - 1;

            // Weak <- insertIndex == 0, ..., insertIndex == mLength -> Strong
            if (insertIndex == 0 && mLength >= mMaxLength) {
                // In the future, we may want to keep track of the best suggestion score even if
                // we are asked for 0 suggestions. In this case, we can use the following
                // (tested) code to keep it:
                // If the maxLength is 0 (should never be less, but if it is, it's treated as 0)
                // then we need to keep track of the best suggestion in mBestScore and
                // mBestSuggestion. This is so that we know whether the best suggestion makes
                // the score cutoff, since we need to know that to return a meaningful
                // looksLikeTypo.
                // if (0 >= mMaxLength) {
                //     if (score > mBestScore) {
                //         mBestScore = score;
                //         mBestSuggestion = new String(word, wordOffset, wordLength);
                //     }
                // }
                return true;
            }

            final String wordString = new String(word, wordOffset, wordLength);
            if (mLength < mMaxLength) {
                final int copyLen = mLength - insertIndex;
                ++mLength;
                System.arraycopy(mScores, insertIndex, mScores, insertIndex + 1, copyLen);
                mSuggestions.add(insertIndex, wordString);
                mScores[insertIndex] = score;
            } else {
                System.arraycopy(mScores, 1, mScores, 0, insertIndex - 1);
                mSuggestions.add(insertIndex, wordString);
                mSuggestions.remove(0);
                mScores[insertIndex - 1] = score;
            }

            return true;
        }

        public Result getResults(final int capitalizeType, final Locale locale) {
            final String[] gatheredSuggestions;
            final boolean hasRecommendedSuggestions;
            if (0 == mLength) {
                // TODO: the comment below describes what is intended, but in the practice
                // mBestSuggestion is only ever set to null so it doesn't work. Fix this.
                // Either we found no suggestions, or we found some BUT the max length was 0.
                // If we found some mBestSuggestion will not be null. If it is null, then
                // we found none, regardless of the max length.
                if (null == mBestSuggestion) {
                    gatheredSuggestions = null;
                    hasRecommendedSuggestions = false;
                } else {
                    gatheredSuggestions = EMPTY_STRING_ARRAY;
                    final float normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(
                            mOriginalText, mBestSuggestion, mBestScore);
                    hasRecommendedSuggestions = (normalizedScore > mRecommendedThreshold);
                }
            } else {
                if (DBG) {
                    if (mLength != mSuggestions.size()) {
                        Log.e(TAG, "Suggestion size is not the same as stored mLength");
                    }
                    for (int i = mLength - 1; i >= 0; --i) {
                        Log.i(TAG, "" + mScores[i] + " " + mSuggestions.get(i));
                    }
                }
                Collections.reverse(mSuggestions);
                StringUtils.removeDupes(mSuggestions);
                if (StringUtils.CAPITALIZE_ALL == capitalizeType) {
                    for (int i = 0; i < mSuggestions.size(); ++i) {
                        // get(i) returns a CharSequence which is actually a String so .toString()
                        // should return the same object.
                        mSuggestions.set(i, mSuggestions.get(i).toString().toUpperCase(locale));
                    }
                } else if (StringUtils.CAPITALIZE_FIRST == capitalizeType) {
                    for (int i = 0; i < mSuggestions.size(); ++i) {
                        // Likewise
                        mSuggestions.set(i, StringUtils.capitalizeFirstCodePoint(
                                mSuggestions.get(i).toString(), locale));
                    }
                }
                // This returns a String[], while toArray() returns an Object[] which cannot be cast
                // into a String[].
                gatheredSuggestions = mSuggestions.toArray(EMPTY_STRING_ARRAY);

                final int bestScore = mScores[mLength - 1];
                final String bestSuggestion = mSuggestions.get(0);
                final float normalizedScore =
                        BinaryDictionaryUtils.calcNormalizedScore(
                                mOriginalText, bestSuggestion.toString(), bestScore);
                hasRecommendedSuggestions = (normalizedScore > mRecommendedThreshold);
                if (DBG) {
                    Log.i(TAG, "Best suggestion : " + bestSuggestion + ", score " + bestScore);
                    Log.i(TAG, "Normalized score = " + normalizedScore
                            + " (threshold " + mRecommendedThreshold
                            + ") => hasRecommendedSuggestions = " + hasRecommendedSuggestions);
                }
            }
            return new Result(gatheredSuggestions, hasRecommendedSuggestions);
        }
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        closeAllDictionaries();
        return false;
    }

    private void closeAllDictionaries() {
        final Map<String, DictionaryPool> oldPools = mDictionaryPools;
        mDictionaryPools = CollectionUtils.newSynchronizedTreeMap();
        final Map<String, UserBinaryDictionary> oldUserDictionaries = mUserDictionaries;
        mUserDictionaries = CollectionUtils.newSynchronizedTreeMap();
        new Thread("spellchecker_close_dicts") {
            @Override
            public void run() {
                // Contacts dictionary can be closed multiple times here. If the dictionary is
                // already closed, extra closings are no-ops, so it's safe.
                for (DictionaryPool pool : oldPools.values()) {
                    pool.close();
                }
                for (Dictionary dict : oldUserDictionaries.values()) {
                    dict.close();
                }
                synchronized (mUseContactsLock) {
                    if (null != mContactsDictionary) {
                        // The synchronously loaded contacts dictionary should have been in one
                        // or several pools, but it is shielded against multiple closing and it's
                        // safe to call it several times.
                        final ContactsBinaryDictionary dictToClose = mContactsDictionary;
                        // TODO: revert to the concrete type when USE_BINARY_CONTACTS_DICTIONARY
                        // is no longer needed
                        mContactsDictionary = null;
                        dictToClose.close();
                    }
                }
            }
        }.start();
    }

    public DictionaryPool getDictionaryPool(final String locale) {
        DictionaryPool pool = mDictionaryPools.get(locale);
        if (null == pool) {
            final Locale localeObject = LocaleUtils.constructLocaleFromString(locale);
            pool = new DictionaryPool(POOL_SIZE, this, localeObject);
            mDictionaryPools.put(locale, pool);
        }
        return pool;
    }

    public DictAndKeyboard createDictAndKeyboard(final Locale locale) {
        final int script = ScriptUtils.getScriptFromSpellCheckerLocale(locale);
        final String keyboardLayoutName = getKeyboardLayoutNameForScript(script);
        final InputMethodSubtype subtype = AdditionalSubtypeUtils.createDummyAdditionalSubtype(
                locale.toString(), keyboardLayoutName);
        final KeyboardLayoutSet keyboardLayoutSet = createKeyboardSetForSpellChecker(subtype);

        final DictionaryCollection dictionaryCollection =
                DictionaryFactory.createMainDictionaryFromManager(this, locale,
                        true /* useFullEditDistance */);
        final String localeStr = locale.toString();
        UserBinaryDictionary userDictionary = mUserDictionaries.get(localeStr);
        if (null == userDictionary) {
            userDictionary = new SynchronouslyLoadedUserBinaryDictionary(this, locale, true);
            mUserDictionaries.put(localeStr, userDictionary);
        }
        dictionaryCollection.addDictionary(userDictionary);
        synchronized (mUseContactsLock) {
            if (mUseContactsDictionary) {
                if (null == mContactsDictionary) {
                    // TODO: use the right locale. We can't do it right now because the
                    // spell checker is reusing the contacts dictionary across sessions
                    // without regard for their locale, so we need to fix that first.
                    mContactsDictionary = new SynchronouslyLoadedContactsBinaryDictionary(this,
                            Locale.getDefault());
                }
            }
            dictionaryCollection.addDictionary(mContactsDictionary);
            mDictionaryCollectionsList.add(new WeakReference<>(dictionaryCollection));
        }
        return new DictAndKeyboard(dictionaryCollection, keyboardLayoutSet);
    }

    private KeyboardLayoutSet createKeyboardSetForSpellChecker(final InputMethodSubtype subtype) {
        final EditorInfo editorInfo = new EditorInfo();
        editorInfo.inputType = InputType.TYPE_CLASS_TEXT;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(this, editorInfo);
        builder.setKeyboardGeometry(
                SPELLCHECKER_DUMMY_KEYBOARD_WIDTH, SPELLCHECKER_DUMMY_KEYBOARD_HEIGHT);
        builder.setSubtype(subtype);
        builder.setIsSpellChecker(true /* isSpellChecker */);
        builder.disableTouchPositionCorrectionData();
        return builder.build();
    }
}
