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

#include "suggest/core/dicnode/dic_node_utils.h"

#include <cstring>

#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/dicnode/dic_node_vector.h"
#include "suggest/core/dictionary/multi_bigram_map.h"
#include "suggest/core/policy/dictionary_structure_with_buffer_policy.h"

namespace latinime {

///////////////////////////////
// Node initialization utils //
///////////////////////////////

/* static */ void DicNodeUtils::initAsRoot(
        const DictionaryStructureWithBufferPolicy *const dictionaryStructurePolicy,
        const int prevWordPtNodePos, DicNode *const newRootDicNode) {
    newRootDicNode->initAsRoot(dictionaryStructurePolicy->getRootPosition(), prevWordPtNodePos);
}

/*static */ void DicNodeUtils::initAsRootWithPreviousWord(
        const DictionaryStructureWithBufferPolicy *const dictionaryStructurePolicy,
        const DicNode *const prevWordLastDicNode, DicNode *const newRootDicNode) {
    newRootDicNode->initAsRootWithPreviousWord(
            prevWordLastDicNode, dictionaryStructurePolicy->getRootPosition());
}

/* static */ void DicNodeUtils::initByCopy(const DicNode *const srcDicNode,
        DicNode *const destDicNode) {
    destDicNode->initByCopy(srcDicNode);
}

///////////////////////////////////
// Traverse node expansion utils //
///////////////////////////////////
/* static */ void DicNodeUtils::getAllChildDicNodes(DicNode *dicNode,
        const DictionaryStructureWithBufferPolicy *const dictionaryStructurePolicy,
        DicNodeVector *const childDicNodes) {
    if (dicNode->isTotalInputSizeExceedingLimit()) {
        return;
    }
    if (!dicNode->isLeavingNode()) {
        childDicNodes->pushPassingChild(dicNode);
    } else {
        dictionaryStructurePolicy->createAndGetAllChildDicNodes(dicNode, childDicNodes);
    }
}

///////////////////
// Scoring utils //
///////////////////
/**
 * Computes the combined bigram / unigram cost for the given dicNode.
 */
/* static */ float DicNodeUtils::getBigramNodeImprobability(
        const DictionaryStructureWithBufferPolicy *const dictionaryStructurePolicy,
        const DicNode *const dicNode, MultiBigramMap *const multiBigramMap) {
    if (dicNode->hasMultipleWords() && !dicNode->isValidMultipleWordSuggestion()) {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }
    const int probability = getBigramNodeProbability(dictionaryStructurePolicy, dicNode,
            multiBigramMap);
    // TODO: This equation to calculate the improbability looks unreasonable.  Investigate this.
    const float cost = static_cast<float>(MAX_PROBABILITY - probability)
            / static_cast<float>(MAX_PROBABILITY);
    return cost;
}

/* static */ int DicNodeUtils::getBigramNodeProbability(
        const DictionaryStructureWithBufferPolicy *const dictionaryStructurePolicy,
        const DicNode *const dicNode, MultiBigramMap *const multiBigramMap) {
    const int unigramProbability = dicNode->getProbability();
    const int ptNodePos = dicNode->getPtNodePos();
    const int prevWordTerminalPtNodePos = dicNode->getPrevWordTerminalPtNodePos();
    if (NOT_A_DICT_POS == ptNodePos || NOT_A_DICT_POS == prevWordTerminalPtNodePos) {
        // Note: Normally wordPos comes from the dictionary and should never equal
        // NOT_A_VALID_WORD_POS.
        return dictionaryStructurePolicy->getProbability(unigramProbability,
                NOT_A_PROBABILITY);
    }
    if (multiBigramMap) {
        return multiBigramMap->getBigramProbability(dictionaryStructurePolicy,
                prevWordTerminalPtNodePos, ptNodePos, unigramProbability);
    }
    return dictionaryStructurePolicy->getProbability(unigramProbability,
            NOT_A_PROBABILITY);
}

////////////////
// Char utils //
////////////////

// TODO: Move to char_utils?
/* static */ int DicNodeUtils::appendTwoWords(const int *const src0, const int16_t length0,
        const int *const src1, const int16_t length1, int *const dest) {
    int actualLength0 = 0;
    for (int i = 0; i < length0; ++i) {
        if (src0[i] == 0) {
            break;
        }
        actualLength0 = i + 1;
    }
    actualLength0 = min(actualLength0, MAX_WORD_LENGTH);
    memmove(dest, src0, actualLength0 * sizeof(dest[0]));
    if (!src1 || length1 == 0) {
        return actualLength0;
    }
    int actualLength1 = 0;
    for (int i = 0; i < length1; ++i) {
        if (src1[i] == 0) {
            break;
        }
        actualLength1 = i + 1;
    }
    actualLength1 = min(actualLength1, MAX_WORD_LENGTH - actualLength0);
    memmove(&dest[actualLength0], src1, actualLength1 * sizeof(dest[0]));
    return actualLength0 + actualLength1;
}
} // namespace latinime
