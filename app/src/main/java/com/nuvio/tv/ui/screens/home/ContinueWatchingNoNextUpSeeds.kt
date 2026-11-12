package com.nuvio.tv.ui.screens.home

/** The longest a series confirmed to have no next episode is skipped before it is looked up again. */
internal const val NO_NEXT_UP_RECHECK_MS = 24L * 60 * 60 * 1000

/**
 * When a series with no episode after the one last watched has to be looked up again: when its
 * next announced episode or season is released, and at the latest after a day, so an episode
 * added without a date still shows up.
 */
internal fun noNextUpRecheckAt(nextReleaseMs: Long?, nowMs: Long): Long {
    val latest = nowMs + NO_NEXT_UP_RECHECK_MS
    return nextReleaseMs?.coerceIn(nowMs, latest) ?: latest
}

/**
 * Whether a next-up lookup can be skipped because an earlier launch confirmed that the same seed
 * has no next episode and its recheck time has not come yet. The key holds the episode last
 * watched, so watching on is never skipped.
 */
internal fun isNoNextUpStillConfirmed(recheckAt: Long?, nowMs: Long): Boolean =
    recheckAt != null && nowMs < recheckAt
