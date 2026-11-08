package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds

internal data class SimklIndexedSnapshotUpdate(
    val snapshot: SimklSyncSnapshot,
    val entryIndex: SimklEntryIndex?
)

internal fun SimklSyncSnapshot.applyScrobbleResult(
    result: SimklScrobbleResult,
    committedAtEpochMs: Long,
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot = applyScrobbleResultWithIndex(result, committedAtEpochMs, entryIndex).snapshot

internal fun SimklSyncSnapshot.applyScrobbleResultWithIndex(
    result: SimklScrobbleResult,
    committedAtEpochMs: Long,
    entryIndex: SimklEntryIndex? = null
): SimklIndexedSnapshotUpdate {
    val localCommittedAt = committedAtEpochMs.epochMsToUtcIso()
        ?: return SimklIndexedSnapshotUpdate(this, entryIndex)
    return when (result.outcome) {
        SimklScrobbleOutcome.START -> SimklIndexedSnapshotUpdate(
            snapshot = this.reconcileWatchedPlayback(entryIndex),
            entryIndex = entryIndex
        )
        SimklScrobbleOutcome.PAUSE -> SimklIndexedSnapshotUpdate(
            snapshot = withPausedScrobble(result, localCommittedAt, entryIndex)
                .reconcileWatchedPlayback(entryIndex),
            entryIndex = entryIndex
        )
        SimklScrobbleOutcome.SCROBBLE -> withCompletedScrobble(
            result = result,
            committedAt = result.watchedAt
                ?.takeIf { value -> parseSimklUtcEpochMs(value) != null }
                ?: localCommittedAt,
            entryIndex = entryIndex
        )
    }
}

private fun SimklSyncSnapshot.withPausedScrobble(
    result: SimklScrobbleResult,
    committedAt: String,
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot {
    val existingEntry = entries.matchingEntry(result, entryIndex)
    val targetIds = result.media.toTrackingExternalIds()
    val existingSession = playback.firstOrNull { session ->
        session.media?.matchesTargetWithIds(
            session.media?.toTrackingExternalIds() ?: TrackingExternalIds(),
            result.media,
            targetIds
        ) == true
    }
    val media = result.media
        .mergeMissing(existingEntry?.media)
        .mergeMissing(existingSession?.media)
    val mediaType = existingEntry?.mediaType ?: result.mediaType
    val isAnimeMovie = mediaType == SimklMediaType.ANIME &&
        (existingEntry?.animeType == "movie" || result.episode == null)
    val session = SimklPlaybackSession(
        id = result.playbackId ?: existingSession?.id,
        progress = result.progress,
        pausedAt = committedAt,
        type = if (mediaType == SimklMediaType.MOVIES || isAnimeMovie) "movie" else "episode",
        episode = result.episode?.mergeMissing(existingSession?.episode),
        show = media.takeIf { mediaType == SimklMediaType.SHOWS },
        anime = media.takeIf { mediaType == SimklMediaType.ANIME && !isAnimeMovie },
        movie = media.takeIf { mediaType == SimklMediaType.MOVIES || isAnimeMovie }
    )
    return copy(
        playback = playback.filterNot { candidate ->
            val candidateMedia = candidate.media ?: return@filterNot false
            candidateMedia.matchesTargetWithIds(
                candidateMedia.toTrackingExternalIds(),
                result.media,
                targetIds
            )
        } + session
    )
}

private fun SimklSyncSnapshot.withCompletedScrobble(
    result: SimklScrobbleResult,
    committedAt: String,
    entryIndex: SimklEntryIndex? = null
): SimklIndexedSnapshotUpdate {
    val updatedEntries = entries.toMutableList()
    val index = entries.indexOfMatchingEntry(result, entryIndex)
    val existing = updatedEntries.getOrNull(index)
    val updated = when {
        result.mediaType == SimklMediaType.MOVIES ||
            (result.mediaType == SimklMediaType.ANIME &&
                (existing?.animeType == "movie" || result.episode == null)) -> existing
            ?.withWatchedMovie(result, committedAt)
            ?: result.toWatchedMovieEntry(committedAt)
        else -> existing
            ?.withWatchedEpisode(result, committedAt)
            ?: result.toWatchedSeriesEntry(committedAt)
    }
    if (index >= 0) {
        updatedEntries[index] = updated
    } else {
        updatedEntries += updated
    }
    val targetIds = result.media.toTrackingExternalIds()
    val updatedIndex = entryIndex?.withUpdatedEntry(
        newEntries = updatedEntries,
        index = if (index >= 0) index else updatedEntries.lastIndex,
        updatedEntry = updated
    )
    val reconciledSnapshot = copy(
        entries = updatedEntries,
        playback = playback.filterNot { session ->
            val sessionMedia = session.media ?: return@filterNot false
            sessionMedia.matchesTargetWithIds(
                sessionMedia.toTrackingExternalIds(),
                result.media,
                targetIds
            )
        }
    ).reconcileWatchedPlayback(updatedIndex)
    return SimklIndexedSnapshotUpdate(reconciledSnapshot, updatedIndex)
}

private fun SimklLibraryEntry.withWatchedMovie(
    result: SimklScrobbleResult,
    committedAt: String
): SimklLibraryEntry = copy(
    mediaType = result.mediaType,
    animeType = if (result.mediaType == SimklMediaType.ANIME) "movie" else animeType,
    lastWatchedAt = committedAt,
    status = SimklListStatus.COMPLETED,
    movie = result.media.mergeMissing(media),
    show = null
)

private fun SimklScrobbleResult.toWatchedMovieEntry(committedAt: String): SimklLibraryEntry =
    SimklLibraryEntry(
        mediaType = mediaType,
        animeType = if (mediaType == SimklMediaType.ANIME) "movie" else null,
        lastWatchedAt = committedAt,
        status = SimklListStatus.COMPLETED,
        movie = media
    )

private fun SimklLibraryEntry.withWatchedEpisode(
    result: SimklScrobbleResult,
    committedAt: String
): SimklLibraryEntry {
    val target = result.episode ?: return this
    val update = seasons.withWatchedEpisode(target, committedAt)
    return copy(
        lastWatchedAt = committedAt,
        watchedEpisodesCount = watchedEpisodesCount + if (update.wasAlreadyWatched) 0 else 1,
        show = result.media.mergeMissing(media),
        movie = null,
        seasons = update.seasons
    )
}

private fun SimklScrobbleResult.toWatchedSeriesEntry(committedAt: String): SimklLibraryEntry {
    val target = episode
    val update = if (target == null) {
        EpisodeUpdate(emptyList(), wasAlreadyWatched = false)
    } else {
        emptyList<SimklSeason>().withWatchedEpisode(target, committedAt)
    }
    return SimklLibraryEntry(
        mediaType = mediaType,
        lastWatchedAt = committedAt,
        status = SimklListStatus.WATCHING,
        watchedEpisodesCount = if (target == null) 0 else 1,
        show = media,
        seasons = update.seasons
    )
}

private data class EpisodeUpdate(
    val seasons: List<SimklSeason>,
    val wasAlreadyWatched: Boolean
)

private fun List<SimklSeason>.withWatchedEpisode(
    target: SimklPlaybackEpisode,
    committedAt: String
): EpisodeUpdate {
    val targetNumber = target.number ?: return EpisodeUpdate(this, wasAlreadyWatched = false)
    val targetSeason = target.season ?: 1
    val targetMapping = if (target.tvdbSeason != null && target.tvdbNumber != null) {
        SimklEpisodeMapping(target.tvdbSeason, target.tvdbNumber)
    } else {
        null
    }
    var matched = false
    var wasAlreadyWatched = false
    val updated = map { season ->
        val updatedEpisodes = season.episodes.map { episode ->
            if (!episode.matches(targetSeason, targetNumber, targetMapping, season.number)) {
                episode
            } else {
                matched = true
                wasAlreadyWatched = episode.watchedAt != null
                episode.copy(
                    watchedAt = committedAt,
                    tvdb = targetMapping ?: episode.tvdb
                )
            }
        }
        if (matched || season.number != targetSeason) {
            season.copy(episodes = updatedEpisodes)
        } else {
            matched = true
            season.copy(
                episodes = updatedEpisodes + SimklEpisode(
                    number = targetNumber,
                    watchedAt = committedAt,
                    tvdb = targetMapping
                )
            )
        }
    }.toMutableList()
    if (!matched) {
        updated += SimklSeason(
            number = targetSeason,
            episodes = listOf(
                SimklEpisode(
                    number = targetNumber,
                    watchedAt = committedAt,
                    tvdb = targetMapping
                )
            )
        )
    }
    return EpisodeUpdate(updated, wasAlreadyWatched)
}

private fun SimklEpisode.matches(
    targetSeason: Int,
    targetNumber: Int,
    targetMapping: SimklEpisodeMapping?,
    seasonNumber: Int?
): Boolean {
    if (
        targetMapping?.season != null &&
        targetMapping.episode != null &&
        tvdb?.season == targetMapping.season &&
        tvdb.episode == targetMapping.episode
    ) {
        return true
    }
    return seasonNumber == targetSeason && number == targetNumber
}

private fun SimklPlaybackEpisode.mergeMissing(
    fallback: SimklPlaybackEpisode?
): SimklPlaybackEpisode {
    if (fallback == null) return this
    return copy(
        season = season ?: fallback.season,
        number = number ?: fallback.number,
        title = title?.takeIf(String::isNotBlank) ?: fallback.title,
        tvdbSeason = tvdbSeason ?: fallback.tvdbSeason,
        tvdbNumber = tvdbNumber ?: fallback.tvdbNumber
    )
}

private fun List<SimklLibraryEntry>.matchingEntry(
    result: SimklScrobbleResult,
    entryIndex: SimklEntryIndex? = null
): SimklLibraryEntry? = getOrNull(indexOfMatchingEntry(result, entryIndex))

private fun List<SimklLibraryEntry>.indexOfMatchingEntry(
    result: SimklScrobbleResult,
    entryIndex: SimklEntryIndex? = null
): Int = entryIndex?.indexOfMatchingEntry(this, result) ?: indexOfMatchingEntryLinear(result)

internal fun List<SimklLibraryEntry>.indexOfMatchingEntryLinear(
    result: SimklScrobbleResult
): Int {
    val target = result.media
    val targetIds = target.toTrackingExternalIds()
    var fallbackIndex = -1
    for (index in indices) {
        val entry = this[index]
        val media = entry.media ?: continue
        if (media.matchesTargetWithIds(media.toTrackingExternalIds(), target, targetIds)) {
            if (entry.mediaType == result.mediaType) return index
            if (fallbackIndex < 0) fallbackIndex = index
        }
    }
    return fallbackIndex
}

internal fun SimklMedia.matchesTarget(target: SimklMedia): Boolean =
    matchesTargetWithIds(toTrackingExternalIds(), target, target.toTrackingExternalIds())

internal fun SimklMedia.matchesTargetWithIds(
    candidateIds: TrackingExternalIds,
    target: SimklMedia,
    targetIds: TrackingExternalIds
): Boolean {
    candidateIds.comparableMatch(targetIds)?.let { return it }
    return title?.trim()?.equals(target.title?.trim(), ignoreCase = true) == true &&
        (year == null || target.year == null || year == target.year)
}

private fun TrackingExternalIds.comparableMatch(target: TrackingExternalIds): Boolean? {
    if (simkl != null && target.simkl != null) return simkl == target.simkl
    if (!imdb.isNullOrBlank() && !target.imdb.isNullOrBlank()) {
        return imdb.equals(target.imdb, ignoreCase = true)
    }
    if (tmdb != null && target.tmdb != null) return tmdb == target.tmdb
    if (!tvdb.isNullOrBlank() && !target.tvdb.isNullOrBlank()) {
        return tvdb.equals(target.tvdb, ignoreCase = true)
    }
    if (mal != null && target.mal != null) return mal == target.mal
    if (anidb != null && target.anidb != null) return anidb == target.anidb
    if (anilist != null && target.anilist != null) return anilist == target.anilist
    if (kitsu != null && target.kitsu != null) return kitsu == target.kitsu
    return null
}
