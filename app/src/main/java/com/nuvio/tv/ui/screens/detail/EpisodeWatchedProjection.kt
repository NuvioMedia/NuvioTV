package com.nuvio.tv.ui.screens.detail

internal fun resolveEpisodeWatchedState(
    currentlyWatched: Boolean,
    completedByProgress: Boolean,
    optimisticallyMarked: Boolean,
    optimisticallyUnmarked: Boolean,
    watchedByVideoId: Boolean?
): Boolean {
    if (watchedByVideoId == true && !optimisticallyUnmarked) return true
    if (
        watchedByVideoId == false &&
        currentlyWatched &&
        !completedByProgress &&
        !optimisticallyMarked
    ) {
        return false
    }
    return currentlyWatched
}

/**
 * Content IDs that may hold Nuvio Sync watched/progress rows for the open details page.
 *
 * Playback often writes under the navigation/catalog id (e.g. `tmdb:123` or an addon id),
 * while the details screen later prefers the canonical meta id (usually IMDB `tt…`).
 * Looking up only one of those keys leaves episode ticks empty even though Continue
 * Watching and mobile still see the same history.
 */
internal fun detailWatchedContentIds(
    navigationItemId: String,
    effectiveContentId: String,
    metaId: String?,
    metaImdbId: String?
): List<String> {
    val result = ArrayList<String>(4)
    fun addId(raw: String?) {
        val id = raw?.trim().orEmpty()
        if (id.isNotEmpty() && id !in result) {
            result.add(id)
        }
    }
    addId(effectiveContentId)
    addId(navigationItemId)
    addId(metaId)
    addId(metaImdbId)
    return result
}

internal fun mergeEpisodeKeySets(
    sets: Iterable<Set<Pair<Int, Int>>>
): Set<Pair<Int, Int>> {
    val merged = linkedSetOf<Pair<Int, Int>>()
    for (set in sets) {
        merged.addAll(set)
    }
    return merged
}
