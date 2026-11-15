package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The longest single wait for a release. Delays stop while the device sleeps, so waiting in steps
 * and checking the clock again keeps a release from being noticed much later.
 */
internal const val CW_RELEASE_REFRESH_MAX_WAIT_MS = 15 * 60_000L

/** Refreshes slightly after the release, so the episode already counts as aired. */
internal const val CW_RELEASE_REFRESH_MARGIN_MS = 1_000L

/**
 * Times that change what Continue Watching shows without anything else changing: an episode
 * shown as upcoming airing, and a series without a next-up being due to be looked up again.
 */
internal fun continueWatchingReleaseTimes(
    items: List<ContinueWatchingItem>,
    noNextUpRecheckTimes: Collection<Long>
): Sequence<Long> {
    val upcomingReleases = items.asSequence()
        .filterIsInstance<ContinueWatchingItem.NextUp>()
        .filter { !it.info.hasAired }
        .mapNotNull { parseEpisodeReleaseInstant(it.info.released)?.toEpochMilli() }
    return upcomingReleases + noNextUpRecheckTimes.asSequence()
}

/** The next of [continueWatchingReleaseTimes] still ahead, or null when nothing is due. */
internal fun nextContinueWatchingReleaseMs(
    items: List<ContinueWatchingItem>,
    noNextUpRecheckTimes: Collection<Long>,
    nowMs: Long
): Long? = continueWatchingReleaseTimes(items, noNextUpRecheckTimes).filter { it > nowMs }.minOrNull()

/** Whether one of [continueWatchingReleaseTimes] passed after [sinceMs], up to now. */
internal fun hasContinueWatchingReleaseSince(
    items: List<ContinueWatchingItem>,
    noNextUpRecheckTimes: Collection<Long>,
    sinceMs: Long,
    nowMs: Long
): Boolean = continueWatchingReleaseTimes(items, noNextUpRecheckTimes).any { it in (sinceMs + 1)..nowMs }

/**
 * Runs Continue Watching again when an upcoming episode airs while the app stays open, so it
 * moves out of upcoming without a restart or another change to trigger it.
 */
internal fun HomeViewModel.observeContinueWatchingReleases() {
    viewModelScope.launch {
        _uiState
            .map { it.continueWatchingItems + it.upcomingItems }
            .distinctUntilChanged()
            .collectLatest { items ->
                while (true) {
                    val nowMs = System.currentTimeMillis()
                    val nextMs = nextContinueWatchingReleaseMs(items, cwNoNextUpSeeds.values, nowMs) ?: break
                    val waitMs = nextMs - nowMs + CW_RELEASE_REFRESH_MARGIN_MS
                    if (waitMs > CW_RELEASE_REFRESH_MAX_WAIT_MS) {
                        delay(CW_RELEASE_REFRESH_MAX_WAIT_MS)
                        continue
                    }
                    delay(waitMs)
                    refreshContinueWatchingForRelease()
                }
            }
    }
}

/** Covers a release passed while the app was in the background, where the wait may not have run. */
internal fun HomeViewModel.refreshContinueWatchingIfReleased() {
    val state = _uiState.value
    val released = hasContinueWatchingReleaseSince(
        items = state.continueWatchingItems + state.upcomingItems,
        noNextUpRecheckTimes = cwNoNextUpSeeds.values,
        sinceMs = cwLastCycleStartedAtMs,
        nowMs = System.currentTimeMillis()
    )
    if (released) refreshContinueWatchingForRelease()
}

private fun HomeViewModel.refreshContinueWatchingForRelease() {
    cwLastCycleStartedAtMs = System.currentTimeMillis()
    cwPipelineRefreshTrigger.value++
}
