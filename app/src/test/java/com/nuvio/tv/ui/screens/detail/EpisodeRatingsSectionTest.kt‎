package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeRatingsSectionTest {

    @Test
    fun `episodes across layout remains available for inverted mode`() {
        val chart = buildEpisodeRatingsChartData(
            episodes = listOf(
                episode(id = "s1e1", season = 1, episode = 1),
                episode(id = "s1e2", season = 1, episode = 2),
                episode(id = "s2e1", season = 2, episode = 1),
                episode(id = "s2e3", season = 2, episode = 3)
            ),
            ratings = mapOf(
                (1 to 1) to 8.4,
                (2 to 3) to 9.3
            )
        )

        val display = chart.toDisplayModel(RatingsLayoutMode.EPISODES_ACROSS)

        assertEquals(listOf(1, 2), chart.displaySeasonNumbers)
        assertEquals(3, chart.maxEpisodeNumber)
        assertEquals("Season", display.leadingHeader)
        assertEquals(listOf("Avg", "E1", "E2", "E3"), display.columnHeaders.map { it.label })
        assertEquals("S1", display.rows[0].label)
        assertEquals("8.4", display.rows[0].cells[0].ratingLabel)
        assertEquals("s1e1", display.rows[0].cells[1].episodeId)
        assertEquals("s2e3", display.rows[1].cells[3].episodeId)
        assertEquals("9.3", display.rows[1].cells[3].ratingLabel)
    }

    @Test
    fun `seasons across layout keeps mobile style orientation available`() {
        val chart = buildEpisodeRatingsChartData(
            episodes = listOf(
                episode(id = "s1e1", season = 1, episode = 1),
                episode(id = "s1e2", season = 1, episode = 2),
                episode(id = "s2e1", season = 2, episode = 1),
                episode(id = "s2e2", season = 2, episode = 2)
            ),
            ratings = emptyMap()
        )

        val display = chart.toDisplayModel(RatingsLayoutMode.SEASONS_ACROSS)

        assertEquals("Episode", display.leadingHeader)
        assertEquals(listOf("S1", "S2"), display.columnHeaders.map { it.label })
        assertEquals("Avg", display.rows[0].label)
        assertEquals(3, display.rows.size)
        assertEquals(EpisodeRatingCellState.SUMMARY, display.rows[0].cells[0].state)
        assertEquals("—", display.rows[0].cells[0].ratingLabel)
        assertEquals("E1", display.rows[1].label)
        assertEquals("s1e1", display.rows[1].cells[0].episodeId)
        assertEquals("s2e2", display.rows[2].cells[1].episodeId)
    }

    @Test
    fun `season averages are computed from available ratings only`() {
        val chart = buildEpisodeRatingsChartData(
            episodes = listOf(
                episode(id = "s1e1", season = 1, episode = 1),
                episode(id = "s1e2", season = 1, episode = 2),
                episode(id = "s2e1", season = 2, episode = 1)
            ),
            ratings = mapOf(
                (1 to 1) to 8.0,
                (1 to 2) to 6.0,
                (2 to 1) to 9.0
            )
        )

        assertEquals(2, chart.seasonAverages.size)
        assertEquals(7.0, chart.seasonAverages.first { it.seasonNumber == 1 }.average, 0.001)
        assertEquals(9.0, chart.seasonAverages.first { it.seasonNumber == 2 }.average, 0.001)
        val display = chart.toDisplayModel(RatingsLayoutMode.SEASONS_ACROSS)
        assertEquals("Avg", display.rows.first().label)
        assertEquals(EpisodeRatingCellState.SUMMARY, display.rows.first().cells[0].state)
        assertEquals("7.0", display.rows.first().cells[0].ratingLabel)
        assertEquals("9.0", display.rows.first().cells[1].ratingLabel)
        assertNotNull(chart.toDisplayModel(RatingsLayoutMode.EPISODES_ACROSS).firstEpisodeId)
        assertTrue(display.rows.isNotEmpty())
    }

    private fun episode(id: String, season: Int?, episode: Int?) = Video(
        id = id,
        title = id,
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )
}
