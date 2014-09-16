/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef LATINIME_INT_ARRAY_VIEW_H
#define LATINIME_INT_ARRAY_VIEW_H

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

#include "defines.h"

namespace latinime {

/**
 * Helper class used to provide a read-only view of a given range of integer array. This class
 * does not take ownership of the underlying integer array but is designed to be a lightweight
 * object that obeys value semantics.
 *
 * Example:
 * <code>
 * bool constinsX(IntArrayView view) {
 *     for (size_t i = 0; i < view.size(); ++i) {
 *         if (view[i] == 'X') {
 *             return true;
 *         }
 *     }
 *     return false;
 * }
 *
 * const int codePointArray[] = { 'A', 'B', 'X', 'Z' };
 * auto view = IntArrayView(codePointArray, NELEMS(codePointArray));
 * const bool hasX = constinsX(view);
 * </code>
 */
class IntArrayView {
 public:
    IntArrayView() : mPtr(nullptr), mSize(0) {}

    IntArrayView(const int *const ptr, const size_t size)
            : mPtr(ptr), mSize(size) {}

    explicit IntArrayView(const std::vector<int> &vector)
            : mPtr(vector.data()), mSize(vector.size()) {}

    template <size_t N>
    AK_FORCE_INLINE static IntArrayView fromArray(const std::array<int, N> &array) {
        return IntArrayView(array.data(), array.size());
    }

    // Returns a view that points one int object.
    AK_FORCE_INLINE static IntArrayView singleElementView(const int *const ptr) {
        return IntArrayView(ptr, 1);
    }

    AK_FORCE_INLINE int operator[](const size_t index) const {
        ASSERT(index < mSize);
        return mPtr[index];
    }

    AK_FORCE_INLINE bool empty() const {
        return size() == 0;
    }

    AK_FORCE_INLINE size_t size() const {
        return mSize;
    }

    AK_FORCE_INLINE const int *data() const {
        return mPtr;
    }

    AK_FORCE_INLINE const int *begin() const {
        return mPtr;
    }

    AK_FORCE_INLINE const int *end() const {
        return mPtr + mSize;
    }

    AK_FORCE_INLINE bool contains(const int value) const {
        return std::find(begin(), end(), value) != end();
    }

    // Returns the view whose size is smaller than or equal to the given count.
    AK_FORCE_INLINE const IntArrayView limit(const size_t maxSize) const {
        return IntArrayView(mPtr, std::min(maxSize, mSize));
    }

    AK_FORCE_INLINE const IntArrayView skip(const size_t n) const {
        if (mSize <= n) {
            return IntArrayView();
        }
        return IntArrayView(mPtr + n, mSize - n);
    }

    template <size_t N>
    void copyToArray(std::array<int, N> *const buffer, const size_t offset) const {
        ASSERT(mSize + offset <= N);
        memmove(buffer->data() + offset, mPtr, sizeof(int) * mSize);
    }

 private:
    DISALLOW_ASSIGNMENT_OPERATOR(IntArrayView);

    const int *const mPtr;
    const size_t mSize;
};

using WordIdArrayView = IntArrayView;
using PtNodePosArrayView = IntArrayView;
using CodePointArrayView = IntArrayView;
template <size_t size>
using WordIdArray = std::array<int, size>;

} // namespace latinime
#endif // LATINIME_MEMORY_VIEW_H
