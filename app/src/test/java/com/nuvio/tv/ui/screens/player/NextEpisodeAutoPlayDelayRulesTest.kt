package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class NextEpisodeAutoPlayDelayRulesTest {
    @Test fun `delay is measured from prompt appearance`() {
        assertEquals(20_000L, NextEpisodeAutoPlayDelayRules.remainingMillis(30, 10_000L))
    }

    @Test fun `stream search time counts toward configured delay`() {
        assertEquals(0L, NextEpisodeAutoPlayDelayRules.remainingMillis(30, 35_000L))
    }

    @Test fun `negative elapsed time is safely clamped`() {
        assertEquals(30_000L, NextEpisodeAutoPlayDelayRules.remainingMillis(30, -1L))
    }

    @Test fun `end of episode option has no time deadline`() {
        assertEquals(Long.MAX_VALUE, NextEpisodeAutoPlayDelayRules.remainingMillis(
            PlayerSettings.NEXT_EPISODE_AUTOPLAY_AT_END, 600_000L))
    }

    @Test fun `display seconds round up without adding an extra second`() {
        assertEquals(3, NextEpisodeAutoPlayDelayRules.displaySeconds(3_000L))
        assertEquals(3, NextEpisodeAutoPlayDelayRules.displaySeconds(2_001L))
        assertEquals(0, NextEpisodeAutoPlayDelayRules.displaySeconds(0L))
    }

    @Test fun `end of episode countdown uses live playback timeline`() {
        assertEquals(12, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(48_001L, 60_000L))
        assertEquals(5, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(55_000L, 60_000L))
        assertEquals(0, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(61_000L, 60_000L))
    }

    @Test fun `end of episode countdown is unavailable until duration is known`() {
        assertEquals(null, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(10_000L, 0L))
    }

    @Test fun `end countdown reflects playback speed`() {
        assertEquals(5, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(50_000L, 60_000L, 2f))
        assertEquals(20, NextEpisodeAutoPlayDelayRules.episodeRemainingSeconds(50_000L, 60_000L, 0.5f))
    }

    @Test fun `finite countdown shows natural end when it will happen first`() {
        assertEquals(8, NextEpisodeAutoPlayDelayRules.effectiveCountdownSeconds(30_000L, 8_000L))
        assertEquals(10, NextEpisodeAutoPlayDelayRules.effectiveCountdownSeconds(10_000L, 40_000L))
    }

    @Test fun `end option exposes unknown countdown until duration is known`() {
        assertEquals(null, NextEpisodeAutoPlayDelayRules.effectiveCountdownSeconds(Long.MAX_VALUE, null))
    }

    @Test fun `active timer stops while paused and resumes without losing elapsed time`() {
        val tracker = ActivePlaybackElapsedTracker(startedAtMillis = 1_000L, initiallyPlaying = true)
        assertEquals(2_000L, tracker.sample(nowMillis = 3_000L, isPlaying = false))
        assertEquals(2_000L, tracker.sample(nowMillis = 8_000L, isPlaying = true))
        assertEquals(3_000L, tracker.sample(nowMillis = 9_000L, isPlaying = true))
    }

    @Test fun `stalled end fallback never overrides a manual pause`() {
        assertEquals(true, NextEpisodeAutoPlayDelayRules.isStalledAtEpisodeEnd(59_900L, 60_000L, 2_000L, false))
        assertEquals(false, NextEpisodeAutoPlayDelayRules.isStalledAtEpisodeEnd(59_900L, 60_000L, 2_000L, true))
        assertEquals(false, NextEpisodeAutoPlayDelayRules.isStalledAtEpisodeEnd(59_000L, 60_000L, 5_000L, false))
    }

    @Test fun `meaningful backward seek resets countdown but tiny corrections do not`() {
        assertEquals(true, NextEpisodeAutoPlayDelayRules.shouldResetAfterBackwardSeek(50_000L, 40_000L))
        assertEquals(false, NextEpisodeAutoPlayDelayRules.shouldResetAfterBackwardSeek(50_000L, 49_500L))
        assertEquals(false, NextEpisodeAutoPlayDelayRules.shouldResetAfterBackwardSeek(50_000L, 55_000L))
    }
}
