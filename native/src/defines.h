/*
**
** Copyright 2010, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef LATINIME_DEFINES_H
#define LATINIME_DEFINES_H

#ifdef FLAG_DBG
#include <cutils/log.h>
#ifndef LOG_TAG
#define LOG_TAG "LatinIME: "
#endif
#define DEBUG_DICT true
#define DEBUG_DICT_FULL false
#define DEBUG_SHOW_FOUND_WORD DEBUG_DICT_FULL
#define DEBUG_NODE DEBUG_DICT_FULL
#define DEBUG_TRACE DEBUG_DICT_FULL
#define DEBUG_PROXIMITY_INFO true

// Profiler
#include <time.h>
#define PROF_BUF_SIZE 100
static double profile_buf[PROF_BUF_SIZE];
static double profile_old[PROF_BUF_SIZE];
static unsigned int profile_counter[PROF_BUF_SIZE];

#define PROF_RESET               prof_reset()
#define PROF_COUNT(prof_buf_id)  ++profile_counter[prof_buf_id]
#define PROF_OPEN                do { PROF_RESET; PROF_START(PROF_BUF_SIZE - 1); } while(0)
#define PROF_START(prof_buf_id)  do { \
        PROF_COUNT(prof_buf_id); profile_old[prof_buf_id] = (clock()); } while(0)
#define PROF_CLOSE               do { PROF_END(PROF_BUF_SIZE - 1); PROF_OUTALL; } while(0)
#define PROF_END(prof_buf_id)    profile_buf[prof_buf_id] += ((clock()) - profile_old[prof_buf_id])
#define PROF_CLOCKOUT(prof_buf_id) \
        LOGI("%s : clock is %f", __FUNCTION__, (clock() - profile_old[prof_buf_id]))
#define PROF_OUTALL              do { LOGI("--- %s ---", __FUNCTION__); prof_out(); } while(0)

static void prof_reset(void) {
    for (int i = 0; i < PROF_BUF_SIZE; ++i) {
        profile_buf[i] = 0;
        profile_old[i] = 0;
        profile_counter[i] = 0;
    }
}

static void prof_out(void) {
    if (profile_counter[PROF_BUF_SIZE - 1] != 1) {
        LOGI("Error: You must call PROF_OPEN before PROF_CLOSE.");
    }
    LOGI("Total time is %6.3f ms.",
            profile_buf[PROF_BUF_SIZE - 1] * 1000 / (double)CLOCKS_PER_SEC);
    double all = 0;
    for (int i = 0; i < PROF_BUF_SIZE - 1; ++i) {
        all += profile_buf[i];
    }
    if (all == 0) all = 1;
    for (int i = 0; i < PROF_BUF_SIZE - 1; ++i) {
        if (profile_buf[i] != 0) {
            LOGI("(%d): Used %4.2f%%, %8.4f ms. Called %d times.",
                    i, (profile_buf[i] * 100 / all),
                    profile_buf[i] * 1000 / (double)CLOCKS_PER_SEC, profile_counter[i]);
        }
    }
}

#else // FLAG_DBG
#define LOGE(fmt, ...)
#define LOGI(fmt, ...)
#define DEBUG_DICT false
#define DEBUG_DICT_FULL false
#define DEBUG_SHOW_FOUND_WORD false
#define DEBUG_NODE false
#define DEBUG_TRACE false
#define DEBUG_PROXIMITY_INFO false

#define PROF_BUF_SIZE 0
#define PROF_RESET
#define PROF_COUNT(prof_buf_id)
#define PROF_OPEN
#define PROF_START(prof_buf_id)
#define PROF_CLOSE
#define PROF_END(prof_buf_id)
#define PROF_CLOCK_OUT(prof_buf_id)
#define PROF_CLOCKOUT(prof_buf_id)
#define PROF_OUTALL

#endif // FLAG_DBG

#ifndef U_SHORT_MAX
#define U_SHORT_MAX 1 << 16
#endif
#ifndef S_INT_MAX
#define S_INT_MAX 2147483647 // ((1 << 31) - 1)
#endif

// Define this to use mmap() for dictionary loading.  Undefine to use malloc() instead of mmap().
// We measured and compared performance of both, and found mmap() is fairly good in terms of
// loading time, and acceptable even for several initial lookups which involve page faults.
#define USE_MMAP_FOR_DICTIONARY

// 22-bit address = ~4MB dictionary size limit, which on average would be about 200k-300k words
#define ADDRESS_MASK 0x3FFFFF

// The bit that decides if an address follows in the next 22 bits
#define FLAG_ADDRESS_MASK 0x40
// The bit that decides if this is a terminal node for a word. The node could still have children,
// if the word has other endings.
#define FLAG_TERMINAL_MASK 0x80

#define FLAG_BIGRAM_READ 0x80
#define FLAG_BIGRAM_CHILDEXIST 0x40
#define FLAG_BIGRAM_CONTINUED 0x80
#define FLAG_BIGRAM_FREQ 0x7F

#define DICTIONARY_VERSION_MIN 200
#define DICTIONARY_HEADER_SIZE 2
#define NOT_VALID_WORD -99

#define KEYCODE_SPACE ' '

#define SUGGEST_WORDS_WITH_MISSING_CHARACTER true
#define SUGGEST_WORDS_WITH_MISSING_SPACE_CHARACTER true
#define SUGGEST_WORDS_WITH_EXCESSIVE_CHARACTER true
#define SUGGEST_WORDS_WITH_TRANSPOSED_CHARACTERS true
#define SUGGEST_WORDS_WITH_SPACE_PROXIMITY true

// The following "rate"s are used as a multiplier before dividing by 100, so they are in percent.
#define WORDS_WITH_MISSING_CHARACTER_DEMOTION_RATE 80
#define WORDS_WITH_MISSING_CHARACTER_DEMOTION_START_POS_10X 12
#define WORDS_WITH_MISSING_SPACE_CHARACTER_DEMOTION_RATE 80
#define WORDS_WITH_EXCESSIVE_CHARACTER_DEMOTION_RATE 75
#define WORDS_WITH_EXCESSIVE_CHARACTER_OUT_OF_PROXIMITY_DEMOTION_RATE 75
#define WORDS_WITH_TRANSPOSED_CHARACTERS_DEMOTION_RATE 60
#define FULL_MATCHED_WORDS_PROMOTION_RATE 120
#define WORDS_WITH_PROXIMITY_CHARACTER_DEMOTION_RATE 90

// This should be greater than or equal to MAX_WORD_LENGTH defined in BinaryDictionary.java
// This is only used for the size of array. Not to be used in c functions.
#define MAX_WORD_LENGTH_INTERNAL 48

#define MAX_DEPTH_MULTIPLIER 3

// TODO: Reduce this constant if possible; check the maximum number of umlauts in the same German
// word in the dictionary
#define DEFAULT_MAX_UMLAUT_SEARCH_DEPTH 5

// Minimum suggest depth for one word for all cases except for missing space suggestions.
#define MIN_SUGGEST_DEPTH 1
#define MIN_USER_TYPED_LENGTH_FOR_MISSING_SPACE_SUGGESTION 3
#define MIN_USER_TYPED_LENGTH_FOR_EXCESSIVE_CHARACTER_SUGGESTION 3

// The size of next letters frequency array.  Zero will disable the feature.
#define NEXT_LETTERS_SIZE 0

#define min(a,b) ((a)<(b)?(a):(b))

#endif // LATINIME_DEFINES_H
