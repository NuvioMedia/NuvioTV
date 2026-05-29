package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSettingsTimeoutMigrationTest {

    @Test
    fun `null stored value returns default of 3 seconds`() {
        assertEquals(3, PlayerSettings.applyLegacyTimeoutSentinelMigration(null))
    }

    @Test
    fun `legacy sentinel 11 translates to unlimited`() {
        assertEquals(
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED,
            PlayerSettings.applyLegacyTimeoutSentinelMigration(11)
        )
    }

    @Test
    fun `known value 5 passes through unchanged`() {
        assertEquals(5, PlayerSettings.applyLegacyTimeoutSentinelMigration(5))
    }

    @Test
    fun `known value 30 passes through unchanged`() {
        assertEquals(30, PlayerSettings.applyLegacyTimeoutSentinelMigration(30))
    }

    @Test
    fun `every entry in STREAM_AUTOPLAY_TIMEOUT_VALUES passes through unchanged`() {
        PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES.forEach { value ->
            assertEquals(
                "value $value should pass through migration unchanged",
                value,
                PlayerSettings.applyLegacyTimeoutSentinelMigration(value)
            )
        }
    }

    @Test
    fun `current sentinel passes through unchanged`() {
        assertEquals(
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED,
            PlayerSettings.applyLegacyTimeoutSentinelMigration(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED)
        )
    }

    @Test
    fun `unknown value 12 snaps down to 10 (closer than 15)`() {
        assertEquals(10, PlayerSettings.applyLegacyTimeoutSentinelMigration(12))
    }

    @Test
    fun `unknown value 13 snaps up to 15`() {
        assertEquals(15, PlayerSettings.applyLegacyTimeoutSentinelMigration(13))
    }

    @Test
    fun `unknown value 99 snaps down to 30`() {
        assertEquals(30, PlayerSettings.applyLegacyTimeoutSentinelMigration(99))
    }

    @Test
    fun `negative value snaps up to 0`() {
        assertEquals(0, PlayerSettings.applyLegacyTimeoutSentinelMigration(-5))
    }

    @Test
    fun `Int MAX_VALUE minus one snaps to 30`() {
        assertEquals(
            30,
            PlayerSettings.applyLegacyTimeoutSentinelMigration(Int.MAX_VALUE - 1)
        )
    }

    @Test
    fun `large unknown value snaps to 30 not unlimited`() {
        assertEquals(30, PlayerSettings.applyLegacyTimeoutSentinelMigration(1_000_000))
    }
}
