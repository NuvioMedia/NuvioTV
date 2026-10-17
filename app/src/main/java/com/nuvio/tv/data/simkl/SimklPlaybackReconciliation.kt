package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(): SimklSyncSnapshot {
    if (playback.isEmpty()) return this
    val retainedPlayback = playback.filterNot { session ->
        session.toWatchProgress(entries)?.let { progress ->
            entries.any { entry ->
                entry.hidesPlayback(progress) || entry.supersedesPlayback(progress)
            }
        } == true
    }
    return if (retainedPlayback.size == playback.size) this else copy(playback = retainedPlayback)
}

internal fun SimklSyncSnapshot.isHiddenFromContinueWatching(contentId: String): Boolean =
    entries.any { entry ->
        entry.status.hidesContinueWatching() &&
            entry.matchesContent(contentId = contentId, trackingProviderItemId = null)
    }

internal fun SimklListStatus?.hidesContinueWatching(): Boolean =
    this == SimklListStatus.ON_HOLD || this == SimklListStatus.DROPPED

private fun SimklLibraryEntry.supersedesPlayback(progress: WatchProgress): Boolean {
    if (
        !matchesContent(
            contentId = progress.contentId,
            trackingProviderItemId = progress.trackingProviderItemId
        )
    ) {
        return false
    }
    if (isMovieEntry()) {
        if (!progress.contentType.equals("movie", ignoreCase = true)) return false
        if (progress.season != null || progress.episode != null) return false
        if (lastWatchedAt == null && status != SimklListStatus.COMPLETED) return false
        val watchedAt = parseSimklUtcEpochMs(lastWatchedAt)
            ?: parseSimklUtcEpochMs(addedToWatchlistAt)
            ?: 0L
        return watchedAt >= progress.lastWatched
    }
    if (progress.contentType.equals("movie", ignoreCase = true)) return false
    val targetSeason = progress.season ?: return false
    val targetEpisode = progress.episode ?: return false
    seasons.forEach { season ->
        season.episodes.forEach { episode ->
            val seasonNumber = episode.tvdb?.season ?: season.number
            val episodeNumber = episode.tvdb?.episode ?: episode.number
            if (seasonNumber == targetSeason && episodeNumber == targetEpisode) {
                val episodeWatchedAt = parseSimklUtcEpochMs(episode.watchedAt) ?: return@forEach
                if (episodeWatchedAt >= progress.lastWatched) {
                    return true
                }
            }
        }
    }
    return false
}

private fun SimklLibraryEntry.hidesPlayback(progress: WatchProgress): Boolean {
    if (
        !matchesContent(
            contentId = progress.contentId,
            trackingProviderItemId = progress.trackingProviderItemId
        )
    ) {
        return false
    }
    return when {
        status.hidesContinueWatching() -> true
        status == SimklListStatus.COMPLETED ->
            parseSimklUtcEpochMs(lastWatchedAt)?.let { completedAt ->
                completedAt >= progress.lastWatched
            } == true
        else -> false
    }
}

private fun SimklLibraryEntry.matchesContent(
    contentId: String,
    trackingProviderItemId: String?
): Boolean {
    val providerItemId = media?.simklTrackingProviderItemId()
    val sameProviderItem = providerItemId != null &&
        providerItemId.equals(trackingProviderItemId, ignoreCase = true)
    return sameProviderItem || matchesSimklContentId(contentId)
}
