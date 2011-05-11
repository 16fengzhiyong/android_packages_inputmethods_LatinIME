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

package com.android.inputmethod.latin;

import com.android.inputmethod.compat.InputConnectionCompatUtils;
import com.android.inputmethod.deprecated.VoiceProxy;
import com.android.inputmethod.keyboard.KeyboardSwitcher;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;

/**
 * Manager of re-correction functionalities
 */
public class Recorrection {
    private static final Recorrection sInstance = new Recorrection();

    private LatinIME mService;
    private boolean mRecorrectionEnabled = false;
    private final ArrayList<WordAlternatives> mWordHistory = new ArrayList<WordAlternatives>();

    public static Recorrection getInstance() {
        return sInstance;
    }

    public static void init(LatinIME context, SharedPreferences prefs) {
        if (context == null || prefs == null) {
            return;
        }
        sInstance.initInternal(context, prefs);
    }

    private Recorrection() {
    }

    public boolean isRecorrectionEnabled() {
        return mRecorrectionEnabled;
    }

    private void initInternal(LatinIME context, SharedPreferences prefs) {
        final Resources res = context.getResources();
        // If the option should not be shown, do not read the re-correction preference
        // but always use the default setting defined in the resources.
        if (res.getBoolean(R.bool.config_enable_show_recorrection_option)) {
            mRecorrectionEnabled = prefs.getBoolean(Settings.PREF_RECORRECTION_ENABLED,
                    res.getBoolean(R.bool.config_default_recorrection_enabled));
        } else {
            mRecorrectionEnabled = res.getBoolean(R.bool.config_default_recorrection_enabled);
        }
        mService = context;
    }

    public void checkRecorrectionOnStart() {
        if (!mRecorrectionEnabled) return;

        final InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        // There could be a pending composing span.  Clean it up first.
        ic.finishComposingText();

        if (mService.isShowingSuggestionsStrip() && mService.isSuggestionsRequested()) {
            // First get the cursor position. This is required by setOldSuggestions(), so that
            // it can pass the correct range to setComposingRegion(). At this point, we don't
            // have valid values for mLastSelectionStart/End because onUpdateSelection() has
            // not been called yet.
            ExtractedTextRequest etr = new ExtractedTextRequest();
            etr.token = 0; // anything is fine here
            ExtractedText et = ic.getExtractedText(etr, 0);
            if (et == null) return;
            mService.setLastSelection(
                    et.startOffset + et.selectionStart, et.startOffset + et.selectionEnd);

            // Then look for possible corrections in a delayed fashion
            if (!TextUtils.isEmpty(et.text) && mService.isCursorTouchingWord()) {
                mService.mHandler.postUpdateOldSuggestions();
            }
        }
    }

    public void updateRecorrectionSelection(KeyboardSwitcher keyboardSwitcher,
            CandidateView candidateView, int candidatesStart, int candidatesEnd, int newSelStart,
            int newSelEnd, int oldSelStart, int lastSelectionStart,
            int lastSelectionEnd, boolean hasUncommittedTypedChars) {
        if (mRecorrectionEnabled && mService.isShowingSuggestionsStrip()) {
            // Don't look for corrections if the keyboard is not visible
            if (keyboardSwitcher.isInputViewShown()) {
                // Check if we should go in or out of correction mode.
                if (mService.isSuggestionsRequested()
                        && (candidatesStart == candidatesEnd || newSelStart != oldSelStart
                                || TextEntryState.isRecorrecting())
                                && (newSelStart < newSelEnd - 1 || !hasUncommittedTypedChars)) {
                    if (mService.isCursorTouchingWord() || lastSelectionStart < lastSelectionEnd) {
                        mService.mHandler.cancelUpdateBigramPredictions();
                        mService.mHandler.postUpdateOldSuggestions();
                    } else {
                        abortRecorrection(false);
                        // If showing the "touch again to save" hint, do not replace it. Else,
                        // show the bigrams if we are at the end of the text, punctuation otherwise.
                        if (candidateView != null
                                && !candidateView.isShowingAddToDictionaryHint()) {
                            InputConnection ic = mService.getCurrentInputConnection();
                            if (null == ic || !TextUtils.isEmpty(ic.getTextAfterCursor(1, 0))) {
                                if (!mService.isShowingPunctuationList()) {
                                    mService.setPunctuationSuggestions();
                                }
                            } else {
                                mService.mHandler.postUpdateBigramPredictions();
                            }
                        }
                    }
                }
            }
        }
    }

    public void saveWordInHistory(WordComposer word, CharSequence result) {
        if (word.size() <= 1) {
            return;
        }
        // Skip if result is null. It happens in some edge case.
        if (TextUtils.isEmpty(result)) {
            return;
        }

        // Make a copy of the CharSequence, since it is/could be a mutable CharSequence
        final String resultCopy = result.toString();
        WordAlternatives entry = new WordAlternatives(resultCopy, new WordComposer(word));
        mWordHistory.add(entry);
    }

    public void clearWordsInHistory() {
        mWordHistory.clear();
    }

    /**
     * Tries to apply any typed alternatives for the word if we have any cached alternatives,
     * otherwise tries to find new corrections and completions for the word.
     * @param touching The word that the cursor is touching, with position information
     * @return true if an alternative was found, false otherwise.
     */
    public boolean applyTypedAlternatives(WordComposer word, Suggest suggest,
            KeyboardSwitcher keyboardSwitcher, EditingUtils.SelectedWord touching) {
        // If we didn't find a match, search for result in typed word history
        WordComposer foundWord = null;
        WordAlternatives alternatives = null;
        // Search old suggestions to suggest re-corrected suggestions.
        for (WordAlternatives entry : mWordHistory) {
            if (TextUtils.equals(entry.getChosenWord(), touching.mWord)) {
                foundWord = entry.mWordComposer;
                alternatives = entry;
                break;
            }
        }
        // If we didn't find a match, at least suggest corrections as re-corrected suggestions.
        if (foundWord == null
                && (AutoCorrection.isValidWord(suggest.getUnigramDictionaries(),
                        touching.mWord, true))) {
            foundWord = new WordComposer();
            for (int i = 0; i < touching.mWord.length(); i++) {
                foundWord.add(touching.mWord.charAt(i),
                        new int[] { touching.mWord.charAt(i) }, WordComposer.NOT_A_COORDINATE,
                        WordComposer.NOT_A_COORDINATE);
            }
            foundWord.setFirstCharCapitalized(Character.isUpperCase(touching.mWord.charAt(0)));
        }
        // Found a match, show suggestions
        if (foundWord != null || alternatives != null) {
            if (alternatives == null) {
                alternatives = new WordAlternatives(touching.mWord, foundWord);
            }
            showRecorrections(suggest, keyboardSwitcher, alternatives);
            if (foundWord != null) {
                word.init(foundWord);
            } else {
                word.reset();
            }
            return true;
        }
        return false;
    }


    private void showRecorrections(Suggest suggest, KeyboardSwitcher keyboardSwitcher,
            WordAlternatives alternatives) {
        SuggestedWords.Builder builder = alternatives.getAlternatives(suggest, keyboardSwitcher);
        builder.setTypedWordValid(false).setHasMinimalSuggestion(false);
        mService.showSuggestions(builder.build(), alternatives.getOriginalWord());
    }

    public void setRecorrectionSuggestions(VoiceProxy voiceProxy, CandidateView candidateView,
            Suggest suggest, KeyboardSwitcher keyboardSwitcher, WordComposer word,
            boolean hasUncommittedTypedChars, int lastSelectionStart, int lastSelectionEnd,
            String wordSeparators) {
        if (!InputConnectionCompatUtils.RECORRECTION_SUPPORTED) return;
        voiceProxy.setShowingVoiceSuggestions(false);
        if (candidateView != null && candidateView.isShowingAddToDictionaryHint()) {
            return;
        }
        InputConnection ic = mService.getCurrentInputConnection();
        if (ic == null) return;
        if (!hasUncommittedTypedChars) {
            // Extract the selected or touching text
            EditingUtils.SelectedWord touching = EditingUtils.getWordAtCursorOrSelection(ic,
                    lastSelectionStart, lastSelectionEnd, wordSeparators);

            if (touching != null && touching.mWord.length() > 1) {
                ic.beginBatchEdit();

                if (applyTypedAlternatives(word, suggest, keyboardSwitcher, touching)
                        || voiceProxy.applyVoiceAlternatives(touching)) {
                    TextEntryState.selectedForRecorrection();
                    InputConnectionCompatUtils.underlineWord(ic, touching);
                } else {
                    abortRecorrection(true);
                }

                ic.endBatchEdit();
            } else {
                abortRecorrection(true);
                mService.setPunctuationSuggestions();  // Show the punctuation suggestions list
            }
        } else {
            abortRecorrection(true);
        }
    }

    public void abortRecorrection(boolean force) {
        if (force || TextEntryState.isRecorrecting()) {
            TextEntryState.onAbortRecorrection();
            mService.setCandidatesViewShown(mService.isCandidateStripVisible());
            mService.getCurrentInputConnection().finishComposingText();
            mService.clearSuggestions();
        }
    }
}
