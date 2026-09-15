package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class MdbListProgressProjection(snapshot: MdbListSyncSnapshot) {
    private val mediaIndex = MdbListMediaIndex(snapshot)
    private val watchedById = snapshot.watched.filter { it.type == MdbListItemType.EPISODE }
        .flatMap { record -> record.media.ids.aliases().map { it to record } }
        .groupBy({ it.first }, { it.second })
    private val droppedById = snapshot.dropped.flatMap { record ->
        mediaIndex.resolve(MdbListItemType.SHOW, record.ids).ids.aliases().map { it to record.season }
    }
        .groupBy({ it.first }, { it.second })
    private val historyByKey = snapshot.watched.associateBy(MdbListWatchedRecord::key)
    val watchedMovieIds = snapshot.watched.filter { it.type == MdbListItemType.MOVIE }
        .flatMapTo(linkedSetOf()) { it.media.ids.aliases() }
    val watchedItems = snapshot.watched.filter { it.type in setOf(MdbListItemType.MOVIE, MdbListItemType.EPISODE) }
        .map { record ->
            WatchedItem(
                contentId = record.media.ids.contentId,
                contentType = if (record.type == MdbListItemType.MOVIE) "movie" else "series",
                title = record.media.title ?: record.media.ids.contentId,
                season = record.season,
                episode = record.episode,
                watchedAt = mdbListTimestamp(record.watchedAt),
                poster = record.media.poster,
                releaseInfo = record.media.year?.toString(),
                trackingProviderId = "mdblist",
                trackingProviderItemId = record.media.ids.mdblist?.let { "mdblist:$it" }
            )
        }.sortedByDescending(WatchedItem::watchedAt)
    val watchedShowEpisodes = watchedById.mapValues { (_, records) ->
        records.mapNotNullTo(linkedSetOf()) { record ->
            record.season?.let { season -> record.episode?.let { season to it } }
        }
    }
    val showIdSiblings = snapshot.watched.filter { it.type != MdbListItemType.MOVIE }
        .map { it.media.ids.aliases() }.plus(snapshot.playback.filter { it.type != MdbListItemType.MOVIE }.map { it.media.ids.aliases() })
        .flatMap { aliases -> aliases.map { it to aliases } }.toMap()
    val progress = snapshot.playback.filter { session ->
        val watched = historyByKey["${session.type}:${session.media.ids.key}:${session.season ?: -1}:${session.episode ?: -1}"]
        session.progress < COMPLETION_PERCENT && !isHidden(session.media.ids.contentId, session.season) &&
            (watched == null || mdbListTimestamp(session.updatedAt) > mdbListTimestamp(watched.watchedAt))
    }.map { session ->
        progress(
            session.type, session.media, session.season, session.episode, session.episodeTitle,
            session.progress, session.updatedAt, session.runtimeMinutes, WatchProgress.SOURCE_REMOTE_PLAYBACK
        )
    }.groupBy { Triple(it.contentId, it.season, it.episode) }
        .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
        .sortedByDescending(WatchProgress::lastWatched)
    private val episodeProgressById = progress.filter { it.season != null && it.episode != null }
        .flatMap { item -> (showIdSiblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
        .groupBy({ it.first }, { it.second })
    private val seeds = snapshot.watched.filter { record ->
        record.type == MdbListItemType.EPISODE && record.season != null && record.season > 0 &&
            !isHidden(record.media.ids.contentId)
    }.map { record ->
        progress(
            record.type, record.media, record.season, record.episode, record.episodeTitle,
            100f, record.watchedAt, null, WatchProgress.SOURCE_REMOTE_HISTORY
        ).copy(excludedNextUpSeasons = droppedById[record.media.ids.contentId].orEmpty().filterNotNull().toSet())
    }

    fun nextUp(preferFurthest: Boolean): List<WatchProgress> =
        selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isHidden(contentId: String, season: Int? = null): Boolean =
        droppedById[contentId]?.any { it == null || (season != null && it == season) } == true

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    private fun progress(
        type: MdbListItemType,
        media: MdbListMedia,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        percent: Float,
        timestamp: String,
        runtime: Int?,
        source: String
    ) = WatchProgress(
        contentId = media.ids.contentId,
        contentType = if (type == MdbListItemType.MOVIE) "movie" else "series",
        name = media.title ?: media.ids.contentId,
        poster = media.poster,
        backdrop = media.backdrop,
        logo = null,
        videoId = if (type == MdbListItemType.MOVIE) media.ids.contentId else "${media.ids.contentId}:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 0,
        duration = (runtime ?: media.runtimeMinutes ?: 0).toLong() * 60_000L,
        lastWatched = mdbListTimestamp(timestamp),
        progressPercent = percent,
        source = source,
        trackingProviderId = "mdblist",
        trackingProviderItemId = media.ids.mdblist?.let { "mdblist:$it" },
        completionThresholdOverride = COMPLETION_PERCENT / 100f
    )

    companion object {
        const val COMPLETION_PERCENT = 80f
        val Empty = MdbListProgressProjection(MdbListSyncSnapshot(0))
    }
}
