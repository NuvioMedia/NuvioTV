package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EpisodeRatingsAlignmentTest {

    @Test
    fun `same seasons keep their numbers`() {
        val videos = listOf(video(1, 1, "2020-01-01"), video(1, 2, "2020-01-08"), video(2, 1, "2020-06-01"))
        val external = listOf(
            external(1, 1, "2020-01-01", 7.0),
            external(1, 2, "2020-01-08", 7.5),
            external(1, 3, "2020-01-15", 8.0),
            external(2, 1, "2020-06-01", 8.5)
        )

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((1 to 1) to 7.0, (1 to 2) to 7.5, (1 to 3) to 8.0, (2 to 1) to 8.5), result)
    }

    @Test
    fun `different totals are matched by air date`() {
        val videos = weekly(season = 1, count = 4, start = "2020-01-01") +
            weekly(season = 2, count = 3, start = "2021-01-01")
        val external = weekly(season = 1, count = 2, start = "2020-01-01", ratings = listOf(7.0, null)) +
            weekly(season = 2, count = 2, start = "2020-01-15", ratings = listOf(7.2, 7.3)) +
            weekly(season = 3, count = 2, start = "2021-01-01", ratings = listOf(8.0, 8.1))

        val result = alignEpisodeRatings(videos, external)

        assertEquals(
            mapOf(
                (1 to 1) to 7.0,
                (1 to 3) to 7.2,
                (1 to 4) to 7.3,
                (2 to 1) to 8.0,
                (2 to 2) to 8.1
            ),
            result
        )
    }

    @Test
    fun `single cour entry is matched by air date instead of position`() {
        val videos = weekly(season = 1, count = 3, start = "2020-04-01")
        val external = weekly(season = 1, count = 3, start = "2020-01-01", ratings = listOf(6.0, 6.1, 6.2)) +
            weekly(season = 2, count = 3, start = "2020-04-01", ratings = listOf(9.0, 9.1, 9.2))

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((1 to 1) to 9.0, (1 to 2) to 9.1, (1 to 3) to 9.2), result)
    }

    @Test
    fun `equal totals follow the airing order`() {
        val videos = (1..4).map { video(1, it, null) }
        val external = listOf(
            external(1, 1, null, 7.0),
            external(1, 2, null, 7.1),
            external(2, 1, null, 7.2),
            external(2, 2, null, 7.3)
        )

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((1 to 1) to 7.0, (1 to 2) to 7.1, (1 to 3) to 7.2, (1 to 4) to 7.3), result)
    }

    @Test
    fun `different totals without air dates keep their numbers`() {
        val videos = (1..3).map { video(1, it, null) }
        val external = listOf(
            external(1, 1, null, 7.0),
            external(1, 2, null, 7.1),
            external(2, 1, null, 7.2),
            external(2, 2, null, 7.3)
        )

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((1 to 1) to 7.0, (1 to 2) to 7.1, (2 to 1) to 7.2, (2 to 2) to 7.3), result)
    }

    @Test
    fun `unaired episodes are left out when comparing totals`() {
        val videos = weekly(season = 1, count = 3, start = "2020-01-01") +
            weekly(season = 2, count = 2, start = "2020-01-22")
        val external = weekly(season = 1, count = 2, start = "2020-01-01", ratings = listOf(7.0, 7.1)) +
            listOf(external(2, 1, "2020-01-15", 7.2), external(3, 1, null, 7.3))

        val result = alignEpisodeRatings(videos, external, today = LocalDate.parse("2020-01-23").toEpochDay())

        assertEquals(mapOf((1 to 1) to 7.0, (1 to 2) to 7.1, (1 to 3) to 7.2, (2 to 1) to 7.3), result)
    }

    @Test
    fun `episodes sharing an air date are paired in order`() {
        val videos = listOf(video(1, 1, "2020-01-01"), video(1, 2, "2020-01-01"), video(1, 3, "2020-01-08"))
        val external = listOf(
            external(1, 1, "2020-01-01", 7.0),
            external(1, 2, "2020-01-01", 7.1),
            external(2, 1, "2020-01-09", 7.2),
            external(2, 2, "2020-01-16", 7.3)
        )

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((1 to 1) to 7.0, (1 to 2) to 7.1, (1 to 3) to 7.2), result)
    }

    @Test
    fun `specials keep their numbers when regular seasons are remapped`() {
        val videos = listOf(video(0, 1, "2019-12-01")) + weekly(season = 1, count = 2, start = "2020-01-01")
        val external = listOf(external(0, 1, "2019-12-01", 6.5)) +
            weekly(season = 1, count = 1, start = "2020-01-01", ratings = listOf(7.0)) +
            weekly(season = 2, count = 1, start = "2020-01-08", ratings = listOf(7.5))

        val result = alignEpisodeRatings(videos, external)

        assertEquals(mapOf((0 to 1) to 6.5, (1 to 1) to 7.0, (1 to 2) to 7.5), result)
    }

    @Test
    fun `missing videos keep external numbering`() {
        val external = listOf(external(1, 1, "2020-01-01", 7.0), external(2, 1, "2020-06-01", 8.0))

        assertEquals(mapOf((1 to 1) to 7.0, (2 to 1) to 8.0), alignEpisodeRatings(emptyList(), external))
    }

    private fun video(season: Int, episode: Int, released: String?) = AddonEpisodeAirDate(
        season = season,
        episode = episode,
        released = released?.let { "${it}T00:00:00.000Z" }
    )

    private fun external(season: Int, episode: Int, airDate: String?, rating: Double?) =
        ExternalEpisodeRating(season = season, episode = episode, airDate = airDate, rating = rating)

    private fun weekly(season: Int, count: Int, start: String): List<AddonEpisodeAirDate> {
        val first = LocalDate.parse(start)
        return (1..count).map { episode -> video(season, episode, first.plusWeeks(episode - 1L).toString()) }
    }

    private fun weekly(season: Int, count: Int, start: String, ratings: List<Double?>): List<ExternalEpisodeRating> {
        val first = LocalDate.parse(start)
        return (1..count).map { episode ->
            external(season, episode, first.plusWeeks(episode - 1L).toString(), ratings[episode - 1])
        }
    }
}
