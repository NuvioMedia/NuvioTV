package com.nuvio.tv.core.player

/**
 * Pure decision rules for the external-player auto-next state machine, split out from
 * [ExternalPlaybackTracker] so the flag/timing logic is unit-testable without Android dependencies.
 */
internal object ExternalAutoNextPolicy {

    private val SERIES_TYPES = setOf("series", "tv")

    /**
     * Whether a launch should clear a pending chain abort. A launch is "fresh" (clears the abort)
     * unless it is an auto-next continuation: an auto-launch within [continuationWindowMs] of the
     * last emit. A manual launch is always fresh, so manually starting an episode right after a
     * Back-abort re-enables auto-next.
     */
    fun shouldResetChainAbort(
        autoLaunch: Boolean,
        nowMs: Long,
        lastAutoNextEmitMs: Long,
        continuationWindowMs: Long
    ): Boolean = !(autoLaunch && nowMs - lastAutoNextEmitMs < continuationWindowMs)

    /**
     * Whether a user's abort should still skip the pending auto-launch. Only while inside the
     * continuation window, so a stale abort can't suppress a fresh auto-play of an unrelated title.
     */
    fun isAbortedContinuation(
        chainAborted: Boolean,
        nowMs: Long,
        lastAutoNextEmitMs: Long,
        continuationWindowMs: Long
    ): Boolean = chainAborted && nowMs - lastAutoNextEmitMs < continuationWindowMs

    /**
     * Whether the auto-advance loader may be raised. Only for a series/tv episode that has not been
     * aborted, explicitly released, or already raised. Season may be null (absolute-numbered content).
     */
    fun shouldRaiseLoader(
        episode: Int?,
        contentType: String,
        cancelled: Boolean,
        chainAborted: Boolean,
        overlaySuppressed: Boolean,
        alreadyShowing: Boolean,
        autoNextEnabled: Boolean? = null,
        hasNextEpisode: Boolean? = null,
        allowUnknownNextEpisode: Boolean = false
    ): Boolean {
        if (cancelled || chainAborted || overlaySuppressed || alreadyShowing) return false
        if (autoNextEnabled == false || hasNextEpisode == false) return false
        if (hasNextEpisode == null && !allowUnknownNextEpisode) return false
        return isSeriesEpisode(episode, contentType)
    }

    /**
     * Whether auto-advance should be attempted. Only for a series/tv episode the user hasn't aborted.
     * Season may be null (absolute-numbered content); only the episode number is required.
     */
    fun shouldAttemptAdvance(
        episode: Int?,
        contentType: String,
        cancelled: Boolean,
        chainAborted: Boolean
    ): Boolean {
        if (cancelled || chainAborted) return false
        return isSeriesEpisode(episode, contentType)
    }

    /** External players can only advance to an episode that is both published and available. */
    fun isPlayableNextEpisode(available: Boolean?, hasAired: Boolean): Boolean =
        available != false && hasAired

    fun shouldHoldLoaderOnSettle(
        overlayShowing: Boolean,
        handoffActive: Boolean,
        mayHaveNextEpisode: Boolean,
        forceRelease: Boolean
    ): Boolean = overlayShowing && handoffActive && mayHaveNextEpisode && !forceRelease

    fun shouldIgnoreLoaderRelease(externalPlaybackActive: Boolean): Boolean =
        externalPlaybackActive

    fun shouldCancelPendingAutoLaunch(
        autoLaunch: Boolean,
        autoNextNavigationPending: Boolean,
        cancelled: Boolean,
        chainAborted: Boolean
    ): Boolean = autoLaunch && autoNextNavigationPending && (cancelled || chainAborted)

    private fun isSeriesEpisode(episode: Int?, contentType: String): Boolean =
        episode != null && contentType.lowercase() in SERIES_TYPES
}
