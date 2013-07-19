#
# Copyright (C) 2012 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

BUILD_TOP := ../../../../..
LATINIME_DIR := $(BUILD_TOP)/packages/inputmethods/LatinIME
LATINIME_BASE_SOURCE_DIRECTORY := $(LATINIME_DIR)/java/src/com/android/inputmethod
LATINIME_CORE_SOURCE_DIRECTORY := $(LATINIME_BASE_SOURCE_DIRECTORY)/latin
LATINIME_ANNOTATIONS_SOURCE_DIRECTORY := $(LATINIME_BASE_SOURCE_DIRECTORY)/annotations
MAKEDICT_CORE_SOURCE_DIRECTORY := $(LATINIME_CORE_SOURCE_DIRECTORY)/makedict
DICTTOOL_COMPAT_TESTS_DIRECTORY := compat
DICTTOOL_ONDEVICE_TESTS_DIRECTORY := \
        $(LATINIME_DIR)/tests/src/com/android/inputmethod/latin/makedict/

USED_TARGETTED_UTILS := \
        $(LATINIME_CORE_SOURCE_DIRECTORY)/utils/ByteArrayWrapper.java \
        $(LATINIME_CORE_SOURCE_DIRECTORY)/utils/CollectionUtils.java

LOCAL_MAIN_SRC_FILES := $(call all-java-files-under, $(MAKEDICT_CORE_SOURCE_DIRECTORY))
LOCAL_TOOL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_ANNOTATIONS_SRC_FILES := \
        $(call all-java-files-under, $(LATINIME_ANNOTATIONS_SOURCE_DIRECTORY))
LOCAL_SRC_FILES := $(LOCAL_TOOL_SRC_FILES) \
        $(filter-out $(addprefix %/, $(notdir $(LOCAL_TOOL_SRC_FILES))), $(LOCAL_MAIN_SRC_FILES)) \
        $(LOCAL_ANNOTATIONS_SRC_FILES) \
        $(LATINIME_CORE_SOURCE_DIRECTORY)/Constants.java \
        $(call all-java-files-under, tests) \
        $(call all-java-files-under, $(DICTTOOL_ONDEVICE_TESTS_DIRECTORY)) \
        $(call all-java-files-under, $(DICTTOOL_COMPAT_TESTS_DIRECTORY)) \
        $(USED_TARGETTED_UTILS)

LOCAL_JAVA_LIBRARIES := junit

LOCAL_JAR_MANIFEST := etc/manifest.txt
LOCAL_MODULE := dicttool_aosp

include $(BUILD_HOST_JAVA_LIBRARY)
include $(LOCAL_PATH)/etc/Android.mk
