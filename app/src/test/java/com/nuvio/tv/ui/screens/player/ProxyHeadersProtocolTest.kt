package com.nuvio.tv.ui.screens.player

import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyHeadersProtocolTest {

    @Test
    fun `clients without proxy headers stay on http 1_1`() {
        val client = PlayerPlaybackNetworking.createHttpClient()
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun `a range only header does not opt in to http 2`() {
        val client = PlayerPlaybackNetworking.createHttpClient(mapOf("Range" to "bytes=0-"))
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun `clients with proxy headers may negotiate http 2 and fall back to http 1_1`() {
        val client = PlayerPlaybackNetworking.createHttpClient(mapOf("Referer" to "https://example.com/"))
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), client.protocols)
    }
}
