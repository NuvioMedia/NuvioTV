package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

/**
 * Whether a pause is worth reporting.
 *
 * An active session owns its playback below and above the bar, and a playback that only ever got its
 * start above the bar is reported by the tracker that took that start, so its pause goes out too and
 * the tracker learns where the user stopped.
 */
internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    hasStartAboveCompletionBar: Boolean,
    progressPercent: Float
): Boolean = progressPercent in 0f..100f && (hasActiveScrobble || hasStartAboveCompletionBar)

/**
 * Whether a stop is worth reporting.
 *
 * Same as the pause, plus the rule the completion bar already had: a stop above the bar stands on
 * its own, because that is where a viewing ends and the trackers have to hear about the finish.
 */
internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    hasStartAboveCompletionBar: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || hasStartAboveCompletionBar ||
    progressPercent >= TRACKING_COMPLETION_BAR_PERCENT

/**
 * Whether a playback that began above the completion bar still needs its start sent.
 *
 * Below the bar the start is the session the rest of the playback is reported through, and that path
 * owns it. Above the bar the start is only a "watching now" report, sent once per playback stretch:
 * a pause clears the mark, so the resume after the pause sends one again and the tracker sees the
 * playback continue.
 */
internal fun shouldSendStartAboveCompletionBar(
    hasSentAboveCompletionBar: Boolean,
    progressPercent: Float
): Boolean =
    progressPercent.toDouble() >= TRACKING_COMPLETION_BAR_PERCENT && !hasSentAboveCompletionBar
