package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

/**
 * Contract tests for watchdog identity gates and job cancel-on-release hygiene.
 *
 * Bug context:
 * 1) releasePlayer must cancel firstFrame/stall watchdogs (not leave orphan Jobs).
 * 2) Delayed callbacks must refuse to act when _exoPlayer was replaced.
 */
class PlayerWatchdogPolicyTest {

    private val playerA = Any()
    private val playerB = Any()

    // ── identity (bug 2) ─────────────────────────────────────────────

    @Test
    fun `same player instance is eligible`() {
        assertTrue(shouldApplyDelayedWatchdogToPlayer(playerA, playerA))
    }

    @Test
    fun `different player instance is not eligible`() {
        assertFalse(shouldApplyDelayedWatchdogToPlayer(playerA, playerB))
    }

    @Test
    fun `null live player is not eligible`() {
        assertFalse(shouldApplyDelayedWatchdogToPlayer(playerA, null))
    }

    @Test
    fun `null captured player is not eligible`() {
        assertFalse(shouldApplyDelayedWatchdogToPlayer(null, playerA))
    }

    @Test
    fun `both null is not eligible`() {
        assertFalse(shouldApplyDelayedWatchdogToPlayer(null, null))
    }

    // ── first-frame action gate ──────────────────────────────────────

    @Test
    fun `first frame action runs when same player and still waiting`() {
        assertTrue(
            shouldRunFirstFrameWatchdogAction(
                capturedPlayer = playerA,
                livePlayer = playerA,
                hasRenderedFirstFrame = false,
                userPausedManually = false
            )
        )
    }

    @Test
    fun `first frame action skips after real first frame`() {
        assertFalse(
            shouldRunFirstFrameWatchdogAction(
                capturedPlayer = playerA,
                livePlayer = playerA,
                hasRenderedFirstFrame = true,
                userPausedManually = false
            )
        )
    }

    @Test
    fun `first frame action skips when user paused`() {
        assertFalse(
            shouldRunFirstFrameWatchdogAction(
                capturedPlayer = playerA,
                livePlayer = playerA,
                hasRenderedFirstFrame = false,
                userPausedManually = true
            )
        )
    }

    @Test
    fun `first frame action skips when player rebuilt during delay`() {
        // releasePlayer/rebuild path: captured A, live is now B
        assertFalse(
            shouldRunFirstFrameWatchdogAction(
                capturedPlayer = playerA,
                livePlayer = playerB,
                hasRenderedFirstFrame = false,
                userPausedManually = false
            )
        )
    }

    @Test
    fun `first frame action skips when player released during delay`() {
        assertFalse(
            shouldRunFirstFrameWatchdogAction(
                capturedPlayer = playerA,
                livePlayer = null,
                hasRenderedFirstFrame = false,
                userPausedManually = false
            )
        )
    }

    // ── stall gate ───────────────────────────────────────────────────

    @Test
    fun `stall iteration requires same player`() {
        assertTrue(shouldRunStallWatchdogIteration(playerA, playerA))
        assertFalse(shouldRunStallWatchdogIteration(playerA, playerB))
        assertFalse(shouldRunStallWatchdogIteration(playerA, null))
    }

    // ── cancel hygiene (bug 1) ───────────────────────────────────────

    @Test
    fun `cancelAndClearWatchdogJob cancels active job and returns null`() = runTest {
        val job = launch {
            delay(60_000)
        }
        assertTrue(job.isActive)

        val cleared = cancelAndClearWatchdogJob(job)

        assertNull(cleared)
        assertTrue(job.isCancelled)
        assertFalse(job.isActive)
    }

    @Test
    fun `cancelAndClearWatchdogJob is safe on null`() {
        assertNull(cancelAndClearWatchdogJob(null))
    }

    @Test
    fun `cancelAndClearWatchdogJob is safe on already completed job`() = runTest {
        val job = launch { /* immediate complete */ }
        runCurrent()
        assertTrue(job.isCompleted)

        val cleared = cancelAndClearWatchdogJob(job)
        assertNull(cleared)
    }

    /**
     * Simulates the releasePlayer contract: both watchdog holders must be cleared
     * so delayed coroutines cannot outlive the released ExoPlayer.
     */
    @Test
    fun `release clears both first-frame and stall watchdog holders`() = runTest {
        var firstFrameWatchdogJob: Job? = launch { delay(12_000) }
        var stallWatchdogJob: Job? = launch {
            while (isActive) {
                delay(1_000)
            }
        }

        // What releasePlayer must do (via cancelFirstFrameWatchdog / cancelStallWatchdog).
        firstFrameWatchdogJob = cancelAndClearWatchdogJob(firstFrameWatchdogJob)
        stallWatchdogJob = cancelAndClearWatchdogJob(stallWatchdogJob)

        assertNull(firstFrameWatchdogJob)
        assertNull(stallWatchdogJob)

        // Advancing time must not keep work alive under cancelled jobs.
        advanceTimeBy(30_000)
        runCurrent()
    }

    /**
     * If identity check is missing, a delayed job would treat a new player as fair game.
     * This documents the rebuild race: captured A, live B → must not apply.
     */
    @Test
    fun `rebuild race matrix - only same instance may act`() {
        data class Case(
            val captured: Any?,
            val live: Any?,
            val firstFrameDone: Boolean,
            val paused: Boolean,
            val expectAct: Boolean
        )
        val cases = listOf(
            Case(playerA, playerA, false, false, true),
            Case(playerA, playerB, false, false, false),
            Case(playerA, null, false, false, false),
            Case(playerA, playerA, true, false, false),
            Case(playerA, playerA, false, true, false),
            Case(playerB, playerB, false, false, true)
        )
        cases.forEach { c ->
            assertTrue(
                "case=$c",
                shouldRunFirstFrameWatchdogAction(
                    capturedPlayer = c.captured,
                    livePlayer = c.live,
                    hasRenderedFirstFrame = c.firstFrameDone,
                    userPausedManually = c.paused
                ) == c.expectAct
            )
        }
    }

    @Test
    fun `orphan job without cancel stays active - documents pre-fix risk`() = runTest {
        // Baseline: without cancelAndClear, a delayed job survives "release".
        val orphan = launch { delay(12_000) }
        // Simulated release that forgets to cancel:
        // (do nothing)
        assertTrue("pre-fix: orphan job still active", orphan.isActive)
        advanceTimeBy(5_000)
        assertTrue(orphan.isActive)
        orphan.cancel()
    }
}
