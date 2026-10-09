package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.core.tracking.TrackingMediaReference

/**
 * Reads the rewatch sessions of an account and keeps the ones that look like a run.
 *
 * Simkl keeps a rewatch in its own session and never moves the canonical watch position, so a client
 * that reads only the canonical row offers the episode the user finished months ago, or nothing at
 * all. The sessions are the only place that knows where the run is, and they live on the account, so
 * a run read from here shows up on every device the user signs in on.
 *
 * Only the session the account still reports as running is read. Simkl closes a session and opens a
 * fresh one once the same episode is rewatched days later, and a closed or finished one is a run the
 * user left, so reading the sessions together would put the row back on a position the user is not in.
 * Episodes then have to form a chain of consecutive numbers inside one season, and the chain holding
 * the most recently rewatched episode is the run. A run the user is in is always offered; the mode
 * decides whether the account is asked about rewatches at all (see [SimklRewatchMode]).
 *
 * Difference from mobile: mobile takes `animeIdPreference` and groups through
 * `canonicalContentId(preference)`. The TV `SimklMedia.canonicalContentId()` takes no parameter and
 * keeps the anime preference in `SimklAnimeIdPreferenceHolder`, so grouping on `contentId` goes
 * through it and the preference shows up only in the rest of the pipeline.
 */
internal fun deriveSimklRewatchRuns(
    entries: List<SimklLibraryEntry>,
    offerRuns: Boolean,
): List<RewatchRunPosition> {
    if (!offerRuns) return emptyList()
    val sessions = entries.filter { entry ->
        entry.isRewatch && entry.media != null && entry.isRunningRewatchSession()
    }
    if (sessions.isEmpty()) return emptyList()
    return sessions
        .groupBy { entry -> entry.media?.canonicalContentId().orEmpty() }
        .filterKeys { contentId -> contentId.isNotEmpty() }
        .mapNotNull { (contentId, rows) ->
            buildRewatchRun(contentId, rows)
        }
        .sortedByDescending(RewatchRunPosition::markedAtEpochMs)
}

/**
 * What one read of the rewatch sessions produced: the runs the app offers and the sessions they were
 * derived from.
 *
 * The sessions are kept on the snapshot next to the runs, so a change of [SimklRewatchMode] can
 * re-derive the runs straight away instead of waiting for the next read of the account.
 */
internal data class SimklRewatchRead(
    val runs: List<RewatchRunPosition>,
    val sessions: List<SimklLibraryEntry>,
)

private fun buildRewatchRun(
    contentId: String,
    rows: List<SimklLibraryEntry>,
): RewatchRunPosition? {
    val episodes = rows
        .flatMap { row -> row.rewatchedEpisodes() }
        .newestPerEpisode()
    if (episodes.isEmpty()) return null
    val newest = episodes.maxWith(
        compareBy(
            { episode -> episode.watchedAtEpochMs ?: Long.MIN_VALUE },
            { episode -> episode.seasonNumber },
            { episode -> episode.episodeNumber },
        ),
    )
    val chain = consecutiveChains(episodes)
        .firstOrNull { candidate -> candidate.any { it.isSameEpisodeAs(newest) } }
        ?: return null
    val position = chain.maxBy { episode -> episode.episodeNumber }
    return RewatchRunPosition(
        contentId = contentId,
        matchKeys = rows.firstNotNullOfOrNull(SimklLibraryEntry::media)?.rewatchMatchKeys(contentId).orEmpty(),
        seasonNumber = position.seasonNumber,
        episodeNumber = position.episodeNumber,
        markedAtEpochMs = newest.watchedAtEpochMs ?: position.watchedAtEpochMs ?: 0L,
    )
}

private data class RewatchedEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val watchedAtEpochMs: Long?,
) {
    fun isSameEpisodeAs(other: RewatchedEpisode): Boolean =
        seasonNumber == other.seasonNumber && episodeNumber == other.episodeNumber
}

/** Every episode the session rows carry, falling back to the row date when the episode has none. */
private fun SimklLibraryEntry.rewatchedEpisodes(): List<RewatchedEpisode> {
    val rowWatchedAt = lastWatchedAt?.let(::parseSimklUtcEpochMs)
    return seasons.flatMap { season ->
        val seasonNumber = season.number ?: 0
        season.episodes.mapNotNull { episode ->
            val episodeNumber = episode.number?.takeIf { number -> number > 0 } ?: return@mapNotNull null
            RewatchedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                watchedAtEpochMs = episode.watchedAt?.let(::parseSimklUtcEpochMs) ?: rowWatchedAt,
            )
        }
    }
}

/** Keeps one row per episode, dated with the latest time it was rewatched across all sessions. */
private fun List<RewatchedEpisode>.newestPerEpisode(): List<RewatchedEpisode> =
    groupBy { episode -> episode.seasonNumber to episode.episodeNumber }
        .values
        .map { sameEpisode -> sameEpisode.maxBy { episode -> episode.watchedAtEpochMs ?: Long.MIN_VALUE } }

/** Splits episodes into chains of consecutive numbers, per season. */
private fun consecutiveChains(episodes: List<RewatchedEpisode>): List<List<RewatchedEpisode>> =
    episodes
        .groupBy(RewatchedEpisode::seasonNumber)
        .values
        .flatMap { seasonEpisodes ->
            seasonEpisodes
                .sortedBy(RewatchedEpisode::episodeNumber)
                .fold(mutableListOf<MutableList<RewatchedEpisode>>()) { chains, episode ->
                    val running = chains.lastOrNull()
                    if (running != null && episode.episodeNumber == running.last().episodeNumber + 1) {
                        running.add(episode)
                    } else {
                        chains.add(mutableListOf(episode))
                    }
                    chains
                }
        }

/**
 * The episodes the running session of the item holds: what a rewatch in progress has covered so far.
 *
 * The detail screen draws the same marker on every episode the account watched, so a run needs its
 * own set to tell an episode it has already rewatched from one it has not reached yet. Only the
 * session the account still reports as running is read, the same rule the run itself is read with,
 * and the item is the one the run was read for: the content id a [RewatchRunPosition] carries.
 */
internal fun List<SimklLibraryEntry>.rewatchedEpisodesOf(contentId: String): Set<Pair<Int, Int>> =
    filter { entry ->
        entry.isRewatch &&
            entry.isRunningRewatchSession() &&
            entry.media?.canonicalContentId() == contentId
    }
        .flatMap(SimklLibraryEntry::rewatchedEpisodes)
        .map { episode -> episode.seasonNumber to episode.episodeNumber }
        .toSet()

/**
 * The session a repeat viewing of this item joins, when the account already has one running.
 *
 * Simkl asks a client to pin the session on every write after the one that opened it, so the write
 * lands in the run the user is in instead of in whichever session the account would have picked. Only
 * a session the account still reports as `active` can be pinned: a closed or finished one is a run the
 * user ended, and a new viewing must not be written into it.
 *
 * The sessions are the ones the last read of the account left. A session a write has opened but the
 * read has not published yet is simply not pinned, and the account resolves that write on its own,
 * which is what the client sends today.
 */
internal fun List<SimklLibraryEntry>.activeRewatchSessionId(media: TrackingMediaReference): Long? {
    val target = media.toSimklMedia()
    return filter { entry ->
        entry.isRewatch &&
            entry.media?.matchesTarget(target) == true &&
            entry.isRunningRewatchSession()
    }
        .maxByOrNull { entry -> entry.lastWatchedAt?.let(::parseSimklUtcEpochMs) ?: Long.MIN_VALUE }
        ?.rewatchId
}

/** The state Simkl reports for the session a run continues in; only that one is ever pinned. */
private const val ACTIVE_REWATCH_STATUS = "active"

/** True when the account reports this row as the session still running for its item. */
internal fun SimklLibraryEntry.isRunningRewatchSession(): Boolean =
    rewatchStatus?.trim()?.equals(ACTIVE_REWATCH_STATUS, ignoreCase = true) == true

internal fun SimklMedia.rewatchMatchKeys(contentId: String): List<String> = buildList {
    add(contentId)
    ids.idValue("imdb")?.let { imdb -> add(imdb); add("imdb:$imdb") }
    ids.idValue("tmdb")?.let { tmdb -> add("tmdb:$tmdb") }
    ids.idValue("tvdb")?.let { tvdb -> add("tvdb:$tvdb") }
    ids.simklIdValue()?.let { simkl -> add("simkl:$simkl") }
}.distinct()
