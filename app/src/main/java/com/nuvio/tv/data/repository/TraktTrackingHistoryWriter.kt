package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktTrackingHistoryWriter @Inject constructor(
    private val service: TraktProgressService,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.TRAKT

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        val progress = items.map { item -> item.media.toWatchProgress(item.watchedAtEpochMs) }
        progress.groupBy { item -> item.contentId }.values.forEach { group ->
            if (group.size > 1 && group.all { it.season != null && it.episode != null }) {
                service.markSeasonWatchedBatch(group)
            } else {
                group.forEach { item ->
                    service.markAsWatched(item, item.name.takeIf(String::isNotBlank), null)
                }
            }
        }
        return TrackingMutationResult(items.size)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        items.groupBy(TrackingMediaReference::stableKey).values.forEach { group ->
            val first = group.first()
            val episodes = group.mapNotNull { media ->
                media.episode?.season?.let { season -> season to media.episode.number }
            }
            if (episodes.size == group.size && episodes.size > 1) {
                service.removeSeasonFromHistoryBatch(first.contentId(), episodes)
            } else {
                group.forEach { media ->
                    service.removeFromHistory(
                        contentId = media.contentId(),
                        videoId = media.catalog?.videoId,
                        season = media.episode?.season,
                        episode = media.episode?.number
                    )
                }
            }
        }
        return TrackingMutationResult(items.size)
    }

    private fun TrackingMediaReference.toWatchProgress(watchedAtEpochMs: Long?): WatchProgress {
        val contentId = contentId()
        val contentType = if (kind == TrackingMediaKind.MOVIE) "movie" else "series"
        val season = episode?.season
        val episodeNumber = episode?.number
        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = title?.takeIf(String::isNotBlank) ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = catalog?.videoId
                ?: if (season != null && episodeNumber != null) "$contentId:$season:$episodeNumber" else contentId,
            season = season,
            episode = episodeNumber,
            episodeTitle = episode?.title,
            position = 1L,
            duration = 1L,
            lastWatched = watchedAtEpochMs ?: System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun TrackingMediaReference.contentId(): String = catalog?.contentId
        ?.takeIf(String::isNotBlank)
        ?: ids.preferredContentId()
        ?: title?.trim()?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Trakt history mutation requires an identity")

    private fun TrackingExternalIds.preferredContentId(): String? =
        imdb?.takeIf(String::isNotBlank)
            ?: tmdb?.let { "tmdb:$it" }
            ?: trakt?.let { "trakt:$it" }
            ?: tvdb?.takeIf(String::isNotBlank)?.let { "tvdb:$it" }
}
