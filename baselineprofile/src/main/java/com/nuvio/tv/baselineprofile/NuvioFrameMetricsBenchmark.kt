package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing guard rails for the TV UI.
 *
 * Every journey is measured twice: once with the baseline profile applied
 * (`CompilationMode.Partial`) and once without any compilation (`CompilationMode.None`), so the
 * effect of the profile on frame duration can be compared.
 *
 * The metrics are written by androidx.benchmark into the additional test output directory of
 * the module; see `scripts/perf_baseline.sh metrics` for the extraction step.
 */
@RunWith(AndroidJUnit4::class)
class NuvioFrameMetricsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun homeScrollWithBaselineProfile() = measure(Journey.HOME_SCROLL, WITH_PROFILE)

    @Test
    fun homeScrollWithoutCompilation() = measure(Journey.HOME_SCROLL, WITHOUT_COMPILATION)

    @Test
    fun fastScrollWithBaselineProfile() = measure(Journey.FAST_SCROLL, WITH_PROFILE)

    @Test
    fun fastScrollWithoutCompilation() = measure(Journey.FAST_SCROLL, WITHOUT_COMPILATION)

    @Test
    fun navigationWithBaselineProfile() = measure(Journey.NAVIGATION, WITH_PROFILE)

    @Test
    fun navigationWithoutCompilation() = measure(Journey.NAVIGATION, WITHOUT_COMPILATION)

    @Test
    fun playerWithBaselineProfile() = measure(Journey.PLAYER, WITH_PROFILE)

    @Test
    fun playerWithoutCompilation() = measure(Journey.PLAYER, WITHOUT_COMPILATION)

    @Test
    fun settingsLibraryWithBaselineProfile() = measure(Journey.SETTINGS_LIBRARY, WITH_PROFILE)

    @Test
    fun settingsLibraryWithoutCompilation() = measure(Journey.SETTINGS_LIBRARY, WITHOUT_COMPILATION)

    private fun measure(journey: Journey, compilationMode: CompilationMode) {
        val device = uiDevice()
        val packageName = resolveTargetPackage(device)
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.prepareForJourney(packageName)
            },
            measureBlock = {
                device.performJourney(journey)
            }
        )
    }

    private companion object {
        /** Minimum number of iterations that still yields a usable median. */
        const val ITERATIONS = 3

        /** Baseline profile applied, falling back to no compilation when none is installed. */
        val WITH_PROFILE = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)

        /** No compilation at all, the cold guard rail. */
        val WITHOUT_COMPILATION = CompilationMode.None()
    }
}
