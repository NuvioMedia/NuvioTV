package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeAutoPlayFlowTest {
    @Test
    fun `IntroDB prompt countdown pause resume and natural end stay consistent`() {
        val outro = SkipInterval(1_500.0, 1_620.0, "outro", "introdb")
        assertTrue(showCard(positionMs = 1_500_000L, durationMs = 1_650_000L, outro))

        val tracker = ActivePlaybackElapsedTracker(startedAtMillis = 0L, initiallyPlaying = true)
        tracker.sample(nowMillis = 5_000L, isPlaying = false)
        tracker.sample(nowMillis = 15_000L, isPlaying = true)
        tracker.sample(nowMillis = 20_000L, isPlaying = true)

        val configuredRemaining = NextEpisodeAutoPlayDelayRules.remainingMillis(
            configuredDelaySeconds = 30,
            elapsedMillisSincePrompt = tracker.elapsedMillis(),
        )
        assertEquals(20_000L, configuredRemaining)
        assertEquals(
            8,
            NextEpisodeAutoPlayDelayRules.effectiveCountdownSeconds(
                configuredRemainingMillis = configuredRemaining,
                episodeRemainingMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `backward seek disarms an active provider-driven countdown`() {
        val outro = SkipInterval(1_500.0, 1_620.0, "ed", "aniskip")
        assertTrue(showCard(positionMs = 1_510_000L, durationMs = 1_650_000L, outro))
        assertTrue(NextEpisodeAutoPlayDelayRules.shouldResetAfterBackwardSeek(1_510_000L, 1_300_000L))
        assertFalse(showCard(positionMs = 1_300_000L, durationMs = 1_650_000L, outro))
    }

    @Test
    fun `end option waits without duration then recovers from a missing ended event`() {
        assertEquals(
            Long.MAX_VALUE,
            NextEpisodeAutoPlayDelayRules.remainingMillis(
                configuredDelaySeconds = PlayerSettings.NEXT_EPISODE_AUTOPLAY_AT_END,
                elapsedMillisSincePrompt = 60_000L,
            ),
        )
        assertNull(
            NextEpisodeAutoPlayDelayRules.effectiveCountdownSeconds(
                configuredRemainingMillis = Long.MAX_VALUE,
                episodeRemainingMillis = null,
            ),
        )
        assertTrue(
            NextEpisodeAutoPlayDelayRules.isStalledAtEpisodeEnd(
                currentPositionMillis = 1_649_900L,
                durationMillis = 1_650_000L,
                stableForMillis = 2_000L,
                userPausedManually = false,
            ),
        )
    }

    private fun showCard(positionMs: Long, durationMs: Long, interval: SkipInterval): Boolean =
        PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = listOf(interval),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 98f,
            thresholdMinutesBeforeEnd = 2f,
        )
}
