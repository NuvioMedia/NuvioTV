package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings

internal object NextEpisodeAutoPlayDelayRules {
    fun remainingMillis(configuredDelaySeconds: Int, elapsedMillisSincePrompt: Long): Long {
        if (configuredDelaySeconds == PlayerSettings.NEXT_EPISODE_AUTOPLAY_AT_END) return Long.MAX_VALUE
        val delayMillis = configuredDelaySeconds.coerceAtLeast(0) * 1_000L
        return (delayMillis - elapsedMillisSincePrompt.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun displaySeconds(remainingMillis: Long): Int =
        ((remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L).toInt()

    fun episodeRemainingMillis(
        currentPositionMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1f,
    ): Long? {
        if (durationMillis <= 0L) return null
        val mediaMillisRemaining =
            (durationMillis - currentPositionMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
        val effectiveSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (mediaMillisRemaining / effectiveSpeed).toLong().coerceAtLeast(0L)
    }

    fun episodeRemainingSeconds(
        currentPositionMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1f,
    ): Int? = episodeRemainingMillis(currentPositionMillis, durationMillis, playbackSpeed)
        ?.let(::displaySeconds)

    fun effectiveCountdownSeconds(
        configuredRemainingMillis: Long,
        episodeRemainingMillis: Long?,
    ): Int? {
        val effectiveRemaining = when {
            configuredRemainingMillis == Long.MAX_VALUE -> episodeRemainingMillis
            episodeRemainingMillis == null -> configuredRemainingMillis
            else -> minOf(configuredRemainingMillis, episodeRemainingMillis)
        }
        return effectiveRemaining?.let(::displaySeconds)
    }

    fun isStalledAtEpisodeEnd(
        currentPositionMillis: Long,
        durationMillis: Long,
        stableForMillis: Long,
        userPausedManually: Boolean,
    ): Boolean =
        !userPausedManually &&
            durationMillis > 0L &&
            currentPositionMillis >= durationMillis - END_STALL_POSITION_TOLERANCE_MS &&
            stableForMillis >= END_STALL_GRACE_MS

    fun shouldResetAfterBackwardSeek(fromPositionMillis: Long, toPositionMillis: Long): Boolean =
        toPositionMillis < fromPositionMillis - BACKWARD_SEEK_RESET_THRESHOLD_MS

    const val END_STALL_POSITION_TOLERANCE_MS = 250L
    const val END_STALL_GRACE_MS = 2_000L
    const val BACKWARD_SEEK_RESET_THRESHOLD_MS = 1_000L
}

internal class ActivePlaybackElapsedTracker(
    startedAtMillis: Long,
    initiallyPlaying: Boolean,
) {
    private var lastSampleMillis = startedAtMillis
    private var activeElapsedMillis = 0L
    private var wasPlaying = initiallyPlaying

    @Synchronized
    fun sample(nowMillis: Long, isPlaying: Boolean): Long {
        val safeNow = maxOf(nowMillis, lastSampleMillis)
        if (wasPlaying) activeElapsedMillis += safeNow - lastSampleMillis
        lastSampleMillis = safeNow
        wasPlaying = isPlaying
        return activeElapsedMillis
    }

    @Synchronized
    fun elapsedMillis(): Long = activeElapsedMillis
}
