package com.nuvio.tv.domain.model

import java.time.LocalDate

data class CalendarEpisode(
    val showId: String,
    val showTmdbId: Int,
    val showName: String,
    val addonBaseUrl: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeName: String,
    val overview: String?,
    val stillUrl: String?,
    val airDate: LocalDate
) {
    val videoId: String = "$showId:$seasonNumber:$episodeNumber"
}

data class CalendarDay(val date: LocalDate, val episodes: List<CalendarEpisode>)

data class CalendarRecommendation(
    val item: MetaPreview,
    val recommendedBy: List<String>,
    val score: Int
)
