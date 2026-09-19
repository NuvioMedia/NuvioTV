package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.SurroundFormatResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TunnelDeadClockMemoTest {

    private val direct = SurroundFormatResolver.DirectSupport(
        ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = false
    )
    private val signature = PlayerTunnelAvSyncPolicy.chainSignature(
        fingerprint = "fp1", routeKey = "type:hdmi|name:box", direct = direct, maxPcmChannels = 8
    )

    @Before
    fun clean() {
        PlayerTunnelAvSyncPolicy.resetMemo()
    }

    @After
    fun cleanup() {
        PlayerTunnelAvSyncPolicy.resetMemo()
    }

    @Test
    fun signature_changesWithFirmwareRouteClaimsAndPcmWidth() {
        val base = signature
        assertNotEquals(base, PlayerTunnelAvSyncPolicy.chainSignature("fp2", "type:hdmi|name:box", direct, 8))
        assertNotEquals(base, PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:other", direct, 8))
        assertNotEquals(base, PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", direct.copy(dtsHd = true), 8))
        assertNotEquals(base, PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", direct, 2))
        assertEquals(base, PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", direct, 8))
        assertEquals("fp1|type:hdmi|name:box|na|na", PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", null, null))
    }

    @Test
    fun signature_distinguishesUnreadableFromReadAsEmpty() {
        // A snapshot taken while the sink was gone (null) must not match one taken against a
        // sink that answered but claimed nothing (all-false direct, or a channel count of 0),
        // otherwise a memo learned during an HDMI drop matches a real chain.
        val allFalse = SurroundFormatResolver.DirectSupport(
            ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false
        )
        assertNotEquals(
            PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", null, null),
            PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", allFalse, 0)
        )
    }

    @Test
    fun seed_restoresOnlyUnderTheSameSignature() {
        val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), "some-other-chain", signature)
        assertTrue(seeded.isEmpty())
        assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())

        PlayerTunnelAvSyncPolicy.resetMemo()
        val restored = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        assertEquals(setOf("pcm"), restored)
        assertEquals(setOf("pcm"), PlayerTunnelAvSyncPolicy.deadAudioClasses)
    }

    @Test
    fun seed_runsOncePerSignature() {
        PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        PlayerTunnelAvSyncPolicy.deadAudioClasses.remove("pcm")
        val again = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        assertTrue(again.isEmpty())
        assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())
    }

    @Test
    fun seed_underANewSignatureDropsWhatTheOldChainTaught() {
        PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        val other = PlayerTunnelAvSyncPolicy.chainSignature("fp1", "type:hdmi|name:box", direct.copy(dtsHd = true), 8)
        val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, other)
        assertTrue(seeded.isEmpty())
        assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())
    }

    @Test
    fun resetMemo_clearsAndAllowsReseed() {
        PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        PlayerTunnelAvSyncPolicy.resetMemo()
        assertTrue(PlayerTunnelAvSyncPolicy.deadAudioClasses.isEmpty())
        val restored = PlayerTunnelAvSyncPolicy.seedFromStore(setOf("pcm"), signature, signature)
        assertEquals(setOf("pcm"), restored)
    }
}
