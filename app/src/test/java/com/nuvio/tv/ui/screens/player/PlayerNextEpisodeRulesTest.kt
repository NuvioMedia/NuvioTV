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

    @Test
    fun `IntroDB outro arms post play at the outro start`() {
        val outro = SkipInterval(1_500.0, 1_620.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 1_499_000L, durationMs = 1_650_000L, outro))
        assertTrue(shouldShowAt(positionMs = 1_500_000L, durationMs = 1_650_000L, outro))
    }

    @Test
    fun `IntroDB outro still works when stream duration is unavailable`() {
        val outro = SkipInterval(1_500.0, 1_620.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 1_499_000L, durationMs = 0L, outro))
        assertTrue(shouldShowAt(positionMs = 1_500_000L, durationMs = 0L, outro))
    }

    @Test
    fun `credits and ending aliases are accepted case insensitively`() {
        assertTrue(shouldShowAt(1_500_000L, 1_650_000L, SkipInterval(1_500.0, 1_640.0, "Credits", "future")))
        assertTrue(shouldShowAt(1_500_000L, 1_650_000L, SkipInterval(1_500.0, 1_640.0, "ending", "future")))
    }

    @Test
    fun `invalid outro timestamps fall back to the normal threshold`() {
        val invalidOutro = SkipInterval(1_600.0, 1_500.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 1_500_000L, durationMs = 1_650_000L, invalidOutro))
        assertTrue(shouldShowAt(positionMs = 1_620_000L, durationMs = 1_650_000L, invalidOutro))
    }

    @Test
    fun `wildly early outro marker is capped to five minutes before the end`() {
        val badOutro = SkipInterval(1_800.0, 3_540.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 3_299_000L, durationMs = 3_600_000L, badOutro))
        assertTrue(shouldShowAt(positionMs = 3_300_000L, durationMs = 3_600_000L, badOutro))
    }

    @Test
    fun `outro starting beyond known duration is ignored`() {
        val impossibleOutro = SkipInterval(1_700.0, 1_800.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 1_500_000L, durationMs = 1_650_000L, impossibleOutro))
    }

    @Test
    fun `unknown duration rejects implausibly early marker until playback is established`() {
        val earlyOutro = SkipInterval(30.0, 60.0, "outro", "introdb")

        assertFalse(shouldShowAt(positionMs = 119_000L, durationMs = 0L, earlyOutro))
        assertTrue(shouldShowAt(positionMs = 120_000L, durationMs = 0L, earlyOutro))
    }

    private fun shouldShowAt(positionMs: Long, durationMs: Long, interval: SkipInterval): Boolean =
        PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = listOf(interval),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 98f,
            thresholdMinutesBeforeEnd = 2f,
        )
}
