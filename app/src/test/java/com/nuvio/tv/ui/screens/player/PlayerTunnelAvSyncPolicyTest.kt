package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
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
        tunnelingAlreadyDisarmed = false,
    )

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
            baseInput().copy(isReady = false, stalledMs = 4_000L),
            baseInput().copy(playWhenReady = false, stalledMs = 4_000L),
            baseInput().copy(userPausedManually = true, stalledMs = 4_000L),
        )) {
            val result = PlayerTunnelAvSyncPolicy.evaluate(input)
            assertEquals(PlayerTunnelAvSyncPolicy.Decision.None, result.decision)
            assertEquals(0L, result.stalledMs)
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
}
