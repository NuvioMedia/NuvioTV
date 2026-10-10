package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Http2CustomNetworkTest {

    private fun settings(http2: Boolean, customNetwork: Boolean) = PlayerSettings(
        enableHttp2 = http2,
        parallelNetworkEnabled = customNetwork
    )

    private fun protocolsFor(settings: PlayerSettings): List<Protocol> {
        NuvioExoPlayerPerformanceHelper.enableHttp2 = NuvioExoPlayerPerformanceHelper.http2InEffect(settings)
        return NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(OkHttpClient.Builder()).build().protocols
    }

    @After
    fun tearDown() {
        NuvioExoPlayerPerformanceHelper.enableHttp2 = false
    }

    @Test
    fun `http2 needs both its own toggle and custom network`() {
        assertTrue(NuvioExoPlayerPerformanceHelper.http2InEffect(settings(http2 = true, customNetwork = true)))
        assertFalse(NuvioExoPlayerPerformanceHelper.http2InEffect(settings(http2 = true, customNetwork = false)))
        assertFalse(NuvioExoPlayerPerformanceHelper.http2InEffect(settings(http2 = false, customNetwork = true)))
        assertFalse(NuvioExoPlayerPerformanceHelper.http2InEffect(settings(http2 = false, customNetwork = false)))
    }

    @Test
    fun `a stored http2 toggle under a custom network that is off leaves playback on http 1_1`() {
        assertEquals(listOf(Protocol.HTTP_1_1), protocolsFor(settings(http2 = true, customNetwork = false)))
        assertEquals(
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1),
            protocolsFor(settings(http2 = true, customNetwork = true))
        )
    }
}
