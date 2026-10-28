package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class MdbListProgressProjection private constructor(
    internal val mediaIndex: MdbListMediaIndex,
    private val watchedById: Map<String, List<MdbListWatchedRecord>>,
    private val droppedById: Map<String, List<Int?>>,
    private val historyByKey: Map<String, MdbListWatchedRecord>,
    val watchedMovieIds: Set<String>,
    val watchedItems: List<WatchedItem>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    val progress: List<WatchProgress>,
    private val episodeProgressById: Map<String, List<WatchProgress>>,
    private val seeds: List<WatchProgress>,
    private val watchedShowIdSiblings: Map<String, Set<String>>
) {
    internal fun watchedEpisodesForShow(ids: MdbListIds): Sequence<MdbListWatchedRecord> =
        ids.aliases().asSequence().flatMap { alias -> watchedById[alias].orEmpty().asSequence() }

    fun withUpdatedPlayback(newPlayback: List<MdbListPlayback>): MdbListProgressProjection {
        val newShowIdSiblings = buildShowIdSiblings(watchedShowIdSiblings, newPlayback)
        val newDroppedById = if (droppedById.isEmpty()) {
            droppedById
        } else {
            var updatedMap: MutableMap<String, List<Int?>>? = null
            for (session in newPlayback) {
                if (session.type == MdbListItemType.MOVIE) continue
                val aliases = session.media.ids.aliases()
                val droppedSeasons = aliases.firstNotNullOfOrNull { droppedById[it] }
                if (droppedSeasons != null) {
                    for (alias in aliases) {
                        if (droppedById[alias] != droppedSeasons) {
                            val map = updatedMap ?: HashMap(droppedById).also { updatedMap = it }
                            map[alias] = droppedSeasons
                        }
                    }
                }
            }
            updatedMap ?: droppedById
        }

        fun isDropped(contentId: String, season: Int? = null): Boolean =
            newDroppedById[contentId]?.any { it == null || (season != null && it == season) } == true

        val newProgress = newPlayback.filter { session ->
            val watched = historyByKey["${session.type}:${session.media.ids.key}:${session.season ?: -1}:${session.episode ?: -1}"]
            session.progress < COMPLETION_PERCENT && !isDropped(session.media.ids.contentId, session.season) &&
                (watched == null || mdbListTimestamp(session.updatedAt) > mdbListTimestamp(watched.watchedAt))
        }.map { session ->
            progress(
                session.type, session.media, session.season, session.episode, session.episodeTitle,
                session.progress, session.updatedAt, session.runtimeMinutes, WatchProgress.SOURCE_REMOTE_PLAYBACK
            )
        }.groupBy { Triple(it.contentId, it.season, it.episode) }
            .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
            .sortedByDescending(WatchProgress::lastWatched)
        val newEpisodeProgressById = newProgress.filter { it.season != null && it.episode != null }
            .flatMap { item -> (newShowIdSiblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
            .groupBy({ it.first }, { it.second })

        return MdbListProgressProjection(
            mediaIndex = mediaIndex,
            watchedById = watchedById,
            droppedById = newDroppedById,
            historyByKey = historyByKey,
            watchedMovieIds = watchedMovieIds,
            watchedItems = watchedItems,
            watchedShowEpisodes = watchedShowEpisodes,
            showIdSiblings = newShowIdSiblings,
            progress = newProgress,
            episodeProgressById = newEpisodeProgressById,
            seeds = seeds,
            watchedShowIdSiblings = watchedShowIdSiblings
        )
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

    companion object {
        const val COMPLETION_PERCENT = 80f
        val Empty = MdbListProgressProjection(MdbListSyncSnapshot(0))

        operator fun invoke(snapshot: MdbListSyncSnapshot): MdbListProgressProjection {
            val mediaIndex = MdbListMediaIndex(snapshot)
            val watchedById = snapshot.watched.filter { it.type == MdbListItemType.EPISODE }
                .flatMap { record -> record.media.ids.aliases().map { it to record } }
                .groupBy({ it.first }, { it.second })
            val droppedById = snapshot.dropped.flatMap { record ->
                mediaIndex.resolve(MdbListItemType.SHOW, record.ids).ids.aliases().map { it to record.season }
            }.groupBy({ it.first }, { it.second })
            val historyByKey = snapshot.watched.associateBy(MdbListWatchedRecord::key)
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
            val watchedShowIdSiblings = buildMap<String, Set<String>> {
                for (record in snapshot.watched) {
                    if (record.type != MdbListItemType.MOVIE) {
                        val aliases = record.media.ids.aliases()
                        for (alias in aliases) {
                            put(alias, aliases)
                        }
                    }
                }
            }
            val showIdSiblings = buildShowIdSiblings(watchedShowIdSiblings, snapshot.playback)

            fun isDropped(contentId: String, season: Int? = null): Boolean =
                droppedById[contentId]?.any { it == null || (season != null && it == season) } == true

            val progress = snapshot.playback.filter { session ->
                val watched = historyByKey["${session.type}:${session.media.ids.key}:${session.season ?: -1}:${session.episode ?: -1}"]
                session.progress < COMPLETION_PERCENT && !isDropped(session.media.ids.contentId, session.season) &&
                    (watched == null || mdbListTimestamp(session.updatedAt) > mdbListTimestamp(watched.watchedAt))
            }.map { session ->
                progress(
                    session.type, session.media, session.season, session.episode, session.episodeTitle,
                    session.progress, session.updatedAt, session.runtimeMinutes, WatchProgress.SOURCE_REMOTE_PLAYBACK
                )
            }.groupBy { Triple(it.contentId, it.season, it.episode) }
                .map { (_, entries) -> entries.maxBy(WatchProgress::lastWatched) }
                .sortedByDescending(WatchProgress::lastWatched)
            val episodeProgressById = progress.filter { it.season != null && it.episode != null }
                .flatMap { item -> (showIdSiblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
                .groupBy({ it.first }, { it.second })
            val seeds = snapshot.watched.filter { record ->
                record.type == MdbListItemType.EPISODE && record.season != null && record.season > 0 &&
                    !isDropped(record.media.ids.contentId)
            }.map { record ->
                progress(
                    record.type, record.media, record.season, record.episode, record.episodeTitle,
                    100f, record.watchedAt, null, WatchProgress.SOURCE_REMOTE_HISTORY
                ).copy(excludedNextUpSeasons = droppedById[record.media.ids.contentId].orEmpty().filterNotNull().toSet())
            }

            return MdbListProgressProjection(
                mediaIndex = mediaIndex,
                watchedById = watchedById,
                droppedById = droppedById,
                historyByKey = historyByKey,
                watchedMovieIds = watchedMovieIds,
                watchedItems = watchedItems,
                watchedShowEpisodes = watchedShowEpisodes,
                showIdSiblings = showIdSiblings,
                progress = progress,
                episodeProgressById = episodeProgressById,
                seeds = seeds,
                watchedShowIdSiblings = watchedShowIdSiblings
            )
        }

        private fun buildShowIdSiblings(
            watchedShowIdSiblings: Map<String, Set<String>>,
            playback: List<MdbListPlayback>
        ): Map<String, Set<String>> {
            var overlay: MutableMap<String, Set<String>>? = null
            for (session in playback) {
                if (session.type == MdbListItemType.MOVIE) continue
                val aliases = session.media.ids.aliases()
                if (aliases.all { alias -> watchedShowIdSiblings[alias] == aliases && overlay?.containsKey(alias) != true }) {
                    continue
                }
                val map = overlay ?: LinkedHashMap<String, Set<String>>(aliases.size * 2).also { overlay = it }
                for (alias in aliases) {
                    map[alias] = aliases
                }
            }
            val resolvedOverlay = overlay ?: return watchedShowIdSiblings
            return OverlayShowIdSiblingsMap(watchedShowIdSiblings, resolvedOverlay)
        }

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
    }
}

private class OverlayShowIdSiblingsMap(
    private val base: Map<String, Set<String>>,
    private val overlay: Map<String, Set<String>>
) : AbstractMap<String, Set<String>>() {
    private val extraOverlayKeys: List<String> = overlay.keys.filter { !base.containsKey(it) }

    override val size: Int = base.size + extraOverlayKeys.size

    override fun isEmpty(): Boolean = size == 0

    override fun get(key: String): Set<String>? = overlay[key] ?: base[key]

    override fun containsKey(key: String): Boolean = overlay.containsKey(key) || base.containsKey(key)

    override val entries: Set<Map.Entry<String, Set<String>>> = object : AbstractSet<Map.Entry<String, Set<String>>>() {
        override val size: Int
            get() = this@OverlayShowIdSiblingsMap.size

        override fun iterator(): Iterator<Map.Entry<String, Set<String>>> = iterator {
            for (entry in base.entries) {
                val overrideValue = overlay[entry.key]
                if (overrideValue != null) {
                    yield( java.util.AbstractMap.SimpleImmutableEntry(entry.key, overrideValue))
                } else {
                    yield(entry)
                }
            }
            for (key in extraOverlayKeys) {
                val value = overlay[key] ?: continue
                yield(java.util.AbstractMap.SimpleImmutableEntry(key, value))
            }
        }
    }
}
