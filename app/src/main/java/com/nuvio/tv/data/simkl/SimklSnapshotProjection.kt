package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class SimklSnapshotProjection private constructor(
    val library: SimklLibraryProjection,
    val watched: SimklWatchedProjection,
    val progress: List<WatchProgress>,
    val watchedMovieIds: Set<String>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    private val recentNextUp: List<WatchProgress>,
    private val furthestNextUp: List<WatchProgress>,
    private val canonicalIdByAlias: Map<String, String>,
    private val watchedItemsByContent: Map<String, List<WatchedItem>>,
    private val watchedKeys: Set<SimklProjectionItemKey>,
    private val playbackByEpisode: Map<SimklProjectionItemKey, WatchProgress>,
    private val hiddenContentIds: Set<String>,
    private val watchedAnimeEpisodes: Map<String, Set<Int>>,
    private val membershipByContent: Map<SimklProjectionMembershipKey, SimklProjectionMembership>
) {
    fun nextUp(preferFurthestEpisode: Boolean): List<WatchProgress> =
        if (preferFurthestEpisode) furthestNextUp else recentNextUp

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> {
        val key = contentId.simklLookupKey()
        val resolvedKey = canonicalIdByAlias[key]?.simklLookupKey()
        val matchKeys = setOfNotNull(key, resolvedKey)
        val completed = linkedMapOf<Pair<Int, Int>, WatchProgress>()
        matchKeys.forEach { matchKey ->
            watchedItemsByContent[matchKey].orEmpty().forEach { item ->
                completed[requireNotNull(item.season) to requireNotNull(item.episode)] =
                    item.toSimklCompletedProgress()
            }
        }
        progress.filter { item ->
            item.contentId.simklLookupKey() in matchKeys && item.season != null && item.episode != null
        }.forEach { item ->
            completed[requireNotNull(item.season) to requireNotNull(item.episode)] = item
        }
        return completed
    }

    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Boolean {
        val directKey = SimklProjectionItemKey(contentId.simklLookupKey(), season, episode)
        val playback = playbackByEpisode[directKey]
        if (playback != null && playback.progressPercentage > 0f && !playback.isCompleted()) return false
        if (directKey in watchedKeys) return true
        val resolvedId = canonicalIdByAlias[directKey.contentId]
        if (resolvedId != null) {
            val resolvedKey = SimklProjectionItemKey(resolvedId.simklLookupKey(), season, episode)
            if (resolvedKey in watchedKeys) return true
        }
        return videoId != null && episode != null && isWatchedByVideoId(videoId, episode)
    }

    fun isHidden(contentId: String): Boolean = contentId.simklLookupKey() in hiddenContentIds

    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean {
        val parsed = parseSimklProjectionVideoId(videoId) ?: return false
        return (parsed.episode ?: episode) in watchedAnimeEpisodes[parsed.lookupKey].orEmpty()
    }

    fun membershipKey(contentId: String, contentType: String): String? =
        membershipByContent[
            SimklProjectionMembershipKey(contentId.simklLookupKey(), contentType.simklLookupKey())
        ]?.listKey

    companion object {
        val Empty = create(SimklSyncSnapshot())

        fun create(snapshot: SimklSyncSnapshot): SimklSnapshotProjection {
            val library = snapshot.toSimklLibraryProjection()
            val watched = snapshot.toSimklWatchedProjection()
            val progress = snapshot.toSimklProgressEntries()
            val canonicalIdByAlias = linkedMapOf<String, String>()
            val siblings = linkedMapOf<String, MutableSet<String>>()
            val hiddenContentIds = linkedSetOf<String>()
            val membershipByContent = linkedMapOf<SimklProjectionMembershipKey, SimklProjectionMembership>()
            val watchedAnimeEpisodes = linkedMapOf<String, Set<Int>>()

            snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val ids = media.simklTrackingContentIds()
                val canonicalId = media.canonicalContentId()
                ids.forEach { id ->
                    canonicalId?.let { canonicalIdByAlias.putIfAbsent(id.simklLookupKey(), it) }
                    siblings.getOrPut(id) { linkedSetOf() }.addAll(ids - id)
                }
                if (entry.status.hidesContinueWatching()) {
                    ids.mapTo(hiddenContentIds, String::simklLookupKey)
                }
                val listKey = entry.status?.let { status ->
                    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.status == status }?.key
                }
                val membershipType = if (entry.mediaType == SimklMediaType.MOVIES) "movie" else "series"
                ids.forEach { id ->
                    membershipByContent.putIfAbsent(
                        SimklProjectionMembershipKey(id.simklLookupKey(), membershipType),
                        SimklProjectionMembership(listKey)
                    )
                }
                if (entry.mediaType == SimklMediaType.ANIME) {
                    val watchedEpisodes = entry.seasons.asSequence()
                        .flatMap { season -> season.episodes.asSequence() }
                        .filter { episode -> episode.watchedAt != null }
                        .mapNotNull(SimklEpisode::number)
                        .toSet()
                    SIMKL_PROJECTION_ANIME_IDS.forEach { prefix ->
                        media.ids.idValue(prefix)?.toLongOrNull()?.let { id ->
                            watchedAnimeEpisodes.putIfAbsent("$prefix:$id", watchedEpisodes)
                        }
                    }
                }
            }

            val watchedKeys = watched.items.mapTo(linkedSetOf()) { item ->
                SimklProjectionItemKey(item.contentId.simklLookupKey(), item.season, item.episode)
            }
            val playbackByEpisode = linkedMapOf<SimklProjectionItemKey, WatchProgress>()
            progress.forEach { item ->
                playbackByEpisode.putIfAbsent(
                    SimklProjectionItemKey(item.contentId.simklLookupKey(), item.season, item.episode),
                    item
                )
            }
            val watchedItemsByContent = watched.items
                .filter { item -> item.season != null && item.episode != null }
                .groupBy { item -> item.contentId.simklLookupKey() }
            val nextUpCandidates = watched.items.asSequence()
                .filterNot { item -> item.contentId.simklLookupKey() in hiddenContentIds }
                .filter { item -> item.season != null && item.episode != null && item.season != 0 }
                .map(WatchedItem::toSimklCompletedProgress)
                .toList()

            return SimklSnapshotProjection(
                library = library,
                watched = watched,
                progress = progress,
                watchedMovieIds = snapshot.simklWatchedMovieIds(progress),
                watchedShowEpisodes = watched.items
                    .filter { item -> item.season != null && item.episode != null }
                    .groupBy(WatchedItem::contentId)
                    .mapValues { (_, items) ->
                        items.mapTo(linkedSetOf()) { item ->
                            requireNotNull(item.season) to requireNotNull(item.episode)
                        }
                    },
                showIdSiblings = siblings.mapValues { (_, ids) -> ids.toSet() },
                recentNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, false),
                furthestNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, true),
                canonicalIdByAlias = canonicalIdByAlias,
                watchedItemsByContent = watchedItemsByContent,
                watchedKeys = watchedKeys,
                playbackByEpisode = playbackByEpisode,
                hiddenContentIds = hiddenContentIds,
                watchedAnimeEpisodes = watchedAnimeEpisodes,
                membershipByContent = membershipByContent
            )
        }
    }
}

internal fun SimklSyncSnapshot.toSimklNextUpSeeds(
    preferFurthestEpisode: Boolean
): List<WatchProgress> = SimklSnapshotProjection.create(this).nextUp(preferFurthestEpisode)

private fun SimklSyncSnapshot.simklWatchedMovieIds(progress: List<WatchProgress>): Set<String> {
    val watched = linkedSetOf<String>()
    entries.forEach { entry ->
        if (!entry.isMovieEntry()) return@forEach
        if (entry.lastWatchedAt == null && entry.status != SimklListStatus.COMPLETED) return@forEach
        val media = entry.media ?: return@forEach
        media.canonicalContentId()?.let(watched::add)
        media.ids.idValue("imdb")?.takeIf(String::isNotBlank)?.let(watched::add)
        media.ids.idValue("tmdb")?.takeIf(String::isNotBlank)?.let { watched.add("tmdb:$it") }
        media.ids.idValue("tvdb")?.takeIf(String::isNotBlank)?.let { watched.add("tvdb:$it") }
        if (entry.mediaType == SimklMediaType.ANIME) {
            media.ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { watched.add("mal:$it") }
            media.ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { watched.add("anidb:$it") }
            media.ids.idValue("anilist")?.takeIf(String::isNotBlank)?.let { watched.add("anilist:$it") }
            media.ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { watched.add("kitsu:$it") }
        }
    }
    progress.filter { item ->
        item.contentType == "movie" && item.progressPercentage > 0f && !item.isCompleted()
    }.forEach { item -> watched.remove(item.contentId) }
    return watched
}

private fun SimklMedia.simklTrackingContentIds(): Set<String> = buildSet {
    canonicalContentId()?.let(::add)
    ids.idValue("imdb")?.let(::add)
    ids.idValue("tmdb")?.let { add("tmdb:$it") }
    ids.idValue("tvdb")?.let { add("tvdb:$it") }
    ids.idValue("mal")?.let { add("mal:$it") }
    ids.idValue("anidb")?.let { add("anidb:$it") }
    ids.idValue("anilist")?.let { add("anilist:$it") }
    ids.idValue("kitsu")?.let { add("kitsu:$it") }
    ids.simklIdValue()?.let { add("simkl:$it") }
}

private fun WatchedItem.toSimklCompletedProgress(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = contentType,
    name = title,
    poster = poster,
    backdrop = null,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:$season:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = watchedAt,
    progressPercent = 100f,
    source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
    trackingProviderId = trackingProviderId,
    trackingProviderItemId = trackingProviderItemId,
    trackingSourceUrl = trackingSourceUrl
)

private fun parseSimklProjectionVideoId(videoId: String): SimklProjectionVideoId? {
    val trimmed = videoId.trim()
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in SIMKL_PROJECTION_ANIME_IDS) return null
    val remainder = trimmed.substringAfter(':', "")
    val id = remainder.substringBefore(':').toLongOrNull() ?: return null
    val episode = remainder.substringAfter(':', "").substringBefore(':').toIntOrNull()
    return SimklProjectionVideoId("$prefix:$id", episode)
}

private fun String.simklLookupKey(): String = trim().lowercase()

private data class SimklProjectionItemKey(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

private data class SimklProjectionMembershipKey(val contentId: String, val contentType: String)

private data class SimklProjectionMembership(val listKey: String?)

private data class SimklProjectionVideoId(val lookupKey: String, val episode: Int?)

private val SIMKL_PROJECTION_ANIME_IDS = setOf("mal", "anidb", "anilist", "kitsu")
