package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTunnelAvSyncPolicyTest {

    private fun baseInput() = PlayerTunnelAvSyncPolicy.Input(
        isTunnelingActive = true,
        hasVideoTrack = true,
        isReady = true,
        playWhenReady = true,
        userPausedManually = false,
        positionMs = 0L,
        bufferedPositionMs = 50_000L,
        lastPositionMs = 0L,
        stalledMs = 0L,
        intervalMs = 1_000L,
        stallThresholdMs = 5_000L,
        renderedOutputBufferCount = 1,
        readyMs = 0L,
        noFrameThresholdMs = 8_000L,
        tunnelingAlreadyDisarmed = false,
    )

    // Position advancing every sample, so only the frame leg can decide.
    private fun advancingInput() = baseInput().copy(positionMs = 1_000L, lastPositionMs = 0L)

    @Test
    fun frozenPositionWithDataBuffered_accumulatesUntilThreshold() {
        var stalled = 0L
        repeat(4) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(stalledMs = stalled))
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            stalled = result.stalledMs
        }
        assertEquals(4_000L, stalled)
        val fifth = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(stalledMs = stalled))
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, fifth.decision)
        assertEquals(5_000L, fifth.stalledMs)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, fifth.reason)
    }

    @Test
    fun advancingPosition_resetsTheStall() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(positionMs = 1_200L, lastPositionMs = 0L, stalledMs = 4_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun firstSample_hasNothingToCompare() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(lastPositionMs = null))
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun frozenPositionWithoutDataAhead_isANetworkStallNotTheTunnel() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(bufferedPositionMs = 0L, stalledMs = 4_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
        assertEquals(0L, result.stalledMs)
    }

    @Test
    fun notReadyOrNotPlaying_resetsWithoutDeciding() {
        for (input in listOf(
            baseInput().copy(isReady = false, stalledMs = 4_000L, readyMs = 7_000L),
            baseInput().copy(playWhenReady = false, stalledMs = 4_000L, readyMs = 7_000L),
            baseInput().copy(userPausedManually = true, stalledMs = 4_000L, readyMs = 7_000L),
        )) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(input)
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            assertEquals(0L, result.stalledMs)
            assertEquals(0L, result.readyMs)
        }
    }

    @Test
    fun tunnelingOffOrNoVideoOrDisarmed_stopsTheWatchdog() {
        for (input in listOf(
            baseInput().copy(isTunnelingActive = false),
            baseInput().copy(hasVideoTrack = false),
            baseInput().copy(tunnelingAlreadyDisarmed = true),
        )) {
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.Stop, PlayerTunnelAvSyncPolicy.evaluate(input).decision)
        }
    }

    @Test
    fun noRenderedFramesWhileReady_accumulatesUntilThreshold() {
        var ready = 0L
        repeat(7) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                advancingInput().copy(renderedOutputBufferCount = 0, readyMs = ready)
            )
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            ready = result.readyMs
        }
        assertEquals(7_000L, ready)
        val eighth = PlayerTunnelAvSyncPolicy.evaluate(
            advancingInput().copy(renderedOutputBufferCount = 0, readyMs = ready)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, eighth.decision)
        assertEquals(8_000L, eighth.readyMs)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.NoFramesRendered, eighth.reason)
    }

    @Test
    fun renderedFramesOrUnknownCounters_keepTheFrameLegQuiet() {
        for (count in listOf(1, 250, null)) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                advancingInput().copy(renderedOutputBufferCount = count, readyMs = 20_000L)
            )
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            assertEquals(21_000L, result.readyMs)
            assertNull(result.reason)
        }
    }

    @Test
    fun frozenPosition_isReportedAheadOfMissingFrames() {
        val result = PlayerTunnelAvSyncPolicy.evaluate(
            baseInput().copy(renderedOutputBufferCount = 0, stalledMs = 4_000L, readyMs = 9_000L)
        )
        assertEquals(PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild, result.decision)
        assertEquals(PlayerTunnelAvSyncPolicy.Reason.PositionFrozen, result.reason)
    }
}
