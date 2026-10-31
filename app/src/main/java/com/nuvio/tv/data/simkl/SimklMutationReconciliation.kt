package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference

internal fun SimklSyncSnapshot.applyMutationReceipt(
    receipt: SimklMutationReceipt,
    committedAtEpochMs: Long,
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot {
    val committedAt = committedAtEpochMs.epochMsToUtcIso() ?: return this
    return when (val mutation = receipt.mutation) {
        is SimklCommittedMutation.MoveToList -> withListMutations(mutation.items, committedAt, entryIndex)
        is SimklCommittedMutation.AddToHistory -> withHistoryMutations(
            mutation.items,
            committedAtEpochMs,
            entryIndex
        )
        is SimklCommittedMutation.RemoveFromHistory -> withoutHistory(mutation.items)
    }.reconcileWatchedPlayback()
}

private fun SimklSyncSnapshot.withListMutations(
    mutations: List<SimklResolvedListMutation>,
    committedAt: String,
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot {
    if (mutations.isEmpty()) return this
    val updated = entries.toMutableList()
    mutations.forEachIndexed { idx, mutation ->
        val requestMedia = mutation.request.toSimklMedia()
        val media = mutation.responseMedia.mergeMissing(requestMedia)
        val index = if (idx == 0 && entryIndex != null) {
            entryIndex.indexOfMatchingMedia(entries, media)
        } else {
            updated.indexOfMatchingMediaLinear(media)
        }
        val existing = updated.getOrNull(index)
        val mediaType = existing?.mediaType
            ?: mutation.resolvedMediaType
            ?: mutation.request.kind.toSimklMediaType()
        val mergedMedia = media.mergeMissing(existing?.media)
        val entry = (existing ?: SimklLibraryEntry()).copy(
            mediaType = mediaType,
            animeType = mutation.animeType ?: existing?.animeType,
            localPosterUrl = existing?.localPosterUrl
                ?: mutation.request.posterUrl?.trim()?.takeIf(String::isNotBlank),
            addedToWatchlistAt = existing?.addedToWatchlistAt ?: committedAt,
            status = mutation.status,
            show = mergedMedia.takeIf { mediaType != SimklMediaType.MOVIES },
            movie = mergedMedia.takeIf { mediaType == SimklMediaType.MOVIES }
        )
        if (index >= 0) {
            updated[index] = entry
        } else {
            updated += entry
        }
    }
    return copy(entries = updated)
}

private fun SimklSyncSnapshot.withHistoryMutations(
    mutations: List<SimklResolvedHistoryMutation>,
    committedAtEpochMs: Long,
    entryIndex: SimklEntryIndex? = null
): SimklSyncSnapshot {
    var updated = this
    mutations.forEachIndexed { idx, mutation ->
        val request = mutation.request
        val mediaType = mutation.mediaType ?: request.media.kind.toSimklMediaType()
        updated = updated.applyScrobbleResult(
            result = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                playbackId = null,
                progress = 100.0,
                mediaType = mediaType,
                media = request.media.toSimklMedia(),
                episode = request.media.episode?.let { episode ->
                    SimklPlaybackEpisode(
                        season = episode.season,
                        number = episode.number,
                        title = episode.title
                    )
                },
                watchedAt = request.watchedAtEpochMs?.epochMsToUtcIso()
            ),
            committedAtEpochMs = committedAtEpochMs,
            entryIndex = if (idx == 0) entryIndex else null
        )
        updated = updated.withResolvedHistoryStatus(mutation)
    }
    return updated
}

private fun SimklSyncSnapshot.withResolvedHistoryStatus(
    mutation: SimklResolvedHistoryMutation
): SimklSyncSnapshot {
    val target = mutation.request.media.toSimklMedia()
    val index = entries.indexOfMatchingMediaLinear(target)
    val existing = entries.getOrNull(index) ?: return this
    val mediaType = mutation.mediaType ?: existing.mediaType
    val media = existing.media ?: target
    val updated = existing.copy(
        mediaType = mediaType,
        status = mutation.status ?: existing.status,
        animeType = mutation.animeType ?: existing.animeType,
        show = media.takeIf { mediaType != SimklMediaType.MOVIES },
        movie = media.takeIf { mediaType == SimklMediaType.MOVIES }
    )
    return copy(entries = entries.toMutableList().apply { this[index] = updated })
}

private fun SimklSyncSnapshot.withoutHistory(
    mutations: List<TrackingMediaReference>
): SimklSyncSnapshot {
    if (mutations.isEmpty()) return this
    var updated = entries
    mutations.forEach { mutation ->
        val target = mutation.toSimklMedia()
        val targetIds = target.toTrackingExternalIds()
        if (mutation.kind == TrackingMediaKind.MOVIE || mutation.episode == null) {
            updated = updated.filterNot { entry ->
                val media = entry.media ?: return@filterNot false
                media.matchesTargetWithIds(media.toTrackingExternalIds(), target, targetIds)
            }
        } else {
            updated = updated.map { entry ->
                val media = entry.media
                if (media != null && media.matchesTargetWithIds(media.toTrackingExternalIds(), target, targetIds)) {
                    entry.withoutWatchedEpisode(
                        season = mutation.episode.season,
                        number = mutation.episode.number
                    )
                } else {
                    entry
                }
            }
        }
    }
    return copy(entries = updated)
}

private fun SimklLibraryEntry.withoutWatchedEpisode(
    season: Int?,
    number: Int
): SimklLibraryEntry {
    val targetSeason = season ?: 1
    val updatedSeasons = seasons.map { candidateSeason ->
        candidateSeason.copy(
            episodes = candidateSeason.episodes.map { episode ->
                val matchesCanonical = candidateSeason.number == targetSeason && episode.number == number
                val matchesTvdb = episode.tvdb?.season == targetSeason &&
                    episode.tvdb.episode == number
                if (matchesCanonical || matchesTvdb) episode.copy(watchedAt = null) else episode
            }
        )
    }
    val watchedEpisodes = updatedSeasons.flatMap(SimklSeason::episodes)
        .filter { episode -> episode.watchedAt != null }
    return copy(
        lastWatchedAt = watchedEpisodes.maxOfOrNull { episode -> requireNotNull(episode.watchedAt) },
        watchedEpisodesCount = watchedEpisodes.size,
        seasons = updatedSeasons
    )
}

internal fun List<SimklLibraryEntry>.indexOfMatchingMediaLinear(target: SimklMedia): Int {
    val targetIds = target.toTrackingExternalIds()
    for (index in indices) {
        val media = this[index].media ?: continue
        if (media.matchesTargetWithIds(media.toTrackingExternalIds(), target, targetIds)) {
            return index
        }
    }
    return -1
}
