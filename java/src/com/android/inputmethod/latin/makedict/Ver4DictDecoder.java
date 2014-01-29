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

package com.android.inputmethod.latin.makedict;

import com.android.inputmethod.annotations.UsedForTesting;
import com.android.inputmethod.latin.makedict.BinaryDictDecoderUtils.CharEncoding;
import com.android.inputmethod.latin.makedict.BinaryDictDecoderUtils.DictBuffer;
import com.android.inputmethod.latin.makedict.FormatSpec.FileHeader;
import com.android.inputmethod.latin.makedict.FormatSpec.FormatOptions;
import com.android.inputmethod.latin.makedict.FusionDictionary.PtNode;
import com.android.inputmethod.latin.makedict.FusionDictionary.WeightedString;
import com.android.inputmethod.latin.utils.CollectionUtils;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * An implementation of binary dictionary decoder for version 4 binary dictionary.
 */
@UsedForTesting
public class Ver4DictDecoder extends AbstractDictDecoder {
    private static final String TAG = Ver4DictDecoder.class.getSimpleName();

    protected static final int FILETYPE_TRIE = 1;
    protected static final int FILETYPE_FREQUENCY = 2;
    protected static final int FILETYPE_TERMINAL_ADDRESS_TABLE = 3;
    protected static final int FILETYPE_BIGRAM_FREQ = 4;
    protected static final int FILETYPE_SHORTCUT = 5;
    protected static final int FILETYPE_HEADER = 6;

    protected final File mDictDirectory;
    protected final DictionaryBufferFactory mBufferFactory;
    protected DictBuffer mDictBuffer;
    protected DictBuffer mHeaderBuffer;
    protected DictBuffer mFrequencyBuffer;
    protected DictBuffer mTerminalAddressTableBuffer;
    private BigramContentReader mBigramReader;
    private ShortcutContentReader mShortcutReader;

    /**
     * Raw PtNode info straight out of a trie file in version 4 dictionary.
     */
    protected static final class Ver4PtNodeInfo {
        public final int mFlags;
        public final int[] mCharacters;
        public final int mTerminalId;
        public final int mChildrenPos;
        public final int mParentPos;
        public final int mNodeSize;
        public int mStartIndexOfCharacters;
        public int mEndIndexOfCharacters; // exclusive

        public Ver4PtNodeInfo(final int flags, final int[] characters, final int terminalId,
                final int childrenPos, final int parentPos, final int nodeSize) {
            mFlags = flags;
            mCharacters = characters;
            mTerminalId = terminalId;
            mChildrenPos = childrenPos;
            mParentPos = parentPos;
            mNodeSize = nodeSize;
            mStartIndexOfCharacters = 0;
            mEndIndexOfCharacters = characters.length;
        }
    }

    @UsedForTesting
    /* package */ Ver4DictDecoder(final File dictDirectory, final int factoryFlag) {
        mDictDirectory = dictDirectory;
        mDictBuffer = mHeaderBuffer = mFrequencyBuffer = null;

        if ((factoryFlag & MASK_DICTBUFFER) == USE_READONLY_BYTEBUFFER) {
            mBufferFactory = new DictionaryBufferFromReadOnlyByteBufferFactory();
        } else if ((factoryFlag  & MASK_DICTBUFFER) == USE_BYTEARRAY) {
            mBufferFactory = new DictionaryBufferFromByteArrayFactory();
        } else if ((factoryFlag & MASK_DICTBUFFER) == USE_WRITABLE_BYTEBUFFER) {
            mBufferFactory = new DictionaryBufferFromWritableByteBufferFactory();
        } else {
            mBufferFactory = new DictionaryBufferFromReadOnlyByteBufferFactory();
        }
    }

    @UsedForTesting
    /* package */ Ver4DictDecoder(final File dictDirectory, final DictionaryBufferFactory factory) {
        mDictDirectory = dictDirectory;
        mBufferFactory = factory;
        mDictBuffer = mHeaderBuffer = mFrequencyBuffer = null;
    }

    protected File getFile(final int fileType) throws UnsupportedFormatException {
        if (fileType == FILETYPE_TRIE) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.TRIE_FILE_EXTENSION);
        } else if (fileType == FILETYPE_HEADER) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.HEADER_FILE_EXTENSION);
        } else if (fileType == FILETYPE_FREQUENCY) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.FREQ_FILE_EXTENSION);
        } else if (fileType == FILETYPE_TERMINAL_ADDRESS_TABLE) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.TERMINAL_ADDRESS_TABLE_FILE_EXTENSION);
        } else if (fileType == FILETYPE_BIGRAM_FREQ) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.BIGRAM_FILE_EXTENSION
                            + FormatSpec.BIGRAM_FREQ_CONTENT_ID);
        } else if (fileType == FILETYPE_SHORTCUT) {
            return new File(mDictDirectory,
                    mDictDirectory.getName() + FormatSpec.SHORTCUT_FILE_EXTENSION
                            + FormatSpec.SHORTCUT_CONTENT_ID);
        } else {
            throw new UnsupportedFormatException("Unsupported kind of file : " + fileType);
        }
    }

    @Override
    public void openDictBuffer() throws FileNotFoundException, IOException,
            UnsupportedFormatException {
        if (!mDictDirectory.isDirectory()) {
            throw new UnsupportedFormatException("Format 4 dictionary needs a directory");
        }
        mHeaderBuffer = mBufferFactory.getDictionaryBuffer(getFile(FILETYPE_HEADER));
        mDictBuffer = mBufferFactory.getDictionaryBuffer(getFile(FILETYPE_TRIE));
        mFrequencyBuffer = mBufferFactory.getDictionaryBuffer(getFile(FILETYPE_FREQUENCY));
        mTerminalAddressTableBuffer = mBufferFactory.getDictionaryBuffer(
                getFile(FILETYPE_TERMINAL_ADDRESS_TABLE));
        mBigramReader = new BigramContentReader(mDictDirectory.getName(),
                mDictDirectory, mBufferFactory);
        mBigramReader.openBuffers();
        mShortcutReader = new ShortcutContentReader(mDictDirectory.getName(), mDictDirectory,
                mBufferFactory);
        mShortcutReader.openBuffers();
    }

    @Override
    public boolean isDictBufferOpen() {
        return mDictBuffer != null;
    }

    @UsedForTesting
    /* package */ DictBuffer getHeaderBuffer() {
        return mHeaderBuffer;
    }

    @UsedForTesting
    /* package */ DictBuffer getDictBuffer() {
        return mDictBuffer;
    }

    @Override
    public FileHeader readHeader() throws IOException, UnsupportedFormatException {
        if (mHeaderBuffer == null) {
            openDictBuffer();
        }
        mHeaderBuffer.position(0);
        final FileHeader header = super.readHeader(mHeaderBuffer);
        final int version = header.mFormatOptions.mVersion;
        if (version != FormatSpec.VERSION4) {
            throw new UnsupportedFormatException("File header has a wrong version : " + version);
        }
        return header;
    }

    /**
     * An auxiliary class for reading bigrams.
     */
    protected static class BigramContentReader extends SparseTableContentReader {
        public BigramContentReader(final String name, final File baseDir,
                final DictionaryBufferFactory factory) {
            super(name + FormatSpec.BIGRAM_FILE_EXTENSION,
                    FormatSpec.BIGRAM_ADDRESS_TABLE_BLOCK_SIZE, baseDir,
                    getContentFilenames(name), getContentIds(), factory);
        }

        // TODO: Consolidate this method and BigramContentWriter.getContentFilenames.
        protected static String[] getContentFilenames(final String name) {
            return new String[] { name + FormatSpec.BIGRAM_FILE_EXTENSION };
        }

        // TODO: Consolidate this method and BigramContentWriter.getContentIds.
        protected static String[] getContentIds() {
            return new String[] { FormatSpec.BIGRAM_FREQ_CONTENT_ID };
        }

        public ArrayList<PendingAttribute> readTargetsAndFrequencies(final int terminalId,
                final DictBuffer terminalAddressTableBuffer, final FormatOptions options) {
            final ArrayList<PendingAttribute> bigrams = CollectionUtils.newArrayList();
            read(FormatSpec.BIGRAM_FREQ_CONTENT_INDEX, terminalId,
                    new SparseTableContentReaderInterface() {
                        @Override
                        public void read(final DictBuffer buffer) {
                            while (bigrams.size() < FormatSpec.MAX_BIGRAMS_IN_A_PTNODE) {
                                // If bigrams.size() reaches FormatSpec.MAX_BIGRAMS_IN_A_PTNODE,
                                // remaining bigram entries are ignored.
                                final int bigramFlags = buffer.readUnsignedByte();
                                final int probability;

                                if (options.mHasTimestamp) {
                                    probability = buffer.readUnsignedByte();
                                    final int pos = buffer.position();
                                    // Skip historical info.
                                    buffer.position(pos + FormatSpec.BIGRAM_TIMESTAMP_SIZE
                                            + FormatSpec.BIGRAM_LEVEL_SIZE
                                            + FormatSpec.BIGRAM_COUNTER_SIZE);
                                } else {
                                    probability = bigramFlags
                                            & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY;
                                }
                                final int targetTerminalId = buffer.readUnsignedInt24();
                                terminalAddressTableBuffer.position(targetTerminalId
                                        * FormatSpec.TERMINAL_ADDRESS_TABLE_ADDRESS_SIZE);
                                final int targetAddress =
                                        terminalAddressTableBuffer.readUnsignedInt24();
                                bigrams.add(new PendingAttribute(probability, targetAddress));
                                if (0 == (bigramFlags
                                        & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT)) {
                                    break;
                                }
                            }
                            if (bigrams.size() >= FormatSpec.MAX_BIGRAMS_IN_A_PTNODE) {
                                throw new RuntimeException("Too many bigrams in a PtNode ("
                                        + bigrams.size() + " but max is "
                                        + FormatSpec.MAX_BIGRAMS_IN_A_PTNODE + ")");
                            }
                        }
                    });
            if (bigrams.isEmpty()) return null;
            return bigrams;
        }
    }

    /**
     * An auxiliary class for reading shortcuts.
     */
    protected static class ShortcutContentReader extends SparseTableContentReader {
        public ShortcutContentReader(final String name, final File baseDir,
                final DictionaryBufferFactory factory) {
            super(name + FormatSpec.SHORTCUT_FILE_EXTENSION,
                    FormatSpec.SHORTCUT_ADDRESS_TABLE_BLOCK_SIZE, baseDir,
                    new String[] { name + FormatSpec.SHORTCUT_FILE_EXTENSION },
                    new String[] { FormatSpec.SHORTCUT_CONTENT_ID }, factory);
        }

        public ArrayList<WeightedString> readShortcuts(final int terminalId) {
            final ArrayList<WeightedString> shortcuts = CollectionUtils.newArrayList();
            read(FormatSpec.SHORTCUT_CONTENT_INDEX, terminalId,
                    new SparseTableContentReaderInterface() {
                        @Override
                        public void read(final DictBuffer buffer) {
                            while (true) {
                                final int flags = buffer.readUnsignedByte();
                                final String word = CharEncoding.readString(buffer);
                                shortcuts.add(new WeightedString(word,
                                        flags & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY));
                                if (0 == (flags & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT)) {
                                    break;
                                }
                            }
                        }
                    });
            if (shortcuts.isEmpty()) return null;
            return shortcuts;
        }
    }

    protected static class PtNodeReader extends AbstractDictDecoder.PtNodeReader {
        protected static int readFrequency(final DictBuffer frequencyBuffer, final int terminalId,
                final FormatOptions formatOptions) {
            final int readingPos;
            if (formatOptions.mHasTimestamp) {
                final int entrySize = FormatSpec.FREQUENCY_AND_FLAGS_SIZE
                        + FormatSpec.UNIGRAM_TIMESTAMP_SIZE + FormatSpec.UNIGRAM_LEVEL_SIZE
                        + FormatSpec.UNIGRAM_COUNTER_SIZE;
                readingPos = terminalId * entrySize + FormatSpec.FLAGS_IN_FREQ_FILE_SIZE;
            } else {
                readingPos = terminalId * FormatSpec.FREQUENCY_AND_FLAGS_SIZE
                        + FormatSpec.FLAGS_IN_FREQ_FILE_SIZE;
            }
            frequencyBuffer.position(readingPos);
            return frequencyBuffer.readUnsignedByte();
        }

        protected static int readTerminalId(final DictBuffer dictBuffer) {
            return dictBuffer.readInt();
        }
    }

    private final int[] mCharacterBufferForReadingVer4PtNodeInfo
            = new int[FormatSpec.MAX_WORD_LENGTH];

    /**
     * Reads PtNode from ptNodePos in the trie file and returns Ver4PtNodeInfo.
     *
     * @param ptNodePos the position of PtNode.
     * @param options the format options.
     * @return Ver4PtNodeInfo.
     */
    // TODO: Make this buffer thread safe.
    // TODO: Support words longer than FormatSpec.MAX_WORD_LENGTH.
    protected Ver4PtNodeInfo readVer4PtNodeInfo(final int ptNodePos, final FormatOptions options) {
        int readingPos = ptNodePos;
        final int flags = PtNodeReader.readPtNodeOptionFlags(mDictBuffer);
        readingPos += FormatSpec.PTNODE_FLAGS_SIZE;

        final int parentPos = PtNodeReader.readParentAddress(mDictBuffer, options);
        if (BinaryDictIOUtils.supportsDynamicUpdate(options)) {
            readingPos += FormatSpec.PARENT_ADDRESS_SIZE;
        }

        final int characters[];
        if (0 != (flags & FormatSpec.FLAG_HAS_MULTIPLE_CHARS)) {
            int index = 0;
            int character = CharEncoding.readChar(mDictBuffer);
            readingPos += CharEncoding.getCharSize(character);
            while (FormatSpec.INVALID_CHARACTER != character
                    && index < FormatSpec.MAX_WORD_LENGTH) {
                mCharacterBufferForReadingVer4PtNodeInfo[index++] = character;
                character = CharEncoding.readChar(mDictBuffer);
                readingPos += CharEncoding.getCharSize(character);
            }
            characters = Arrays.copyOfRange(mCharacterBufferForReadingVer4PtNodeInfo, 0, index);
        } else {
            final int character = CharEncoding.readChar(mDictBuffer);
            readingPos += CharEncoding.getCharSize(character);
            characters = new int[] { character };
        }
        final int terminalId;
        if (0 != (FormatSpec.FLAG_IS_TERMINAL & flags)) {
            terminalId = PtNodeReader.readTerminalId(mDictBuffer);
            readingPos += FormatSpec.PTNODE_TERMINAL_ID_SIZE;
        } else {
            terminalId = PtNode.NOT_A_TERMINAL;
        }

        int childrenPos = PtNodeReader.readChildrenAddress(mDictBuffer, flags, options);
        if (childrenPos != FormatSpec.NO_CHILDREN_ADDRESS) {
            childrenPos += readingPos;
        }
        readingPos += BinaryDictIOUtils.getChildrenAddressSize(flags, options);

        return new Ver4PtNodeInfo(flags, characters, terminalId, childrenPos, parentPos,
                readingPos - ptNodePos);
    }

    @Override
    public PtNodeInfo readPtNode(final int ptNodePos, final FormatOptions options) {
        final Ver4PtNodeInfo nodeInfo = readVer4PtNodeInfo(ptNodePos, options);

        final int frequency;
        if (0 != (FormatSpec.FLAG_IS_TERMINAL & nodeInfo.mFlags)) {
            frequency = PtNodeReader.readFrequency(mFrequencyBuffer, nodeInfo.mTerminalId, options);
        } else {
            frequency = PtNode.NOT_A_TERMINAL;
        }

        final ArrayList<WeightedString> shortcutTargets = mShortcutReader.readShortcuts(
                nodeInfo.mTerminalId);
        final ArrayList<PendingAttribute> bigrams = mBigramReader.readTargetsAndFrequencies(
                nodeInfo.mTerminalId, mTerminalAddressTableBuffer, options);

        return new PtNodeInfo(ptNodePos, ptNodePos + nodeInfo.mNodeSize, nodeInfo.mFlags,
                nodeInfo.mCharacters, frequency, nodeInfo.mParentPos, nodeInfo.mChildrenPos,
                shortcutTargets, bigrams);
    }

    private void deleteDictFiles() {
        final File[] files = mDictDirectory.listFiles();
        for (int i = 0; i < files.length; ++i) {
            files[i].delete();
        }
    }

    @Override
    public FusionDictionary readDictionaryBinary(final FusionDictionary dict,
            final boolean deleteDictIfBroken)
            throws FileNotFoundException, IOException, UnsupportedFormatException {
        if (mDictBuffer == null) {
            openDictBuffer();
        }
        try {
            return BinaryDictDecoderUtils.readDictionaryBinary(this, dict);
        } catch (IOException e) {
            Log.e(TAG, "The dictionary " + mDictDirectory.getName() + " is broken.", e);
            if (deleteDictIfBroken) {
                deleteDictFiles();
            }
            throw e;
        } catch (UnsupportedFormatException e) {
            Log.e(TAG, "The dictionary " + mDictDirectory.getName() + " is broken.", e);
            if (deleteDictIfBroken) {
                deleteDictFiles();
            }
            throw e;
        }
    }

    @Override
    public void setPosition(int newPos) {
        mDictBuffer.position(newPos);
    }

    @Override
    public int getPosition() {
        return mDictBuffer.position();
    }

    @Override
    public int readPtNodeCount() {
        return BinaryDictDecoderUtils.readPtNodeCount(mDictBuffer);
    }

    @Override
    public boolean readAndFollowForwardLink() {
        final int forwardLinkPos = mDictBuffer.position();
        int nextRelativePos = BinaryDictDecoderUtils.readSInt24(mDictBuffer);
        if (nextRelativePos != FormatSpec.NO_FORWARD_LINK_ADDRESS) {
            final int nextPos = forwardLinkPos + nextRelativePos;
            if (nextPos >= 0 && nextPos < mDictBuffer.limit()) {
              mDictBuffer.position(nextPos);
              return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasNextPtNodeArray() {
        return mDictBuffer.position() != FormatSpec.NO_FORWARD_LINK_ADDRESS;
    }

    @Override
    @UsedForTesting
    public void skipPtNode(final FormatOptions formatOptions) {
        final int flags = PtNodeReader.readPtNodeOptionFlags(mDictBuffer);
        PtNodeReader.readParentAddress(mDictBuffer, formatOptions);
        BinaryDictIOUtils.skipString(mDictBuffer,
                (flags & FormatSpec.FLAG_HAS_MULTIPLE_CHARS) != 0);
        if ((flags & FormatSpec.FLAG_IS_TERMINAL) != 0) PtNodeReader.readTerminalId(mDictBuffer);
        PtNodeReader.readChildrenAddress(mDictBuffer, flags, formatOptions);
    }
}
