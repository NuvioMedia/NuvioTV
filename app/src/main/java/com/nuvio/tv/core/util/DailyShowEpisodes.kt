package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.Video
import java.time.LocalDate

private const val DAILY_DETECTION_WINDOW = 30
private const val DAILY_MIN_DISTINCT_WEEKDAYS = 4
private const val DAILY_MIN_DATED_EPISODES = 4

/**
 * Whether a series airs on a daily (date-based) schedule — talk shows, soaps,
 * game shows, late-night. Such shows are numbered by air date, so their
 * season/episode numbers are meaningless to a viewer ("S01E01 of Jeopardy from
 * 1983") and episodes should be presented most-recent-first.
 *
 * Detection is pure signal from the episodes already in hand: the distinct
 * weekdays across the most recent aired episodes. A weekly show always lands
 * on 1 weekday; a 4+ days-a-week show lands on 4+. Missing dates are skipped,
 * and a show with too few dated episodes is left undetected rather than guessed.
 */
fun isDailyShow(videos: List<Video>): Boolean {
    val recentAirDates = videos.asSequence()
        .filter { (it.season ?: 0) > 0 }
        .mapNotNull { it.released?.let(::parseEpisodeReleaseLocalDate) }
        .sortedDescending()
        .take(DAILY_DETECTION_WINDOW)
        .toList()
    if (recentAirDates.size < DAILY_MIN_DATED_EPISODES) return false
    return recentAirDates.mapTo(mutableSetOf()) { it.dayOfWeek }.size >= DAILY_MIN_DISTINCT_WEEKDAYS
}

/**
 * Ordering for an episode row. Daily shows run newest air date first (the date
 * is the real identity of the episode); everything else keeps numeric episode
 * order. Episodes without a parseable date sort last, by episode number.
 */
fun sortEpisodesForDisplay(videos: List<Video>, daily: Boolean): List<Video> {
    if (!daily) return videos.sortedBy { it.episode }
    return videos.sortedWith(
        compareByDescending<Video> { it.released?.let(::parseEpisodeReleaseLocalDate) ?: LocalDate.MIN }
            .thenByDescending { it.episode ?: Int.MAX_VALUE }
    )
}
