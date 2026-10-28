package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.WatchProgress

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot {
    if (playback.isEmpty()) return this
    val retainedPlayback = playback.filterNot { session ->
        val progress = session.toWatchProgress(entries, entryIndex) ?: return@filterNot false
        if (entryIndex != null && entryIndex.matchesEntries(entries)) {
            entryIndex.matchingEntriesForContent(
                entries = entries,
                contentId = progress.contentId,
                trackingProviderItemId = progress.trackingProviderItemId
            ).any { entry ->
                entry.hidesMatchedPlayback(progress) || entry.supersedesMatchedPlayback(progress)
            }
        } else {
            val parsedContentIds = parseTrackingExternalIds(progress.contentId)
            entries.any { entry ->
                entry.matchesContentWithParsedIds(
                    contentId = progress.contentId,
                    parsedContentIds = parsedContentIds,
                    trackingProviderItemId = progress.trackingProviderItemId
                ) && (entry.hidesMatchedPlayback(progress) || entry.supersedesMatchedPlayback(progress))
            }
        }
    }
    return if (retainedPlayback.size == playback.size) this else copy(playback = retainedPlayback)
}

internal fun SimklSyncSnapshot.isHiddenFromContinueWatching(
    contentId: String,
    entryIndex: SimklEntryIndex? = null
): Boolean {
    if (entryIndex != null && entryIndex.matchesEntries(entries)) {
        return entryIndex.matchingEntriesForContent(entries, contentId, trackingProviderItemId = null)
            .any { entry -> entry.status.hidesContinueWatching() }
    }
    val parsedContentIds = parseTrackingExternalIds(contentId)
    return entries.any { entry ->
        entry.status.hidesContinueWatching() &&
            entry.matchesContentWithParsedIds(
                contentId = contentId,
                parsedContentIds = parsedContentIds,
                trackingProviderItemId = null
            )
    }
}

internal fun SimklListStatus?.hidesContinueWatching(): Boolean =
    this == SimklListStatus.ON_HOLD || this == SimklListStatus.DROPPED

private fun SimklLibraryEntry.supersedesMatchedPlayback(progress: WatchProgress): Boolean {
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

private fun SimklLibraryEntry.hidesMatchedPlayback(progress: WatchProgress): Boolean =
    when {
        status.hidesContinueWatching() -> true
        status == SimklListStatus.COMPLETED ->
            parseSimklUtcEpochMs(lastWatchedAt)?.let { completedAt ->
                completedAt >= progress.lastWatched
            } == true
        else -> false
    }

internal fun SimklLibraryEntry.matchesContentWithParsedIds(
    contentId: String,
    parsedContentIds: TrackingExternalIds,
    trackingProviderItemId: String?
): Boolean {
    val currentMedia = media ?: return false
    val providerItemId = currentMedia.simklTrackingProviderItemId()
    val sameProviderItem = providerItemId != null &&
        providerItemId.equals(trackingProviderItemId, ignoreCase = true)
    if (sameProviderItem) return true
    if (currentMedia.canonicalContentId().equals(contentId, ignoreCase = true)) return true
    return parsedContentIds.sharesIdentityWith(currentMedia.toTrackingExternalIds())
}
