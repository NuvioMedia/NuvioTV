package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyShowEpisodesTest {

    private fun ep(season: Int?, episode: Int?, released: String?): Video = Video(
        id = "e${season}x${episode}",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    @Test
    fun `five days a week is a daily show`() {
        val videos = listOf(
            ep(42, 100, "2026-08-24"), // Mon
            ep(42, 101, "2026-08-25"), // Tue
            ep(42, 102, "2026-08-26"), // Wed
            ep(42, 103, "2026-08-27"), // Thu
            ep(42, 104, "2026-08-28")  // Fri
        )
        assertTrue(isDailyShow(videos))
    }

    @Test
    fun `weekly show is not daily`() {
        val sundays = listOf(
            ep(1, 1, "2026-08-02"),
            ep(1, 2, "2026-08-09"),
            ep(1, 3, "2026-08-16"),
            ep(1, 4, "2026-08-23"),
            ep(1, 5, "2026-08-30")
        )
        assertFalse(isDailyShow(sundays))
    }

    @Test
    fun `three days a week is not daily`() {
        val videos = listOf(
            ep(1, 1, "2026-08-03"), // Mon
            ep(1, 2, "2026-08-05"), // Wed
            ep(1, 3, "2026-08-07")  // Fri
        )
        assertFalse(isDailyShow(videos))
    }

    @Test
    fun `too few dated episodes is not guessed`() {
        val videos = listOf(
            ep(1, 1, "2026-08-24"),
            ep(1, 2, "2026-08-25")
        )
        assertFalse(isDailyShow(videos))
    }

    @Test
    fun `missing dates are skipped and do not trigger detection`() {
        val undated = (0 until 12).map { i -> ep(1, i + 1, null) }
        assertFalse(isDailyShow(undated))
    }

    @Test
    fun `specials season zero is ignored`() {
        val specials = listOf(
            ep(0, 1, "2026-08-24"),
            ep(0, 2, "2026-08-25"),
            ep(0, 3, "2026-08-26"),
            ep(0, 4, "2026-08-27"),
            ep(0, 5, "2026-08-28")
        )
        assertFalse(isDailyShow(specials))
    }

    @Test
    fun `daily episodes sort newest air date first`() {
        val videos = listOf(
            ep(42, 100, "2026-08-24"),
            ep(42, 102, "2026-08-26"),
            ep(42, 101, "2026-08-25")
        )
        assertEquals(
            listOf(102, 101, 100),
            sortEpisodesForDisplay(videos, daily = true).map { it.episode }
        )
    }

    @Test
    fun `undated episodes sort last in daily mode`() {
        val videos = listOf(
            ep(42, 100, "2026-08-24"),
            ep(42, 200, null),
            ep(42, 101, "2026-08-25")
        )
        assertEquals(
            listOf(101, 100, 200),
            sortEpisodesForDisplay(videos, daily = true).map { it.episode }
        )
    }

    @Test
    fun `non daily episodes keep numeric order`() {
        val videos = listOf(
            ep(1, 5, null),
            ep(1, 1, "2026-08-24"),
            ep(1, 3, null)
        )
        assertEquals(
            listOf(1, 3, 5),
            sortEpisodesForDisplay(videos, daily = false).map { it.episode }
        )
    }
}
