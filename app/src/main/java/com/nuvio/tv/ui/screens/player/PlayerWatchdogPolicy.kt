package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Job

/**
 * Gates for delayed first-frame / stall watchdog coroutines.
 * Keep these pure so release/rebuild races can be unit-tested without ExoPlayer.
 */

/**
 * After a delay, should this job still touch [livePlayer]?
 *
 * [capturedPlayer] is the instance observed when the job was scheduled. If the controller
 * rebuilt the player (or released it), live will be null or a different instance — bail out.
 */
internal fun shouldApplyDelayedWatchdogToPlayer(
    capturedPlayer: Any?,
    livePlayer: Any?
): Boolean {
    if (livePlayer == null) return false
    if (capturedPlayer == null) return false
    return capturedPlayer === livePlayer
}

/**
 * First-frame watchdog post-delay gate (identity + session state).
 * Does not check READY — caller already filtered playbackState.
 */
internal fun shouldRunFirstFrameWatchdogAction(
    capturedPlayer: Any?,
    livePlayer: Any?,
    hasRenderedFirstFrame: Boolean,
    userPausedManually: Boolean,
    isReleasingPlayer: Boolean = false
): Boolean {
    if (isReleasingPlayer) return false
    if (!shouldApplyDelayedWatchdogToPlayer(capturedPlayer, livePlayer)) return false
    if (hasRenderedFirstFrame) return false
    if (userPausedManually) return false
    return true
}

/**
 * Stall watchdog iteration gate: same player instance still installed, not mid-release.
 */
internal fun shouldRunStallWatchdogIteration(
    capturedPlayer: Any?,
    livePlayer: Any?,
    isReleasingPlayer: Boolean = false
): Boolean {
    if (isReleasingPlayer) return false
    return shouldApplyDelayedWatchdogToPlayer(capturedPlayer, livePlayer)
}

/**
 * Cancel + clear a single watchdog job holder. Returns null for assignment.
 * Used by cancelFirstFrameWatchdog / cancelStallWatchdog / releasePlayer.
 */
internal fun cancelAndClearWatchdogJob(job: Job?): Job? {
    job?.cancel()
    return null
}
