package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup guard rail for the TV UI.
 *
 * The launch is measured with the baseline profile applied
 * (`CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)`) and the metric is the time
 * the app needs until it shows its first frame.
 *
 * This is a setup-only measurement: no journey is walked inside the measured block, so the
 * number describes the cold launch of the home screen and nothing else. The frame timing of
 * the journeys lives in [NuvioFrameMetricsBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class NuvioStartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupWithBaselineProfile() {
        val device = uiDevice()
        val packageName = resolveTargetPackage(device)
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
            // A cold start: the library kills the app process after the setup block and the
            // launch inside the measured block is therefore a real cold start.
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.prepareForJourney(packageName)
            },
            measureBlock = {
                // The launch has to happen inside the measured block: the startup metric reads
                // the launch from the trace of the measured block, a launch in the setup block
                // is not part of that trace.
                startActivityAndWait()
            }
        )
    }

    private companion object {
        /** Startup medians are noisy, so this runs more iterations than the frame journeys. */
        const val ITERATIONS = 5
    }
}
