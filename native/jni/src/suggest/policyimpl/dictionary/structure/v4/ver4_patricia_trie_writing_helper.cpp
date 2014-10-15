/*
 * Copyright (C) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "suggest/policyimpl/dictionary/structure/v4/ver4_patricia_trie_writing_helper.h"

#include <cstring>
#include <queue>

#include "suggest/policyimpl/dictionary/header/header_policy.h"
#include "suggest/policyimpl/dictionary/structure/v4/shortcut/ver4_shortcut_list_policy.h"
#include "suggest/policyimpl/dictionary/structure/v4/ver4_dict_buffers.h"
#include "suggest/policyimpl/dictionary/structure/v4/ver4_dict_constants.h"
#include "suggest/policyimpl/dictionary/structure/v4/ver4_patricia_trie_node_reader.h"
#include "suggest/policyimpl/dictionary/structure/v4/ver4_patricia_trie_node_writer.h"
#include "suggest/policyimpl/dictionary/structure/v4/ver4_pt_node_array_reader.h"
#include "suggest/policyimpl/dictionary/utils/buffer_with_extendable_buffer.h"
#include "suggest/policyimpl/dictionary/utils/file_utils.h"
#include "suggest/policyimpl/dictionary/utils/forgetting_curve_utils.h"

namespace latinime {

bool Ver4PatriciaTrieWritingHelper::writeToDictFile(const char *const dictDirPath,
        const int unigramCount, const int bigramCount) const {
    const HeaderPolicy *const headerPolicy = mBuffers->getHeaderPolicy();
    BufferWithExtendableBuffer headerBuffer(
            BufferWithExtendableBuffer::DEFAULT_MAX_ADDITIONAL_BUFFER_SIZE);
    const int extendedRegionSize = headerPolicy->getExtendedRegionSize()
            + mBuffers->getTrieBuffer()->getUsedAdditionalBufferSize();
    if (!headerPolicy->fillInAndWriteHeaderToBuffer(false /* updatesLastDecayedTime */,
            unigramCount, bigramCount, extendedRegionSize, &headerBuffer)) {
        AKLOGE("Cannot write header structure to buffer. "
                "updatesLastDecayedTime: %d, unigramCount: %d, bigramCount: %d, "
                "extendedRegionSize: %d", false, unigramCount, bigramCount,
                extendedRegionSize);
        return false;
    }
    return mBuffers->flushHeaderAndDictBuffers(dictDirPath, &headerBuffer);
}

bool Ver4PatriciaTrieWritingHelper::writeToDictFileWithGC(const int rootPtNodeArrayPos,
        const char *const dictDirPath) {
    const HeaderPolicy *const headerPolicy = mBuffers->getHeaderPolicy();
    Ver4DictBuffers::Ver4DictBuffersPtr dictBuffers(
            Ver4DictBuffers::createVer4DictBuffers(headerPolicy,
                    Ver4DictConstants::MAX_DICTIONARY_SIZE));
    int unigramCount = 0;
    int bigramCount = 0;
    if (!runGC(rootPtNodeArrayPos, headerPolicy, dictBuffers.get(), &unigramCount, &bigramCount)) {
        return false;
    }
    BufferWithExtendableBuffer headerBuffer(
            BufferWithExtendableBuffer::DEFAULT_MAX_ADDITIONAL_BUFFER_SIZE);
    if (!headerPolicy->fillInAndWriteHeaderToBuffer(true /* updatesLastDecayedTime */,
            unigramCount, bigramCount, 0 /* extendedRegionSize */, &headerBuffer)) {
        return false;
    }
    return dictBuffers->flushHeaderAndDictBuffers(dictDirPath, &headerBuffer);
}

bool Ver4PatriciaTrieWritingHelper::runGC(const int rootPtNodeArrayPos,
        const HeaderPolicy *const headerPolicy, Ver4DictBuffers *const buffersToWrite,
        int *const outUnigramCount, int *const outBigramCount) {
    Ver4PatriciaTrieNodeReader ptNodeReader(mBuffers->getTrieBuffer());
    Ver4PtNodeArrayReader ptNodeArrayReader(mBuffers->getTrieBuffer());
    Ver4ShortcutListPolicy shortcutPolicy(mBuffers->getMutableShortcutDictContent(),
            mBuffers->getTerminalPositionLookupTable());
    Ver4PatriciaTrieNodeWriter ptNodeWriter(mBuffers->getWritableTrieBuffer(),
            mBuffers, &ptNodeReader, &ptNodeArrayReader, &shortcutPolicy);

    int entryCountTable[MAX_PREV_WORD_COUNT_FOR_N_GRAM + 1];
    if (!mBuffers->getMutableLanguageModelDictContent()->updateAllProbabilityEntriesForGC(
            headerPolicy, entryCountTable)) {
        AKLOGE("Failed to update probabilities in language model dict content.");
        return false;
    }
    if (headerPolicy->isDecayingDict()) {
        int maxEntryCountTable[MAX_PREV_WORD_COUNT_FOR_N_GRAM + 1];
        maxEntryCountTable[LanguageModelDictContent::UNIGRAM_COUNT_INDEX_IN_ENTRY_COUNT_TABLE] =
                headerPolicy->getMaxUnigramCount();
        maxEntryCountTable[LanguageModelDictContent::BIGRAM_COUNT_INDEX_IN_ENTRY_COUNT_TABLE] =
                headerPolicy->getMaxBigramCount();
        for (size_t i = 2; i < NELEMS(maxEntryCountTable); ++i) {
            // TODO: Have max n-gram count.
            maxEntryCountTable[i] = headerPolicy->getMaxBigramCount();
        }
        if (!mBuffers->getMutableLanguageModelDictContent()->truncateEntries(entryCountTable,
                maxEntryCountTable, headerPolicy, entryCountTable)) {
            AKLOGE("Failed to truncate entries in language model dict content.");
            return false;
        }
    }

    DynamicPtReadingHelper readingHelper(&ptNodeReader, &ptNodeArrayReader);
    readingHelper.initWithPtNodeArrayPos(rootPtNodeArrayPos);
    DynamicPtGcEventListeners
            ::TraversePolicyToUpdateUnigramProbabilityAndMarkUselessPtNodesAsDeleted
                    traversePolicyToUpdateUnigramProbabilityAndMarkUselessPtNodesAsDeleted(
                            &ptNodeWriter);
    if (!readingHelper.traverseAllPtNodesInPostorderDepthFirstManner(
            &traversePolicyToUpdateUnigramProbabilityAndMarkUselessPtNodesAsDeleted)) {
        return false;
    }

    // Mapping from positions in mBuffer to positions in bufferToWrite.
    PtNodeWriter::DictPositionRelocationMap dictPositionRelocationMap;
    readingHelper.initWithPtNodeArrayPos(rootPtNodeArrayPos);
    Ver4PatriciaTrieNodeWriter ptNodeWriterForNewBuffers(buffersToWrite->getWritableTrieBuffer(),
            buffersToWrite, &ptNodeReader, &ptNodeArrayReader, &shortcutPolicy);
    DynamicPtGcEventListeners::TraversePolicyToPlaceAndWriteValidPtNodesToBuffer
            traversePolicyToPlaceAndWriteValidPtNodesToBuffer(&ptNodeWriterForNewBuffers,
                    buffersToWrite->getWritableTrieBuffer(), &dictPositionRelocationMap);
    if (!readingHelper.traverseAllPtNodesInPtNodeArrayLevelPreorderDepthFirstManner(
            &traversePolicyToPlaceAndWriteValidPtNodesToBuffer)) {
        return false;
    }

    // Create policy instances for the GCed dictionary.
    Ver4PatriciaTrieNodeReader newPtNodeReader(buffersToWrite->getTrieBuffer());
    Ver4PtNodeArrayReader newPtNodeArrayreader(buffersToWrite->getTrieBuffer());
    Ver4ShortcutListPolicy newShortcutPolicy(buffersToWrite->getMutableShortcutDictContent(),
            buffersToWrite->getTerminalPositionLookupTable());
    Ver4PatriciaTrieNodeWriter newPtNodeWriter(buffersToWrite->getWritableTrieBuffer(),
            buffersToWrite, &newPtNodeReader, &newPtNodeArrayreader,
            &newShortcutPolicy);
    // Re-assign terminal IDs for valid terminal PtNodes.
    TerminalPositionLookupTable::TerminalIdMap terminalIdMap;
    if(!buffersToWrite->getMutableTerminalPositionLookupTable()->runGCTerminalIds(
            &terminalIdMap)) {
        return false;
    }
    // Run GC for probability dict content.
    if (!buffersToWrite->getMutableLanguageModelDictContent()->runGC(&terminalIdMap,
            mBuffers->getLanguageModelDictContent(), nullptr /* outNgramCount */)) {
        return false;
    }
    // Run GC for shortcut dict content.
    if(!buffersToWrite->getMutableShortcutDictContent()->runGC(&terminalIdMap,
            mBuffers->getShortcutDictContent())) {
        return false;
    }
    DynamicPtReadingHelper newDictReadingHelper(&newPtNodeReader, &newPtNodeArrayreader);
    newDictReadingHelper.initWithPtNodeArrayPos(rootPtNodeArrayPos);
    DynamicPtGcEventListeners::TraversePolicyToUpdateAllPositionFields
            traversePolicyToUpdateAllPositionFields(&newPtNodeWriter, &dictPositionRelocationMap);
    if (!newDictReadingHelper.traverseAllPtNodesInPtNodeArrayLevelPreorderDepthFirstManner(
            &traversePolicyToUpdateAllPositionFields)) {
        return false;
    }
    newDictReadingHelper.initWithPtNodeArrayPos(rootPtNodeArrayPos);
    TraversePolicyToUpdateAllPtNodeFlagsAndTerminalIds
            traversePolicyToUpdateAllPtNodeFlagsAndTerminalIds(&newPtNodeWriter, &terminalIdMap);
    if (!newDictReadingHelper.traverseAllPtNodesInPostorderDepthFirstManner(
            &traversePolicyToUpdateAllPtNodeFlagsAndTerminalIds)) {
        return false;
    }
    *outUnigramCount =
            entryCountTable[LanguageModelDictContent::UNIGRAM_COUNT_INDEX_IN_ENTRY_COUNT_TABLE];
    *outBigramCount =
            entryCountTable[LanguageModelDictContent::BIGRAM_COUNT_INDEX_IN_ENTRY_COUNT_TABLE];
    return true;
}

bool Ver4PatriciaTrieWritingHelper::TraversePolicyToUpdateAllPtNodeFlagsAndTerminalIds
        ::onVisitingPtNode(const PtNodeParams *const ptNodeParams) {
    if (!ptNodeParams->isTerminal()) {
        return true;
    }
    TerminalPositionLookupTable::TerminalIdMap::const_iterator it =
            mTerminalIdMap->find(ptNodeParams->getTerminalId());
    if (it == mTerminalIdMap->end()) {
        AKLOGE("terminal Id %d is not in the terminal position map. map size: %zd",
                ptNodeParams->getTerminalId(), mTerminalIdMap->size());
        return false;
    }
    if (!mPtNodeWriter->updateTerminalId(ptNodeParams, it->second)) {
        AKLOGE("Cannot update terminal id. %d -> %d", it->first, it->second);
        return false;
    }
    return true;
}

} // namespace latinime
