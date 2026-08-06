package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress

internal fun selectPreferredTrackingNextUpSeeds(
    candidates: List<WatchProgress>,
    preferFurthestEpisode: Boolean
): List<WatchProgress> = candidates
    .groupBy(WatchProgress::contentId)
    .mapNotNull { (_, items) ->
        selectPreferredTrackingNextUpSeed(items, preferFurthestEpisode)
    }
    .sortedWith(
        compareByDescending<WatchProgress>(WatchProgress::lastWatched)
            .thenByDescending { it.season ?: -1 }
            .thenByDescending { it.episode ?: -1 }
    )

internal fun selectPreferredTrackingNextUpSeed(
    candidates: List<WatchProgress>,
    preferFurthestEpisode: Boolean
): WatchProgress? {
    if (candidates.isEmpty()) return null
    val bestRank = candidates.minOf(::trackingNextUpSeedSourceRank)
    return candidates
        .asSequence()
        .filter { trackingNextUpSeedSourceRank(it) == bestRank }
        .maxWithOrNull(
            if (preferFurthestEpisode) {
                compareBy<WatchProgress>(
                    { it.season ?: -1 },
                    { it.episode ?: -1 },
                    WatchProgress::lastWatched
                )
            } else {
                compareBy<WatchProgress>(
                    WatchProgress::lastWatched,
                    { it.season ?: -1 },
                    { it.episode ?: -1 }
                )
            }
        )
}

private fun trackingNextUpSeedSourceRank(progress: WatchProgress): Int = when (progress.source) {
    WatchProgress.SOURCE_TRAKT_PLAYBACK -> 0
    WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> 0
    WatchProgress.SOURCE_TRAKT_HISTORY -> 1
    WatchProgress.SOURCE_LOCAL -> 2
    else -> 4
}
