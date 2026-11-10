package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.WatchProgress

/** The content type the catalogue uses for series, which is what the next-up resolver fetches by. */
internal const val SERIES_CONTENT_TYPE = "series"

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

/**
 * Replaces the account runs with the optimistic ones a Continue Watching mark just advanced.
 *
 * The optimistic entry is keyed by the run's own content id, which is what the row matches. It is
 * kept until the account read reports the same or a later episode, so a stale read cannot pull the
 * row back below the mark the user just made.
 */
internal fun mergeOptimisticRewatchRuns(
    accountRuns: List<RewatchRunPosition>,
    optimisticRuns: Map<String, RewatchRunPosition>
): List<RewatchRunPosition> {
    if (optimisticRuns.isEmpty()) return accountRuns
    val accountIds = accountRuns.mapTo(mutableSetOf()) { run -> run.contentId }
    return (
        accountRuns.map { run -> optimisticRuns[run.contentId] ?: run } +
            optimisticRuns.values.filter { run -> run.contentId !in accountIds }
        ).sortedByDescending { run -> run.markedAtEpochMs }
}

/**
 * The id forms of the runs whose Continue Watching answers are stale.
 *
 * A run that appears, disappears or moves to another episode invalidates the row answers of its
 * series. A run whose mark time alone changes does not: the next-up resolution is keyed by the
 * position, so a mark that leaves the position in place leaves that key valid too. Returning every
 * id form the run matches keeps a catalogue id and the provider ids from missing the same series.
 */
internal fun changedRewatchRunKeys(
    previous: List<RewatchRunPosition>,
    current: List<RewatchRunPosition>,
): Set<String> {
    val previousByContentId = previous.associateBy(RewatchRunPosition::contentId)
    val currentByContentId = current.associateBy(RewatchRunPosition::contentId)
    val changed = mutableListOf<RewatchRunPosition>()
    previousByContentId.forEach { (contentId, before) ->
        val after = currentByContentId[contentId]
        when {
            after == null -> changed += before
            after.seasonNumber != before.seasonNumber ||
                after.episodeNumber != before.episodeNumber -> {
                changed += before
                changed += after
            }
        }
    }
    currentByContentId.forEach { (contentId, after) ->
        if (contentId !in previousByContentId) changed += after
    }
    return changed
        .flatMapTo(mutableSetOf()) { run -> listOf(run.contentId) + run.matchKeys }
        .mapTo(mutableSetOf()) { key -> key.trim() }
        .apply { remove("") }
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

/**
 * The saved next up dismissals without the ones a running rewatch now overrides.
 *
 * A dismissal is kept per content id, and closing a run used to save one. A new run of the same
 * title opened on another device would then be filtered out of Continue Watching even though the
 * account reports it as running, while the detail screen still shows it. A run wins over the
 * dismissal of its title; the other dismissals are left alone.
 */
internal fun withoutLiveRewatchDismissals(
    dismissed: Set<String>,
    runs: List<RewatchRunPosition>
): Set<String> {
    if (dismissed.isEmpty() || runs.isEmpty()) return dismissed
    return dismissed.filterNotTo(mutableSetOf()) { key ->
        runs.any { run -> run.matches(key) }
    }
}
