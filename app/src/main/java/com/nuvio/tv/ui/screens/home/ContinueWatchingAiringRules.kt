package com.nuvio.tv.ui.screens.home

/**
 * Pure Continue Watching rules for currently-airing / fully-watched series.
 * Kept free of Android / ViewModel dependencies so unit tests can lock the
 * air-day regression (checkmark + new episode → series removed from CW).
 */

/**
 * What to do when a series is marked fully-watched (caught-up checkmark) but
 * next-up resolution still found a next episode.
 */
enum class FullyWatchedNextUpAction {
    /** Unaired next episode: keep on CW, leave the checkmark. */
    KEEP_WITH_BADGE,

    /**
     * Aired next episode: user is no longer caught up. Keep on CW and clear the
     * stale fully-watched badge. Pre-fix code dropped the item instead.
     */
    KEEP_AND_CLEAR_BADGE,
}

/**
 * Decision for a fully-watched series that already resolved a next-up item.
 *
 * Historical bug (`71e4fb6` + mid-season checkmarks from `a58809`):
 * `if (hasAired) drop` removed airing shows from CW the day a new episode aired.
 * Correct behavior: never drop; clear the badge when the next episode has aired.
 */
fun fullyWatchedNextUpAction(nextUpHasAired: Boolean): FullyWatchedNextUpAction {
    return if (nextUpHasAired) {
        FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE
    } else {
        FullyWatchedNextUpAction.KEEP_WITH_BADGE
    }
}

/**
 * Whether a fully-watched next-up item should be excluded from Continue Watching.
 * Always false after the air-day fix — kept as an explicit API so tests can
 * assert the regression never returns.
 */
fun shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired: Boolean): Boolean {
    // Intentionally ignore [nextUpHasAired]: both aired and unaired next-ups stay.
    @Suppress("UNUSED_PARAMETER")
    val unused = nextUpHasAired
    return false
}

/**
 * Pre-fix behavior retained only for regression tests (must not be used in prod).
 */
internal fun legacyShouldDropFullyWatchedNextUp(nextUpHasAired: Boolean): Boolean = nextUpHasAired

/**
 * Inject an aired unwatched episode into the badge episode set so a later badge
 * pass does not re-apply the fully-watched checkmark from a stale aired list.
 */
fun mergeAiredNextEpisodeIntoBadgeCache(
    existingAiredEpisodes: Set<Pair<Int, Int>>?,
    nextSeason: Int?,
    nextEpisode: Int?,
): Set<Pair<Int, Int>>? {
    if (existingAiredEpisodes == null) return null
    if (nextSeason == null || nextEpisode == null) return existingAiredEpisodes
    return existingAiredEpisodes + (nextSeason to nextEpisode)
}

/**
 * After injecting the new aired episode, the series is fully watched only if
 * every aired episode (including the new one) is in the watched set.
 */
fun isFullyWatchedAgainstAiredList(
    airedEpisodes: Set<Pair<Int, Int>>,
    watchedEpisodes: Set<Pair<Int, Int>>,
): Boolean {
    if (airedEpisodes.isEmpty()) return false
    return airedEpisodes.all { it in watchedEpisodes }
}
