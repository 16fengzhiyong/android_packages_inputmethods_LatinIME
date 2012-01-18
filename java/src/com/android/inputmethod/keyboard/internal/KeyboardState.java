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

package com.android.inputmethod.keyboard.internal;

import android.text.TextUtils;
import android.util.Log;

import com.android.inputmethod.keyboard.Keyboard;

/**
 * Keyboard state machine.
 *
 * This class contains all keyboard state transition logic.
 *
 * The input events are {@link #onLoadKeyboard(String)}, {@link #onSaveKeyboardState()},
 * {@link #onPressKey(int)}, {@link #onReleaseKey(int, boolean)},
 * {@link #onCodeInput(int, boolean, boolean)}, {@link #onCancelInput(boolean)},
 * {@link #onUpdateShiftState(boolean)}.
 *
 * The actions are {@link SwitchActions}'s methods.
 */
public class KeyboardState {
    private static final String TAG = KeyboardState.class.getSimpleName();
    private static final boolean DEBUG_EVENT = false;
    private static final boolean DEBUG_ACTION = false;

    public interface SwitchActions {
        public void setAlphabetKeyboard();

        public static final int UNSHIFT = 0;
        public static final int MANUAL_SHIFT = 1;
        public static final int AUTOMATIC_SHIFT = 2;
        public void setShifted(int shiftMode);

        public void setShiftLocked(boolean shiftLocked);

        public void setSymbolsKeyboard();

        public void setSymbolsShiftedKeyboard();

        /**
         * Request to call back {@link KeyboardState#onUpdateShiftState(boolean)}.
         */
        public void requestUpdatingShiftState();
    }

    private KeyboardShiftState mKeyboardShiftState = new KeyboardShiftState();

    private ShiftKeyState mShiftKeyState = new ShiftKeyState("Shift");
    private ModifierKeyState mSymbolKeyState = new ModifierKeyState("Symbol");

    private static final int SWITCH_STATE_ALPHA = 0;
    private static final int SWITCH_STATE_SYMBOL_BEGIN = 1;
    private static final int SWITCH_STATE_SYMBOL = 2;
    // The following states are used only on the distinct multi-touch panel devices.
    private static final int SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL = 3;
    private static final int SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE = 4;
    private static final int SWITCH_STATE_CHORDING_ALPHA = 5;
    private static final int SWITCH_STATE_CHORDING_SYMBOL = 6;
    private int mSwitchState = SWITCH_STATE_ALPHA;

    private String mLayoutSwitchBackSymbols;

    private final SwitchActions mSwitchActions;

    private boolean mIsAlphabetMode;
    private boolean mIsSymbolShifted;

    private final SavedKeyboardState mSavedKeyboardState = new SavedKeyboardState();
    private boolean mPrevMainKeyboardWasShiftLocked;

    static class SavedKeyboardState {
        public boolean mIsValid;
        public boolean mIsAlphabetMode;
        public boolean mIsShiftLocked;
        public boolean mIsShifted;
    }

    public KeyboardState(SwitchActions switchActions) {
        mSwitchActions = switchActions;
    }

    public void onLoadKeyboard(String layoutSwitchBackSymbols) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onLoadKeyboard");
        }
        mLayoutSwitchBackSymbols = layoutSwitchBackSymbols;
        mKeyboardShiftState.setShifted(false);
        mKeyboardShiftState.setShiftLocked(false);
        mShiftKeyState.onRelease();
        mSymbolKeyState.onRelease();
        mPrevMainKeyboardWasShiftLocked = false;
        onRestoreKeyboardState();
    }

    public void onSaveKeyboardState() {
        final SavedKeyboardState state = mSavedKeyboardState;
        state.mIsAlphabetMode = mIsAlphabetMode;
        if (mIsAlphabetMode) {
            state.mIsShiftLocked = mKeyboardShiftState.isShiftLocked();
            state.mIsShifted = !state.mIsShiftLocked
                    && mKeyboardShiftState.isShiftedOrShiftLocked();
        } else {
            state.mIsShiftLocked = false;
            state.mIsShifted = mIsSymbolShifted;
        }
        state.mIsValid = true;
        if (DEBUG_EVENT) {
            Log.d(TAG, "onSaveKeyboardState: alphabet=" + state.mIsAlphabetMode
                    + " shiftLocked=" + state.mIsShiftLocked + " shift=" + state.mIsShifted);
        }
    }

    private void onRestoreKeyboardState() {
        final SavedKeyboardState state = mSavedKeyboardState;
        if (DEBUG_EVENT) {
            Log.d(TAG, "onRestoreKeyboardState: valid=" + state.mIsValid
                    + " alphabet=" + state.mIsAlphabetMode
                    + " shiftLocked=" + state.mIsShiftLocked + " shift=" + state.mIsShifted);
        }
        if (!state.mIsValid || state.mIsAlphabetMode) {
            setAlphabetKeyboard();
        } else {
            if (state.mIsShifted) {
                setSymbolsShiftedKeyboard();
            } else {
                setSymbolsKeyboard();
            }
        }

        if (!state.mIsValid) return;
        state.mIsValid = false;

        if (state.mIsAlphabetMode) {
            setShiftLocked(state.mIsShiftLocked);
            if (!state.mIsShiftLocked) {
                setShifted(state.mIsShifted ? SwitchActions.MANUAL_SHIFT : SwitchActions.UNSHIFT);
            }
        }
    }

    // TODO: Remove this method.
    public boolean isShiftLocked() {
        return mKeyboardShiftState.isShiftLocked();
    }

    private void setShifted(int shiftMode) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setShifted: shiftMode=" + shiftModeToString(shiftMode));
        }
        switch (shiftMode) {
        case SwitchActions.AUTOMATIC_SHIFT:
            mKeyboardShiftState.setAutomaticTemporaryUpperCase();
            break;
        case SwitchActions.MANUAL_SHIFT:
            mKeyboardShiftState.setShifted(true);
            break;
        case SwitchActions.UNSHIFT:
            mKeyboardShiftState.setShifted(false);
            break;
        }
        mSwitchActions.setShifted(shiftMode);
    }

    private void setShiftLocked(boolean shiftLocked) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setShiftLocked: shiftLocked=" + shiftLocked);
        }
        mKeyboardShiftState.setShiftLocked(shiftLocked);
        mSwitchActions.setShiftLocked(shiftLocked);
    }

    private void toggleAlphabetAndSymbols() {
        if (mIsAlphabetMode) {
            setSymbolsKeyboard();
        } else {
            setAlphabetKeyboard();
        }
    }

    private void toggleShiftInSymbols() {
        if (mIsSymbolShifted) {
            setSymbolsKeyboard();
        } else {
            setSymbolsShiftedKeyboard();
        }
    }

    private void setAlphabetKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        mSwitchActions.setAlphabetKeyboard();
        mIsAlphabetMode = true;
        mIsSymbolShifted = false;
        mSwitchState = SWITCH_STATE_ALPHA;
        setShiftLocked(mPrevMainKeyboardWasShiftLocked);
        mPrevMainKeyboardWasShiftLocked = false;
        mSwitchActions.requestUpdatingShiftState();
    }

    // TODO: Make this method private
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        mPrevMainKeyboardWasShiftLocked = mKeyboardShiftState.isShiftLocked();
        mSwitchActions.setSymbolsKeyboard();
        mIsAlphabetMode = false;
        mIsSymbolShifted = false;
        mSwitchState = SWITCH_STATE_SYMBOL_BEGIN;
    }

    private void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        mSwitchActions.setSymbolsShiftedKeyboard();
        mIsAlphabetMode = false;
        mIsSymbolShifted = true;
        mSwitchState = SWITCH_STATE_SYMBOL_BEGIN;
    }

    public void onPressKey(int code) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onPressKey: code=" + Keyboard.printableCode(code) + " " + this);
        }
        if (code == Keyboard.CODE_SHIFT) {
            onPressShift();
        } else if (code == Keyboard.CODE_SWITCH_ALPHA_SYMBOL) {
            onPressSymbol();
        } else {
            mShiftKeyState.onOtherKeyPressed();
            mSymbolKeyState.onOtherKeyPressed();
        }
    }

    public void onReleaseKey(int code, boolean withSliding) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onReleaseKey: code=" + Keyboard.printableCode(code)
                    + " sliding=" + withSliding + " " + this);
        }
        if (code == Keyboard.CODE_SHIFT) {
            onReleaseShift(withSliding);
        } else if (code == Keyboard.CODE_SWITCH_ALPHA_SYMBOL) {
            // TODO: Make use of withSliding instead of relying on mSwitchState.
            onReleaseSymbol();
        }
    }

    private void onPressSymbol() {
        toggleAlphabetAndSymbols();
        mSymbolKeyState.onPress();
        mSwitchState = SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL;
    }

    private void onReleaseSymbol() {
        // Snap back to the previous keyboard mode if the user chords the mode change key and
        // another key, then releases the mode change key.
        if (mSwitchState == SWITCH_STATE_CHORDING_ALPHA) {
            toggleAlphabetAndSymbols();
        }
        mSymbolKeyState.onRelease();
    }

    public void onUpdateShiftState(boolean autoCaps) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onUpdateShiftState: autoCaps=" + autoCaps + " " + this);
        }
        onUpdateShiftStateInternal(autoCaps);
    }

    private void onUpdateShiftStateInternal(boolean autoCaps) {
        if (mIsAlphabetMode) {
            if (!mKeyboardShiftState.isShiftLocked() && !mShiftKeyState.isIgnoring()) {
                if (mShiftKeyState.isReleasing() && autoCaps) {
                    // Only when shift key is releasing, automatic temporary upper case will be set.
                    setShifted(SwitchActions.AUTOMATIC_SHIFT);
                } else {
                    setShifted(mShiftKeyState.isMomentary()
                            ? SwitchActions.MANUAL_SHIFT : SwitchActions.UNSHIFT);
                }
            }
        } else {
            // In symbol keyboard mode, we should clear shift key state because only alphabet
            // keyboard has shift key.
            mSymbolKeyState.onRelease();
        }
    }

    private void onPressShift() {
        if (mIsAlphabetMode) {
            if (mKeyboardShiftState.isShiftLocked()) {
                // Shift key is pressed while caps lock state, we will treat this state as shifted
                // caps lock state and mark as if shift key pressed while normal state.
                setShifted(SwitchActions.MANUAL_SHIFT);
                mShiftKeyState.onPress();
            } else if (mKeyboardShiftState.isAutomaticTemporaryUpperCase()) {
                // Shift key is pressed while automatic temporary upper case, we have to move to
                // manual temporary upper case.
                setShifted(SwitchActions.MANUAL_SHIFT);
                mShiftKeyState.onPress();
            } else if (mKeyboardShiftState.isShiftedOrShiftLocked()) {
                // In manual upper case state, we just record shift key has been pressing while
                // shifted state.
                mShiftKeyState.onPressOnShifted();
            } else {
                // In base layout, chording or manual temporary upper case mode is started.
                setShifted(SwitchActions.MANUAL_SHIFT);
                mShiftKeyState.onPress();
            }
        } else {
            // In symbol mode, just toggle symbol and symbol more keyboard.
            toggleShiftInSymbols();
            mSwitchState = SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE;
            mShiftKeyState.onPress();
        }
    }

    private void onReleaseShift(boolean withSliding) {
        if (mIsAlphabetMode) {
            final boolean isShiftLocked = mKeyboardShiftState.isShiftLocked();
            if (mShiftKeyState.isMomentary()) {
                // After chording input while normal state.
                setShifted(SwitchActions.UNSHIFT);
            } else if (isShiftLocked && !mKeyboardShiftState.isShiftLockShifted()
                    && (mShiftKeyState.isPressing() || mShiftKeyState.isPressingOnShifted())
                    && !withSliding) {
                // Shift has been long pressed, ignore this release.
            } else if (isShiftLocked && !mShiftKeyState.isIgnoring() && !withSliding) {
                // Shift has been pressed without chording while caps lock state.
                setShiftLocked(false);
            } else if (mKeyboardShiftState.isShiftedOrShiftLocked()
                    && mShiftKeyState.isPressingOnShifted() && !withSliding) {
                // Shift has been pressed without chording while shifted state.
                setShifted(SwitchActions.UNSHIFT);
            } else if (mKeyboardShiftState.isManualTemporaryUpperCaseFromAuto()
                    && mShiftKeyState.isPressing() && !withSliding) {
                // Shift has been pressed without chording while manual temporary upper case
                // transited from automatic temporary upper case.
                setShifted(SwitchActions.UNSHIFT);
            }
        } else {
            // In symbol mode, snap back to the previous keyboard mode if the user chords the shift
            // key and another key, then releases the shift key.
            if (mSwitchState == SWITCH_STATE_CHORDING_SYMBOL) {
                toggleShiftInSymbols();
            }
        }
        mShiftKeyState.onRelease();
    }

    public void onCancelInput(boolean isSinglePointer) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onCancelInput: single=" + isSinglePointer + " " + this);
        }
        // Snap back to the previous keyboard mode if the user cancels sliding input.
        if (isSinglePointer) {
            if (mSwitchState == SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL) {
                toggleAlphabetAndSymbols();
            } else if (mSwitchState == SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE) {
                toggleShiftInSymbols();
            }
        }
    }

    public boolean isInMomentarySwitchState() {
        return mSwitchState == SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL
                || mSwitchState == SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE;
    }

    private static boolean isSpaceCharacter(int c) {
        return c == Keyboard.CODE_SPACE || c == Keyboard.CODE_ENTER;
    }

    private boolean isLayoutSwitchBackCharacter(int c) {
        if (TextUtils.isEmpty(mLayoutSwitchBackSymbols)) return false;
        if (mLayoutSwitchBackSymbols.indexOf(c) >= 0) return true;
        return false;
    }

    public void onCodeInput(int code, boolean isSinglePointer, boolean autoCaps) {
        if (DEBUG_EVENT) {
            Log.d(TAG, "onCodeInput: code=" + Keyboard.printableCode(code)
                    + " single=" + isSinglePointer
                    + " autoCaps=" + autoCaps + " " + this);
        }

        if (mIsAlphabetMode && code == Keyboard.CODE_CAPSLOCK) {
            if (mKeyboardShiftState.isShiftLocked()) {
                setShiftLocked(false);
                // Shift key is long pressed or double tapped while caps lock state, we will
                // toggle back to normal state. And mark as if shift key is released.
                mShiftKeyState.onRelease();
            } else {
                setShiftLocked(true);
            }
        }

        switch (mSwitchState) {
        case SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL:
            // Only distinct multi touch devices can be in this state.
            // On non-distinct multi touch devices, mode change key is handled by
            // {@link LatinIME#onCodeInput}, not by {@link LatinIME#onPress} and
            // {@link LatinIME#onRelease}. So, on such devices, {@link #mSwitchState} starts
            // from {@link #SWITCH_STATE_SYMBOL_BEGIN}, or {@link #SWITCH_STATE_ALPHA}, not from
            // {@link #SWITCH_STATE_MOMENTARY}.
            if (code == Keyboard.CODE_SWITCH_ALPHA_SYMBOL) {
                // Detected only the mode change key has been pressed, and then released.
                if (mIsAlphabetMode) {
                    mSwitchState = SWITCH_STATE_ALPHA;
                } else {
                    mSwitchState = SWITCH_STATE_SYMBOL_BEGIN;
                }
            } else if (isSinglePointer) {
                // Snap back to the previous keyboard mode if the user pressed the mode change key
                // and slid to other key, then released the finger.
                // If the user cancels the sliding input, snapping back to the previous keyboard
                // mode is handled by {@link #onCancelInput}.
                toggleAlphabetAndSymbols();
            } else {
                // Chording input is being started. The keyboard mode will be snapped back to the
                // previous mode in {@link onReleaseSymbol} when the mode change key is released.
                mSwitchState = SWITCH_STATE_CHORDING_ALPHA;
            }
            break;
        case SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE:
            if (code == Keyboard.CODE_SHIFT) {
                // Detected only the shift key has been pressed on symbol layout, and then released.
                mSwitchState = SWITCH_STATE_SYMBOL_BEGIN;
            } else if (isSinglePointer) {
                // Snap back to the previous keyboard mode if the user pressed the shift key on
                // symbol mode and slid to other key, then released the finger.
                toggleShiftInSymbols();
                mSwitchState = SWITCH_STATE_SYMBOL;
            } else {
                // Chording input is being started. The keyboard mode will be snapped back to the
                // previous mode in {@link onReleaseShift} when the shift key is released.
                mSwitchState = SWITCH_STATE_CHORDING_SYMBOL;
            }
            break;
        case SWITCH_STATE_SYMBOL_BEGIN:
            if (!isSpaceCharacter(code) && (Keyboard.isLetterCode(code)
                    || code == Keyboard.CODE_OUTPUT_TEXT)) {
                mSwitchState = SWITCH_STATE_SYMBOL;
            }
            // Snap back to alpha keyboard mode immediately if user types a quote character.
            if (isLayoutSwitchBackCharacter(code)) {
                setAlphabetKeyboard();
            }
            break;
        case SWITCH_STATE_SYMBOL:
        case SWITCH_STATE_CHORDING_SYMBOL:
            // Snap back to alpha keyboard mode if user types one or more non-space/enter
            // characters followed by a space/enter or a quote character.
            if (isSpaceCharacter(code) || isLayoutSwitchBackCharacter(code)) {
                setAlphabetKeyboard();
            }
            break;
        }

        // If the code is a letter, update keyboard shift state.
        if (Keyboard.isLetterCode(code)) {
            onUpdateShiftStateInternal(autoCaps);
        }
    }

    private static String shiftModeToString(int shiftMode) {
        switch (shiftMode) {
        case SwitchActions.UNSHIFT: return "UNSHIFT";
        case SwitchActions.MANUAL_SHIFT: return "MANUAL";
        case SwitchActions.AUTOMATIC_SHIFT: return "AUTOMATIC";
        default: return null;
        }
    }

    private static String switchStateToString(int switchState) {
        switch (switchState) {
        case SWITCH_STATE_ALPHA: return "ALPHA";
        case SWITCH_STATE_SYMBOL_BEGIN: return "SYMBOL-BEGIN";
        case SWITCH_STATE_SYMBOL: return "SYMBOL";
        case SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL: return "MOMENTARY-ALPHA-SYMBOL";
        case SWITCH_STATE_MOMENTARY_SYMBOL_AND_MORE: return "MOMENTARY-SYMBOL-MORE";
        case SWITCH_STATE_CHORDING_ALPHA: return "CHORDING-ALPHA";
        case SWITCH_STATE_CHORDING_SYMBOL: return "CHORDING-SYMBOL";
        default: return null;
        }
    }

    @Override
    public String toString() {
        return "[keyboard=" + mKeyboardShiftState
                + " shift=" + mShiftKeyState
                + " symbol=" + mSymbolKeyState
                + " switch=" + switchStateToString(mSwitchState) + "]";
    }
}
