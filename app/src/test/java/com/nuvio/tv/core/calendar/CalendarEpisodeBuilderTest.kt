package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.domain.model.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CalendarEpisodeBuilderTest {
    private val show = LibraryEntry(
        id = "tmdb:123", type = "series", name = "Saved Show", poster = "poster",
        background = "background", logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList(), addonBaseUrl = "https://example.test"
    )

    @Test
    fun `keeps only upcoming episodes inside horizon and groups by date`() {
        val episodes = listOf(
            TmdbEpisode(episodeNumber = 1, name = "Past", airDate = "2026-08-15"),
            TmdbEpisode(episodeNumber = 2, name = "Tonight", airDate = "2026-08-16"),
            TmdbEpisode(episodeNumber = 3, name = "Next", airDate = "2026-08-23"),
            TmdbEpisode(episodeNumber = 4, name = "Too far", airDate = "2026-12-01"),
            TmdbEpisode(episodeNumber = 5, name = "Unknown", airDate = null)
        )
        val result = CalendarEpisodeBuilder.build(show, 123, 4, episodes, LocalDate.of(2026, 8, 16), 30)
        assertEquals(listOf(2, 3), result.map { it.episodeNumber })
        assertEquals("tmdb:123:4:2", result.first().videoId)
        assertEquals(2, CalendarEpisodeBuilder.groupByDay(result).size)
    }
}
