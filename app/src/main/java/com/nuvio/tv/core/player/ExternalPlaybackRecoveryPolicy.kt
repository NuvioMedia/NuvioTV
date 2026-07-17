package com.nuvio.tv.core.player

/** Conservative rules for recovering from an external player that never started playback. */
internal object ExternalPlaybackRecoveryPolicy {
    const val QUICK_RETURN_WINDOW_MS = 15_000L

    fun shouldInvalidateCachedLink(
        hasUsableResult: Boolean,
        externalPlayerCoveredApp: Boolean,
        launchStartedAtMs: Long,
        returnedAtMs: Long,
        quickReturnWindowMs: Long = QUICK_RETURN_WINDOW_MS
    ): Boolean {
        if (hasUsableResult || !externalPlayerCoveredApp || launchStartedAtMs <= 0L) return false
        if (returnedAtMs < launchStartedAtMs || quickReturnWindowMs < 0L) return false
        return returnedAtMs - launchStartedAtMs <= quickReturnWindowMs
    }
}
