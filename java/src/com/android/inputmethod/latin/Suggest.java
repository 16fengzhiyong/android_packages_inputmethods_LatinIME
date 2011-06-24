/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.inputmethod.latin;

import android.content.Context;
import android.text.AutoText;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * This class loads a dictionary and provides a list of suggestions for a given sequence of
 * characters. This includes corrections and completions.
 */
public class Suggest implements Dictionary.WordCallback {

    public static final String TAG = Suggest.class.getSimpleName();

    public static final int APPROX_MAX_WORD_LENGTH = 32;

    public static final int CORRECTION_NONE = 0;
    public static final int CORRECTION_BASIC = 1;
    public static final int CORRECTION_FULL = 2;
    public static final int CORRECTION_FULL_BIGRAM = 3;

    /**
     * Words that appear in both bigram and unigram data gets multiplier ranging from
     * BIGRAM_MULTIPLIER_MIN to BIGRAM_MULTIPLIER_MAX depending on the score from
     * bigram data.
     */
    public static final double BIGRAM_MULTIPLIER_MIN = 1.2;
    public static final double BIGRAM_MULTIPLIER_MAX = 1.5;

    /**
     * Maximum possible bigram frequency. Will depend on how many bits are being used in data
     * structure. Maximum bigram frequency will get the BIGRAM_MULTIPLIER_MAX as the multiplier.
     */
    public static final int MAXIMUM_BIGRAM_FREQUENCY = 127;

    public static final int DIC_USER_TYPED = 0;
    public static final int DIC_MAIN = 1;
    public static final int DIC_USER = 2;
    public static final int DIC_AUTO = 3;
    public static final int DIC_CONTACTS = 4;
    // If you add a type of dictionary, increment DIC_TYPE_LAST_ID
    public static final int DIC_TYPE_LAST_ID = 4;

    public static final String DICT_KEY_MAIN = "main";
    public static final String DICT_KEY_CONTACTS = "contacts";
    public static final String DICT_KEY_AUTO = "auto";
    public static final String DICT_KEY_USER = "user";
    public static final String DICT_KEY_USER_BIGRAM = "user_bigram";
    public static final String DICT_KEY_WHITELIST ="whitelist";

    private static final boolean DBG = LatinImeLogger.sDBG;

    private AutoCorrection mAutoCorrection;

    private Dictionary mMainDict;
    private WhitelistDictionary mWhiteListDictionary;
    private final Map<String, Dictionary> mUnigramDictionaries = new HashMap<String, Dictionary>();
    private final Map<String, Dictionary> mBigramDictionaries = new HashMap<String, Dictionary>();

    private int mPrefMaxSuggestions = 18;

    private static final int PREF_MAX_BIGRAMS = 60;

    private boolean mQuickFixesEnabled;

    private double mAutoCorrectionThreshold;
    private int[] mScores = new int[mPrefMaxSuggestions];
    private int[] mBigramScores = new int[PREF_MAX_BIGRAMS];

    private ArrayList<CharSequence> mSuggestions = new ArrayList<CharSequence>();
    ArrayList<CharSequence> mBigramSuggestions  = new ArrayList<CharSequence>();
    private ArrayList<CharSequence> mStringPool = new ArrayList<CharSequence>();
    private CharSequence mTypedWord;

    // TODO: Remove these member variables by passing more context to addWord() callback method
    private boolean mIsFirstCharCapitalized;
    private boolean mIsAllUpperCase;

    private int mCorrectionMode = CORRECTION_BASIC;

    public Suggest(Context context, int dictionaryResId, Locale locale) {
        init(context, DictionaryFactory.createDictionaryFromManager(context, locale,
                dictionaryResId));
    }

    /* package for test */ Suggest(Context context, File dictionary, long startOffset, long length,
            Flag[] flagArray) {
        init(null, DictionaryFactory.createDictionaryForTest(context, dictionary, startOffset,
                length, flagArray));
    }

    private void init(Context context, Dictionary mainDict) {
        mMainDict = mainDict;
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_MAIN, mainDict);
        addOrReplaceDictionary(mBigramDictionaries, DICT_KEY_MAIN, mainDict);
        mWhiteListDictionary = WhitelistDictionary.init(context);
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_WHITELIST, mWhiteListDictionary);
        mAutoCorrection = new AutoCorrection();
        initPool();
    }

    private void addOrReplaceDictionary(Map<String, Dictionary> dictionaries, String key,
            Dictionary dict) {
        final Dictionary oldDict = (dict == null)
                ? dictionaries.remove(key)
                : dictionaries.put(key, dict);
        if (oldDict != null && dict != oldDict) {
            oldDict.close();
        }
    }

    public void resetMainDict(Context context, int dictionaryResId, Locale locale) {
        final Dictionary newMainDict = DictionaryFactory.createDictionaryFromManager(
                context, locale, dictionaryResId);
        mMainDict = newMainDict;
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_MAIN, newMainDict);
        addOrReplaceDictionary(mBigramDictionaries, DICT_KEY_MAIN, newMainDict);
    }

    private void initPool() {
        for (int i = 0; i < mPrefMaxSuggestions; i++) {
            StringBuilder sb = new StringBuilder(getApproxMaxWordLength());
            mStringPool.add(sb);
        }
    }

    public void setQuickFixesEnabled(boolean enabled) {
        mQuickFixesEnabled = enabled;
    }

    public int getCorrectionMode() {
        return mCorrectionMode;
    }

    public void setCorrectionMode(int mode) {
        mCorrectionMode = mode;
    }

    public boolean hasMainDictionary() {
        return mMainDict != null;
    }

    public Map<String, Dictionary> getUnigramDictionaries() {
        return mUnigramDictionaries;
    }

    public int getApproxMaxWordLength() {
        return APPROX_MAX_WORD_LENGTH;
    }

    /**
     * Sets an optional user dictionary resource to be loaded. The user dictionary is consulted
     * before the main dictionary, if set.
     */
    public void setUserDictionary(Dictionary userDictionary) {
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_USER, userDictionary);
    }

    /**
     * Sets an optional contacts dictionary resource to be loaded. It is also possible to remove
     * the contacts dictionary by passing null to this method. In this case no contacts dictionary
     * won't be used.
     */
    public void setContactsDictionary(Dictionary contactsDictionary) {
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_CONTACTS, contactsDictionary);
        addOrReplaceDictionary(mBigramDictionaries, DICT_KEY_CONTACTS, contactsDictionary);
    }

    public void setAutoDictionary(Dictionary autoDictionary) {
        addOrReplaceDictionary(mUnigramDictionaries, DICT_KEY_AUTO, autoDictionary);
    }

    public void setUserBigramDictionary(Dictionary userBigramDictionary) {
        addOrReplaceDictionary(mBigramDictionaries, DICT_KEY_USER_BIGRAM, userBigramDictionary);
    }

    public void setAutoCorrectionThreshold(double threshold) {
        mAutoCorrectionThreshold = threshold;
    }

    public boolean isAggressiveAutoCorrectionMode() {
        return (mAutoCorrectionThreshold == 0);
    }

    /**
     * Number of suggestions to generate from the input key sequence. This has
     * to be a number between 1 and 100 (inclusive).
     * @param maxSuggestions
     * @throws IllegalArgumentException if the number is out of range
     */
    public void setMaxSuggestions(int maxSuggestions) {
        if (maxSuggestions < 1 || maxSuggestions > 100) {
            throw new IllegalArgumentException("maxSuggestions must be between 1 and 100");
        }
        mPrefMaxSuggestions = maxSuggestions;
        mScores = new int[mPrefMaxSuggestions];
        mBigramScores = new int[PREF_MAX_BIGRAMS];
        collectGarbage(mSuggestions, mPrefMaxSuggestions);
        while (mStringPool.size() < mPrefMaxSuggestions) {
            StringBuilder sb = new StringBuilder(getApproxMaxWordLength());
            mStringPool.add(sb);
        }
    }

    /**
     * Returns a object which represents suggested words that match the list of character codes
     * passed in. This object contents will be overwritten the next time this function is called.
     * @param view a view for retrieving the context for AutoText
     * @param wordComposer contains what is currently being typed
     * @param prevWordForBigram previous word (used only for bigram)
     * @return suggested words object.
     */
    public SuggestedWords getSuggestions(View view, WordComposer wordComposer,
            CharSequence prevWordForBigram) {
        return getSuggestedWordBuilder(view, wordComposer, prevWordForBigram).build();
    }

    private CharSequence capitalizeWord(boolean all, boolean first, CharSequence word) {
        if (TextUtils.isEmpty(word) || !(all || first)) return word;
        final int wordLength = word.length();
        final int poolSize = mStringPool.size();
        final StringBuilder sb =
                poolSize > 0 ? (StringBuilder) mStringPool.remove(poolSize - 1)
                        : new StringBuilder(getApproxMaxWordLength());
        sb.setLength(0);
        // TODO: Must pay attention to locale when changing case.
        if (all) {
            sb.append(word.toString().toUpperCase());
        } else if (first) {
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (wordLength > 1) {
                sb.append(word.subSequence(1, wordLength));
            }
        }
        return sb;
    }

    protected void addBigramToSuggestions(CharSequence bigram) {
        final int poolSize = mStringPool.size();
        final StringBuilder sb = poolSize > 0 ?
                (StringBuilder) mStringPool.remove(poolSize - 1)
                        : new StringBuilder(getApproxMaxWordLength());
        sb.setLength(0);
        sb.append(bigram);
        mSuggestions.add(sb);
    }

    // TODO: cleanup dictionaries looking up and suggestions building with SuggestedWords.Builder
    public SuggestedWords.Builder getSuggestedWordBuilder(View view, WordComposer wordComposer,
            CharSequence prevWordForBigram) {
        LatinImeLogger.onStartSuggestion(prevWordForBigram);
        mAutoCorrection.init();
        mIsFirstCharCapitalized = wordComposer.isFirstCharCapitalized();
        mIsAllUpperCase = wordComposer.isAllUpperCase();
        collectGarbage(mSuggestions, mPrefMaxSuggestions);
        Arrays.fill(mScores, 0);

        // Save a lowercase version of the original word
        CharSequence typedWord = wordComposer.getTypedWord();
        if (typedWord != null) {
            final String typedWordString = typedWord.toString();
            typedWord = typedWordString;
            // Treating USER_TYPED as UNIGRAM suggestion for logging now.
            LatinImeLogger.onAddSuggestedWord(typedWordString, Suggest.DIC_USER_TYPED,
                    Dictionary.DataType.UNIGRAM);
        }
        mTypedWord = typedWord;

        if (wordComposer.size() <= 1 && (mCorrectionMode == CORRECTION_FULL_BIGRAM
                || mCorrectionMode == CORRECTION_BASIC)) {
            // At first character typed, search only the bigrams
            Arrays.fill(mBigramScores, 0);
            collectGarbage(mBigramSuggestions, PREF_MAX_BIGRAMS);

            if (!TextUtils.isEmpty(prevWordForBigram)) {
                CharSequence lowerPrevWord = prevWordForBigram.toString().toLowerCase();
                if (mMainDict != null && mMainDict.isValidWord(lowerPrevWord)) {
                    prevWordForBigram = lowerPrevWord;
                }
                for (final Dictionary dictionary : mBigramDictionaries.values()) {
                    dictionary.getBigrams(wordComposer, prevWordForBigram, this);
                }
                if (TextUtils.isEmpty(typedWord)) {
                    // Nothing entered: return all bigrams for the previous word
                    int insertCount = Math.min(mBigramSuggestions.size(), mPrefMaxSuggestions);
                    for (int i = 0; i < insertCount; ++i) {
                        addBigramToSuggestions(mBigramSuggestions.get(i));
                    }
                } else {
                    // Word entered: return only bigrams that match the first char of the typed word
                    final char currentChar = typedWord.charAt(0);
                    // TODO: Must pay attention to locale when changing case.
                    final char currentCharUpper = Character.toUpperCase(currentChar);
                    int count = 0;
                    final int bigramSuggestionSize = mBigramSuggestions.size();
                    for (int i = 0; i < bigramSuggestionSize; i++) {
                        final CharSequence bigramSuggestion = mBigramSuggestions.get(i);
                        final char bigramSuggestionFirstChar = bigramSuggestion.charAt(0);
                        if (bigramSuggestionFirstChar == currentChar
                                || bigramSuggestionFirstChar == currentCharUpper) {
                            addBigramToSuggestions(bigramSuggestion);
                            if (++count > mPrefMaxSuggestions) break;
                        }
                    }
                }
            }

        } else if (wordComposer.size() > 1) {
            // At second character typed, search the unigrams (scores being affected by bigrams)
            for (final String key : mUnigramDictionaries.keySet()) {
                // Skip AutoDictionary and WhitelistDictionary to lookup
                if (key.equals(DICT_KEY_AUTO) || key.equals(DICT_KEY_WHITELIST))
                    continue;
                final Dictionary dictionary = mUnigramDictionaries.get(key);
                dictionary.getWords(wordComposer, this);
            }
        }
        CharSequence autoText = null;
        final String typedWordString = typedWord == null ? null : typedWord.toString();
        if (typedWord != null) {
            // Apply quick fix only for the typed word.
            if (mQuickFixesEnabled) {
                final String lowerCaseTypedWord = typedWordString.toLowerCase();
                CharSequence tempAutoText = capitalizeWord(
                        mIsAllUpperCase, mIsFirstCharCapitalized, AutoText.get(
                                lowerCaseTypedWord, 0, lowerCaseTypedWord.length(), view));
                // TODO: cleanup canAdd
                // Is there an AutoText (also known as Quick Fixes) correction?
                // Capitalize as needed
                boolean canAdd = tempAutoText != null;
                // Is that correction already the current prediction (or original word)?
                canAdd &= !TextUtils.equals(tempAutoText, typedWord);
                // Is that correction already the next predicted word?
                if (canAdd && mSuggestions.size() > 0 && mCorrectionMode != CORRECTION_BASIC) {
                    canAdd &= !TextUtils.equals(tempAutoText, mSuggestions.get(0));
                }
                if (canAdd) {
                    if (DBG) {
                        Log.d(TAG, "Auto corrected by AUTOTEXT.");
                    }
                    autoText = tempAutoText;
                }
            }
        }

        CharSequence whitelistedWord = capitalizeWord(mIsAllUpperCase, mIsFirstCharCapitalized,
                mWhiteListDictionary.getWhiteListedWord(typedWordString));

        mAutoCorrection.updateAutoCorrectionStatus(mUnigramDictionaries, wordComposer,
                mSuggestions, mScores, typedWord, mAutoCorrectionThreshold, mCorrectionMode,
                autoText, whitelistedWord);

        if (autoText != null) {
            mSuggestions.add(0, autoText);
        }

        if (whitelistedWord != null) {
            mSuggestions.add(0, whitelistedWord);
        }

        if (typedWord != null) {
            mSuggestions.add(0, typedWordString);
        }
        removeDupes();

        if (DBG) {
            double normalizedScore = mAutoCorrection.getNormalizedScore();
            ArrayList<SuggestedWords.SuggestedWordInfo> scoreInfoList =
                    new ArrayList<SuggestedWords.SuggestedWordInfo>();
            scoreInfoList.add(new SuggestedWords.SuggestedWordInfo("+", false));
            for (int i = 0; i < mScores.length; ++i) {
                if (normalizedScore > 0) {
                    final String scoreThreshold = String.format("%d (%4.2f)", mScores[i],
                            normalizedScore);
                    scoreInfoList.add(
                            new SuggestedWords.SuggestedWordInfo(scoreThreshold, false));
                    normalizedScore = 0.0;
                } else {
                    final String score = Integer.toString(mScores[i]);
                    scoreInfoList.add(new SuggestedWords.SuggestedWordInfo(score, false));
                }
            }
            for (int i = mScores.length; i < mSuggestions.size(); ++i) {
                scoreInfoList.add(new SuggestedWords.SuggestedWordInfo("--", false));
            }
            return new SuggestedWords.Builder().addWords(mSuggestions, scoreInfoList);
        }
        return new SuggestedWords.Builder().addWords(mSuggestions, null);
    }

    private void removeDupes() {
        final ArrayList<CharSequence> suggestions = mSuggestions;
        if (suggestions.size() < 2) return;
        int i = 1;
        // Don't cache suggestions.size(), since we may be removing items
        while (i < suggestions.size()) {
            final CharSequence cur = suggestions.get(i);
            // Compare each candidate with each previous candidate
            for (int j = 0; j < i; j++) {
                CharSequence previous = suggestions.get(j);
                if (TextUtils.equals(cur, previous)) {
                    removeFromSuggestions(i);
                    i--;
                    break;
                }
            }
            i++;
        }
    }

    private void removeFromSuggestions(int index) {
        CharSequence garbage = mSuggestions.remove(index);
        if (garbage != null && garbage instanceof StringBuilder) {
            mStringPool.add(garbage);
        }
    }

    public boolean hasAutoCorrection() {
        return mAutoCorrection.hasAutoCorrection();
    }

    @Override
    public boolean addWord(final char[] word, final int offset, final int length, int score,
            final int dicTypeId, final Dictionary.DataType dataType) {
        Dictionary.DataType dataTypeForLog = dataType;
        final ArrayList<CharSequence> suggestions;
        final int[] sortedScores;
        final int prefMaxSuggestions;
        if(dataType == Dictionary.DataType.BIGRAM) {
            suggestions = mBigramSuggestions;
            sortedScores = mBigramScores;
            prefMaxSuggestions = PREF_MAX_BIGRAMS;
        } else {
            suggestions = mSuggestions;
            sortedScores = mScores;
            prefMaxSuggestions = mPrefMaxSuggestions;
        }

        int pos = 0;

        // Check if it's the same word, only caps are different
        if (Utils.equalsIgnoreCase(mTypedWord, word, offset, length)) {
            // TODO: remove this surrounding if clause and move this logic to
            // getSuggestedWordBuilder.
            if (suggestions.size() > 0) {
                final String currentHighestWord = suggestions.get(0).toString();
                // If the current highest word is also equal to typed word, we need to compare
                // frequency to determine the insertion position. This does not ensure strictly
                // correct ordering, but ensures the top score is on top which is enough for
                // removing duplicates correctly.
                if (Utils.equalsIgnoreCase(currentHighestWord, word, offset, length)
                        && score <= sortedScores[0]) {
                    pos = 1;
                }
            }
        } else {
            if (dataType == Dictionary.DataType.UNIGRAM) {
                // Check if the word was already added before (by bigram data)
                int bigramSuggestion = searchBigramSuggestion(word,offset,length);
                if(bigramSuggestion >= 0) {
                    dataTypeForLog = Dictionary.DataType.BIGRAM;
                    // turn freq from bigram into multiplier specified above
                    double multiplier = (((double) mBigramScores[bigramSuggestion])
                            / MAXIMUM_BIGRAM_FREQUENCY)
                            * (BIGRAM_MULTIPLIER_MAX - BIGRAM_MULTIPLIER_MIN)
                            + BIGRAM_MULTIPLIER_MIN;
                    /* Log.d(TAG,"bigram num: " + bigramSuggestion
                            + "  wordB: " + mBigramSuggestions.get(bigramSuggestion).toString()
                            + "  currentScore: " + score + "  bigramScore: "
                            + mBigramScores[bigramSuggestion]
                            + "  multiplier: " + multiplier); */
                    score = (int)Math.round((score * multiplier));
                }
            }

            // Check the last one's score and bail
            if (sortedScores[prefMaxSuggestions - 1] >= score) return true;
            while (pos < prefMaxSuggestions) {
                if (sortedScores[pos] < score
                        || (sortedScores[pos] == score && length < suggestions.get(pos).length())) {
                    break;
                }
                pos++;
            }
        }
        if (pos >= prefMaxSuggestions) {
            return true;
        }

        System.arraycopy(sortedScores, pos, sortedScores, pos + 1, prefMaxSuggestions - pos - 1);
        sortedScores[pos] = score;
        int poolSize = mStringPool.size();
        StringBuilder sb = poolSize > 0 ? (StringBuilder) mStringPool.remove(poolSize - 1)
                : new StringBuilder(getApproxMaxWordLength());
        sb.setLength(0);
        // TODO: Must pay attention to locale when changing case.
        if (mIsAllUpperCase) {
            sb.append(new String(word, offset, length).toUpperCase());
        } else if (mIsFirstCharCapitalized) {
            sb.append(Character.toUpperCase(word[offset]));
            if (length > 1) {
                sb.append(word, offset + 1, length - 1);
            }
        } else {
            sb.append(word, offset, length);
        }
        suggestions.add(pos, sb);
        if (suggestions.size() > prefMaxSuggestions) {
            CharSequence garbage = suggestions.remove(prefMaxSuggestions);
            if (garbage instanceof StringBuilder) {
                mStringPool.add(garbage);
            }
        } else {
            LatinImeLogger.onAddSuggestedWord(sb.toString(), dicTypeId, dataTypeForLog);
        }
        return true;
    }

    private int searchBigramSuggestion(final char[] word, final int offset, final int length) {
        // TODO This is almost O(n^2). Might need fix.
        // search whether the word appeared in bigram data
        int bigramSuggestSize = mBigramSuggestions.size();
        for(int i = 0; i < bigramSuggestSize; i++) {
            if(mBigramSuggestions.get(i).length() == length) {
                boolean chk = true;
                for(int j = 0; j < length; j++) {
                    if(mBigramSuggestions.get(i).charAt(j) != word[offset+j]) {
                        chk = false;
                        break;
                    }
                }
                if(chk) return i;
            }
        }

        return -1;
    }

    private void collectGarbage(ArrayList<CharSequence> suggestions, int prefMaxSuggestions) {
        int poolSize = mStringPool.size();
        int garbageSize = suggestions.size();
        while (poolSize < prefMaxSuggestions && garbageSize > 0) {
            CharSequence garbage = suggestions.get(garbageSize - 1);
            if (garbage != null && garbage instanceof StringBuilder) {
                mStringPool.add(garbage);
                poolSize++;
            }
            garbageSize--;
        }
        if (poolSize == prefMaxSuggestions + 1) {
            Log.w("Suggest", "String pool got too big: " + poolSize);
        }
        suggestions.clear();
    }

    public void close() {
        final Set<Dictionary> dictionaries = new HashSet<Dictionary>();
        dictionaries.addAll(mUnigramDictionaries.values());
        dictionaries.addAll(mBigramDictionaries.values());
        for (final Dictionary dictionary : dictionaries) {
            dictionary.close();
        }
        mMainDict = null;
    }
}
