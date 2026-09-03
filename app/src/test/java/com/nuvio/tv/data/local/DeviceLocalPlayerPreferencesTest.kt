package com.nuvio.tv.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceLocalPlayerPreferencesTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var preferences: DeviceLocalPlayerPreferences

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("device_local_player_prefs_test").toFile()
        context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns tempDir
        }
        preferences = DeviceLocalPlayerPreferences(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun defaultValuesAreFalse() = runTest {
        assertFalse(preferences.playerStatsHudButtonEnabled.first())
        assertFalse(preferences.playerStatsHudActive.first())
    }

    @Test
    fun enablingButtonDoesNotAutoEnableHud() = runTest {
        preferences.setPlayerStatsHudButtonEnabled(true)

        assertTrue(preferences.playerStatsHudButtonEnabled.first())
        assertFalse(preferences.playerStatsHudActive.first())
    }

    @Test
    fun togglingHudActivePersists() = runTest {
        preferences.setPlayerStatsHudButtonEnabled(true)
        preferences.setPlayerStatsHudActive(true)

        assertTrue(preferences.playerStatsHudButtonEnabled.first())
        assertTrue(preferences.playerStatsHudActive.first())

        preferences.setPlayerStatsHudActive(false)
        assertTrue(preferences.playerStatsHudButtonEnabled.first())
        assertFalse(preferences.playerStatsHudActive.first())
    }

    @Test
    fun disablingButtonDisablesHudActiveState() = runTest {
        preferences.setPlayerStatsHudButtonEnabled(true)
        preferences.setPlayerStatsHudActive(true)

        assertTrue(preferences.playerStatsHudButtonEnabled.first())
        assertTrue(preferences.playerStatsHudActive.first())

        // Disabling advanced setting option will disable HUD regardless of previous state
        preferences.setPlayerStatsHudButtonEnabled(false)

        assertFalse(preferences.playerStatsHudButtonEnabled.first())
        assertFalse(preferences.playerStatsHudActive.first())
    }

    @Test
    fun reEnablingButtonKeepsHudDisabled() = runTest {
        preferences.setPlayerStatsHudButtonEnabled(true)
        preferences.setPlayerStatsHudActive(true)
        preferences.setPlayerStatsHudButtonEnabled(false)

        // Re-enabling advanced setting should ONLY enable the button, HUD should remain disabled
        preferences.setPlayerStatsHudButtonEnabled(true)

        assertTrue(preferences.playerStatsHudButtonEnabled.first())
        assertFalse(preferences.playerStatsHudActive.first())
    }
}
