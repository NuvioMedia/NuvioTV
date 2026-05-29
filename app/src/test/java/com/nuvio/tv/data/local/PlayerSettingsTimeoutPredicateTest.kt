package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsTimeoutPredicateTest {

    @Test
    fun `value list last entry equals unlimited sentinel`() {
        assertEquals(
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED,
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.last()
        )
    }

    @Test
    fun `value list is sorted ascending`() {
        val list = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES
        assertEquals(list, list.sorted())
    }

    @Test
    fun `value list contains zero (instant)`() {
        assertTrue(0 in PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES)
    }

    @Test
    fun `unlimited sentinel does not collide with a real value`() {
        val withoutSentinel = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.dropLast(1)
        assertFalse(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED in withoutSentinel)
    }

    @Test
    fun `value list contains expected ten-to-thirty entries`() {
        val list = PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES
        assertTrue(10 in list)
        assertTrue(15 in list)
        assertTrue(20 in list)
        assertTrue(25 in list)
        assertTrue(30 in list)
    }

    @Test
    fun `isBoundedTimeout returns false for zero (instant)`() {
        assertFalse(PlayerSettings.isBoundedTimeout(0))
    }

    @Test
    fun `isBoundedTimeout returns true for 1 second`() {
        assertTrue(PlayerSettings.isBoundedTimeout(1))
    }

    @Test
    fun `isBoundedTimeout returns true for 10 seconds (old max)`() {
        assertTrue(PlayerSettings.isBoundedTimeout(10))
    }

    @Test
    fun `isBoundedTimeout returns true for new values 15 20 25 30`() {
        assertTrue(PlayerSettings.isBoundedTimeout(15))
        assertTrue(PlayerSettings.isBoundedTimeout(20))
        assertTrue(PlayerSettings.isBoundedTimeout(25))
        assertTrue(PlayerSettings.isBoundedTimeout(30))
    }

    @Test
    fun `isBoundedTimeout returns false for the unlimited sentinel`() {
        assertFalse(PlayerSettings.isBoundedTimeout(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED))
    }

    @Test
    fun `isBoundedTimeout returns false for negative values (defensive)`() {
        assertFalse(PlayerSettings.isBoundedTimeout(-5))
        assertFalse(PlayerSettings.isBoundedTimeout(-1))
    }
}
