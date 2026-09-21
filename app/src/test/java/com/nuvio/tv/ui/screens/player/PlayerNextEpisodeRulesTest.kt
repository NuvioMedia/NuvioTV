package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {

    private fun ep(season: Int?, episode: Int?, id: String = "s${season}e${episode}") =
        Video(
            id = id,
            title = id,
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null
        )

    @Test
    fun `advances to next episode in same season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s1e3", next?.id)
    }

    @Test
    fun `crosses into next season after the season finale`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s2e1", next?.id)
    }

    @Test
    fun `returns null after the very last episode`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertNull(next)
    }

    @Test
    fun `returns null when the current episode is not in the list`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 3, currentEpisode = 9)
        assertNull(next)
    }

    @Test
    fun `absolute numbering advances by episode when the caller has no season`() {
        // Season-less anime: the caller has no season but the meta videos still carry one.
        val videos = listOf(ep(1, 5), ep(1, 6), ep(1, 7))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("s1e7", next?.id)
    }

    @Test
    fun `absolute numbering advances when the meta videos also lack a season`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"), ep(null, 7, "e7"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("e7", next?.id)
    }

    @Test
    fun `absolute numbering returns null after the last episode`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertNull(next)
    }

    @Test
    fun `timestamped episode does not air early on its local release day`() {
        val eastern = ZoneId.of("America/Detroit")
        val before = Clock.fixed(Instant.parse("2026-07-15T14:59:59Z"), eastern)
        val exact = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), eastern)

        assertFalse(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", before))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", exact))
    }

    private fun shouldShow(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval> = emptyList(),
        mode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        percent: Float = 97f,
        minutesBeforeEnd: Float = 2f
    ) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = mode,
        thresholdPercent = percent,
        thresholdMinutesBeforeEnd = minutesBeforeEnd
    )

    private fun outro(startSec: Double, endSec: Double) =
        SkipInterval(startTime = startSec, endTime = endSec, type = "outro", provider = "introdb")

    @Test
    fun `percentage mode fires past the threshold`() {
        assertTrue(shouldShow(positionMs = 44 * 60_000L, durationMs = 45 * 60_000L))
    }

    @Test
    fun `a duration below the position does not fire in percentage mode`() {
        // 25 minutes into a 45 minute episode, player transiently reports 20 minutes.
        assertFalse(shouldShow(positionMs = 25 * 60_000L, durationMs = 20 * 60_000L))
    }

    @Test
    fun `a duration below the position does not fire in minutes mode`() {
        assertFalse(
            shouldShow(
                positionMs = 25 * 60_000L,
                durationMs = 20 * 60_000L,
                mode = NextEpisodeThresholdMode.MINUTES_BEFORE_END
            )
        )
    }

    @Test
    fun `a duration below the position does not fire with outro segments`() {
        assertFalse(
            shouldShow(
                positionMs = 25 * 60_000L,
                durationMs = 20 * 60_000L,
                skipIntervals = listOf(outro(startSec = 1_180.0, endSec = 1_200.0))
            )
        )
    }

    @Test
    fun `the end of the episode still fires within the epsilon`() {
        val durationMs = 45 * 60_000L
        assertTrue(shouldShow(positionMs = durationMs, durationMs = durationMs))
        assertTrue(shouldShow(positionMs = durationMs + 500L, durationMs = durationMs))
    }

    @Test
    fun `position beyond the epsilon does not fire`() {
        val durationMs = 45 * 60_000L
        assertFalse(shouldShow(positionMs = durationMs + 1_001L, durationMs = durationMs))
    }

    @Test
    fun `minutes mode fires inside the window`() {
        assertTrue(
            shouldShow(
                positionMs = 44 * 60_000L,
                durationMs = 45 * 60_000L,
                mode = NextEpisodeThresholdMode.MINUTES_BEFORE_END
            )
        )
    }

    @Test
    fun `an unknown duration never fires`() {
        assertFalse(shouldShow(positionMs = 25 * 60_000L, durationMs = 0L))
    }

    private fun awayFromEnd(positionMs: Long, durationMs: Long) =
        PlayerNextEpisodeRules.isAwayFromEnd(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 97f,
            thresholdMinutesBeforeEnd = 2f
        )

    @Test
    fun `the start of a stream is away from the end`() {
        assertTrue(awayFromEnd(positionMs = 0L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `mid-episode is away from the end`() {
        assertTrue(awayFromEnd(positionMs = 11 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `the previous file's end position is not away from the end`() {
        // Stale reading after a switch: previous episode's end against a similar duration.
        assertFalse(awayFromEnd(positionMs = 22 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `a stale position inside the card threshold is not away from the end`() {
        // Previous episode ended at 22:00; the new one reports 22:20. Before the file end, but
        // past 97%, so it must not arm.
        assertFalse(awayFromEnd(positionMs = 22 * 60_000L, durationMs = 22 * 60_000L + 20_000L))
    }

    @Test
    fun `a resume inside the card window but below completion is not away from the end`() {
        // 17:30 of a 20 minute episode is 87.5%, so it resumes, but it is inside a 3 minute window.
        assertFalse(
            PlayerNextEpisodeRules.isAwayFromEnd(
                positionMs = 17 * 60_000L + 30_000L,
                durationMs = 20 * 60_000L,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 3f
            )
        )
    }

    @Test
    fun `an unknown duration is not away from the end`() {
        assertFalse(awayFromEnd(positionMs = 0L, durationMs = 0L))
    }
}
