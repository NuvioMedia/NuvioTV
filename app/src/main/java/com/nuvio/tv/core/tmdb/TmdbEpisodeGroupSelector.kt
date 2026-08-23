package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbEpisodeGroupSummary

/**
 * TMDB Episode Group Types & Standard Indexing Hierarchy:
 * - 1: Original Air Date (Broadcast Order - Standard default)
 * - 6: Production (Production / Season Order)
 * - 2: Absolute (Flat / Consecutive episode ordering)
 * - 3: DVD (Physical media release)
 * - 4: Digital (Digital streaming platforms)
 * - 5: Story Arc (Story / Chapter arcs)
 * - 7: TV (Syndication)
 */
fun selectBestTmdbEpisodeGroup(
    groups: List<TmdbEpisodeGroupSummary>
): TmdbEpisodeGroupSummary? {
    val validGroups = groups.filter { (it.episodeCount ?: 0) > 0 }
    if (validGroups.isEmpty()) return null

    return validGroups.sortedWith(
        compareBy<TmdbEpisodeGroupSummary> { tmdbEpisodeGroupTypeScore(it.type) }
            .thenByDescending { it.episodeCount ?: 0 }
            .thenByDescending { it.groupCount ?: 0 }
    ).firstOrNull()
}

fun tmdbEpisodeGroupTypeScore(type: Int?): Int = when (type) {
    1 -> 0
    6 -> 1
    2 -> 2
    3 -> 3
    4 -> 4
    5 -> 5
    7 -> 6
    else -> 99
}
