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
