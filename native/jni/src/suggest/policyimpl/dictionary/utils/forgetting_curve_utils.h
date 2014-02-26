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

#ifndef LATINIME_FORGETTING_CURVE_UTILS_H
#define LATINIME_FORGETTING_CURVE_UTILS_H

#include <vector>

#include "defines.h"
#include "suggest/policyimpl/dictionary/utils/historical_info.h"

namespace latinime {

class HeaderPolicy;

class ForgettingCurveUtils {
 public:
    static const int MAX_UNIGRAM_COUNT;
    static const int MAX_UNIGRAM_COUNT_AFTER_GC;
    static const int MAX_BIGRAM_COUNT;
    static const int MAX_BIGRAM_COUNT_AFTER_GC;

    static const HistoricalInfo createUpdatedHistoricalInfo(
            const HistoricalInfo *const originalHistoricalInfo, const int newProbability,
            const int timestamp, const HeaderPolicy *const headerPolicy);

    static const HistoricalInfo createHistoricalInfoToSave(
            const HistoricalInfo *const originalHistoricalInfo,
            const HeaderPolicy *const headerPolicy);

    static int decodeProbability(const HistoricalInfo *const historicalInfo,
            const HeaderPolicy *const headerPolicy);

    static int getProbability(const int encodedUnigramProbability,
            const int encodedBigramProbability);

    static bool needsToKeep(const HistoricalInfo *const historicalInfo);

    static bool needsToDecay(const bool mindsBlockByDecay, const int unigramCount,
            const int bigramCount, const HeaderPolicy *const headerPolicy);

 private:
    DISALLOW_IMPLICIT_CONSTRUCTORS(ForgettingCurveUtils);

    class ProbabilityTable {
     public:
        ProbabilityTable();

        int getProbability(const int tableId, const int level,
                const int elapsedTimeStepCount) const {
            return mTables[tableId][level][elapsedTimeStepCount];
        }

     private:
        DISALLOW_COPY_AND_ASSIGN(ProbabilityTable);

        static const int PROBABILITY_TABLE_COUNT;

        std::vector<std::vector<std::vector<int> > > mTables;
    };

    static const int MULTIPLIER_TWO_IN_PROBABILITY_SCALE;
    static const int MAX_COMPUTED_PROBABILITY;
    static const int DECAY_INTERVAL_SECONDS;

    static const int MAX_LEVEL;
    static const int MIN_VALID_LEVEL;
    static const int TIME_STEP_DURATION_IN_SECONDS;
    static const int MAX_ELAPSED_TIME_STEP_COUNT;
    static const int DISCARD_LEVEL_ZERO_ENTRY_TIME_STEP_COUNT_THRESHOLD;
    static const int HALF_LIFE_TIME_IN_SECONDS;

    static const ProbabilityTable sProbabilityTable;

    static int backoff(const int unigramProbability);

    static int getElapsedTimeStepCount(const int timestamp);
};
} // namespace latinime
#endif /* LATINIME_FORGETTING_CURVE_UTILS_H */
