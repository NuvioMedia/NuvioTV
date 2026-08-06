package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun stepMsForKeyRepeat_rampsWithHold() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(0))
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(2))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(3))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(7))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(8))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(14))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(15))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(100))
    }

    @Test
    fun deltaMsForKeyRepeat_appliesDirection() {
        assertEquals(-PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.deltaMsForKeyRepeat(0, forward = false))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.deltaMsForKeyRepeat(8, forward = true))
    }

    @Test
    fun stepMsForKeyRepeat_clampsNegativeRepeatCount() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(-1))
    }
}
