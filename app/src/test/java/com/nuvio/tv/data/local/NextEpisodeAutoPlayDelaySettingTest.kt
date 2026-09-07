package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeAutoPlayDelaySettingTest {
    @Test fun `default preserves existing three second countdown`() {
        assertEquals(3, PlayerSettings.normalizeNextEpisodeAutoPlayDelay(null))
    }

    @Test fun `all exposed values round trip unchanged`() {
        PlayerSettings.NEXT_EPISODE_AUTOPLAY_DELAY_VALUES.forEach {
            assertEquals(it, PlayerSettings.normalizeNextEpisodeAutoPlayDelay(it))
        }
    }

    @Test fun `unknown persisted values migrate to nearest supported value`() {
        assertEquals(30, PlayerSettings.normalizeNextEpisodeAutoPlayDelay(29))
        assertEquals(300, PlayerSettings.normalizeNextEpisodeAutoPlayDelay(999))
        assertEquals(3, PlayerSettings.normalizeNextEpisodeAutoPlayDelay(-5))
    }

    @Test fun `end of episode remains the final selectable value`() {
        assertEquals(PlayerSettings.NEXT_EPISODE_AUTOPLAY_AT_END,
            PlayerSettings.NEXT_EPISODE_AUTOPLAY_DELAY_VALUES.last())
        assertTrue(PlayerSettings.NEXT_EPISODE_AUTOPLAY_DELAY_VALUES.dropLast(1).all { it > 0 })
    }
}
