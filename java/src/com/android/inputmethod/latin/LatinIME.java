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

import static com.android.inputmethod.latin.Constants.ImeOption.FORCE_ASCII;
import static com.android.inputmethod.latin.Constants.ImeOption.NO_MICROPHONE;
import static com.android.inputmethod.latin.Constants.ImeOption.NO_MICROPHONE_COMPAT;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.accessibility.AccessibleKeyboardViewProxy;
import com.android.inputmethod.annotations.UsedForTesting;
import com.android.inputmethod.compat.CompatUtils;
import com.android.inputmethod.compat.InputMethodServiceCompatUtils;
import com.android.inputmethod.compat.SuggestionSpanUtils;
import com.android.inputmethod.event.EventInterpreter;
import com.android.inputmethod.keyboard.KeyDetector;
import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.KeyboardActionListener;
import com.android.inputmethod.keyboard.KeyboardId;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.keyboard.KeyboardView;
import com.android.inputmethod.keyboard.MainKeyboardView;
import com.android.inputmethod.latin.LocaleUtils.RunInLocale;
import com.android.inputmethod.latin.Utils.Stats;
import com.android.inputmethod.latin.define.ProductionFlag;
import com.android.inputmethod.latin.suggestions.SuggestionStripView;
import com.android.inputmethod.research.ResearchLogger;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public final class LatinIME extends InputMethodService implements KeyboardActionListener,
        SuggestionStripView.Listener, TargetApplicationGetter.OnTargetApplicationKnownListener,
        Suggest.SuggestInitializationListener {
    private static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;
    private static boolean DEBUG;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;

    // How many continuous deletes at which to start deleting at a higher speed.
    private static final int DELETE_ACCELERATE_AT = 20;
    // Key events coming any faster than this are long-presses.
    private static final int QUICK_PRESS = 200;

    private static final int PENDING_IMS_CALLBACK_DURATION = 800;

    /**
     * The name of the scheme used by the Package Manager to warn of a new package installation,
     * replacement or removal.
     */
    private static final String SCHEME_PACKAGE = "package";

    private static final int SPACE_STATE_NONE = 0;
    // Double space: the state where the user pressed space twice quickly, which LatinIME
    // resolved as period-space. Undoing this converts the period to a space.
    private static final int SPACE_STATE_DOUBLE = 1;
    // Swap punctuation: the state where a weak space and a punctuation from the suggestion strip
    // have just been swapped. Undoing this swaps them back; the space is still considered weak.
    private static final int SPACE_STATE_SWAP_PUNCTUATION = 2;
    // Weak space: a space that should be swapped only by suggestion strip punctuation. Weak
    // spaces happen when the user presses space, accepting the current suggestion (whether
    // it's an auto-correction or not).
    private static final int SPACE_STATE_WEAK = 3;
    // Phantom space: a not-yet-inserted space that should get inserted on the next input,
    // character provided it's not a separator. If it's a separator, the phantom space is dropped.
    // Phantom spaces happen when a user chooses a word from the suggestion strip.
    private static final int SPACE_STATE_PHANTOM = 4;

    // Current space state of the input method. This can be any of the above constants.
    private int mSpaceState;

    private SettingsValues mCurrentSettings;

    private View mExtractArea;
    private View mKeyPreviewBackingView;
    private View mSuggestionsContainer;
    private SuggestionStripView mSuggestionStripView;
    @UsedForTesting Suggest mSuggest;
    private CompletionInfo[] mApplicationSpecifiedCompletions;
    private ApplicationInfo mTargetApplicationInfo;

    private RichInputMethodManager mRichImm;
    private Resources mResources;
    private SharedPreferences mPrefs;
    @UsedForTesting final KeyboardSwitcher mKeyboardSwitcher;
    private final SubtypeSwitcher mSubtypeSwitcher;
    private final SubtypeState mSubtypeState = new SubtypeState();
    // At start, create a default event interpreter that does nothing by passing it no decoder spec.
    // The event interpreter should never be null.
    private EventInterpreter mEventInterpreter = new EventInterpreter();

    private boolean mIsMainDictionaryAvailable;
    private UserBinaryDictionary mUserDictionary;
    private UserHistoryDictionary mUserHistoryDictionary;
    private boolean mIsUserDictionaryAvailable;

    private LastComposedWord mLastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD;
    private PositionalInfoForUserDictPendingAddition
            mPositionalInfoForUserDictPendingAddition = null;
    private final WordComposer mWordComposer = new WordComposer();
    private RichInputConnection mConnection = new RichInputConnection(this);

    // Keep track of the last selection range to decide if we need to show word alternatives
    private static final int NOT_A_CURSOR_POSITION = -1;
    private int mLastSelectionStart = NOT_A_CURSOR_POSITION;
    private int mLastSelectionEnd = NOT_A_CURSOR_POSITION;

    // Whether we are expecting an onUpdateSelection event to fire. If it does when we don't
    // "expect" it, it means the user actually moved the cursor.
    private boolean mExpectingUpdateSelection;
    private int mDeleteCount;
    private long mLastKeyTime;

    // Member variables for remembering the current device orientation.
    private int mDisplayOrientation;

    // Object for reacting to adding/removing a dictionary pack.
    // TODO: The experimental version is not supported by the Dictionary Pack Service yet.
    private BroadcastReceiver mDictionaryPackInstallReceiver =
            ProductionFlag.IS_EXPERIMENTAL
                    ? null : new DictionaryPackInstallBroadcastReceiver(this);

    // Keeps track of most recently inserted text (multi-character key) for reverting
    private String mEnteredText;

    private boolean mIsAutoCorrectionIndicatorOn;

    private AlertDialog mOptionsDialog;

    private final boolean mIsHardwareAcceleratedDrawingEnabled;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends StaticInnerHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_UPDATE_SUGGESTION_STRIP = 2;
        private static final int MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP = 3;

        private static final int ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT = 1;

        private int mDelayUpdateSuggestions;
        private int mDelayUpdateShiftState;
        private long mDoubleSpacePeriodTimeout;
        private long mDoubleSpacePeriodTimerStart;

        public UIHandler(final LatinIME outerInstance) {
            super(outerInstance);
        }

        public void onCreate() {
            final Resources res = getOuterInstance().getResources();
            mDelayUpdateSuggestions =
                    res.getInteger(R.integer.config_delay_update_suggestions);
            mDelayUpdateShiftState =
                    res.getInteger(R.integer.config_delay_update_shift_state);
            mDoubleSpacePeriodTimeout =
                    res.getInteger(R.integer.config_double_space_period_timeout);
        }

        @Override
        public void handleMessage(final Message msg) {
            final LatinIME latinIme = getOuterInstance();
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            switch (msg.what) {
            case MSG_UPDATE_SUGGESTION_STRIP:
                latinIme.updateSuggestionStrip();
                break;
            case MSG_UPDATE_SHIFT_STATE:
                switcher.updateShiftState();
                break;
            case MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP:
                latinIme.showGesturePreviewAndSuggestionStrip((SuggestedWords)msg.obj,
                        msg.arg1 == ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT);
                break;
            }
        }

        public void postUpdateSuggestionStrip() {
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SUGGESTION_STRIP), mDelayUpdateSuggestions);
        }

        public void cancelUpdateSuggestionStrip() {
            removeMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public boolean hasPendingUpdateSuggestions() {
            return hasMessages(MSG_UPDATE_SUGGESTION_STRIP);
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessageDelayed(obtainMessage(MSG_UPDATE_SHIFT_STATE), mDelayUpdateShiftState);
        }

        public void cancelUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
        }

        public void showGesturePreviewAndSuggestionStrip(final SuggestedWords suggestedWords,
                final boolean dismissGestureFloatingPreviewText) {
            removeMessages(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP);
            final int arg1 = dismissGestureFloatingPreviewText
                    ? ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT : 0;
            obtainMessage(MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP, arg1, 0, suggestedWords)
                    .sendToTarget();
        }

        public void startDoubleSpacePeriodTimer() {
            mDoubleSpacePeriodTimerStart = SystemClock.uptimeMillis();
        }

        public void cancelDoubleSpacePeriodTimer() {
            mDoubleSpacePeriodTimerStart = 0;
        }

        public boolean isAcceptingDoubleSpacePeriod() {
            return SystemClock.uptimeMillis() - mDoubleSpacePeriodTimerStart
                    < mDoubleSpacePeriodTimeout;
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        public void startOrientationChanging() {
            removeMessages(MSG_PENDING_IMS_CALLBACK);
            resetPendingImsCallback();
            mIsOrientationChanging = true;
            final LatinIME latinIme = getOuterInstance();
            if (latinIme.isInputViewShown()) {
                latinIme.mKeyboardSwitcher.saveKeyboardState();
            }
        }

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                boolean restarting) {
            if (mHasPendingFinishInputView)
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            if (mHasPendingFinishInput)
                latinIme.onFinishInputInternal();
            if (mHasPendingStartInput)
                latinIme.onStartInputInternal(editorInfo, restarting);
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOuterInstance();
                executePendingImsCallback(latinIme, editorInfo, restarting);
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION);
                }
                final LatinIME latinIme = getOuterInstance();
                executePendingImsCallback(latinIme, editorInfo, restarting);
                latinIme.onStartInputViewInternal(editorInfo, restarting);
                mAppliedEditorInfo = editorInfo;
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOuterInstance();
                latinIme.onFinishInputViewInternal(finishingInput);
                mAppliedEditorInfo = null;
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOuterInstance();
                executePendingImsCallback(latinIme, null, false);
                latinIme.onFinishInputInternal();
            }
        }
    }

    static final class SubtypeState {
        private InputMethodSubtype mLastActiveSubtype;
        private boolean mCurrentSubtypeUsed;

        public void currentSubtypeUsed() {
            mCurrentSubtypeUsed = true;
        }

        public void switchSubtype(final IBinder token, final RichInputMethodManager richImm) {
            final InputMethodSubtype currentSubtype = richImm.getInputMethodManager()
                    .getCurrentInputMethodSubtype();
            final InputMethodSubtype lastActiveSubtype = mLastActiveSubtype;
            final boolean currentSubtypeUsed = mCurrentSubtypeUsed;
            if (currentSubtypeUsed) {
                mLastActiveSubtype = currentSubtype;
                mCurrentSubtypeUsed = false;
            }
            if (currentSubtypeUsed
                    && richImm.checkIfSubtypeBelongsToThisImeAndEnabled(lastActiveSubtype)
                    && !currentSubtype.equals(lastActiveSubtype)) {
                richImm.setInputMethodAndSubtype(token, lastActiveSubtype);
                return;
            }
            richImm.switchToNextInputMethod(token, true /* onlyCurrentIme */);
        }
    }

    public LatinIME() {
        super();
        mSubtypeSwitcher = SubtypeSwitcher.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        mIsHardwareAcceleratedDrawingEnabled =
                InputMethodServiceCompatUtils.enableHardwareAcceleration(this);
        Log.i(TAG, "Hardware accelerated drawing: " + mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void onCreate() {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mResources = getResources();

        LatinImeLogger.init(this, mPrefs);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.getInstance().init(this, mPrefs);
        }
        RichInputMethodManager.init(this, mPrefs);
        mRichImm = RichInputMethodManager.getInstance();
        SubtypeSwitcher.init(this);
        KeyboardSwitcher.init(this, mPrefs);
        AccessibilityUtils.init(this);

        super.onCreate();

        mHandler.onCreate();
        DEBUG = LatinImeLogger.sDBG;

        loadSettings();
        initSuggest();

        mDisplayOrientation = mResources.getConfiguration().orientation;

        // Register to receive ringer mode change and network state change.
        // Also receive installation and removal of a dictionary pack.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, filter);

        // TODO: The experimental version is not supported by the Dictionary Pack Service yet.
        if (!ProductionFlag.IS_EXPERIMENTAL) {
            final IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme(SCHEME_PACKAGE);
            registerReceiver(mDictionaryPackInstallReceiver, packageFilter);

            final IntentFilter newDictFilter = new IntentFilter();
            newDictFilter.addAction(
                    DictionaryPackInstallBroadcastReceiver.NEW_DICTIONARY_INTENT_ACTION);
            registerReceiver(mDictionaryPackInstallReceiver, newDictFilter);
        }
    }

    // Has to be package-visible for unit tests
    @UsedForTesting
    void loadSettings() {
        // Note that the calling sequence of onCreate() and onCurrentInputMethodSubtypeChanged()
        // is not guaranteed. It may even be called at the same time on a different thread.
        if (null == mPrefs) mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        final InputAttributes inputAttributes =
                new InputAttributes(getCurrentInputEditorInfo(), isFullscreenMode());
        final RunInLocale<SettingsValues> job = new RunInLocale<SettingsValues>() {
            @Override
            protected SettingsValues job(Resources res) {
                return new SettingsValues(mPrefs, inputAttributes, LatinIME.this);
            }
        };
        mCurrentSettings = job.runInLocale(mResources, mSubtypeSwitcher.getCurrentSubtypeLocale());
        resetContactsDictionary(null == mSuggest ? null : mSuggest.getContactsDictionary());
    }

    // Note that this method is called from a non-UI thread.
    @Override
    public void onUpdateMainDictionaryAvailability(final boolean isMainDictionaryAvailable) {
        mIsMainDictionaryAvailable = isMainDictionaryAvailable;
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.setMainDictionaryAvailability(isMainDictionaryAvailable);
        }
    }

    private void initSuggest() {
        final Locale subtypeLocale = mSubtypeSwitcher.getCurrentSubtypeLocale();
        final String localeStr = subtypeLocale.toString();

        final ContactsBinaryDictionary oldContactsDictionary;
        if (mSuggest != null) {
            oldContactsDictionary = mSuggest.getContactsDictionary();
            mSuggest.close();
        } else {
            oldContactsDictionary = null;
        }
        mSuggest = new Suggest(this /* Context */, subtypeLocale,
                this /* SuggestInitializationListener */);
        if (mCurrentSettings.mCorrectionEnabled) {
            mSuggest.setAutoCorrectionThreshold(mCurrentSettings.mAutoCorrectionThreshold);
        }

        mIsMainDictionaryAvailable = DictionaryFactory.isDictionaryAvailable(this, subtypeLocale);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.getInstance().initSuggest(mSuggest);
        }

        mUserDictionary = new UserBinaryDictionary(this, localeStr);
        mIsUserDictionaryAvailable = mUserDictionary.isEnabled();
        mSuggest.setUserDictionary(mUserDictionary);

        resetContactsDictionary(oldContactsDictionary);

        // Note that the calling sequence of onCreate() and onCurrentInputMethodSubtypeChanged()
        // is not guaranteed. It may even be called at the same time on a different thread.
        if (null == mPrefs) mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mUserHistoryDictionary = UserHistoryDictionary.getInstance(this, localeStr, mPrefs);
        mSuggest.setUserHistoryDictionary(mUserHistoryDictionary);
    }

    /**
     * Resets the contacts dictionary in mSuggest according to the user settings.
     *
     * This method takes an optional contacts dictionary to use when the locale hasn't changed
     * since the contacts dictionary can be opened or closed as necessary depending on the settings.
     *
     * @param oldContactsDictionary an optional dictionary to use, or null
     */
    private void resetContactsDictionary(final ContactsBinaryDictionary oldContactsDictionary) {
        final boolean shouldSetDictionary = (null != mSuggest && mCurrentSettings.mUseContactsDict);

        final ContactsBinaryDictionary dictionaryToUse;
        if (!shouldSetDictionary) {
            // Make sure the dictionary is closed. If it is already closed, this is a no-op,
            // so it's safe to call it anyways.
            if (null != oldContactsDictionary) oldContactsDictionary.close();
            dictionaryToUse = null;
        } else {
            final Locale locale = mSubtypeSwitcher.getCurrentSubtypeLocale();
            if (null != oldContactsDictionary) {
                if (!oldContactsDictionary.mLocale.equals(locale)) {
                    // If the locale has changed then recreate the contacts dictionary. This
                    // allows locale dependent rules for handling bigram name predictions.
                    oldContactsDictionary.close();
                    dictionaryToUse = new ContactsBinaryDictionary(this, locale);
                } else {
                    // Make sure the old contacts dictionary is opened. If it is already open,
                    // this is a no-op, so it's safe to call it anyways.
                    oldContactsDictionary.reopen(this);
                    dictionaryToUse = oldContactsDictionary;
                }
            } else {
                dictionaryToUse = new ContactsBinaryDictionary(this, locale);
            }
        }

        if (null != mSuggest) {
            mSuggest.setContactsDictionary(dictionaryToUse);
        }
    }

    /* package private */ void resetSuggestMainDict() {
        final Locale subtypeLocale = mSubtypeSwitcher.getCurrentSubtypeLocale();
        mSuggest.resetMainDict(this, subtypeLocale, this /* SuggestInitializationListener */);
        mIsMainDictionaryAvailable = DictionaryFactory.isDictionaryAvailable(this, subtypeLocale);
    }

    @Override
    public void onDestroy() {
        if (mSuggest != null) {
            mSuggest.close();
            mSuggest = null;
        }
        unregisterReceiver(mReceiver);
        // TODO: The experimental version is not supported by the Dictionary Pack Service yet.
        if (!ProductionFlag.IS_EXPERIMENTAL) {
            unregisterReceiver(mDictionaryPackInstallReceiver);
        }
        LatinImeLogger.commit();
        LatinImeLogger.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        // System locale has been changed. Needs to reload keyboard.
        if (mSubtypeSwitcher.onConfigurationChanged(conf)) {
            loadKeyboard();
        }
        // If orientation changed while predicting, commit the change
        if (mDisplayOrientation != conf.orientation) {
            mDisplayOrientation = conf.orientation;
            mHandler.startOrientationChanging();
            mConnection.beginBatchEdit();
            commitTyped(LastComposedWord.NOT_A_SEPARATOR);
            mConnection.finishComposingText();
            mConnection.endBatchEdit();
            if (isShowingOptionDialog()) {
                mOptionsDialog.dismiss();
            }
        }
        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        return mKeyboardSwitcher.onCreateInputView(mIsHardwareAcceleratedDrawingEnabled);
    }

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mExtractArea = getWindow().getWindow().getDecorView()
                .findViewById(android.R.id.extractArea);
        mKeyPreviewBackingView = view.findViewById(R.id.key_preview_backing);
        mSuggestionsContainer = view.findViewById(R.id.suggestions_container);
        mSuggestionStripView = (SuggestionStripView)view.findViewById(R.id.suggestion_strip_view);
        if (mSuggestionStripView != null)
            mSuggestionStripView.setListener(this, view);
        if (LatinImeLogger.sVISUALDEBUG) {
            mKeyPreviewBackingView.setBackgroundColor(0x10FF0000);
        }
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
        return;
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(final InputMethodSubtype subtype) {
        // Note that the calling sequence of onCreate() and onCurrentInputMethodSubtypeChanged()
        // is not guaranteed. It may even be called at the same time on a different thread.
        mSubtypeSwitcher.updateSubtype(subtype);
        loadKeyboard();
    }

    private void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);
    }

    @SuppressWarnings("deprecation")
    private void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (LatinImeLogger.sDBG) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onStartInputView: editorInfo:"
                    + String.format("inputType=0x%08x imeOptions=0x%08x",
                            editorInfo.inputType, editorInfo.imeOptions));
            Log.d(TAG, "All caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
                    + ", sentence caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
                    + ", word caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0));
        }
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_onStartInputViewInternal(editorInfo, mPrefs);
        }
        if (InputAttributes.inPrivateImeOptions(null, NO_MICROPHONE_COMPAT, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: "
                    + editorInfo.privateImeOptions);
            Log.w(TAG, "Use " + getPackageName() + "." + NO_MICROPHONE + " instead");
        }
        if (InputAttributes.inPrivateImeOptions(getPackageName(), FORCE_ASCII, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: "
                    + editorInfo.privateImeOptions);
            Log.w(TAG, "Use EditorInfo.IME_FLAG_FORCE_ASCII flag instead");
        }

        mTargetApplicationInfo =
                TargetApplicationGetter.getCachedApplicationInfo(editorInfo.packageName);
        if (null == mTargetApplicationInfo) {
            new TargetApplicationGetter(this /* context */, this /* listener */)
                    .execute(editorInfo.packageName);
        }

        LatinImeLogger.onStartInputView(editorInfo);
        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        // Forward this event to the accessibility utilities, if enabled.
        final AccessibilityUtils accessUtils = AccessibilityUtils.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        final boolean inputTypeChanged = !mCurrentSettings.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;
        if (isDifferentTextField) {
            final boolean currentSubtypeEnabled = mSubtypeSwitcher
                    .updateParametersOnStartInputViewAndReturnIfCurrentSubtypeEnabled();
            if (!currentSubtypeEnabled) {
                // Current subtype is disabled. Needs to update subtype and keyboard.
                final InputMethodSubtype newSubtype = mRichImm.getCurrentInputMethodSubtype(
                        mSubtypeSwitcher.getNoLanguageSubtype());
                mSubtypeSwitcher.updateSubtype(newSubtype);
                loadKeyboard();
            }
        }

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode();
        mApplicationSpecifiedCompletions = null;

        // The app calling setText() has the effect of clearing the composing
        // span, so we should reset our state unconditionally, even if restarting is true.
        mEnteredText = null;
        resetComposingState(true /* alsoResetLastComposedWord */);
        mDeleteCount = 0;
        mSpaceState = SPACE_STATE_NONE;

        if (mSuggestionStripView != null) {
            // This will set the punctuation suggestions if next word suggestion is off;
            // otherwise it will clear the suggestion strip.
            setPunctuationSuggestions();
        }

        mConnection.resetCachesUponCursorMove(editorInfo.initialSelStart);

        if (isDifferentTextField) {
            mainKeyboardView.closing();
            loadSettings();

            if (mSuggest != null && mCurrentSettings.mCorrectionEnabled) {
                mSuggest.setAutoCorrectionThreshold(mCurrentSettings.mAutoCorrectionThreshold);
            }

            switcher.loadKeyboard(editorInfo, mCurrentSettings);
        } else if (restarting) {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet();
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
            switcher.updateShiftState();
        }
        setSuggestionStripShownInternal(
                isSuggestionsStripVisible(), /* needsInputViewShown */ false);

        mLastSelectionStart = editorInfo.initialSelStart;
        mLastSelectionEnd = editorInfo.initialSelEnd;

        mHandler.cancelUpdateSuggestionStrip();
        mHandler.cancelDoubleSpacePeriodTimer();

        mainKeyboardView.setMainDictionaryAvailability(mIsMainDictionaryAvailable);
        mainKeyboardView.setKeyPreviewPopupEnabled(mCurrentSettings.mKeyPreviewPopupOn,
                mCurrentSettings.mKeyPreviewPopupDismissDelay);
        mainKeyboardView.setGestureHandlingEnabledByUser(mCurrentSettings.mGestureInputEnabled);
        mainKeyboardView.setGesturePreviewMode(mCurrentSettings.mGesturePreviewTrailEnabled,
                mCurrentSettings.mGestureFloatingPreviewTextEnabled);

        // If we have a user dictionary addition in progress, we should check now if we should
        // replace the previously committed string with the word that has actually been added
        // to the user dictionary.
        if (null != mPositionalInfoForUserDictPendingAddition
                && mPositionalInfoForUserDictPendingAddition.tryReplaceWithActualWord(
                        mConnection, editorInfo, mLastSelectionEnd)) {
            mPositionalInfoForUserDictPendingAddition = null;
        }
        // If tryReplaceWithActualWord returns false, we don't know what word was
        // added to the user dictionary yet, so we keep the data and defer processing. The word will
        // be replaced when the user dictionary reports back with the actual word, which ends
        // up calling #onWordAddedToUserDictionary() in this class.

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    // Callback for the TargetApplicationGetter
    @Override
    public void onTargetApplicationKnown(final ApplicationInfo info) {
        mTargetApplicationInfo = info;
    }

    @Override
    public void onWindowHidden() {
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_onWindowHidden(mLastSelectionStart, mLastSelectionEnd,
                    getCurrentInputConnection());
        }
        super.onWindowHidden();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    private void onFinishInputInternal() {
        super.onFinishInput();

        LatinImeLogger.commit();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    private void onFinishInputViewInternal(final boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        mKeyboardSwitcher.onFinishInputView();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.cancelAllMessages();
        }
        // Remove pending messages related to update suggestions
        mHandler.cancelUpdateSuggestionStrip();
        resetComposingState(true /* alsoResetLastComposedWord */);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.getInstance().latinIME_onFinishInputViewInternal();
        }
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd,
            final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        if (DEBUG) {
            Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart
                    + ", ose=" + oldSelEnd
                    + ", lss=" + mLastSelectionStart
                    + ", lse=" + mLastSelectionEnd
                    + ", nss=" + newSelStart
                    + ", nse=" + newSelEnd
                    + ", cs=" + composingSpanStart
                    + ", ce=" + composingSpanEnd);
        }
        if (ProductionFlag.IS_EXPERIMENTAL) {
            final boolean expectingUpdateSelectionFromLogger =
                    ResearchLogger.getAndClearLatinIMEExpectingUpdateSelection();
            ResearchLogger.latinIME_onUpdateSelection(mLastSelectionStart, mLastSelectionEnd,
                    oldSelStart, oldSelEnd, newSelStart, newSelEnd, composingSpanStart,
                    composingSpanEnd, mExpectingUpdateSelection,
                    expectingUpdateSelectionFromLogger, mConnection);
            if (expectingUpdateSelectionFromLogger) {
                // TODO: Investigate. Quitting now sounds wrong - we won't do the resetting work
                return;
            }
        }

        // TODO: refactor the following code to be less contrived.
        // "newSelStart != composingSpanEnd" || "newSelEnd != composingSpanEnd" means
        // that the cursor is not at the end of the composing span, or there is a selection.
        // "mLastSelectionStart != newSelStart" means that the cursor is not in the same place
        // as last time we were called (if there is a selection, it means the start hasn't
        // changed, so it's the end that did).
        final boolean selectionChanged = (newSelStart != composingSpanEnd
                || newSelEnd != composingSpanEnd) && mLastSelectionStart != newSelStart;
        // if composingSpanStart and composingSpanEnd are -1, it means there is no composing
        // span in the view - we can use that to narrow down whether the cursor was moved
        // by us or not. If we are composing a word but there is no composing span, then
        // we know for sure the cursor moved while we were composing and we should reset
        // the state.
        final boolean noComposingSpan = composingSpanStart == -1 && composingSpanEnd == -1;
        if (!mExpectingUpdateSelection
                && !mConnection.isBelatedExpectedUpdate(oldSelStart, newSelStart)) {
            // TAKE CARE: there is a race condition when we enter this test even when the user
            // did not explicitly move the cursor. This happens when typing fast, where two keys
            // turn this flag on in succession and both onUpdateSelection() calls arrive after
            // the second one - the first call successfully avoids this test, but the second one
            // enters. For the moment we rely on noComposingSpan to further reduce the impact.

            // TODO: the following is probably better done in resetEntireInputState().
            // it should only happen when the cursor moved, and the very purpose of the
            // test below is to narrow down whether this happened or not. Likewise with
            // the call to updateShiftState.
            // We set this to NONE because after a cursor move, we don't want the space
            // state-related special processing to kick in.
            mSpaceState = SPACE_STATE_NONE;

            if ((!mWordComposer.isComposingWord()) || selectionChanged || noComposingSpan) {
                // If we are composing a word and moving the cursor, we would want to set a
                // suggestion span for recorrection to work correctly. Unfortunately, that
                // would involve the keyboard committing some new text, which would move the
                // cursor back to where it was. Latin IME could then fix the position of the cursor
                // again, but the asynchronous nature of the calls results in this wreaking havoc
                // with selection on double tap and the like.
                // Another option would be to send suggestions each time we set the composing
                // text, but that is probably too expensive to do, so we decided to leave things
                // as is.
                resetEntireInputState(newSelStart);
            }

            mKeyboardSwitcher.updateShiftState();
        }
        mExpectingUpdateSelection = false;
        // TODO: Decide to call restartSuggestionsOnWordBeforeCursorIfAtEndOfWord() or not
        // here. It would probably be too expensive to call directly here but we may want to post a
        // message to delay it. The point would be to unify behavior between backspace to the
        // end of a word and manually put the pointer at the end of the word.

        // Make a note of the cursor position
        mLastSelectionStart = newSelStart;
        mLastSelectionEnd = newSelEnd;
        mSubtypeState.currentSubtypeUsed();
    }

    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the suggestions view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedTextClicked() {
        if (mCurrentSettings.isSuggestionsRequested(mDisplayOrientation)) return;

        super.onExtractedTextClicked();
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the suggestions view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Here we override the behavior due to the possibility that a re-correction could
     * cause the suggestions strip to disappear and re-appear.
     */
    @Override
    public void onExtractedCursorMovement(final int dx, final int dy) {
        if (mCurrentSettings.isSuggestionsRequested(mDisplayOrientation)) return;

        super.onExtractedCursorMovement(dx, dy);
    }

    @Override
    public void hideWindow() {
        LatinImeLogger.commit();
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onDisplayCompletions(final CompletionInfo[] applicationSpecifiedCompletions) {
        if (DEBUG) {
            Log.i(TAG, "Received completions:");
            if (applicationSpecifiedCompletions != null) {
                for (int i = 0; i < applicationSpecifiedCompletions.length; i++) {
                    Log.i(TAG, "  #" + i + ": " + applicationSpecifiedCompletions[i]);
                }
            }
        }
        if (!mCurrentSettings.isApplicationSpecifiedCompletionsOn()) return;
        mApplicationSpecifiedCompletions = applicationSpecifiedCompletions;
        if (applicationSpecifiedCompletions == null) {
            clearSuggestionStrip();
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.latinIME_onDisplayCompletions(null);
            }
            return;
        }

        final ArrayList<SuggestedWords.SuggestedWordInfo> applicationSuggestedWords =
                SuggestedWords.getFromApplicationSpecifiedCompletions(
                        applicationSpecifiedCompletions);
        final SuggestedWords suggestedWords = new SuggestedWords(
                applicationSuggestedWords,
                false /* typedWordValid */,
                false /* hasAutoCorrectionCandidate */,
                false /* isPunctuationSuggestions */,
                false /* isObsoleteSuggestions */,
                false /* isPrediction */);
        // When in fullscreen mode, show completions generated by the application
        final boolean isAutoCorrection = false;
        setSuggestionStrip(suggestedWords, isAutoCorrection);
        setAutoCorrectionIndicator(isAutoCorrection);
        // TODO: is this the right thing to do? What should we auto-correct to in
        // this case? This says to keep whatever the user typed.
        mWordComposer.setAutoCorrection(mWordComposer.getTypedWord());
        setSuggestionStripShown(true);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_onDisplayCompletions(applicationSpecifiedCompletions);
        }
    }

    private void setSuggestionStripShownInternal(final boolean shown,
            final boolean needsInputViewShown) {
        // TODO: Modify this if we support suggestions with hard keyboard
        if (onEvaluateInputViewShown() && mSuggestionsContainer != null) {
            final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
            final boolean inputViewShown = (mainKeyboardView != null)
                    ? mainKeyboardView.isShown() : false;
            final boolean shouldShowSuggestions = shown
                    && (needsInputViewShown ? inputViewShown : true);
            if (isFullscreenMode()) {
                mSuggestionsContainer.setVisibility(
                        shouldShowSuggestions ? View.VISIBLE : View.GONE);
            } else {
                mSuggestionsContainer.setVisibility(
                        shouldShowSuggestions ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private void setSuggestionStripShown(final boolean shown) {
        setSuggestionStripShownInternal(shown, /* needsInputViewShown */true);
    }

    private int getAdjustedBackingViewHeight() {
        final int currentHeight = mKeyPreviewBackingView.getHeight();
        if (currentHeight > 0) {
            return currentHeight;
        }

        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            return 0;
        }
        final int keyboardHeight = mainKeyboardView.getHeight();
        final int suggestionsHeight = mSuggestionsContainer.getHeight();
        final int displayHeight = mResources.getDisplayMetrics().heightPixels;
        final Rect rect = new Rect();
        mKeyPreviewBackingView.getWindowVisibleDisplayFrame(rect);
        final int notificationBarHeight = rect.top;
        final int remainingHeight = displayHeight - notificationBarHeight - suggestionsHeight
                - keyboardHeight;

        final LayoutParams params = mKeyPreviewBackingView.getLayoutParams();
        params.height = mSuggestionStripView.setMoreSuggestionsHeight(remainingHeight);
        mKeyPreviewBackingView.setLayoutParams(params);
        return params.height;
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null || mSuggestionsContainer == null) {
            return;
        }
        final int adjustedBackingHeight = getAdjustedBackingViewHeight();
        final boolean backingGone = (mKeyPreviewBackingView.getVisibility() == View.GONE);
        final int backingHeight = backingGone ? 0 : adjustedBackingHeight;
        // In fullscreen mode, the height of the extract area managed by InputMethodService should
        // be considered.
        // See {@link android.inputmethodservice.InputMethodService#onComputeInsets}.
        final int extractHeight = isFullscreenMode() ? mExtractArea.getHeight() : 0;
        final int suggestionsHeight = (mSuggestionsContainer.getVisibility() == View.GONE) ? 0
                : mSuggestionsContainer.getHeight();
        final int extraHeight = extractHeight + backingHeight + suggestionsHeight;
        int visibleTopY = extraHeight;
        // Need to set touchable region only if input view is being shown
        if (mainKeyboardView.isShown()) {
            if (mSuggestionsContainer.getVisibility() == View.VISIBLE) {
                visibleTopY -= suggestionsHeight;
            }
            final int touchY = mainKeyboardView.isShowingMoreKeysPanel() ? 0 : visibleTopY;
            final int touchWidth = mainKeyboardView.getWidth();
            final int touchHeight = mainKeyboardView.getHeight() + extraHeight
                    // Extend touchable region below the keyboard.
                    + EXTENDED_TOUCHABLE_REGION_HEIGHT;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(0, touchY, touchWidth, touchHeight);
        }
        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // Reread resource value here, because this method is called by framework anytime as needed.
        final boolean isFullscreenModeAllowed =
                SettingsValues.isFullscreenModeAllowed(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            final EditorInfo ei = getCurrentInputEditorInfo();
            return !(ei != null && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0));
        } else {
            return false;
        }
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();

        if (mKeyPreviewBackingView == null) return;
        // In fullscreen mode, no need to have extra space to show the key preview.
        // If not, we should have extra space above the keyboard to show the key preview.
        mKeyPreviewBackingView.setVisibility(isFullscreenMode() ? View.GONE : View.VISIBLE);
    }

    // This will reset the whole input state to the starting state. It will clear
    // the composing word, reset the last composed word, tell the inputconnection about it.
    private void resetEntireInputState(final int newCursorPosition) {
        resetComposingState(true /* alsoResetLastComposedWord */);
        if (mCurrentSettings.mBigramPredictionEnabled) {
            clearSuggestionStrip();
        } else {
            setSuggestionStrip(mCurrentSettings.mSuggestPuncList, false);
        }
        mConnection.resetCachesUponCursorMove(newCursorPosition);
    }

    private void resetComposingState(final boolean alsoResetLastComposedWord) {
        mWordComposer.reset();
        if (alsoResetLastComposedWord)
            mLastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD;
    }

    private void commitTyped(final String separatorString) {
        if (!mWordComposer.isComposingWord()) return;
        final String typedWord = mWordComposer.getTypedWord();
        if (typedWord.length() > 0) {
            commitChosenWord(typedWord, LastComposedWord.COMMIT_TYPE_USER_TYPED_WORD,
                    separatorString);
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.getInstance().onWordComplete(typedWord, Long.MAX_VALUE);
            }
        }
    }

    // Called from the KeyboardSwitcher which needs to know auto caps state to display
    // the right layout.
    public int getCurrentAutoCapsState() {
        if (!mCurrentSettings.mAutoCap) return Constants.TextUtils.CAP_MODE_OFF;

        final EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) return Constants.TextUtils.CAP_MODE_OFF;
        final int inputType = ei.inputType;
        // Warning: this depends on mSpaceState, which may not be the most current value. If
        // mSpaceState gets updated later, whoever called this may need to be told about it.
        return mConnection.getCursorCapsMode(inputType, mSubtypeSwitcher.getCurrentSubtypeLocale(),
                SPACE_STATE_PHANTOM == mSpaceState);
    }

    // Factor in auto-caps and manual caps and compute the current caps mode.
    private int getActualCapsMode() {
        final int keyboardShiftMode = mKeyboardSwitcher.getKeyboardShiftMode();
        if (keyboardShiftMode != WordComposer.CAPS_MODE_AUTO_SHIFTED) return keyboardShiftMode;
        final int auto = getCurrentAutoCapsState();
        if (0 != (auto & TextUtils.CAP_MODE_CHARACTERS)) {
            return WordComposer.CAPS_MODE_AUTO_SHIFT_LOCKED;
        }
        if (0 != auto) return WordComposer.CAPS_MODE_AUTO_SHIFTED;
        return WordComposer.CAPS_MODE_OFF;
    }

    private void swapSwapperAndSpace() {
        CharSequence lastTwo = mConnection.getTextBeforeCursor(2, 0);
        // It is guaranteed lastTwo.charAt(1) is a swapper - else this method is not called.
        if (lastTwo != null && lastTwo.length() == 2
                && lastTwo.charAt(0) == Constants.CODE_SPACE) {
            mConnection.deleteSurroundingText(2, 0);
            final String text = lastTwo.charAt(1) + " ";
            mConnection.commitText(text, 1);
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.getInstance().onWordComplete(text, Long.MAX_VALUE);
                ResearchLogger.latinIME_swapSwapperAndSpace();
            }
            mKeyboardSwitcher.updateShiftState();
        }
    }

    private boolean maybeDoubleSpacePeriod() {
        if (!mCurrentSettings.mCorrectionEnabled) return false;
        if (!mCurrentSettings.mUseDoubleSpacePeriod) return false;
        if (!mHandler.isAcceptingDoubleSpacePeriod()) return false;
        final CharSequence lastThree = mConnection.getTextBeforeCursor(3, 0);
        if (lastThree != null && lastThree.length() == 3
                && canBeFollowedByDoubleSpacePeriod(lastThree.charAt(0))
                && lastThree.charAt(1) == Constants.CODE_SPACE
                && lastThree.charAt(2) == Constants.CODE_SPACE) {
            mHandler.cancelDoubleSpacePeriodTimer();
            mConnection.deleteSurroundingText(2, 0);
            final String textToInsert = ". ";
            mConnection.commitText(textToInsert, 1);
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.getInstance().onWordComplete(textToInsert, Long.MAX_VALUE);
            }
            mKeyboardSwitcher.updateShiftState();
            return true;
        }
        return false;
    }

    private static boolean canBeFollowedByDoubleSpacePeriod(final int codePoint) {
        // TODO: Check again whether there really ain't a better way to check this.
        // TODO: This should probably be language-dependant...
        return Character.isLetterOrDigit(codePoint)
                || codePoint == Constants.CODE_SINGLE_QUOTE
                || codePoint == Constants.CODE_DOUBLE_QUOTE
                || codePoint == Constants.CODE_CLOSING_PARENTHESIS
                || codePoint == Constants.CODE_CLOSING_SQUARE_BRACKET
                || codePoint == Constants.CODE_CLOSING_CURLY_BRACKET
                || codePoint == Constants.CODE_CLOSING_ANGLE_BRACKET;
    }

    // Callback for the {@link SuggestionStripView}, to call when the "add to dictionary" hint is
    // pressed.
    @Override
    public void addWordToUserDictionary(final String word) {
        if (TextUtils.isEmpty(word)) {
            // Probably never supposed to happen, but just in case.
            mPositionalInfoForUserDictPendingAddition = null;
            return;
        }
        mPositionalInfoForUserDictPendingAddition =
                new PositionalInfoForUserDictPendingAddition(
                        word, mLastSelectionEnd, getCurrentInputEditorInfo());
        mUserDictionary.addWordToUserDictionary(word, 128);
    }

    public void onWordAddedToUserDictionary(final String newSpelling) {
        // If word was added but not by us, bail out
        if (null == mPositionalInfoForUserDictPendingAddition) return;
        if (mWordComposer.isComposingWord()) {
            // We are late... give up and return
            mPositionalInfoForUserDictPendingAddition = null;
            return;
        }
        mPositionalInfoForUserDictPendingAddition.setActualWordBeingAdded(newSpelling);
        if (mPositionalInfoForUserDictPendingAddition.tryReplaceWithActualWord(
                mConnection, getCurrentInputEditorInfo(), mLastSelectionEnd)) {
            mPositionalInfoForUserDictPendingAddition = null;
        }
    }

    private static boolean isAlphabet(final int code) {
        return Character.isLetter(code);
    }

    private void onSettingsKeyPressed() {
        if (isShowingOptionDialog()) return;
        showSubtypeSelectorAndSettings();
    }

    // Virtual codes representing custom requests.  These are used in onCustomRequest() below.
    public static final int CODE_SHOW_INPUT_METHOD_PICKER = 1;

    @Override
    public boolean onCustomRequest(final int requestCode) {
        if (isShowingOptionDialog()) return false;
        switch (requestCode) {
        case CODE_SHOW_INPUT_METHOD_PICKER:
            if (mRichImm.hasMultipleEnabledIMEsOrSubtypes(true /* include aux subtypes */)) {
                mRichImm.getInputMethodManager().showInputMethodPicker();
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    private static int getActionId(final Keyboard keyboard) {
        return keyboard != null ? keyboard.mId.imeActionId() : EditorInfo.IME_ACTION_NONE;
    }

    private void performEditorAction(final int actionId) {
        mConnection.performEditorAction(actionId);
    }

    // TODO: Revise the language switch key behavior to make it much smarter and more reasonable.
    private void handleLanguageSwitchKey() {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (mCurrentSettings.mIncludesOtherImesInLanguageSwitchList) {
            mRichImm.switchToNextInputMethod(token, false /* onlyCurrentIme */);
            return;
        }
        mSubtypeState.switchSubtype(token, mRichImm);
    }

    private void sendDownUpKeyEventForBackwardCompatibility(final int code) {
        final long eventTime = SystemClock.uptimeMillis();
        mConnection.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        mConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    private void sendKeyCodePoint(final int code) {
        // TODO: Remove this special handling of digit letters.
        // For backward compatibility. See {@link InputMethodService#sendKeyChar(char)}.
        if (code >= '0' && code <= '9') {
            sendDownUpKeyEventForBackwardCompatibility(code - '0' + KeyEvent.KEYCODE_0);
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.latinIME_sendKeyCodePoint(code);
            }
            return;
        }

        // 16 is android.os.Build.VERSION_CODES.JELLY_BEAN but we can't write it because
        // we want to be able to compile against the Ice Cream Sandwich SDK.
        if (Constants.CODE_ENTER == code && mTargetApplicationInfo != null
                && mTargetApplicationInfo.targetSdkVersion < 16) {
            // Backward compatibility mode. Before Jelly bean, the keyboard would simulate
            // a hardware keyboard event on pressing enter or delete. This is bad for many
            // reasons (there are race conditions with commits) but some applications are
            // relying on this behavior so we continue to support it for older apps.
            sendDownUpKeyEventForBackwardCompatibility(KeyEvent.KEYCODE_ENTER);
        } else {
            final String text = new String(new int[] { code }, 0, 1);
            mConnection.commitText(text, text.length());
        }
    }

    // Implementation of {@link KeyboardActionListener}.
    @Override
    public void onCodeInput(final int primaryCode, final int x, final int y) {
        final long when = SystemClock.uptimeMillis();
        if (primaryCode != Constants.CODE_DELETE || when > mLastKeyTime + QUICK_PRESS) {
            mDeleteCount = 0;
        }
        mLastKeyTime = when;
        mConnection.beginBatchEdit();
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        // The space state depends only on the last character pressed and its own previous
        // state. Here, we revert the space state to neutral if the key is actually modifying
        // the input contents (any non-shift key), which is what we should do for
        // all inputs that do not result in a special state. Each character handling is then
        // free to override the state as they see fit.
        final int spaceState = mSpaceState;
        if (!mWordComposer.isComposingWord()) mIsAutoCorrectionIndicatorOn = false;

        // TODO: Consolidate the double-space period timer, mLastKeyTime, and the space state.
        if (primaryCode != Constants.CODE_SPACE) {
            mHandler.cancelDoubleSpacePeriodTimer();
        }

        boolean didAutoCorrect = false;
        switch (primaryCode) {
        case Constants.CODE_DELETE:
            mSpaceState = SPACE_STATE_NONE;
            handleBackspace(spaceState);
            mDeleteCount++;
            mExpectingUpdateSelection = true;
            LatinImeLogger.logOnDelete(x, y);
            break;
        case Constants.CODE_SHIFT:
        case Constants.CODE_SWITCH_ALPHA_SYMBOL:
            // Shift and symbol key is handled in onPressKey() and onReleaseKey().
            break;
        case Constants.CODE_SETTINGS:
            onSettingsKeyPressed();
            break;
        case Constants.CODE_SHORTCUT:
            mSubtypeSwitcher.switchToShortcutIME(this);
            break;
        case Constants.CODE_ACTION_ENTER:
            performEditorAction(getActionId(switcher.getKeyboard()));
            break;
        case Constants.CODE_ACTION_NEXT:
            performEditorAction(EditorInfo.IME_ACTION_NEXT);
            break;
        case Constants.CODE_ACTION_PREVIOUS:
            performEditorAction(EditorInfo.IME_ACTION_PREVIOUS);
            break;
        case Constants.CODE_LANGUAGE_SWITCH:
            handleLanguageSwitchKey();
            break;
        case Constants.CODE_RESEARCH:
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.getInstance().onResearchKeySelected(this);
            }
            break;
        default:
            mSpaceState = SPACE_STATE_NONE;
            if (mCurrentSettings.isWordSeparator(primaryCode)) {
                didAutoCorrect = handleSeparator(primaryCode, x, y, spaceState);
            } else {
                if (SPACE_STATE_PHANTOM == spaceState) {
                    if (ProductionFlag.IS_INTERNAL) {
                        if (mWordComposer.isComposingWord() && mWordComposer.isBatchMode()) {
                            Stats.onAutoCorrection(
                                    "", mWordComposer.getTypedWord(), " ", mWordComposer);
                        }
                    }
                    commitTyped(LastComposedWord.NOT_A_SEPARATOR);
                }
                final int keyX, keyY;
                final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
                if (keyboard != null && keyboard.hasProximityCharsCorrection(primaryCode)) {
                    keyX = x;
                    keyY = y;
                } else {
                    keyX = Constants.NOT_A_COORDINATE;
                    keyY = Constants.NOT_A_COORDINATE;
                }
                handleCharacter(primaryCode, keyX, keyY, spaceState);
            }
            mExpectingUpdateSelection = true;
            break;
        }
        switcher.onCodeInput(primaryCode);
        // Reset after any single keystroke, except shift and symbol-shift
        if (!didAutoCorrect && primaryCode != Constants.CODE_SHIFT
                && primaryCode != Constants.CODE_SWITCH_ALPHA_SYMBOL)
            mLastComposedWord.deactivate();
        if (Constants.CODE_DELETE != primaryCode) {
            mEnteredText = null;
        }
        mConnection.endBatchEdit();
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_onCodeInput(primaryCode, x, y);
        }
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onTextInput(final String rawText) {
        mConnection.beginBatchEdit();
        if (mWordComposer.isComposingWord()) {
            commitCurrentAutoCorrection(rawText);
        } else {
            resetComposingState(true /* alsoResetLastComposedWord */);
        }
        mHandler.postUpdateSuggestionStrip();
        final String text = specificTldProcessingOnTextInput(rawText);
        if (SPACE_STATE_PHANTOM == mSpaceState) {
            promotePhantomSpace();
        }
        mConnection.commitText(text, 1);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.getInstance().onWordComplete(text, Long.MAX_VALUE);
        }
        mConnection.endBatchEdit();
        // Space state must be updated before calling updateShiftState
        mSpaceState = SPACE_STATE_NONE;
        mKeyboardSwitcher.updateShiftState();
        mKeyboardSwitcher.onCodeInput(Constants.CODE_OUTPUT_TEXT);
        mEnteredText = text;
    }

    @Override
    public void onStartBatchInput() {
        BatchInputUpdater.getInstance().onStartBatchInput(this);
        mConnection.beginBatchEdit();
        if (mWordComposer.isComposingWord()) {
            if (ProductionFlag.IS_INTERNAL) {
                if (mWordComposer.isBatchMode()) {
                    Stats.onAutoCorrection("", mWordComposer.getTypedWord(), " ", mWordComposer);
                }
            }
            if (mWordComposer.size() <= 1) {
                // We auto-correct the previous (typed, not gestured) string iff it's one character
                // long. The reason for this is, even in the middle of gesture typing, you'll still
                // tap one-letter words and you want them auto-corrected (typically, "i" in English
                // should become "I"). However for any longer word, we assume that the reason for
                // tapping probably is that the word you intend to type is not in the dictionary,
                // so we do not attempt to correct, on the assumption that if that was a dictionary
                // word, the user would probably have gestured instead.
                commitCurrentAutoCorrection(LastComposedWord.NOT_A_SEPARATOR);
            } else {
                commitTyped(LastComposedWord.NOT_A_SEPARATOR);
            }
            mExpectingUpdateSelection = true;
            // The following is necessary for the case where the user typed something but didn't
            // manual pick it and didn't input any separator.
            mSpaceState = SPACE_STATE_PHANTOM;
        } else {
            final int codePointBeforeCursor = mConnection.getCodePointBeforeCursor();
            // TODO: reverse this logic. We should have the means to determine whether a character
            // should usually be followed by a space, and it should be more readable.
            if (Constants.NOT_A_CODE != codePointBeforeCursor
                    && !Character.isWhitespace(codePointBeforeCursor)
                    && !mCurrentSettings.isPhantomSpacePromotingSymbol(codePointBeforeCursor)
                    && !mCurrentSettings.isWeakSpaceStripper(codePointBeforeCursor)) {
                mSpaceState = SPACE_STATE_PHANTOM;
            }
        }
        mConnection.endBatchEdit();
        mWordComposer.setCapitalizedModeAtStartComposingTime(getActualCapsMode());
    }

    private static final class BatchInputUpdater implements Handler.Callback {
        private final Handler mHandler;
        private LatinIME mLatinIme;
        private boolean mInBatchInput; // synchronized using "this".

        private BatchInputUpdater() {
            final HandlerThread handlerThread = new HandlerThread(
                    BatchInputUpdater.class.getSimpleName());
            handlerThread.start();
            mHandler = new Handler(handlerThread.getLooper(), this);
        }

        // Initialization-on-demand holder
        private static final class OnDemandInitializationHolder {
            public static final BatchInputUpdater sInstance = new BatchInputUpdater();
        }

        public static BatchInputUpdater getInstance() {
            return OnDemandInitializationHolder.sInstance;
        }

        private static final int MSG_UPDATE_GESTURE_PREVIEW_AND_SUGGESTION_STRIP = 1;

        @Override
        public boolean handleMessage(final Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_GESTURE_PREVIEW_AND_SUGGESTION_STRIP:
                updateBatchInput((InputPointers)msg.obj);
                break;
            }
            return true;
        }

        // Run in the UI thread.
        public synchronized void onStartBatchInput(final LatinIME latinIme) {
            mHandler.removeMessages(MSG_UPDATE_GESTURE_PREVIEW_AND_SUGGESTION_STRIP);
            mLatinIme = latinIme;
            mInBatchInput = true;
        }

        // Run in the Handler thread.
        private synchronized void updateBatchInput(final InputPointers batchPointers) {
            if (!mInBatchInput) {
                // Batch input has ended or canceled while the message was being delivered.
                return;
            }
            final SuggestedWords suggestedWords = getSuggestedWordsGestureLocked(batchPointers);
            mLatinIme.mHandler.showGesturePreviewAndSuggestionStrip(
                    suggestedWords, false /* dismissGestureFloatingPreviewText */);
        }

        // Run in the UI thread.
        public void onUpdateBatchInput(final InputPointers batchPointers) {
            if (mHandler.hasMessages(MSG_UPDATE_GESTURE_PREVIEW_AND_SUGGESTION_STRIP)) {
                return;
            }
            mHandler.obtainMessage(
                    MSG_UPDATE_GESTURE_PREVIEW_AND_SUGGESTION_STRIP, batchPointers)
                    .sendToTarget();
        }

        public synchronized void onCancelBatchInput() {
            mInBatchInput = false;
            mLatinIme.mHandler.showGesturePreviewAndSuggestionStrip(
                    SuggestedWords.EMPTY, true /* dismissGestureFloatingPreviewText */);
        }

        // Run in the UI thread.
        public synchronized SuggestedWords onEndBatchInput(final InputPointers batchPointers) {
            mInBatchInput = false;
            final SuggestedWords suggestedWords = getSuggestedWordsGestureLocked(batchPointers);
            mLatinIme.mHandler.showGesturePreviewAndSuggestionStrip(
                    suggestedWords, true /* dismissGestureFloatingPreviewText */);
            return suggestedWords;
        }

        // {@link LatinIME#getSuggestedWords(int)} method calls with same session id have to
        // be synchronized.
        private SuggestedWords getSuggestedWordsGestureLocked(final InputPointers batchPointers) {
            mLatinIme.mWordComposer.setBatchInputPointers(batchPointers);
            final SuggestedWords suggestedWords =
                    mLatinIme.getSuggestedWords(Suggest.SESSION_GESTURE);
            final int suggestionCount = suggestedWords.size();
            if (suggestionCount <= 1) {
                final String mostProbableSuggestion = (suggestionCount == 0) ? null
                        : suggestedWords.getWord(0);
                return mLatinIme.getOlderSuggestions(mostProbableSuggestion);
            }
            return suggestedWords;
        }
    }

    private void showGesturePreviewAndSuggestionStrip(final SuggestedWords suggestedWords,
            final boolean dismissGestureFloatingPreviewText) {
        showSuggestionStrip(suggestedWords, null);
        final KeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (dismissGestureFloatingPreviewText) {
            mainKeyboardView.dismissGestureFloatingPreviewText();
        } else {
            final String batchInputText = suggestedWords.isEmpty()
                    ? null : suggestedWords.getWord(0);
            mainKeyboardView.showGestureFloatingPreviewText(batchInputText);
        }
    }

    @Override
    public void onUpdateBatchInput(final InputPointers batchPointers) {
        BatchInputUpdater.getInstance().onUpdateBatchInput(batchPointers);
    }

    @Override
    public void onEndBatchInput(final InputPointers batchPointers) {
        final SuggestedWords suggestedWords = BatchInputUpdater.getInstance().onEndBatchInput(
                batchPointers);
        final String batchInputText = suggestedWords.isEmpty()
                ? null : suggestedWords.getWord(0);
        if (TextUtils.isEmpty(batchInputText)) {
            return;
        }
        mWordComposer.setBatchInputWord(batchInputText);
        mConnection.beginBatchEdit();
        if (SPACE_STATE_PHANTOM == mSpaceState) {
            promotePhantomSpace();
        }
        mConnection.setComposingText(batchInputText, 1);
        mExpectingUpdateSelection = true;
        mConnection.endBatchEdit();
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_onEndBatchInput(batchInputText, 0);
        }
        // Space state must be updated before calling updateShiftState
        mSpaceState = SPACE_STATE_PHANTOM;
        mKeyboardSwitcher.updateShiftState();
    }

    private String specificTldProcessingOnTextInput(final String text) {
        if (text.length() <= 1 || text.charAt(0) != Constants.CODE_PERIOD
                || !Character.isLetter(text.charAt(1))) {
            // Not a tld: do nothing.
            return text;
        }
        // We have a TLD (or something that looks like this): make sure we don't add
        // a space even if currently in phantom mode.
        mSpaceState = SPACE_STATE_NONE;
        // TODO: use getCodePointBeforeCursor instead to improve performance and simplify the code
        final CharSequence lastOne = mConnection.getTextBeforeCursor(1, 0);
        if (lastOne != null && lastOne.length() == 1
                && lastOne.charAt(0) == Constants.CODE_PERIOD) {
            return text.substring(1);
        } else {
            return text;
        }
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onCancelInput() {
        // User released a finger outside any key
        mKeyboardSwitcher.onCancelInput();
    }

    @Override
    public void onCancelBatchInput() {
        BatchInputUpdater.getInstance().onCancelBatchInput();
    }

    private void handleBackspace(final int spaceState) {
        // In many cases, we may have to put the keyboard in auto-shift state again. However
        // we want to wait a few milliseconds before doing it to avoid the keyboard flashing
        // during key repeat.
        mHandler.postUpdateShiftState();

        if (mWordComposer.isComposingWord()) {
            final int length = mWordComposer.size();
            if (length > 0) {
                if (mWordComposer.isBatchMode()) {
                    mWordComposer.reset();
                    if (ProductionFlag.IS_EXPERIMENTAL) {
                        ResearchLogger.latinIME_handleBackspace_batch(mWordComposer.getTypedWord());
                    }
                } else {
                    mWordComposer.deleteLast();
                }
                mConnection.setComposingText(getTextWithUnderline(mWordComposer.getTypedWord()), 1);
                mHandler.postUpdateSuggestionStrip();
            } else {
                mConnection.deleteSurroundingText(1, 0);
            }
        } else {
            if (mLastComposedWord.canRevertCommit()) {
                if (ProductionFlag.IS_INTERNAL) {
                    Stats.onAutoCorrectionCancellation();
                }
                revertCommit();
                return;
            }
            if (mEnteredText != null && mConnection.sameAsTextBeforeCursor(mEnteredText)) {
                // Cancel multi-character input: remove the text we just entered.
                // This is triggered on backspace after a key that inputs multiple characters,
                // like the smiley key or the .com key.
                final int length = mEnteredText.length();
                mConnection.deleteSurroundingText(length, 0);
                mEnteredText = null;
                // If we have mEnteredText, then we know that mHasUncommittedTypedChars == false.
                // In addition we know that spaceState is false, and that we should not be
                // reverting any autocorrect at this point. So we can safely return.
                return;
            }
            if (SPACE_STATE_DOUBLE == spaceState) {
                mHandler.cancelDoubleSpacePeriodTimer();
                if (mConnection.revertDoubleSpace()) {
                    // No need to reset mSpaceState, it has already be done (that's why we
                    // receive it as a parameter)
                    return;
                }
            } else if (SPACE_STATE_SWAP_PUNCTUATION == spaceState) {
                if (mConnection.revertSwapPunctuation()) {
                    // Likewise
                    return;
                }
            }

            // No cancelling of commit/double space/swap: we have a regular backspace.
            // We should backspace one char and restart suggestion if at the end of a word.
            if (mLastSelectionStart != mLastSelectionEnd) {
                // If there is a selection, remove it.
                final int lengthToDelete = mLastSelectionEnd - mLastSelectionStart;
                mConnection.setSelection(mLastSelectionEnd, mLastSelectionEnd);
                mConnection.deleteSurroundingText(lengthToDelete, 0);
            } else {
                // There is no selection, just delete one character.
                if (NOT_A_CURSOR_POSITION == mLastSelectionEnd) {
                    // This should never happen.
                    Log.e(TAG, "Backspace when we don't know the selection position");
                }
                // 16 is android.os.Build.VERSION_CODES.JELLY_BEAN but we can't write it because
                // we want to be able to compile against the Ice Cream Sandwich SDK.
                if (mTargetApplicationInfo != null
                        && mTargetApplicationInfo.targetSdkVersion < 16) {
                    // Backward compatibility mode. Before Jelly bean, the keyboard would simulate
                    // a hardware keyboard event on pressing enter or delete. This is bad for many
                    // reasons (there are race conditions with commits) but some applications are
                    // relying on this behavior so we continue to support it for older apps.
                    sendDownUpKeyEventForBackwardCompatibility(KeyEvent.KEYCODE_DEL);
                } else {
                    mConnection.deleteSurroundingText(1, 0);
                }
                if (mDeleteCount > DELETE_ACCELERATE_AT) {
                    mConnection.deleteSurroundingText(1, 0);
                }
            }
            if (mCurrentSettings.isSuggestionsRequested(mDisplayOrientation)) {
                restartSuggestionsOnWordBeforeCursorIfAtEndOfWord();
            }
        }
    }

    private boolean maybeStripSpace(final int code,
            final int spaceState, final boolean isFromSuggestionStrip) {
        if (Constants.CODE_ENTER == code && SPACE_STATE_SWAP_PUNCTUATION == spaceState) {
            mConnection.removeTrailingSpace();
            return false;
        } else if ((SPACE_STATE_WEAK == spaceState
                || SPACE_STATE_SWAP_PUNCTUATION == spaceState)
                && isFromSuggestionStrip) {
            if (mCurrentSettings.isWeakSpaceSwapper(code)) {
                return true;
            } else {
                if (mCurrentSettings.isWeakSpaceStripper(code)) {
                    mConnection.removeTrailingSpace();
                }
                return false;
            }
        } else {
            return false;
        }
    }

    private void handleCharacter(final int primaryCode, final int x,
            final int y, final int spaceState) {
        boolean isComposingWord = mWordComposer.isComposingWord();

        if (SPACE_STATE_PHANTOM == spaceState &&
                !mCurrentSettings.isSymbolExcludedFromWordSeparators(primaryCode)) {
            if (isComposingWord) {
                // Sanity check
                throw new RuntimeException("Should not be composing here");
            }
            promotePhantomSpace();
        }

        // NOTE: isCursorTouchingWord() is a blocking IPC call, so it often takes several
        // dozen milliseconds. Avoid calling it as much as possible, since we are on the UI
        // thread here.
        if (!isComposingWord && (isAlphabet(primaryCode)
                || mCurrentSettings.isSymbolExcludedFromWordSeparators(primaryCode))
                && mCurrentSettings.isSuggestionsRequested(mDisplayOrientation) &&
                !mConnection.isCursorTouchingWord(mCurrentSettings)) {
            // Reset entirely the composing state anyway, then start composing a new word unless
            // the character is a single quote. The idea here is, single quote is not a
            // separator and it should be treated as a normal character, except in the first
            // position where it should not start composing a word.
            isComposingWord = (Constants.CODE_SINGLE_QUOTE != primaryCode);
            // Here we don't need to reset the last composed word. It will be reset
            // when we commit this one, if we ever do; if on the other hand we backspace
            // it entirely and resume suggestions on the previous word, we'd like to still
            // have touch coordinates for it.
            resetComposingState(false /* alsoResetLastComposedWord */);
        }
        if (isComposingWord) {
            final int keyX, keyY;
            if (Constants.isValidCoordinate(x) && Constants.isValidCoordinate(y)) {
                final KeyDetector keyDetector =
                        mKeyboardSwitcher.getMainKeyboardView().getKeyDetector();
                keyX = keyDetector.getTouchX(x);
                keyY = keyDetector.getTouchY(y);
            } else {
                keyX = x;
                keyY = y;
            }
            mWordComposer.add(primaryCode, keyX, keyY);
            // If it's the first letter, make note of auto-caps state
            if (mWordComposer.size() == 1) {
                mWordComposer.setCapitalizedModeAtStartComposingTime(getActualCapsMode());
            }
            mConnection.setComposingText(getTextWithUnderline(mWordComposer.getTypedWord()), 1);
        } else {
            final boolean swapWeakSpace = maybeStripSpace(primaryCode,
                    spaceState, Constants.SUGGESTION_STRIP_COORDINATE == x);

            sendKeyCodePoint(primaryCode);

            if (swapWeakSpace) {
                swapSwapperAndSpace();
                mSpaceState = SPACE_STATE_WEAK;
            }
            // In case the "add to dictionary" hint was still displayed.
            if (null != mSuggestionStripView) mSuggestionStripView.dismissAddToDictionaryHint();
        }
        mHandler.postUpdateSuggestionStrip();
        if (ProductionFlag.IS_INTERNAL) {
            Utils.Stats.onNonSeparator((char)primaryCode, x, y);
        }
    }

    // Returns true if we did an autocorrection, false otherwise.
    private boolean handleSeparator(final int primaryCode, final int x, final int y,
            final int spaceState) {
        boolean didAutoCorrect = false;
        // Handle separator
        if (mWordComposer.isComposingWord()) {
            if (mCurrentSettings.mCorrectionEnabled) {
                // TODO: maybe cache Strings in an <String> sparse array or something
                commitCurrentAutoCorrection(new String(new int[]{primaryCode}, 0, 1));
                didAutoCorrect = true;
            } else {
                commitTyped(new String(new int[]{primaryCode}, 0, 1));
            }
        }

        final boolean swapWeakSpace = maybeStripSpace(primaryCode, spaceState,
                Constants.SUGGESTION_STRIP_COORDINATE == x);

        if (SPACE_STATE_PHANTOM == spaceState &&
                mCurrentSettings.isPhantomSpacePromotingSymbol(primaryCode)) {
            promotePhantomSpace();
        }
        sendKeyCodePoint(primaryCode);

        if (Constants.CODE_SPACE == primaryCode) {
            if (mCurrentSettings.isSuggestionsRequested(mDisplayOrientation)) {
                if (maybeDoubleSpacePeriod()) {
                    mSpaceState = SPACE_STATE_DOUBLE;
                } else if (!isShowingPunctuationList()) {
                    mSpaceState = SPACE_STATE_WEAK;
                }
            }

            mHandler.startDoubleSpacePeriodTimer();
            if (!mConnection.isCursorTouchingWord(mCurrentSettings)) {
                mHandler.postUpdateSuggestionStrip();
            }
        } else {
            if (swapWeakSpace) {
                swapSwapperAndSpace();
                mSpaceState = SPACE_STATE_SWAP_PUNCTUATION;
            } else if (SPACE_STATE_PHANTOM == spaceState
                    && !mCurrentSettings.isWeakSpaceStripper(primaryCode)
                    && !mCurrentSettings.isPhantomSpacePromotingSymbol(primaryCode)) {
                // If we are in phantom space state, and the user presses a separator, we want to
                // stay in phantom space state so that the next keypress has a chance to add the
                // space. For example, if I type "Good dat", pick "day" from the suggestion strip
                // then insert a comma and go on to typing the next word, I want the space to be
                // inserted automatically before the next word, the same way it is when I don't
                // input the comma.
                // The case is a little different if the separator is a space stripper. Such a
                // separator does not normally need a space on the right (that's the difference
                // between swappers and strippers), so we should not stay in phantom space state if
                // the separator is a stripper. Hence the additional test above.
                mSpaceState = SPACE_STATE_PHANTOM;
            }

            // Set punctuation right away. onUpdateSelection will fire but tests whether it is
            // already displayed or not, so it's okay.
            setPunctuationSuggestions();
        }
        if (ProductionFlag.IS_INTERNAL) {
            Utils.Stats.onSeparator((char)primaryCode, x, y);
        }

        mKeyboardSwitcher.updateShiftState();
        return didAutoCorrect;
    }

    private CharSequence getTextWithUnderline(final String text) {
        return mIsAutoCorrectionIndicatorOn
                ? SuggestionSpanUtils.getTextWithAutoCorrectionIndicatorUnderline(this, text)
                : text;
    }

    private void handleClose() {
        // TODO: Verify that words are logged properly when IME is closed.
        commitTyped(LastComposedWord.NOT_A_SEPARATOR);
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    // TODO: make this private
    // Outside LatinIME, only used by the test suite.
    @UsedForTesting
    boolean isShowingPunctuationList() {
        if (mSuggestionStripView == null) return false;
        return mCurrentSettings.mSuggestPuncList == mSuggestionStripView.getSuggestions();
    }

    private boolean isSuggestionsStripVisible() {
        if (mSuggestionStripView == null)
            return false;
        if (mSuggestionStripView.isShowingAddToDictionaryHint())
            return true;
        if (!mCurrentSettings.isSuggestionStripVisibleInOrientation(mDisplayOrientation))
            return false;
        if (mCurrentSettings.isApplicationSpecifiedCompletionsOn())
            return true;
        return mCurrentSettings.isSuggestionsRequested(mDisplayOrientation);
    }

    private void clearSuggestionStrip() {
        setSuggestionStrip(SuggestedWords.EMPTY, false);
        setAutoCorrectionIndicator(false);
    }

    private void setSuggestionStrip(final SuggestedWords words, final boolean isAutoCorrection) {
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setSuggestions(words);
            mKeyboardSwitcher.onAutoCorrectionStateChanged(isAutoCorrection);
        }
    }

    private void setAutoCorrectionIndicator(final boolean newAutoCorrectionIndicator) {
        // Put a blue underline to a word in TextView which will be auto-corrected.
        if (mIsAutoCorrectionIndicatorOn != newAutoCorrectionIndicator
                && mWordComposer.isComposingWord()) {
            mIsAutoCorrectionIndicatorOn = newAutoCorrectionIndicator;
            final CharSequence textWithUnderline =
                    getTextWithUnderline(mWordComposer.getTypedWord());
            // TODO: when called from an updateSuggestionStrip() call that results from a posted
            // message, this is called outside any batch edit. Potentially, this may result in some
            // janky flickering of the screen, although the display speed makes it unlikely in
            // the practice.
            mConnection.setComposingText(textWithUnderline, 1);
        }
    }

    private void updateSuggestionStrip() {
        mHandler.cancelUpdateSuggestionStrip();

        // Check if we have a suggestion engine attached.
        if (mSuggest == null || !mCurrentSettings.isSuggestionsRequested(mDisplayOrientation)) {
            if (mWordComposer.isComposingWord()) {
                Log.w(TAG, "Called updateSuggestionsOrPredictions but suggestions were not "
                        + "requested!");
                mWordComposer.setAutoCorrection(mWordComposer.getTypedWord());
            }
            return;
        }

        if (!mWordComposer.isComposingWord() && !mCurrentSettings.mBigramPredictionEnabled) {
            setPunctuationSuggestions();
            return;
        }

        final SuggestedWords suggestedWords = getSuggestedWords(Suggest.SESSION_TYPING);
        final String typedWord = mWordComposer.getTypedWord();
        showSuggestionStrip(suggestedWords, typedWord);
    }

    private SuggestedWords getSuggestedWords(final int sessionId) {
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        if (keyboard == null || mSuggest == null) {
            return SuggestedWords.EMPTY;
        }
        final String typedWord = mWordComposer.getTypedWord();
        // Get the word on which we should search the bigrams. If we are composing a word, it's
        // whatever is *before* the half-committed word in the buffer, hence 2; if we aren't, we
        // should just skip whitespace if any, so 1.
        // TODO: this is slow (2-way IPC) - we should probably cache this instead.
        final String prevWord =
                mConnection.getNthPreviousWord(mCurrentSettings.mWordSeparators,
                mWordComposer.isComposingWord() ? 2 : 1);
        final SuggestedWords suggestedWords = mSuggest.getSuggestedWords(mWordComposer,
                prevWord, keyboard.getProximityInfo(), mCurrentSettings.mCorrectionEnabled,
                sessionId);
        return maybeRetrieveOlderSuggestions(typedWord, suggestedWords);
    }

    private SuggestedWords maybeRetrieveOlderSuggestions(final String typedWord,
            final SuggestedWords suggestedWords) {
        // TODO: consolidate this into getSuggestedWords
        // We update the suggestion strip only when we have some suggestions to show, i.e. when
        // the suggestion count is > 1; else, we leave the old suggestions, with the typed word
        // replaced with the new one. However, when the word is a dictionary word, or when the
        // length of the typed word is 1 or 0 (after a deletion typically), we do want to remove the
        // old suggestions. Also, if we are showing the "add to dictionary" hint, we need to
        // revert to suggestions - although it is unclear how we can come here if it's displayed.
        if (suggestedWords.size() > 1 || typedWord.length() <= 1
                || suggestedWords.mTypedWordValid
                || mSuggestionStripView.isShowingAddToDictionaryHint()) {
            return suggestedWords;
        } else {
            return getOlderSuggestions(typedWord);
        }
    }

    private SuggestedWords getOlderSuggestions(final String typedWord) {
        SuggestedWords previousSuggestions = mSuggestionStripView.getSuggestions();
        if (previousSuggestions == mCurrentSettings.mSuggestPuncList) {
            previousSuggestions = SuggestedWords.EMPTY;
        }
        if (typedWord == null) {
            return previousSuggestions;
        }
        final ArrayList<SuggestedWords.SuggestedWordInfo> typedWordAndPreviousSuggestions =
                SuggestedWords.getTypedWordAndPreviousSuggestions(typedWord, previousSuggestions);
        return new SuggestedWords(typedWordAndPreviousSuggestions,
                false /* typedWordValid */,
                false /* hasAutoCorrectionCandidate */,
                false /* isPunctuationSuggestions */,
                true /* isObsoleteSuggestions */,
                false /* isPrediction */);
    }

    private void showSuggestionStrip(final SuggestedWords suggestedWords, final String typedWord) {
        if (suggestedWords.isEmpty()) {
            clearSuggestionStrip();
            return;
        }
        final String autoCorrection;
        if (suggestedWords.mWillAutoCorrect) {
            autoCorrection = suggestedWords.getWord(1);
        } else {
            autoCorrection = typedWord;
        }
        mWordComposer.setAutoCorrection(autoCorrection);
        final boolean isAutoCorrection = suggestedWords.willAutoCorrect();
        setSuggestionStrip(suggestedWords, isAutoCorrection);
        setAutoCorrectionIndicator(isAutoCorrection);
        setSuggestionStripShown(isSuggestionsStripVisible());
    }

    private void commitCurrentAutoCorrection(final String separatorString) {
        // Complete any pending suggestions query first
        if (mHandler.hasPendingUpdateSuggestions()) {
            updateSuggestionStrip();
        }
        final String typedAutoCorrection = mWordComposer.getAutoCorrectionOrNull();
        final String typedWord = mWordComposer.getTypedWord();
        final String autoCorrection = (typedAutoCorrection != null)
                ? typedAutoCorrection : typedWord;
        if (autoCorrection != null) {
            if (TextUtils.isEmpty(typedWord)) {
                throw new RuntimeException("We have an auto-correction but the typed word "
                        + "is empty? Impossible! I must commit suicide.");
            }
            if (ProductionFlag.IS_INTERNAL) {
                Stats.onAutoCorrection(typedWord, autoCorrection, separatorString, mWordComposer);
            }
            mExpectingUpdateSelection = true;
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.latinIme_commitCurrentAutoCorrection(typedWord, autoCorrection,
                        separatorString);
            }

            commitChosenWord(autoCorrection, LastComposedWord.COMMIT_TYPE_DECIDED_WORD,
                    separatorString);
            if (!typedWord.equals(autoCorrection)) {
                // This will make the correction flash for a short while as a visual clue
                // to the user that auto-correction happened. It has no other effect; in particular
                // note that this won't affect the text inside the text field AT ALL: it only makes
                // the segment of text starting at the supplied index and running for the length
                // of the auto-correction flash. At this moment, the "typedWord" argument is
                // ignored by TextView.
                mConnection.commitCorrection(
                        new CorrectionInfo(mLastSelectionEnd - typedWord.length(),
                        typedWord, autoCorrection));
            }
        }
    }

    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    @Override
    public void pickSuggestionManually(final int index, final String suggestion) {
        final SuggestedWords suggestedWords = mSuggestionStripView.getSuggestions();
        // If this is a punctuation picked from the suggestion strip, pass it to onCodeInput
        if (suggestion.length() == 1 && isShowingPunctuationList()) {
            // Word separators are suggested before the user inputs something.
            // So, LatinImeLogger logs "" as a user's input.
            LatinImeLogger.logOnManualSuggestion("", suggestion, index, suggestedWords);
            // Rely on onCodeInput to do the complicated swapping/stripping logic consistently.
            final int primaryCode = suggestion.charAt(0);
            onCodeInput(primaryCode,
                    Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE);
            if (ProductionFlag.IS_EXPERIMENTAL) {
                ResearchLogger.latinIME_punctuationSuggestion(index, suggestion);
            }
            return;
        }

        mConnection.beginBatchEdit();
        if (SPACE_STATE_PHANTOM == mSpaceState && suggestion.length() > 0
                // In the batch input mode, a manually picked suggested word should just replace
                // the current batch input text and there is no need for a phantom space.
                && !mWordComposer.isBatchMode()) {
            int firstChar = Character.codePointAt(suggestion, 0);
            if ((!mCurrentSettings.isWeakSpaceStripper(firstChar))
                    && (!mCurrentSettings.isWeakSpaceSwapper(firstChar))) {
                promotePhantomSpace();
            }
        }

        if (mCurrentSettings.isApplicationSpecifiedCompletionsOn()
                && mApplicationSpecifiedCompletions != null
                && index >= 0 && index < mApplicationSpecifiedCompletions.length) {
            if (mSuggestionStripView != null) {
                mSuggestionStripView.clear();
            }
            mKeyboardSwitcher.updateShiftState();
            resetComposingState(true /* alsoResetLastComposedWord */);
            final CompletionInfo completionInfo = mApplicationSpecifiedCompletions[index];
            mConnection.commitCompletion(completionInfo);
            mConnection.endBatchEdit();
            return;
        }

        // We need to log before we commit, because the word composer will store away the user
        // typed word.
        final String replacedWord = mWordComposer.getTypedWord();
        LatinImeLogger.logOnManualSuggestion(replacedWord, suggestion, index, suggestedWords);
        mExpectingUpdateSelection = true;
        commitChosenWord(suggestion, LastComposedWord.COMMIT_TYPE_MANUAL_PICK,
                LastComposedWord.NOT_A_SEPARATOR);
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_pickSuggestionManually(replacedWord, index, suggestion);
        }
        mConnection.endBatchEdit();
        // Don't allow cancellation of manual pick
        mLastComposedWord.deactivate();
        // Space state must be updated before calling updateShiftState
        mSpaceState = SPACE_STATE_PHANTOM;
        mKeyboardSwitcher.updateShiftState();

        // We should show the "Touch again to save" hint if the user pressed the first entry
        // AND it's in none of our current dictionaries (main, user or otherwise).
        // Please note that if mSuggest is null, it means that everything is off: suggestion
        // and correction, so we shouldn't try to show the hint
        final boolean showingAddToDictionaryHint = index == 0 && mSuggest != null
                // If the suggestion is not in the dictionary, the hint should be shown.
                && !AutoCorrection.isValidWord(mSuggest.getUnigramDictionaries(), suggestion, true);

        if (ProductionFlag.IS_INTERNAL) {
            Stats.onSeparator((char)Constants.CODE_SPACE,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        if (showingAddToDictionaryHint && mIsUserDictionaryAvailable) {
            mSuggestionStripView.showAddToDictionaryHint(
                    suggestion, mCurrentSettings.mHintToSaveText);
        } else {
            // If we're not showing the "Touch again to save", then update the suggestion strip.
            mHandler.postUpdateSuggestionStrip();
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later retrieval.
     */
    private void commitChosenWord(final String chosenWord, final int commitType,
            final String separatorString) {
        final SuggestedWords suggestedWords = mSuggestionStripView.getSuggestions();
        mConnection.commitText(SuggestionSpanUtils.getTextWithSuggestionSpan(
                this, chosenWord, suggestedWords, mIsMainDictionaryAvailable), 1);
        // Add the word to the user history dictionary
        final String prevWord = addToUserHistoryDictionary(chosenWord);
        // TODO: figure out here if this is an auto-correct or if the best word is actually
        // what user typed. Note: currently this is done much later in
        // LastComposedWord#didCommitTypedWord by string equality of the remembered
        // strings.
        mLastComposedWord = mWordComposer.commitWord(commitType, chosenWord, separatorString,
                prevWord);
    }

    private void setPunctuationSuggestions() {
        if (mCurrentSettings.mBigramPredictionEnabled) {
            clearSuggestionStrip();
        } else {
            setSuggestionStrip(mCurrentSettings.mSuggestPuncList, false);
        }
        setAutoCorrectionIndicator(false);
        setSuggestionStripShown(isSuggestionsStripVisible());
    }

    private String addToUserHistoryDictionary(final String suggestion) {
        if (TextUtils.isEmpty(suggestion)) return null;
        if (mSuggest == null) return null;

        // If correction is not enabled, we don't add words to the user history dictionary.
        // That's to avoid unintended additions in some sensitive fields, or fields that
        // expect to receive non-words.
        if (!mCurrentSettings.mCorrectionEnabled) return null;

        final UserHistoryDictionary userHistoryDictionary = mUserHistoryDictionary;
        if (userHistoryDictionary != null) {
            final String prevWord
                    = mConnection.getNthPreviousWord(mCurrentSettings.mWordSeparators, 2);
            final String secondWord;
            if (mWordComposer.wasAutoCapitalized() && !mWordComposer.isMostlyCaps()) {
                secondWord = suggestion.toLowerCase(mSubtypeSwitcher.getCurrentSubtypeLocale());
            } else {
                secondWord = suggestion;
            }
            // We demote unrecognized words (frequency < 0, below) by specifying them as "invalid".
            // We don't add words with 0-frequency (assuming they would be profanity etc.).
            final int maxFreq = AutoCorrection.getMaxFrequency(
                    mSuggest.getUnigramDictionaries(), suggestion);
            if (maxFreq == 0) return null;
            userHistoryDictionary.addToUserHistory(prevWord, secondWord, maxFreq > 0);
            return prevWord;
        }
        return null;
    }

    /**
     * Check if the cursor is actually at the end of a word. If so, restart suggestions on this
     * word, else do nothing.
     */
    private void restartSuggestionsOnWordBeforeCursorIfAtEndOfWord() {
        final CharSequence word = mConnection.getWordBeforeCursorIfAtEndOfWord(mCurrentSettings);
        if (null != word) {
            restartSuggestionsOnWordBeforeCursor(word);
        }
    }

    private void restartSuggestionsOnWordBeforeCursor(final CharSequence word) {
        mWordComposer.setComposingWord(word, mKeyboardSwitcher.getKeyboard());
        final int length = word.length();
        mConnection.deleteSurroundingText(length, 0);
        mConnection.setComposingText(word, 1);
        mHandler.postUpdateSuggestionStrip();
    }

    private void revertCommit() {
        final String previousWord = mLastComposedWord.mPrevWord;
        final String originallyTypedWord = mLastComposedWord.mTypedWord;
        final String committedWord = mLastComposedWord.mCommittedWord;
        final int cancelLength = committedWord.length();
        final int separatorLength = LastComposedWord.getSeparatorLength(
                mLastComposedWord.mSeparatorString);
        // TODO: should we check our saved separator against the actual contents of the text view?
        final int deleteLength = cancelLength + separatorLength;
        if (DEBUG) {
            if (mWordComposer.isComposingWord()) {
                throw new RuntimeException("revertCommit, but we are composing a word");
            }
            final CharSequence wordBeforeCursor =
                    mConnection.getTextBeforeCursor(deleteLength, 0)
                            .subSequence(0, cancelLength);
            if (!TextUtils.equals(committedWord, wordBeforeCursor)) {
                throw new RuntimeException("revertCommit check failed: we thought we were "
                        + "reverting \"" + committedWord
                        + "\", but before the cursor we found \"" + wordBeforeCursor + "\"");
            }
        }
        mConnection.deleteSurroundingText(deleteLength, 0);
        if (!TextUtils.isEmpty(previousWord) && !TextUtils.isEmpty(committedWord)) {
            mUserHistoryDictionary.cancelAddingUserHistory(previousWord, committedWord);
        }
        mConnection.commitText(originallyTypedWord + mLastComposedWord.mSeparatorString, 1);
        if (ProductionFlag.IS_INTERNAL) {
            Stats.onSeparator(mLastComposedWord.mSeparatorString,
                    Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE);
        }
        if (ProductionFlag.IS_EXPERIMENTAL) {
            ResearchLogger.latinIME_revertCommit(committedWord, originallyTypedWord);
            ResearchLogger.getInstance().onWordComplete(originallyTypedWord, Long.MAX_VALUE);
        }
        // Don't restart suggestion yet. We'll restart if the user deletes the
        // separator.
        mLastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD;
        // We have a separator between the word and the cursor: we should show predictions.
        mHandler.postUpdateSuggestionStrip();
    }

    // This essentially inserts a space, and that's it.
    public void promotePhantomSpace() {
        if (mCurrentSettings.shouldInsertSpacesAutomatically()) {
            sendKeyCodePoint(Constants.CODE_SPACE);
        }
    }

    // Used by the RingCharBuffer
    public boolean isWordSeparator(final int code) {
        return mCurrentSettings.isWordSeparator(code);
    }

    // TODO: Make this private
    // Outside LatinIME, only used by the {@link InputTestsBase} test suite.
    @UsedForTesting
    void loadKeyboard() {
        // When the device locale is changed in SetupWizard etc., this method may get called via
        // onConfigurationChanged before SoftInputWindow is shown.
        initSuggest();
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mCurrentSettings);
        }
        // Since we just changed languages, we should re-evaluate suggestions with whatever word
        // we are currently composing. If we are not composing anything, we may want to display
        // predictions or punctuation signs (which is done by the updateSuggestionStrip anyway).
        mHandler.postUpdateSuggestionStrip();
    }

    // Callback called by PointerTracker through the KeyboardActionListener. This is called when a
    // key is depressed; release matching call is onReleaseKey below.
    @Override
    public void onPressKey(final int primaryCode) {
        mKeyboardSwitcher.onPressKey(primaryCode);
    }

    // Callback by PointerTracker through the KeyboardActionListener. This is called when a key
    // is released; press matching call is onPressKey above.
    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding);

        // If accessibility is on, ensure the user receives keyboard state updates.
        if (AccessibilityUtils.getInstance().isTouchExplorationEnabled()) {
            switch (primaryCode) {
            case Constants.CODE_SHIFT:
                AccessibleKeyboardViewProxy.getInstance().notifyShiftState();
                break;
            case Constants.CODE_SWITCH_ALPHA_SYMBOL:
                AccessibleKeyboardViewProxy.getInstance().notifySymbolsState();
                break;
            }
        }

        if (Constants.CODE_DELETE == primaryCode) {
            // This is a stopgap solution to avoid leaving a high surrogate alone in a text view.
            // In the future, we need to deprecate deteleSurroundingText() and have a surrogate
            // pair-friendly way of deleting characters in InputConnection.
            // TODO: use getCodePointBeforeCursor instead to improve performance
            final CharSequence lastChar = mConnection.getTextBeforeCursor(1, 0);
            if (!TextUtils.isEmpty(lastChar) && Character.isHighSurrogate(lastChar.charAt(0))) {
                mConnection.deleteSurroundingText(1, 0);
            }
        }
    }

    // Hooks for hardware keyboard
    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        // onHardwareKeyEvent, like onKeyDown returns true if it handled the event, false if
        // it doesn't know what to do with it and leave it to the application. For example,
        // hardware key events for adjusting the screen's brightness are passed as is.
        if (mEventInterpreter.onHardwareKeyEvent(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent event) {
        if (mEventInterpreter.onHardwareKeyEvent(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    // onKeyDown and onKeyUp are the main events we are interested in. There are two more events
    // related to handling of hardware key events that we may want to implement in the future:
    // boolean onKeyLongPress(final int keyCode, final KeyEvent event);
    // boolean onKeyMultiple(final int keyCode, final int count, final KeyEvent event);

    // receive ringer mode change and network state change.
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                mSubtypeSwitcher.onNetworkStateChanged(intent);
            } else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mKeyboardSwitcher.onRingerModeChanged();
            }
        }
    };

    private void launchSettings() {
        handleClose();
        launchSubActivity(SettingsActivity.class);
    }

    // Called from debug code only
    public void launchDebugSettings() {
        handleClose();
        launchSubActivity(DebugSettingsActivity.class);
    }

    public void launchKeyboardedDialogActivity(final Class<? extends Activity> activityClass) {
        // Put the text in the attached EditText into a safe, saved state before switching to a
        // new activity that will also use the soft keyboard.
        commitTyped(LastComposedWord.NOT_A_SEPARATOR);
        launchSubActivity(activityClass);
    }

    private void launchSubActivity(final Class<? extends Activity> activityClass) {
        Intent intent = new Intent();
        intent.setClass(LatinIME.this, activityClass);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showSubtypeSelectorAndSettings() {
        final CharSequence title = getString(R.string.english_ime_input_options);
        final CharSequence[] items = new CharSequence[] {
                // TODO: Should use new string "Select active input modes".
                getString(R.string.language_selection_title),
                getString(R.string.english_ime_settings),
        };
        final DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int position) {
                di.dismiss();
                switch (position) {
                case 0:
                    Intent intent = CompatUtils.getInputLanguageSelectionIntent(
                            mRichImm.getInputMethodIdOfThisIme(),
                            Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    break;
                case 1:
                    launchSettings();
                    break;
                }
            }
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setItems(items, listener)
                .setTitle(title);
        showOptionDialog(builder.create());
    }

    public void showOptionDialog(final AlertDialog dialog) {
        final IBinder windowToken = mKeyboardSwitcher.getMainKeyboardView().getWindowToken();
        if (windowToken == null) {
            return;
        }

        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        final Window window = dialog.getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.token = windowToken;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        mOptionsDialog = dialog;
        dialog.show();
    }

    public void debugDumpStateAndCrashWithException(final String context) {
        final StringBuilder s = new StringBuilder();
        s.append("Target application : ").append(mTargetApplicationInfo.name)
                .append("\nPackage : ").append(mTargetApplicationInfo.packageName)
                .append("\nTarget app sdk version : ")
                .append(mTargetApplicationInfo.targetSdkVersion)
                .append("\nAttributes : ").append(mCurrentSettings.getInputAttributesDebugString())
                .append("\nContext : ").append(context);
        throw new RuntimeException(s.toString());
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
        p.println("  mIsSuggestionsSuggestionsRequested = "
                + mCurrentSettings.isSuggestionsRequested(mDisplayOrientation));
        p.println("  mCorrectionEnabled=" + mCurrentSettings.mCorrectionEnabled);
        p.println("  isComposingWord=" + mWordComposer.isComposingWord());
        p.println("  mSoundOn=" + mCurrentSettings.mSoundOn);
        p.println("  mVibrateOn=" + mCurrentSettings.mVibrateOn);
        p.println("  mKeyPreviewPopupOn=" + mCurrentSettings.mKeyPreviewPopupOn);
        p.println("  inputAttributes=" + mCurrentSettings.getInputAttributesDebugString());
    }
}
