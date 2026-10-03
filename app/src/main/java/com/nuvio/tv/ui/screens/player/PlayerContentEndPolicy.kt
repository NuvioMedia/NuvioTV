package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval

/*
 * The segment types that mark the end of the content: the outro of a series (its anime variants
 * included) and the credits of a film. Mobile has them in `features/player/skip/SkipModels.kt` as
 * `ContentEndSegmentTypes`; TV has `outro` in `PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES`, so the
 * film type is added only here.
 */
internal val ContentEndSegmentTypes: Set<String> =
    PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES + setOf("movie-credits")

/**
 * Where the content really ends, in percent of its own duration, from the IntroDB marker.
 *
 * Only the marker is read, never the skip setting: a playback that reached the credits is finished
 * for the tracker too. The earliest marker of a content end type is used, that is where the content
 * ends and not where the last closing scene ends.
 *
 * Invalid values are refused: a nonsense duration, a start outside the video or a start that is not
 * a positive number all return `null`. That means the threshold the user set decides, the same as
 * for a title IntroDB does not know.
 */
internal fun List<SkipInterval>.contentEndPercent(durationMs: Long): Double? {
    if (durationMs <= 0L) return null
    val creditsStartSeconds = filter { interval -> interval.type in ContentEndSegmentTypes }
        .minOfOrNull { interval -> interval.startTime }
        ?: return null
    if (!creditsStartSeconds.isFinite() || creditsStartSeconds <= 0.0) return null
    val creditsStartMs = creditsStartSeconds * 1_000.0
    if (creditsStartMs >= durationMs) return null
    return creditsStartMs / durationMs.toDouble() * 100.0
}

/**
 * The same marker for the playback that is on screen right now.
 *
 * The duration comes from the player and, when that is no longer available, from the last known
 * duration, the same as `currentPlaybackProgressPercent` does. A stop is also sent when leaving the
 * screen, when the player may not answer any more, and the marker belongs to this playback, not the request.
 */
internal fun PlayerRuntimeController.currentContentEndPercent(): Double? {
    val durationMs = currentPlaybackDurationMs().takeIf { it > 0L } ?: lastKnownDuration
    return skipIntervals.contentEndPercent(durationMs)
}
