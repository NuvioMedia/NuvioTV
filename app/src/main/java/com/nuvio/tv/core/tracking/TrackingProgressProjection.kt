package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean
): List<WatchProgress> {
    val providerKeys = providerEntries.mapTo(mutableSetOf(), WatchProgress::projectionKey)
    return buildList {
        addAll(providerEntries)
        localEntries.forEach { progress ->
            if (retainsLocalProgress(progress.contentId) && progress.projectionKey() !in providerKeys) {
                add(progress)
            }
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

private fun WatchProgress.projectionKey() = Triple(contentId, season, episode)

internal fun mergeWatchedEpisodeProjection(
    providerEpisodes: Map<String, Set<Pair<Int, Int>>>,
    localItems: List<WatchedItem>,
    retainsLocalWatchedEpisode: (WatchedItem) -> Boolean
): Map<String, Set<Pair<Int, Int>>> {
    val merged = providerEpisodes.mapValuesTo(linkedMapOf()) { (_, episodes) -> episodes.toMutableSet() }
    localItems.forEach { item ->
        val season = item.season
        val episode = item.episode
        if (season != null && episode != null && retainsLocalWatchedEpisode(item)) {
            merged.getOrPut(item.contentId, ::linkedSetOf).add(season to episode)
        }
    }
    return merged
}

/**
 * Keeps a local episode position visible when the provider projection has nothing for that
 * episode, which happens because Trakt and Simkl delete the resume point on a stop scrobble at
 * 80% or more, before the watched state arrives.
 *
 * Only in-progress entries are retained. A local completed entry the provider lacks could
 * resurrect watched state removed elsewhere.
 *
 * Retention is durable rather than expiring, since a bounded lifetime would restore the blank card
 * it exists to prevent. The cost is that a provider hole is ambiguous, so a position removed on
 * another device stays visible until the local entry is cleared.
 */
internal fun mergeEpisodeProgressWithRetainedLocal(
    providerEntries: Map<Pair<Int, Int>, WatchProgress>,
    localEntries: Map<Pair<Int, Int>, WatchProgress>
): Map<Pair<Int, Int>, WatchProgress> {
    if (localEntries.isEmpty()) return providerEntries
    val merged = providerEntries.toMutableMap()
    localEntries.forEach { (key, progress) ->
        if (key !in providerEntries && progress.isInProgress()) {
            merged[key] = progress
        }
    }
    return merged
}
