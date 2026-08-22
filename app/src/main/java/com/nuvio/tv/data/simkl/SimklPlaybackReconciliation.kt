package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(): SimklSyncSnapshot {
    if (playback.isEmpty()) return this
    val watchedItems = toSimklWatchedProjection().items
    val retainedPlayback = playback.filterNot { session ->
        session.toWatchProgress(entries)?.let { progress ->
            entries.any { entry -> entry.hidesPlayback(progress) } ||
                watchedItems.any { watched -> watched.supersedes(progress) }
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

private fun WatchedItem.supersedes(progress: WatchProgress): Boolean {
    if (!contentType.equals(progress.contentType, ignoreCase = true)) return false
    if (season != progress.season || episode != progress.episode) return false
    val sameProviderItem = trackingProviderItemId
        ?.takeIf(String::isNotBlank)
        ?.equals(progress.trackingProviderItemId, ignoreCase = true) == true
    val sameContent = contentId.equals(progress.contentId, ignoreCase = true)
    return (sameProviderItem || sameContent) && watchedAt >= progress.lastWatched
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
