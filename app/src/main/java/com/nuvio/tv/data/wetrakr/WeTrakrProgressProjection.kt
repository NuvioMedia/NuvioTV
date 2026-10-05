package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class WeTrakrProgressProjection(snapshot: WeTrakrSyncSnapshot) {
    private val episodes = snapshot.watched.filter { it.type == WeTrakrItemType.EPISODE }
    private val watchedByAlias = episodes
        .flatMap { record -> record.media.ids.aliases().map { it to record } }
        .groupBy({ it.first }, { it.second })
    val watchedMovieIds: Set<String> = snapshot.watched.filter { it.type == WeTrakrItemType.MOVIE }
        .flatMapTo(linkedSetOf()) { it.media.ids.aliases() }
    val watchedItems: List<WatchedItem> = snapshot.watched.map { record ->
        val contentId = record.media.ids.contentId.orEmpty()
        WatchedItem(
            contentId = contentId,
            contentType = if (record.type == WeTrakrItemType.MOVIE) "movie" else "series",
            title = record.media.title ?: contentId,
            season = record.season,
            episode = record.episode,
            watchedAt = weTrakrTimestamp(record.watchedAt),
            poster = record.media.poster,
            releaseInfo = record.media.year?.toString(),
            trackingProviderId = PROVIDER_ID,
            trackingProviderItemId = record.media.id?.let { "$PROVIDER_ID:$it" }
        )
    }.sortedByDescending(WatchedItem::watchedAt)
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = watchedByAlias.mapValues { (_, records) ->
        records.mapNotNullTo(linkedSetOf()) { record ->
            record.season?.let { season -> record.episode?.let { season to it } }
        }
    }
    val showIdSiblings: Map<String, Set<String>> = (episodes.map { it.media.ids.aliases() } +
        snapshot.playback.filter { it.type == WeTrakrItemType.EPISODE }.map { it.media.ids.aliases() })
        .flatMap { aliases -> aliases.map { it to aliases } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, groups) -> groups.flatten().toSet() }
    val progress: List<WatchProgress> = snapshot.playback.filter { session ->
        session.progress < COMPLETION_PERCENT && watchedAfter(snapshot, session) == null
    }.map { session ->
        progress(
            session.type, session.media, session.season, session.episode, session.episodeTitle,
            session.progress, session.updatedAt, session.runtimeSeconds, WatchProgress.SOURCE_REMOTE_PLAYBACK
        )
    }.groupBy { Triple(it.contentId, it.season, it.episode) }
        .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
        .sortedByDescending(WatchProgress::lastWatched)
    private val episodeProgressById = progress.filter { it.season != null && it.episode != null }
        .flatMap { item -> (showIdSiblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
        .groupBy({ it.first }, { it.second })
    private val seeds = episodes.filter { record -> record.season != null && record.season > 0 }.map { record ->
        progress(
            record.type, record.media, record.season, record.episode, null,
            100f, record.watchedAt, null, WatchProgress.SOURCE_REMOTE_HISTORY
        )
    }

    fun nextUp(preferFurthest: Boolean): List<WatchProgress> =
        selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    private fun watchedAfter(snapshot: WeTrakrSyncSnapshot, session: WeTrakrPlayback): WeTrakrWatchedRecord? =
        snapshot.watched.firstOrNull { record ->
            record.type == session.type && record.media.ids.matches(session.media.ids) &&
                record.season == session.season && record.episode == session.episode &&
                weTrakrTimestamp(record.watchedAt) >= weTrakrTimestamp(session.updatedAt)
        }

    private fun progress(
        type: WeTrakrItemType,
        media: WeTrakrMedia,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        percent: Float,
        timestamp: String,
        runtimeSeconds: Long?,
        source: String
    ): WatchProgress {
        val contentId = media.ids.contentId.orEmpty()
        val durationMs = runtimeSeconds?.times(1_000L) ?: ((media.runtimeMinutes ?: 0).toLong() * 60_000L)
        return WatchProgress(
            contentId = contentId,
            contentType = if (type == WeTrakrItemType.MOVIE) "movie" else "series",
            name = media.title ?: contentId,
            poster = media.poster,
            backdrop = media.backdrop,
            logo = null,
            videoId = if (type == WeTrakrItemType.MOVIE) contentId else "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            position = (durationMs * percent / 100f).toLong(),
            duration = durationMs,
            lastWatched = weTrakrTimestamp(timestamp),
            progressPercent = percent,
            source = source,
            trackingProviderId = PROVIDER_ID,
            trackingProviderItemId = media.id?.let { "$PROVIDER_ID:$it" },
            completionThresholdOverride = COMPLETION_PERCENT / 100f
        )
    }

    companion object {
        const val COMPLETION_PERCENT = 80f
        const val PROVIDER_ID = "wetrakr"
        val Empty = WeTrakrProgressProjection(WeTrakrSyncSnapshot(0))
    }
}
