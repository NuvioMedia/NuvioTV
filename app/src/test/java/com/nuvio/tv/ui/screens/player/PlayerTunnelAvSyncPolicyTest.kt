package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTunnelAvSyncPolicyTest {

    private fun baseInput() = PlayerTunnelAvSyncPolicy.Input(
        isTunnelingActive = true,
        isIecHbrActive = true,
        hasVideoTrack = true,
        isReady = true,
        playWhenReady = true,
        userPausedManually = false,
        renderedOutputBufferCount = 0,
        tunnelingAlreadyDisarmed = false,
    )

    @Test
    fun zeroRenderedFramesUnderTunnelWithIecHbr_disablesTunneling() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput())
        )
    }

    @Test
    fun framesRendered_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(renderedOutputBufferCount = 12))
        )
    }

    @Test
    fun tunnelingOff_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(isTunnelingActive = false))
        )
    }

    @Test
    fun iecNotActive_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(isIecHbrActive = false))
        )
    }

    @Test
    fun noVideoTrack_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(hasVideoTrack = false))
        )
    }

    @Test
    fun notReady_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(isReady = false))
        )
    }

    @Test
    fun notPlayingOrPaused_isHealthy() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(playWhenReady = false))
        )
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(userPausedManually = true))
        )
    }

    @Test
    fun alreadyDisarmed_doesNotRepeat() {
        assertEquals(
            PlayerTunnelAvSyncPolicy.Decision.None,
            PlayerTunnelAvSyncPolicy.evaluate(baseInput().copy(tunnelingAlreadyDisarmed = true))
        )
    }
}
