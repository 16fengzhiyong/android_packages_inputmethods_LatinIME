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

package com.android.inputmethod.latin.makedict;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.inputmethod.latin.makedict.BinaryDictDecoderUtils.DictBuffer;
import com.android.inputmethod.latin.makedict.BinaryDictDecoder.
        DictionaryBufferFromWritableByteBufferFactory;
import com.android.inputmethod.latin.makedict.FormatSpec.FileHeader;
import com.android.inputmethod.latin.makedict.FusionDictionary.PtNodeArray;
import com.android.inputmethod.latin.makedict.FusionDictionary.WeightedString;
import com.android.inputmethod.latin.utils.CollectionUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

@LargeTest
public class BinaryDictIOUtilsTests extends AndroidTestCase {
    private static final String TAG = BinaryDictIOUtilsTests.class.getSimpleName();
    private static final FormatSpec.FormatOptions FORMAT_OPTIONS =
            new FormatSpec.FormatOptions(3, true);

    private static final ArrayList<String> sWords = CollectionUtils.newArrayList();
    public static final int DEFAULT_MAX_UNIGRAMS = 1500;
    private final int mMaxUnigrams;

    private static final String TEST_DICT_FILE_EXTENSION = ".testDict";

    private static final String[] CHARACTERS = {
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "\u00FC" /* ü */, "\u00E2" /* â */, "\u00F1" /* ñ */, // accented characters
        "\u4E9C" /* 亜 */, "\u4F0A" /* 伊 */, "\u5B87" /* 宇 */, // kanji
        "\uD841\uDE28" /* 𠘨 */, "\uD840\uDC0B" /* 𠀋 */, "\uD861\uDED7" /* 𨛗 */ // surrogate pair
    };

    public BinaryDictIOUtilsTests() {
        // 1500 is the default max unigrams
        this(System.currentTimeMillis(), DEFAULT_MAX_UNIGRAMS);
    }

    public BinaryDictIOUtilsTests(final long seed, final int maxUnigrams) {
        super();
        Log.d(TAG, "Seed for test is " + seed + ", maxUnigrams is " + maxUnigrams);
        mMaxUnigrams = maxUnigrams;
        final Random random = new Random(seed);
        sWords.clear();
        for (int i = 0; i < maxUnigrams; ++i) {
            sWords.add(generateWord(random.nextInt()));
        }
    }

    // Utilities for test
    private String generateWord(final int value) {
        final int lengthOfChars = CHARACTERS.length;
        StringBuilder builder = new StringBuilder("");
        long lvalue = Math.abs((long)value);
        while (lvalue > 0) {
            builder.append(CHARACTERS[(int)(lvalue % lengthOfChars)]);
            lvalue /= lengthOfChars;
        }
        if (builder.toString().equals("")) return "a";
        return builder.toString();
    }

    private static void printCharGroup(final CharGroupInfo info) {
        Log.d(TAG, "    CharGroup at " + info.mOriginalAddress);
        Log.d(TAG, "        flags = " + info.mFlags);
        Log.d(TAG, "        parentAddress = " + info.mParentAddress);
        Log.d(TAG, "        characters = " + new String(info.mCharacters, 0,
                info.mCharacters.length));
        if (info.mFrequency != -1) Log.d(TAG, "        frequency = " + info.mFrequency);
        if (info.mChildrenAddress == FormatSpec.NO_CHILDREN_ADDRESS) {
            Log.d(TAG, "        children address = no children address");
        } else {
            Log.d(TAG, "        children address = " + info.mChildrenAddress);
        }
        if (info.mShortcutTargets != null) {
            for (final WeightedString ws : info.mShortcutTargets) {
                Log.d(TAG, "        shortcuts = " + ws.mWord);
            }
        }
        if (info.mBigrams != null) {
            for (final PendingAttribute attr : info.mBigrams) {
                Log.d(TAG, "        bigram = " + attr.mAddress);
            }
        }
        Log.d(TAG, "    end address = " + info.mEndAddress);
    }

    private static void printNode(final DictBuffer dictBuffer,
            final FormatSpec.FormatOptions formatOptions) {
        Log.d(TAG, "Node at " + dictBuffer.position());
        final int count = BinaryDictDecoderUtils.readCharGroupCount(dictBuffer);
        Log.d(TAG, "    charGroupCount = " + count);
        for (int i = 0; i < count; ++i) {
            final CharGroupInfo currentInfo = BinaryDictDecoderUtils.readCharGroup(dictBuffer,
                    dictBuffer.position(), formatOptions);
            printCharGroup(currentInfo);
        }
        if (formatOptions.mSupportsDynamicUpdate) {
            final int forwardLinkAddress = dictBuffer.readUnsignedInt24();
            Log.d(TAG, "    forwardLinkAddress = " + forwardLinkAddress);
        }
    }

    private static void printBinaryFile(final BinaryDictDecoder dictDecoder)
            throws IOException, UnsupportedFormatException {
        final FileHeader fileHeader = dictDecoder.readHeader();
        final DictBuffer buffer = dictDecoder.getDictBuffer();
        while (buffer.position() < buffer.limit()) {
            printNode(buffer, fileHeader.mFormatOptions);
        }
    }

    private int getWordPosition(final File file, final String word) {
        int position = FormatSpec.NOT_VALID_WORD;
        final BinaryDictDecoder dictDecoder = new BinaryDictDecoder(file);
        FileInputStream inStream = null;
        try {
            inStream = new FileInputStream(file);
            dictDecoder.openDictBuffer(
                    new BinaryDictDecoder.DictionaryBufferFromReadOnlyByteBufferFactory());
            position = BinaryDictIOUtils.getTerminalPosition(dictDecoder, word);
        } catch (IOException e) {
        } catch (UnsupportedFormatException e) {
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e) {
                    // do nothing
                }
            }
        }
        return position;
    }

    private CharGroupInfo findWordFromFile(final File file, final String word) {
        final BinaryDictDecoder dictDecoder = new BinaryDictDecoder(file);
        CharGroupInfo info = null;
        try {
            dictDecoder.openDictBuffer(
                    new BinaryDictDecoder.DictionaryBufferFromReadOnlyByteBufferFactory());
            info = BinaryDictIOUtils.findWordByBinaryDictReader(dictDecoder, word);
        } catch (IOException e) {
        } catch (UnsupportedFormatException e) {
        }
        return info;
    }

    // return amount of time to insert a word
    private long insertAndCheckWord(final File file, final String word, final int frequency,
            final boolean exist, final ArrayList<WeightedString> bigrams,
            final ArrayList<WeightedString> shortcuts) {
        final BinaryDictDecoder dictDecoder = new BinaryDictDecoder(file);
        BufferedOutputStream outStream = null;
        long amountOfTime = -1;
        try {
            dictDecoder.openDictBuffer(new DictionaryBufferFromWritableByteBufferFactory());
            outStream = new BufferedOutputStream(new FileOutputStream(file, true));

            if (!exist) {
                assertEquals(FormatSpec.NOT_VALID_WORD, getWordPosition(file, word));
            }
            final long now = System.nanoTime();
            DynamicBinaryDictIOUtils.insertWord(dictDecoder, outStream, word, frequency, bigrams,
                    shortcuts, false, false);
            amountOfTime = System.nanoTime() - now;
            outStream.flush();
            MoreAsserts.assertNotEqual(FormatSpec.NOT_VALID_WORD, getWordPosition(file, word));
            outStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Raised an IOException while inserting a word", e);
        } catch (UnsupportedFormatException e) {
            Log.e(TAG, "Raised an UnsupportedFormatException error while inserting a word", e);
        } finally {
            if (outStream != null) {
                try {
                    outStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close the output stream", e);
                }
            }
        }
        return amountOfTime;
    }

    private void deleteWord(final File file, final String word) {
        final BinaryDictDecoder dictDecoder = new BinaryDictDecoder(file);
        try {
            dictDecoder.openDictBuffer(new DictionaryBufferFromWritableByteBufferFactory());
            DynamicBinaryDictIOUtils.deleteWord(dictDecoder, word);
        } catch (IOException e) {
        } catch (UnsupportedFormatException e) {
        }
    }

    private void checkReverseLookup(final File file, final String word, final int position) {
        final BinaryDictDecoder dictDecoder = new BinaryDictDecoder(file);
        try {
            final DictBuffer dictBuffer = dictDecoder.openAndGetDictBuffer(
                    new BinaryDictDecoder.DictionaryBufferFromReadOnlyByteBufferFactory());
            final FileHeader fileHeader = dictDecoder.readHeader();
            assertEquals(word,
                    BinaryDictDecoderUtils.getWordAtAddress(dictDecoder.getDictBuffer(),
                            fileHeader.mHeaderSize, position - fileHeader.mHeaderSize,
                            fileHeader.mFormatOptions).mWord);
        } catch (IOException e) {
            Log.e(TAG, "Raised an IOException while looking up a word", e);
        } catch (UnsupportedFormatException e) {
            Log.e(TAG, "Raised an UnsupportedFormatException error while looking up a word", e);
        }
    }

    public void testInsertWord() {
        File file = null;
        try {
            file = File.createTempFile("testInsertWord", TEST_DICT_FILE_EXTENSION,
                    getContext().getCacheDir());
        } catch (IOException e) {
            fail("IOException while creating temporary file: " + e);
        }

        // set an initial dictionary.
        final FusionDictionary dict = new FusionDictionary(new PtNodeArray(),
                new FusionDictionary.DictionaryOptions(new HashMap<String,String>(), false, false));
        dict.add("abcd", 10, null, false);

        try {
            final FileOutputStream out = new FileOutputStream(file);
            BinaryDictEncoder.writeDictionaryBinary(out, dict, FORMAT_OPTIONS);
            out.close();
        } catch (IOException e) {
            fail("IOException while writing an initial dictionary : " + e);
        } catch (UnsupportedFormatException e) {
            fail("UnsupportedFormatException while writing an initial dictionary : " + e);
        }

        MoreAsserts.assertNotEqual(FormatSpec.NOT_VALID_WORD, getWordPosition(file, "abcd"));
        insertAndCheckWord(file, "abcde", 10, false, null, null);

        insertAndCheckWord(file, "abcdefghijklmn", 10, false, null, null);
        checkReverseLookup(file, "abcdefghijklmn", getWordPosition(file, "abcdefghijklmn"));

        insertAndCheckWord(file, "abcdabcd", 10, false, null, null);
        checkReverseLookup(file, "abcdabcd", getWordPosition(file, "abcdabcd"));

        // update the existing word.
        insertAndCheckWord(file, "abcdabcd", 15, true, null, null);

        // split 1
        insertAndCheckWord(file, "ab", 20, false, null, null);

        // split 2
        insertAndCheckWord(file, "ami", 30, false, null, null);

        deleteWord(file, "ami");
        assertEquals(FormatSpec.NOT_VALID_WORD, getWordPosition(file, "ami"));

        insertAndCheckWord(file, "abcdabfg", 30, false, null, null);

        deleteWord(file, "abcd");
        assertEquals(FormatSpec.NOT_VALID_WORD, getWordPosition(file, "abcd"));
    }

    public void testInsertWordWithBigrams() {
        File file = null;
        try {
            file = File.createTempFile("testInsertWordWithBigrams", TEST_DICT_FILE_EXTENSION,
                    getContext().getCacheDir());
        } catch (IOException e) {
            fail("IOException while creating temporary file: " + e);
        }

        // set an initial dictionary.
        final FusionDictionary dict = new FusionDictionary(new PtNodeArray(),
                new FusionDictionary.DictionaryOptions(new HashMap<String,String>(), false, false));
        dict.add("abcd", 10, null, false);
        dict.add("efgh", 15, null, false);

        try {
            final FileOutputStream out = new FileOutputStream(file);
            BinaryDictEncoder.writeDictionaryBinary(out, dict, FORMAT_OPTIONS);
            out.close();
        } catch (IOException e) {
            fail("IOException while writing an initial dictionary : " + e);
        } catch (UnsupportedFormatException e) {
            fail("UnsupportedFormatException while writing an initial dictionary : " + e);
        }

        final ArrayList<WeightedString> banana = new ArrayList<WeightedString>();
        banana.add(new WeightedString("banana", 10));

        insertAndCheckWord(file, "banana", 0, false, null, null);
        insertAndCheckWord(file, "recursive", 60, true, banana, null);

        final CharGroupInfo info = findWordFromFile(file, "recursive");
        int bananaPos = getWordPosition(file, "banana");
        assertNotNull(info.mBigrams);
        assertEquals(info.mBigrams.size(), 1);
        assertEquals(info.mBigrams.get(0).mAddress, bananaPos);
    }

    public void testRandomWords() {
        File file = null;
        try {
            file = File.createTempFile("testRandomWord", TEST_DICT_FILE_EXTENSION,
                    getContext().getCacheDir());
        } catch (IOException e) {
        }
        assertNotNull(file);

        // set an initial dictionary.
        final FusionDictionary dict = new FusionDictionary(new PtNodeArray(),
                new FusionDictionary.DictionaryOptions(new HashMap<String, String>(), false,
                        false));
        dict.add("initial", 10, null, false);

        try {
            final FileOutputStream out = new FileOutputStream(file);
            BinaryDictEncoder.writeDictionaryBinary(out, dict, FORMAT_OPTIONS);
            out.close();
        } catch (IOException e) {
            assertTrue(false);
        } catch (UnsupportedFormatException e) {
            assertTrue(false);
        }

        long maxTimeToInsert = 0, sum = 0;
        long minTimeToInsert = 100000000; // 1000000000 is an upper bound for minTimeToInsert.
        int cnt = 0;
        for (final String word : sWords) {
            final long diff = insertAndCheckWord(file, word,
                    cnt % FormatSpec.MAX_TERMINAL_FREQUENCY, false, null, null);
            maxTimeToInsert = Math.max(maxTimeToInsert, diff);
            minTimeToInsert = Math.min(minTimeToInsert, diff);
            sum += diff;
            cnt++;
        }
        cnt = 0;
        for (final String word : sWords) {
            MoreAsserts.assertNotEqual(FormatSpec.NOT_VALID_WORD, getWordPosition(file, word));
        }

        Log.d(TAG, "max = " + ((double)maxTimeToInsert/1000000) + " ms.");
        Log.d(TAG, "min = " + ((double)minTimeToInsert/1000000) + " ms.");
        Log.d(TAG, "avg = " + ((double)sum/mMaxUnigrams/1000000) + " ms.");
    }
}
