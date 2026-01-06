package com.github.spwplace.spweeboard

/**
 * Feature flags for gradual rollout and A/B testing.
 *
 * These flags can be backed by BuildConfig fields for compile-time control,
 * or set dynamically at runtime for testing.
 */
object FeatureFlags {
    /**
     * Enable streaming output display (show interpretation as it generates).
     */
    var streamingEnabled: Boolean = BuildConfig.FEATURE_STREAMING
        internal set

    /**
     * Enable expression history in the keyboard.
     */
    var historyEnabled: Boolean = BuildConfig.FEATURE_HISTORY
        internal set

    /**
     * Enable user-defined grounds (custom contexts).
     */
    var customGroundsEnabled: Boolean = BuildConfig.FEATURE_CUSTOM_GROUNDS
        internal set

    /**
     * Enable cancel interpretation button.
     */
    var cancelInterpretationEnabled: Boolean = BuildConfig.FEATURE_CANCEL
        internal set

    /**
     * Maximum number of history entries to keep.
     */
    const val MAX_HISTORY_SIZE = 100

    /**
     * Override flags for testing. Resets to BuildConfig defaults on next app launch.
     */
    fun setForTesting(
        streaming: Boolean? = null,
        history: Boolean? = null,
        customGrounds: Boolean? = null,
        cancelInterpretation: Boolean? = null
    ) {
        streaming?.let { streamingEnabled = it }
        history?.let { historyEnabled = it }
        customGrounds?.let { customGroundsEnabled = it }
        cancelInterpretation?.let { cancelInterpretationEnabled = it }
    }

    /**
     * Reset all flags to BuildConfig defaults.
     */
    fun resetToDefaults() {
        streamingEnabled = BuildConfig.FEATURE_STREAMING
        historyEnabled = BuildConfig.FEATURE_HISTORY
        customGroundsEnabled = BuildConfig.FEATURE_CUSTOM_GROUNDS
        cancelInterpretationEnabled = BuildConfig.FEATURE_CANCEL
    }
}
