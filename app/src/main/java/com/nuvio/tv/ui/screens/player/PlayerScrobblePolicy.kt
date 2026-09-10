package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

/**
 * Progress at which a stop scrobble becomes a completion scrobble. Trakt and Simkl both document
 * 80% as the point where a stop moves the item to history and drops the resume point. Local
 * watched state uses it as a floor so it cannot contradict what was just published. It does not
 * mean 80% is "watched"; PlaybackCompletionRules decides that.
 */
internal const val PROVIDER_COMPLETION_PERCENT = 80f

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
): Boolean = hasActiveScrobble || progressPercent >= PROVIDER_COMPLETION_PERCENT
