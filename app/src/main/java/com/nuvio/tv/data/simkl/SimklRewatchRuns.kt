package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.core.tracking.TrackingMediaKind
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
 * TV groups on `contentId`, which reads the anime preference from `SimklAnimeIdPreferenceHolder`.
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
    /**
     * Whether the read covered every session the account holds.
     *
     * False means the next sync must read without the watermark: the sessions were never read (a
     * snapshot from before the feature kept none), or the read was skipped because the mode or the
     * plan did not allow one. True means a later read may pair the watermark and take the delta.
     */
    val sessionsComplete: Boolean = false,
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
 * The rows of a read that are rewatch sessions, kept for the snapshot and the derivation.
 *
 * A session the account still reports as running is the run the user is in; a closed or finished one
 * is a run they left, and a canonical row is not a session at all. Storing only this subset keeps the
 * whole show library out of the snapshot while every reader keeps working, since each one already
 * filters to exactly these rows.
 */
internal fun List<SimklLibraryEntry>.runningRewatchSessions(): List<SimklLibraryEntry> =
    filter { entry -> entry.isRewatch && entry.isRunningRewatchSession() }

/**
 * Merges a rewatch-session read into the sessions the snapshot already holds.
 *
 * Outside the initial sync the read is paired with the sync watermark, so it carries the rows of the
 * items that changed since it, and nothing at all for the items that did not. The rows of a changed
 * item replace what was stored for that item: a session the account closed elsewhere leaves the
 * stored set here, a new one joins it. Items the read did not mention keep their stored sessions.
 * Only running sessions are kept, the same filter a full read goes through.
 */
internal fun mergeRewatchSessions(
    read: List<SimklLibraryEntry>,
    stored: List<SimklLibraryEntry>
): List<SimklLibraryEntry> {
    val changedKeys = read.mapNotNull(SimklLibraryEntry::stableKey).toSet()
    val kept = stored.filterNot { entry -> entry.stableKey() in changedKeys }
    return (kept + read.runningRewatchSessions())
        .sortedWith(compareBy({ entry -> entry.stableKey().orEmpty() }, { entry -> entry.rewatchId ?: 0L }))
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
 * Whether the running session of the item holds the episode a mark just wrote.
 *
 * The read-back only uses this to stop waiting; the account's read is adopted either way. The exact
 * season is tried first. A flat anime session can list its episodes without a season number, so a
 * unique episode number decides when the exact pair is not there; a number that appears in more than
 * one season is not accepted, because the write cannot be told apart from the other viewing.
 */
internal fun List<SimklLibraryEntry>.rewatchSessionHolds(
    contentId: String,
    kind: TrackingMediaKind,
    season: Int?,
    episode: Int,
): Boolean {
    val rewatched = rewatchedEpisodesOf(contentId)
    val exactPair = rewatched.contains((season ?: 0) to episode)
    if (exactPair || kind != TrackingMediaKind.ANIME) return exactPair
    return rewatched.count { (_, number) -> number == episode } == 1
}

/**
 * Whether a mark of this item is the next step of the account's running run.
 *
 * The account can report a watched title without the episode rows the exact prior-watch lookup
 * needs (the API sometimes answers with counts only). The run position is then the only history
 * left: a mark of the episode right after it, or of an episode the session already holds, is the
 * run's own step and must travel as a rewatch. Anything else stays a first watch, so a new episode
 * away from the run cannot join it.
 *
 * The episode is the one the write resolves to: anime arrives here in Simkl's flat numbering, where
 * a session without a season places the step by its number alone.
 */
internal fun SimklSyncSnapshot.markContinuesRunningRewatch(
    media: TrackingMediaReference
): Boolean {
    val episode = media.episode ?: return false
    val canonicalId = media.toSimklMedia().canonicalContentId()
    val run = rewatchRuns.firstOrNull { run ->
        run.matches(media.catalog?.contentId) || run.matches(canonicalId)
    } ?: return false
    val number = episode.number
    if ((episode.season ?: 0) to number in rewatchSessions.rewatchedEpisodesOf(run.contentId)) {
        return true
    }
    return when {
        run.seasonNumber == 0 -> (episode.season ?: 0) == 0 && number == run.episodeNumber + 1
        episode.season == null -> false
        episode.season == run.seasonNumber -> number == run.episodeNumber + 1
        episode.season == run.seasonNumber + 1 -> number == 1
        else -> false
    }
}

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

/**
 * The running session that actually holds this episode.
 *
 * A mark only needs the show's running session; a removal must name the session the episode was
 * rewatched in, or it would clear a different run.
 *
 * Anime references may carry no season, because Simkl lists anime episodes flat. A known season is
 * still matched exactly, so an episode number that repeats in another season is never mistaken for
 * the target. Without one, the episode number alone decides, and the most recently watched running
 * session wins.
 */
internal fun List<SimklLibraryEntry>.activeRewatchSessionIdForEpisode(
    media: TrackingMediaReference,
    seasonNumber: Int?,
    episodeNumber: Int
): Long? {
    val target = media.toSimklMedia()
    return filter { entry ->
        entry.isRewatch &&
            entry.media?.matchesTarget(target) == true &&
            entry.isRunningRewatchSession() &&
            entry.rewatchedEpisodes().any { episode ->
                episode.episodeNumber == episodeNumber &&
                    (seasonNumber == null || episode.seasonNumber == seasonNumber)
            }
    }
        .maxByOrNull { entry -> entry.lastWatchedAt?.let(::parseSimklUtcEpochMs) ?: Long.MIN_VALUE }
        ?.rewatchId
}

/**
 * The session id a removal may name, using the reference the user marked.
 *
 * A show must name its season: the same episode number exists in every season. An anime may be
 * listed flat, so an exact season is tried first and then the flat form. The fallback is anime only;
 * a show without a season must not match an arbitrary season.
 */
internal fun List<SimklLibraryEntry>.activeRewatchSessionIdForRemoval(
    media: TrackingMediaReference,
    seasonNumber: Int?,
    episodeNumber: Int,
    animeEpisodeNumber: Int? = null
): Long? {
    if (seasonNumber == null && media.kind != TrackingMediaKind.ANIME) return null
    activeRewatchSessionIdForEpisode(media, seasonNumber, episodeNumber)?.let { return it }
    if (media.kind != TrackingMediaKind.ANIME) return null
    // A TV catalogue may name a season while Simkl lists the anime flat. The anime-native number
    // can differ from the catalogue number (TVDB S2E20 is often absolute episode 7), so try the
    // absolute number before the catalogue number.
    val flatNumbers = buildList {
        animeEpisodeNumber?.let(::add)
        if (episodeNumber !in this) add(episodeNumber)
    }
    return flatNumbers.firstNotNullOfOrNull { number ->
        activeRewatchSessionIdForEpisode(media, null, number)
    }
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
    // Anime references reach the row through any of these forms, so the dismissal and close
    // lookups must not depend on which one the preference picked for the canonical id.
    ids.idValue("mal")?.let { mal -> add("mal:$mal") }
    ids.idValue("kitsu")?.let { kitsu -> add("kitsu:$kitsu") }
    ids.idValue("anidb")?.let { anidb -> add("anidb:$anidb") }
    ids.idValue("anilist")?.let { anilist -> add("anilist:$anilist") }
}.distinct()
