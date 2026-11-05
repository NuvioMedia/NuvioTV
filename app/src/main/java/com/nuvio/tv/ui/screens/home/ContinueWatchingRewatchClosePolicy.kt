package com.nuvio.tv.ui.screens.home

/**
 * Keeps the row stable while a running rewatch close is in flight.
 *
 * The hold applies only when a card currently in the row would leave. Additions and replacements
 * pass through, and normal removals are not affected because the guard is inactive unless a close
 * is pending.
 */
internal fun shouldHoldContinueWatchingDuringRewatchClose(
    currentMain: List<ContinueWatchingItem>,
    currentUpcoming: List<ContinueWatchingItem>,
    incomingMain: List<ContinueWatchingItem>,
    incomingUpcoming: List<ContinueWatchingItem>,
    rewatchClosePending: Boolean
): Boolean {
    if (!rewatchClosePending) return false
    val incomingKeys = (incomingMain + incomingUpcoming).mapTo(mutableSetOf()) { item ->
        item.closeHoldKey()
    }
    return (currentMain + currentUpcoming).any { item -> item.closeHoldKey() !in incomingKeys }
}

private fun ContinueWatchingItem.closeHoldKey(): String = when (this) {
    is ContinueWatchingItem.InProgress -> "in-progress|${progress.contentId}"
    is ContinueWatchingItem.NextUp -> "next|${info.contentId}"
}
