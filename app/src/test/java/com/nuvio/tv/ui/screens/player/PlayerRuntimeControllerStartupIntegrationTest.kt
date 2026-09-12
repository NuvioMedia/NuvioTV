package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the real controller watchdog, recovery and UI-state boundary with virtual time.
 * The native player and its network delivery are replaced by delayed first-frame callbacks;
 * these tests do not claim codec, HTTP transport or physical-TV coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRuntimeControllerStartupIntegrationTest {
    @After fun cleanupClock() = unmockkStatic(SystemClock::class)

    private class Harness(val controller: PlayerRuntimeController, val state: MutableStateFlow<PlayerUiState>) {
        var generation = 1
        var releasing = false
        var firstFrame = false
        var paused = false
        var watchdog: Job? = null
    }

    private fun TestScope.harness(): Harness {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val state = MutableStateFlow(PlayerUiState())
        val harness = Harness(controller, state)
        val context = mockk<Context>()
        every { context.getString(R.string.player_error_startup_timeout) } returns "Avvio troppo lento"
        every { controller.context } returns context
        every { controller.scope } returns backgroundScope
        every { controller._uiState } returns state
        every { controller.playerInitializationGeneration } answers { harness.generation }
        every { controller.hasRenderedFirstFrame } answers { harness.firstFrame }
        every { controller.isReleasingPlayer } answers { harness.releasing }
        every { controller.userPausedManually } answers { harness.paused }
        every { controller.startupTimeoutWatchdogJob } answers { harness.watchdog }
        every { controller.startupTimeoutWatchdogJob = any() } answers { harness.watchdog = firstArg() }
        every { controller.autoSwitchInternalPlayerOnErrorEnabled } returns false
        every { controller.startupLoadingReportJob } returns null
        every { controller.loadingDiagnosticEvents } returns ArrayDeque()
        every { controller.loadingDiagnosticRawEventLines } returns ArrayDeque()
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { controller.currentStreamUrl } returns "https://fixture.invalid/video.mp4"
        every { controller.launchStartedAtElapsedMs } returns null
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        return harness
    }

    private fun TestScope.deliverFirstFrame(harness: Harness, afterMs: Long) = backgroundScope.launch {
        delay(afterMs)
        harness.firstFrame = true
        harness.controller.finishLoadingDiagnostics("first_frame_rendered")
    }

    @Test fun `slow first frame before timeout leaves no error and cancels watchdog`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        deliverFirstFrame(h, 24_000)
        advanceTimeBy(26_000)
        runCurrent()
        assertTrue(h.firstFrame)
        assertNull(h.state.value.playbackError)
        assertNull(h.watchdog)
    }

    @Test fun `first frame after timeout removes typed timeout and preserves localized UI text`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        deliverFirstFrame(h, 28_000)
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(PlaybackErrorKind.STARTUP_TIMEOUT, h.state.value.playbackError?.kind)
        assertEquals(h.generation, h.state.value.playbackError?.generation)
        assertEquals("Avvio troppo lento", h.state.value.error)
        assertFalse(h.state.value.showLoadingOverlay)
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(h.state.value.error)
        assertNull(h.watchdog)
    }

    @Test fun `real error with identical text after timeout survives late first frame`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(25_000)
        runCurrent()
        val realError = h.controller.playbackError(h.state.value.error!!)
        h.state.value = h.state.value.copy(playbackError = realError)
        h.firstFrame = true
        h.controller.finishLoadingDiagnostics("first_frame_rendered")
        assertEquals(realError, h.state.value.playbackError)
    }

    @Test fun `real error during slow startup stops watchdog without replacing error`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(5_000)
        val error = h.controller.playbackError("Decoder failed")
        h.state.value = h.state.value.copy(playbackError = error)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(error, h.state.value.playbackError)
    }

    @Test fun `cancelling controller watchdog prevents timeout after exit`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(5_000)
        val job = h.watchdog!!
        h.controller.cancelStartupTimeoutWatchdog()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(h.watchdog)
        assertNull(h.state.value.error)
    }

    @Test fun `release-in-progress flag prevents timeout without requiring teardown completion`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(24_000)
        h.releasing = true
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(h.state.value.error)
    }

    @Test fun `replacement attempt has its own complete timeout budget`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(20_000)
        val oldJob = h.watchdog!!
        h.generation++
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(oldJob.isCancelled)
        assertNull(h.state.value.error)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(2, h.state.value.playbackError?.generation)
    }

    @Test fun `invalidated attempt exits even when replacement has not armed a watchdog yet`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(24_000)
        h.generation++
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(h.state.value.error)
    }

    @Test fun `paused slow startup gets a fresh timeout budget after resume`() = runTest {
        val h = harness()
        h.controller.maybeScheduleStartupTimeoutWatchdog()
        advanceTimeBy(20_000)
        h.paused = true
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(h.state.value.error)
        h.paused = false
        advanceTimeBy(24_999)
        assertNull(h.state.value.error)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PlaybackErrorKind.STARTUP_TIMEOUT, h.state.value.playbackError?.kind)
    }
}
