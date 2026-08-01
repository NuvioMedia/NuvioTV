package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/**
 * Merges provider progress with local shadow copies.
 *
 * Provider entries win by default. Local rows are kept when:
 * - [retainsLocalProgress] says the id cannot be represented remotely (kitsu/mal/…), or
 * - the local row is still in-progress and the provider has no in-progress entry for the
 *   same content/season/episode (fills the gap after a Trakt/Simkl refresh that dropped
 *   playback while local saveProgress still has resume data — #2716).
 *
 * Local in-progress also replaces a completed-only remote row for the same key so a
 * rewatch that only saved locally still appears in Continue Watching.
 */
internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean
): List<WatchProgress> {
    val mergedByKey = LinkedHashMap<Triple<String, Int?, Int?>, WatchProgress>(providerEntries.size)
    providerEntries.forEach { progress ->
        mergedByKey[progress.projectionKey()] = progress
    }
    localEntries.forEach { progress ->
        val key = progress.projectionKey()
        val existing = mergedByKey[key]
        when {
            existing == null -> {
                if (retainsLocalProgress(progress.contentId) || progress.isInProgress()) {
                    mergedByKey[key] = progress
                }
            }
            progress.isInProgress() && !existing.isInProgress() -> {
                // Prefer local resume over completed-only remote for the same episode.
                mergedByKey[key] = progress
            }
        }
    }
    return mergedByKey.values.sortedByDescending(WatchProgress::lastWatched)
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
