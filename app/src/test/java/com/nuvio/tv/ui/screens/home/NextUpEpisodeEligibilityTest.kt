package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NextUpEpisodeEligibilityTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 19)

    @Test
    fun `a past date is aired`() {
        assertFalse(isNextUpEpisodeUnaired(today.minusDays(1), today))
    }

    @Test
    fun `an episode airing today is aired`() {
        assertFalse(isNextUpEpisodeUnaired(today, today))
    }

    @Test
    fun `a future date is unaired`() {
        assertTrue(isNextUpEpisodeUnaired(today.plusDays(1), today))
    }

    @Test
    fun `a missing date is unaired rather than aired`() {
        assertTrue(isNextUpEpisodeUnaired(null, today))
    }

    @Test
    fun `an unstarted show seed resolves to the first aired episode`() {
        val seed = WatchProgress(
            "tt1", "series", "Series", null, null, null, "tt1:1:0", 1, 0, null,
            1, 1, 1, progressPercent = 100f
        )
        val meta = CwMetaSummary(
            "tt1", "Series", null, null, null, null, emptyList(), null, null, null, null,
            (1..2).map { episode ->
                CwVideoSummary("tt1:1:$episode", "Episode", "2020-01-0${episode}T00:00:00Z", null, 1, episode, null)
            }
        )

        val next = resolveNextUpVideoFromMeta(seed, meta, showUnairedNextUp = false)

        assertEquals(1, next?.season)
        assertEquals(1, next?.episode)
    }
}
