package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the baseline profile of the app.
 *
 * Every journey is compiled into its own test so a single unreachable screen (missing catalog,
 * missing stream source) cannot take the whole profile down. The journeys are tolerant on
 * purpose: they wait with a timeout instead of asserting.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start plus the first home scroll, the original profile entry point. */
    @Test
    fun generate() {
        collectJourney(Journey.HOME_SCROLL, includeInStartupProfile = true)
    }

    /** Long horizontal poster scroll plus the vertical row walk. */
    @Test
    fun generateHomeScroll() {
        collectJourney(Journey.HOME_SCROLL)
    }

    /** Rapid focus travel used as the fast-scroll path. */
    @Test
    fun generateFastScroll() {
        collectJourney(Journey.FAST_SCROLL)
    }

    /** Home to detail to episodes and back. */
    @Test
    fun generateNavigation() {
        collectJourney(Journey.NAVIGATION)
    }

    /** Detail to player with the overlay show/hide cycle. */
    @Test
    fun generatePlayer() {
        collectJourney(Journey.PLAYER)
    }

    /** Drawer walk over the library and the settings screens. */
    @Test
    fun generateSettingsLibrary() {
        collectJourney(Journey.SETTINGS_LIBRARY)
    }

    private fun collectJourney(journey: Journey, includeInStartupProfile: Boolean = false) {
        val device = uiDevice()
        rule.collect(
            packageName = resolveTargetPackage(device),
            includeInStartupProfile = includeInStartupProfile
        ) {
            // Force English locale so the onboarding labels are predictable regardless of device language.
            val originalLocale = device.executeShellCommand("getprop persist.sys.locale").trim()
            device.executeShellCommand("setprop persist.sys.locale en-US")
            device.executeShellCommand("settings put system system_locales en-US")

            try {
                pressHome()
                startActivityAndWait()
                device.waitForIdle()
                device.runJourney(journey, packageName)
            } finally {
                // Restore original locale.
                if (originalLocale.isNotBlank()) {
                    device.executeShellCommand("setprop persist.sys.locale $originalLocale")
                    device.executeShellCommand("settings put system system_locales $originalLocale")
                }
            }
        }
    }
}
