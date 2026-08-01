package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/**
 * Whether a natural-completion scrobble/stop at [progressPercent] should be sent.
 *
 * Never invent a 99.5% completion when duration is unknown/zero or a short
 * debrid/error placeholder. That path bulk-marked unwatched episodes on Trakt
 * whenever broken streams reached ENDED and chained auto-play (#2740).
 */
internal fun shouldEmitCompletionScrobbleStop(
    progressPercent: Float,
    hasSentCompletionScrobble: Boolean,
    durationMs: Long
): Boolean {
    if (progressPercent < 80f || hasSentCompletionScrobble) return false
    if (durationMs <= 0L) return false
    if (isShortPlaceholderDuration(durationMs)) return false
    return true
}
