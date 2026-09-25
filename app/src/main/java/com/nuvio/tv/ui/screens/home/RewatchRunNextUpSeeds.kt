package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.WatchProgress

/** The content type the catalogue uses for series, which is what the next-up resolver fetches by. */
private const val SERIES_CONTENT_TYPE = "series"

/**
 * Moves a series the user is re-watching onto its run.
 *
 * Simkl keeps a rewatch in its own session and never moves the canonical watch position, so Next Up
 * would keep offering the episode after the old position, or nothing at all for a series the
 * provider owns the history for. While the run is the newer of the two, it decides instead: a run
 * that reached S01E02 turns the seed into S01E02, and the resolver then offers S01E03. The
 * canonical position takes over again as soon as it is newer than the run.
 *
 * The seed list is this pipeline's completed-position list, so one run per content id is applied:
 * of the runs matching a seed, the one with the newest mark, which is the same choice the mobile
 * client makes over its own candidate list.
 */
internal fun applyRewatchRunPositions(
    seeds: List<WatchProgress>,
    runs: List<RewatchRunPosition>,
): List<WatchProgress> {
    if (runs.isEmpty()) return seeds
    val withRunPositions = seeds.map { seed ->
        val run = runs
            .filter { run -> run.matches(seed.contentId) }
            .maxByOrNull(RewatchRunPosition::markedAtEpochMs)
            ?: return@map seed
        if (run.markedAtEpochMs < seed.lastWatched) return@map seed
        if (run.seasonNumber == seed.season && run.episodeNumber == seed.episode) return@map seed
        // The video id belonged to the episode the seed used to point at, and the resolver falls
        // back to it when the seed is not found in the catalogue, which would then offer the run
        // position itself. The run is the only position known, so leave the id empty.
        seed.copy(
            season = run.seasonNumber,
            episode = run.episodeNumber,
            lastWatched = run.markedAtEpochMs,
            videoId = ""
        )
    }
    // A run can be the only reason its series belongs in the row: with a tracking provider owning
    // the completed history there is no local seed for that series, so there is no canonical seed
    // to move. Build one from the run instead of losing it, and let the resolver fill the rest in
    // from the catalogue the way it does for every other seed.
    val added = runs
        .filterNot { run -> withRunPositions.any { seed -> run.matches(seed.contentId) } }
        .distinctBy(RewatchRunPosition::contentId)
        .map { run -> run.toNextUpSeed() }
    if (added.isEmpty()) return withRunPositions
    return (withRunPositions + added).sortedByDescending { seed -> seed.lastWatched }
}

/** A completed seed standing at the run position, with the mark that makes the run the newer one. */
private fun RewatchRunPosition.toNextUpSeed(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = SERIES_CONTENT_TYPE,
    name = "",
    poster = null,
    backdrop = null,
    logo = null,
    videoId = contentId,
    season = seasonNumber,
    episode = episodeNumber,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = markedAtEpochMs,
    progressPercent = 100f
)
