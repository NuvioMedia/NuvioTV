package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.LibraryEntry
import java.time.LocalDate

object CalendarEpisodeBuilder {
    fun build(
        show: LibraryEntry,
        tmdbId: Int,
        seasonNumber: Int,
        episodes: List<TmdbEpisode>,
        today: LocalDate,
        horizonDays: Long = 90
    ): List<CalendarEpisode> {
        val endDate = today.plusDays(horizonDays.coerceAtLeast(0))
        return episodes.mapNotNull { episode ->
            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
            val airDate = runCatching { LocalDate.parse(episode.airDate) }.getOrNull()
                ?: return@mapNotNull null
            if (airDate < today || airDate > endDate) return@mapNotNull null
            CalendarEpisode(
                showId = show.id,
                showTmdbId = tmdbId,
                showName = show.name,
                addonBaseUrl = show.addonBaseUrl,
                poster = show.poster,
                background = show.background,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeName = episode.name?.takeIf(String::isNotBlank) ?: "Episode $episodeNumber",
                overview = episode.overview?.takeIf(String::isNotBlank),
                stillUrl = episode.stillPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                airDate = airDate
            )
        }.sortedWith(compareBy(CalendarEpisode::airDate, CalendarEpisode::showName, CalendarEpisode::episodeNumber))
    }

    fun groupByDay(episodes: List<CalendarEpisode>): List<CalendarDay> = episodes
        .groupBy(CalendarEpisode::airDate)
        .toSortedMap()
        .map { (date, items) -> CalendarDay(date, items) }
}
