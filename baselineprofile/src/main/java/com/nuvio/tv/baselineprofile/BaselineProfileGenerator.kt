package com.nuvio.tv.baselineprofile

import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LOG_TAG = "NuvioBaselineProfile"

/**
 * Collects the baseline profile of the app.
 *
 * The collection is split in two parts:
 * - the **startup portion** ([collectStartup]): the launch of the app and the settle of the
 *   first screen, collected with `includeInStartupProfile = true`. It therefore only contains
 *   the classes of the launch path, never the ones of a scrolled screen.
 * - the **journey portion** ([collectJourneys]): the five journeys of the app, including the
 *   detail screen step (open a title, come back), collected **without** the startup flag.
 *   Every journey is guarded by its own try/catch, so a single degraded journey does not take
 *   the whole collection down.
 *
 * The five per-journey test methods are kept as collect-profile diagnostics: they collect the
 * profile of a single journey, which is what one needs when a single screen has to be added to
 * the profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Cold start plus all five journeys: the entry point of the profile collection.
     */
    @Test
    fun generate() {
        val device = uiDevice()
        val packageName = resolveTargetPackage(device, DEFAULT_TARGET_PACKAGE)
        device.withEnglishLocale {
            collectStartup(device, packageName)
            collectJourneys(device, packageName)
        }
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

    /**
     * Collects the startup portion of the profile: the launch of the app and the settle of the
     * first screen. Nothing is scrolled here, so the startup profile stays free of the rules
     * that only a scrolled screen produces.
     */
    private fun collectStartup(device: UiDevice, packageName: String) {
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = true,
            maxIterations = COLLECT_MAX_ITERATIONS,
            stableIterations = COLLECT_STABLE_ITERATIONS
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(packageName)), LAUNCH_TIMEOUT_MS)
            device.waitForIdle()
            Thread.sleep(FIRST_SCREEN_SETTLE_MS)
        }
    }

    /**
     * Collects the journey portion of the profile: the detail screen step and the five
     * journeys, none of them with the startup flag.
     */
    private fun collectJourneys(device: UiDevice, packageName: String) {
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = false,
            maxIterations = COLLECT_MAX_ITERATIONS,
            stableIterations = COLLECT_STABLE_ITERATIONS
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(packageName)), LAUNCH_TIMEOUT_MS)

            // The detail screen step: open a title and come back. It is part of the profile on
            // purpose, the detail screen is a screen of its own.
            try {
                device.prepareForJourney(packageName)
                val opened = device.openDetail()
                if (opened) {
                    device.goBack(steps = 2)
                }
                Log.i(LOG_TAG, "Detail screen step: opened=$opened")
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Detail screen step failed: ${t.message}")
            }

            // The five journeys, each one guarded so a degraded journey does not stop the others.
            Journey.entries.forEach { journey ->
                try {
                    val result = device.runJourney(journey, packageName)
                    logJourneyOutcome(result)
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Journey ${journey.key} failed: ${t.message}")
                }
            }
        }
    }

    /** Collects the profile of a single journey, used by the per-journey diagnostics. */
    private fun collectJourney(journey: Journey) {
        val device = uiDevice()
        val packageName = resolveTargetPackage(device, DEFAULT_TARGET_PACKAGE)
        device.withEnglishLocale {
            rule.collect(
                packageName = packageName,
                includeInStartupProfile = false,
                maxIterations = DIAGNOSTIC_MAX_ITERATIONS,
                stableIterations = COLLECT_STABLE_ITERATIONS
            ) {
                pressHome()
                startActivityAndWait()
                device.wait(Until.hasObject(By.pkg(packageName)), LAUNCH_TIMEOUT_MS)
                val result = device.runJourney(journey, packageName)
                logJourneyOutcome(result)
            }
        }
    }

    private companion object {
        /**
         * Upper bound of the collection iterations.
         *
         * The default of the benchmark library is 15 iterations, which multiplies the runtime of
         * every journey by 15. Two iterations already cover the classes of a journey, so the
         * bound keeps the collection of the five journeys in a usable time.
         */
        const val COLLECT_MAX_ITERATIONS = 2

        /**
         * Upper bound of the collection iterations of the per-journey diagnostics.
         *
         * A diagnostic collects the rules of a single journey, one iteration is enough for that.
         */
        const val DIAGNOSTIC_MAX_ITERATIONS = 1

        /** Number of consecutive identical profiles that count as a stable profile. */
        const val COLLECT_STABLE_ITERATIONS = 2

        /** Time budget for the app to show up after the launch. */
        const val LAUNCH_TIMEOUT_MS = 10_000L

        /** Time the first screen needs to settle after the launch. */
        const val FIRST_SCREEN_SETTLE_MS = 3_000L
    }
}
