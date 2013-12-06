/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.inputmethod.latin;

import android.text.TextUtils;
import android.util.SparseArray;

import com.android.inputmethod.annotations.UsedForTesting;
import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.settings.NativeSuggestOptions;
import com.android.inputmethod.latin.utils.CollectionUtils;
import com.android.inputmethod.latin.utils.JniUtils;
import com.android.inputmethod.latin.utils.StringUtils;
import com.android.inputmethod.latin.utils.UnigramProperty;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Implements a static, compacted, binary dictionary of standard words.
 */
// TODO: All methods which should be locked need to have a suffix "Locked".
public final class BinaryDictionary extends Dictionary {
    private static final String TAG = BinaryDictionary.class.getSimpleName();

    // Must be equal to MAX_WORD_LENGTH in native/jni/src/defines.h
    private static final int MAX_WORD_LENGTH = Constants.DICTIONARY_MAX_WORD_LENGTH;
    // Must be equal to MAX_RESULTS in native/jni/src/defines.h
    private static final int MAX_RESULTS = 18;
    // The cutoff returned by native for auto-commit confidence.
    // Must be equal to CONFIDENCE_TO_AUTO_COMMIT in native/jni/src/defines.h
    private static final int CONFIDENCE_TO_AUTO_COMMIT = 1000000;

    @UsedForTesting
    public static final String UNIGRAM_COUNT_QUERY = "UNIGRAM_COUNT";
    @UsedForTesting
    public static final String BIGRAM_COUNT_QUERY = "BIGRAM_COUNT";
    @UsedForTesting
    public static final String MAX_UNIGRAM_COUNT_QUERY = "MAX_UNIGRAM_COUNT";
    @UsedForTesting
    public static final String MAX_BIGRAM_COUNT_QUERY = "MAX_BIGRAM_COUNT";

    public static final int NOT_A_VALID_TIMESTAMP = -1;

    // Format to get unigram flags from native side via getUnigramPropertyNative().
    private static final int FORMAT_UNIGRAM_PROPERTY_OUTPUT_FLAG_COUNT = 4;
    private static final int FORMAT_UNIGRAM_PROPERTY_IS_NOT_A_WORD_INDEX = 0;
    private static final int FORMAT_UNIGRAM_PROPERTY_IS_BLACKLISTED_INDEX = 1;
    private static final int FORMAT_UNIGRAM_PROPERTY_HAS_BIGRAMS_INDEX = 2;
    private static final int FORMAT_UNIGRAM_PROPERTY_HAS_SHORTCUTS_INDEX = 3;

    // Format to get unigram historical info from native side via getUnigramPropertyNative().
    private static final int FORMAT_UNIGRAM_PROPERTY_OUTPUT_HISTORICAL_INFO_COUNT = 3;
    private static final int FORMAT_UNIGRAM_PROPERTY_TIMESTAMP_INDEX = 0;
    private static final int FORMAT_UNIGRAM_PROPERTY_LEVEL_INDEX = 1;
    private static final int FORMAT_UNIGRAM_PROPERTY_COUNT_INDEX = 2;

    private long mNativeDict;
    private final Locale mLocale;
    private final long mDictSize;
    private final String mDictFilePath;
    private final int[] mInputCodePoints = new int[MAX_WORD_LENGTH];
    private final int[] mOutputCodePoints = new int[MAX_WORD_LENGTH * MAX_RESULTS];
    private final int[] mSpaceIndices = new int[MAX_RESULTS];
    private final int[] mOutputScores = new int[MAX_RESULTS];
    private final int[] mOutputTypes = new int[MAX_RESULTS];
    // Only one result is ever used
    private final int[] mOutputAutoCommitFirstWordConfidence = new int[1];

    private final NativeSuggestOptions mNativeSuggestOptions = new NativeSuggestOptions();

    private final SparseArray<DicTraverseSession> mDicTraverseSessions =
            CollectionUtils.newSparseArray();

    // TODO: There should be a way to remove used DicTraverseSession objects from
    // {@code mDicTraverseSessions}.
    private DicTraverseSession getTraverseSession(final int traverseSessionId) {
        synchronized(mDicTraverseSessions) {
            DicTraverseSession traverseSession = mDicTraverseSessions.get(traverseSessionId);
            if (traverseSession == null) {
                traverseSession = mDicTraverseSessions.get(traverseSessionId);
                if (traverseSession == null) {
                    traverseSession = new DicTraverseSession(mLocale, mNativeDict, mDictSize);
                    mDicTraverseSessions.put(traverseSessionId, traverseSession);
                }
            }
            return traverseSession;
        }
    }

    /**
     * Constructor for the binary dictionary. This is supposed to be called from the
     * dictionary factory.
     * @param filename the name of the file to read through native code.
     * @param offset the offset of the dictionary data within the file.
     * @param length the length of the binary data.
     * @param useFullEditDistance whether to use the full edit distance in suggestions
     * @param dictType the dictionary type, as a human-readable string
     * @param isUpdatable whether to open the dictionary file in writable mode.
     */
    public BinaryDictionary(final String filename, final long offset, final long length,
            final boolean useFullEditDistance, final Locale locale, final String dictType,
            final boolean isUpdatable) {
        super(dictType);
        mLocale = locale;
        mDictSize = length;
        mDictFilePath = filename;
        mNativeSuggestOptions.setUseFullEditDistance(useFullEditDistance);
        loadDictionary(filename, offset, length, isUpdatable);
    }

    static {
        JniUtils.loadNativeLibrary();
    }

    private static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
            String[] attributeKeyStringArray, String[] attributeValueStringArray);
    private static native long openNative(String sourceDir, long dictOffset, long dictSize,
            boolean isUpdatable);
    private static native boolean hasValidContentsNative(long dict);
    private static native void flushNative(long dict, String filePath);
    private static native boolean needsToRunGCNative(long dict, boolean mindsBlockByGC);
    private static native void flushWithGCNative(long dict, String filePath);
    private static native void closeNative(long dict);
    private static native int getFormatVersionNative(long dict);
    private static native int getProbabilityNative(long dict, int[] word);
    private static native int getBigramProbabilityNative(long dict, int[] word0, int[] word1);
    private static native void getUnigramPropertyNative(long dict, int[] word,
            int[] outCodePoints, boolean[] outFlags, int[] outProbability,
            int[] outHistoricalInfo, ArrayList<int[]> outShortcutTargets,
            ArrayList<Integer> outShortcutProbabilities);
    private static native int getSuggestionsNative(long dict, long proximityInfo,
            long traverseSession, int[] xCoordinates, int[] yCoordinates, int[] times,
            int[] pointerIds, int[] inputCodePoints, int inputSize, int commitPoint,
            int[] suggestOptions, int[] prevWordCodePointArray,
            int[] outputCodePoints, int[] outputScores, int[] outputIndices, int[] outputTypes,
            int[] outputAutoCommitFirstWordConfidence);
    private static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    private static native int editDistanceNative(int[] before, int[] after);
    private static native void addUnigramWordNative(long dict, int[] word, int probability,
            int[] shortcutTarget, int shortcutProbability, boolean isNotAWord,
            boolean isBlacklisted, int timestamp);
    private static native void addBigramWordsNative(long dict, int[] word0, int[] word1,
            int probability, int timestamp);
    private static native void removeBigramWordsNative(long dict, int[] word0, int[] word1);
    private static native int addMultipleDictionaryEntriesNative(long dict,
            LanguageModelParam[] languageModelParams, int startIndex);
    private static native int calculateProbabilityNative(long dict, int unigramProbability,
            int bigramProbability);
    private static native String getPropertyNative(long dict, String query);

    @UsedForTesting
    public static boolean createEmptyDictFile(final String filePath, final long dictVersion,
            final Map<String, String> attributeMap) {
        final String[] keyArray = new String[attributeMap.size()];
        final String[] valueArray = new String[attributeMap.size()];
        int index = 0;
        for (final String key : attributeMap.keySet()) {
            keyArray[index] = key;
            valueArray[index] = attributeMap.get(key);
            index++;
        }
        return createEmptyDictFileNative(filePath, dictVersion, keyArray, valueArray);
    }

    // TODO: Move native dict into session
    private final void loadDictionary(final String path, final long startOffset,
            final long length, final boolean isUpdatable) {
        mNativeDict = openNative(path, startOffset, length, isUpdatable);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo,
            final boolean blockOffensiveWords, final int[] additionalFeaturesOptions) {
        return getSuggestionsWithSessionId(composer, prevWord, proximityInfo, blockOffensiveWords,
                additionalFeaturesOptions, 0 /* sessionId */);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestionsWithSessionId(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo,
            final boolean blockOffensiveWords, final int[] additionalFeaturesOptions,
            final int sessionId) {
        if (!isValidDictionary()) return null;

        Arrays.fill(mInputCodePoints, Constants.NOT_A_CODE);
        // TODO: toLowerCase in the native code
        final int[] prevWordCodePointArray = (null == prevWord)
                ? null : StringUtils.toCodePointArray(prevWord);
        final int composerSize = composer.size();

        final boolean isGesture = composer.isBatchMode();
        if (composerSize <= 1 || !isGesture) {
            if (composerSize > MAX_WORD_LENGTH - 1) return null;
            for (int i = 0; i < composerSize; i++) {
                mInputCodePoints[i] = composer.getCodeAt(i);
            }
        }

        final InputPointers ips = composer.getInputPointers();
        final int inputSize = isGesture ? ips.getPointerSize() : composerSize;
        mNativeSuggestOptions.setIsGesture(isGesture);
        mNativeSuggestOptions.setAdditionalFeaturesOptions(additionalFeaturesOptions);
        // proximityInfo and/or prevWordForBigrams may not be null.
        final int count = getSuggestionsNative(mNativeDict, proximityInfo.getNativeProximityInfo(),
                getTraverseSession(sessionId).getSession(), ips.getXCoordinates(),
                ips.getYCoordinates(), ips.getTimes(), ips.getPointerIds(), mInputCodePoints,
                inputSize, 0 /* commitPoint */, mNativeSuggestOptions.getOptions(),
                prevWordCodePointArray, mOutputCodePoints, mOutputScores, mSpaceIndices,
                mOutputTypes, mOutputAutoCommitFirstWordConfidence);
        final ArrayList<SuggestedWordInfo> suggestions = CollectionUtils.newArrayList();
        for (int j = 0; j < count; ++j) {
            final int start = j * MAX_WORD_LENGTH;
            int len = 0;
            while (len < MAX_WORD_LENGTH && mOutputCodePoints[start + len] != 0) {
                ++len;
            }
            if (len > 0) {
                final int flags = mOutputTypes[j] & SuggestedWordInfo.KIND_MASK_FLAGS;
                if (blockOffensiveWords
                        && 0 != (flags & SuggestedWordInfo.KIND_FLAG_POSSIBLY_OFFENSIVE)
                        && 0 == (flags & SuggestedWordInfo.KIND_FLAG_EXACT_MATCH)) {
                    // If we block potentially offensive words, and if the word is possibly
                    // offensive, then we don't output it unless it's also an exact match.
                    continue;
                }
                final int kind = mOutputTypes[j] & SuggestedWordInfo.KIND_MASK_KIND;
                final int score = SuggestedWordInfo.KIND_WHITELIST == kind
                        ? SuggestedWordInfo.MAX_SCORE : mOutputScores[j];
                // TODO: check that all users of the `kind' parameter are ready to accept
                // flags too and pass mOutputTypes[j] instead of kind
                suggestions.add(new SuggestedWordInfo(new String(mOutputCodePoints, start, len),
                        score, kind, this /* sourceDict */,
                        mSpaceIndices[j] /* indexOfTouchPointOfSecondWord */,
                        mOutputAutoCommitFirstWordConfidence[0]));
            }
        }
        return suggestions;
    }

    public boolean isValidDictionary() {
        return mNativeDict != 0;
    }

    public boolean hasValidContents() {
        return hasValidContentsNative(mNativeDict);
    }

    public int getFormatVersion() {
        return getFormatVersionNative(mNativeDict);
    }

    public static float calcNormalizedScore(final String before, final String after,
            final int score) {
        return calcNormalizedScoreNative(StringUtils.toCodePointArray(before),
                StringUtils.toCodePointArray(after), score);
    }

    public static int editDistance(final String before, final String after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException();
        }
        return editDistanceNative(StringUtils.toCodePointArray(before),
                StringUtils.toCodePointArray(after));
    }

    @Override
    public boolean isValidWord(final String word) {
        return getFrequency(word) != NOT_A_PROBABILITY;
    }

    @Override
    public int getFrequency(final String word) {
        if (word == null) return NOT_A_PROBABILITY;
        int[] codePoints = StringUtils.toCodePointArray(word);
        return getProbabilityNative(mNativeDict, codePoints);
    }

    // TODO: Add a batch process version (isValidBigramMultiple?) to avoid excessive numbers of jni
    // calls when checking for changes in an entire dictionary.
    public boolean isValidBigram(final String word0, final String word1) {
        return getBigramProbability(word0, word1) != NOT_A_PROBABILITY;
    }

    public int getBigramProbability(final String word0, final String word1) {
        if (TextUtils.isEmpty(word0) || TextUtils.isEmpty(word1)) return NOT_A_PROBABILITY;
        final int[] codePoints0 = StringUtils.toCodePointArray(word0);
        final int[] codePoints1 = StringUtils.toCodePointArray(word1);
        return getBigramProbabilityNative(mNativeDict, codePoints0, codePoints1);
    }

    @UsedForTesting
    public UnigramProperty getUnigramProperty(final String word) {
        if (TextUtils.isEmpty(word)) {
            return null;
        }
        final int[] codePoints = StringUtils.toCodePointArray(word);
        final int[] outCodePoints = new int[MAX_WORD_LENGTH];
        final boolean[] outFlags = new boolean[FORMAT_UNIGRAM_PROPERTY_OUTPUT_FLAG_COUNT];
        final int[] outProbability = new int[1];
        final int[] outHistoricalInfo =
                new int[FORMAT_UNIGRAM_PROPERTY_OUTPUT_HISTORICAL_INFO_COUNT];
        final ArrayList<int[]> outShortcutTargets = CollectionUtils.newArrayList();
        final ArrayList<Integer> outShortcutProbabilities = CollectionUtils.newArrayList();
        getUnigramPropertyNative(mNativeDict, codePoints, outCodePoints, outFlags, outProbability,
                outHistoricalInfo, outShortcutTargets, outShortcutProbabilities);
        return new UnigramProperty(codePoints,
                outFlags[FORMAT_UNIGRAM_PROPERTY_IS_NOT_A_WORD_INDEX],
                outFlags[FORMAT_UNIGRAM_PROPERTY_IS_BLACKLISTED_INDEX],
                outFlags[FORMAT_UNIGRAM_PROPERTY_HAS_BIGRAMS_INDEX],
                outFlags[FORMAT_UNIGRAM_PROPERTY_HAS_SHORTCUTS_INDEX], outProbability[0],
                outHistoricalInfo[FORMAT_UNIGRAM_PROPERTY_TIMESTAMP_INDEX],
                outHistoricalInfo[FORMAT_UNIGRAM_PROPERTY_LEVEL_INDEX],
                outHistoricalInfo[FORMAT_UNIGRAM_PROPERTY_COUNT_INDEX],
                outShortcutTargets, outShortcutProbabilities);
    }

    // Add a unigram entry to binary dictionary with unigram attributes in native code.
    public void addUnigramWord(final String word, final int probability,
            final String shortcutTarget, final int shortcutProbability, final boolean isNotAWord,
            final boolean isBlacklisted, final int timestamp) {
        if (TextUtils.isEmpty(word)) {
            return;
        }
        final int[] codePoints = StringUtils.toCodePointArray(word);
        final int[] shortcutTargetCodePoints = (shortcutTarget != null) ?
                StringUtils.toCodePointArray(shortcutTarget) : null;
        addUnigramWordNative(mNativeDict, codePoints, probability, shortcutTargetCodePoints,
                shortcutProbability, isNotAWord, isBlacklisted, timestamp);
    }

    // Add a bigram entry to binary dictionary with timestamp in native code.
    public void addBigramWords(final String word0, final String word1, final int probability,
            final int timestamp) {
        if (TextUtils.isEmpty(word0) || TextUtils.isEmpty(word1)) {
            return;
        }
        final int[] codePoints0 = StringUtils.toCodePointArray(word0);
        final int[] codePoints1 = StringUtils.toCodePointArray(word1);
        addBigramWordsNative(mNativeDict, codePoints0, codePoints1, probability, timestamp);
    }

    // Remove a bigram entry form binary dictionary in native code.
    public void removeBigramWords(final String word0, final String word1) {
        if (TextUtils.isEmpty(word0) || TextUtils.isEmpty(word1)) {
            return;
        }
        final int[] codePoints0 = StringUtils.toCodePointArray(word0);
        final int[] codePoints1 = StringUtils.toCodePointArray(word1);
        removeBigramWordsNative(mNativeDict, codePoints0, codePoints1);
    }

    public static class LanguageModelParam {
        public final int[] mWord0;
        public final int[] mWord1;
        public final int[] mShortcutTarget;
        public final int mUnigramProbability;
        public final int mBigramProbability;
        public final int mShortcutProbability;
        public final boolean mIsNotAWord;
        public final boolean mIsBlacklisted;
        public final int mTimestamp;

        // Constructor for unigram.
        public LanguageModelParam(final String word, final int unigramProbability,
                final int timestamp) {
            mWord0 = null;
            mWord1 = StringUtils.toCodePointArray(word);
            mShortcutTarget = null;
            mUnigramProbability = unigramProbability;
            mBigramProbability = NOT_A_PROBABILITY;
            mShortcutProbability = NOT_A_PROBABILITY;
            mIsNotAWord = false;
            mIsBlacklisted = false;
            mTimestamp = timestamp;
        }

        // Constructor for unigram and bigram.
        public LanguageModelParam(final String word0, final String word1,
                final int unigramProbability, final int bigramProbability,
                final int timestamp) {
            mWord0 = StringUtils.toCodePointArray(word0);
            mWord1 = StringUtils.toCodePointArray(word1);
            mShortcutTarget = null;
            mUnigramProbability = unigramProbability;
            mBigramProbability = bigramProbability;
            mShortcutProbability = NOT_A_PROBABILITY;
            mIsNotAWord = false;
            mIsBlacklisted = false;
            mTimestamp = timestamp;
        }
    }

    public void addMultipleDictionaryEntries(final LanguageModelParam[] languageModelParams) {
        if (!isValidDictionary()) return;
        int processedParamCount = 0;
        while (processedParamCount < languageModelParams.length) {
            if (needsToRunGC(true /* mindsBlockByGC */)) {
                flushWithGC();
            }
            processedParamCount = addMultipleDictionaryEntriesNative(mNativeDict,
                    languageModelParams, processedParamCount);
            if (processedParamCount <= 0) {
                return;
            }
        }

    }

    private void reopen() {
        close();
        final File dictFile = new File(mDictFilePath);
        // WARNING: Because we pass 0 as the offset and file.length() as the length, this can
        // only be called for actual files. Right now it's only called by the flush() family of
        // functions, which require an updatable dictionary, so it's okay. But beware.
        loadDictionary(dictFile.getAbsolutePath(), 0 /* startOffset */,
                dictFile.length(), true /* isUpdatable */);
    }

    public void flush() {
        if (!isValidDictionary()) return;
        flushNative(mNativeDict, mDictFilePath);
        reopen();
    }

    public void flushWithGC() {
        if (!isValidDictionary()) return;
        flushWithGCNative(mNativeDict, mDictFilePath);
        reopen();
    }

    /**
     * Checks whether GC is needed to run or not.
     * @param mindsBlockByGC Whether to mind operations blocked by GC. We don't need to care about
     * the blocking in some situations such as in idle time or just before closing.
     * @return whether GC is needed to run or not.
     */
    public boolean needsToRunGC(final boolean mindsBlockByGC) {
        if (!isValidDictionary()) return false;
        return needsToRunGCNative(mNativeDict, mindsBlockByGC);
    }

    @UsedForTesting
    public int calculateProbability(final int unigramProbability, final int bigramProbability) {
        if (!isValidDictionary()) return NOT_A_PROBABILITY;
        return calculateProbabilityNative(mNativeDict, unigramProbability, bigramProbability);
    }

    @UsedForTesting
    public String getPropertyForTests(String query) {
        if (!isValidDictionary()) return "";
        return getPropertyNative(mNativeDict, query);
    }

    @Override
    public boolean shouldAutoCommit(final SuggestedWordInfo candidate) {
        return candidate.mAutoCommitFirstWordConfidence > CONFIDENCE_TO_AUTO_COMMIT;
    }

    @Override
    public void close() {
        synchronized (mDicTraverseSessions) {
            final int sessionsSize = mDicTraverseSessions.size();
            for (int index = 0; index < sessionsSize; ++index) {
                final DicTraverseSession traverseSession = mDicTraverseSessions.valueAt(index);
                if (traverseSession != null) {
                    traverseSession.close();
                }
            }
            mDicTraverseSessions.clear();
        }
        closeInternalLocked();
    }

    private synchronized void closeInternalLocked() {
        if (mNativeDict != 0) {
            closeNative(mNativeDict);
            mNativeDict = 0;
        }
    }

    // TODO: Manage BinaryDictionary instances without using WeakReference or something.
    @Override
    protected void finalize() throws Throwable {
        try {
            closeInternalLocked();
        } finally {
            super.finalize();
        }
    }
}
