package com.nuvio.tv.ui.screens.player

import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyHeadersProtocolTest {

    private val baseInterceptors = PlayerPlaybackNetworking.createHttpClient().interceptors.size

    @Test
    fun `clients without proxy headers stay on http 1_1 with no retry interceptor`() {
        val client = PlayerPlaybackNetworking.createHttpClient()
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertEquals(baseInterceptors, client.interceptors.size)
    }

    @Test
    fun `a range only header does not add the http 2 retry`() {
        val client = PlayerPlaybackNetworking.createHttpClient(mapOf("Range" to "bytes=0-"))
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertEquals(baseInterceptors, client.interceptors.size)
    }

    @Test
    fun `clients with proxy headers stay on http 1_1 and add exactly one retry interceptor`() {
        val client = PlayerPlaybackNetworking.createHttpClient(mapOf("Referer" to "https://example.com/"))
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertEquals(baseInterceptors + 1, client.interceptors.size)
    }

    @Test
    fun `withHttp2On403Fallback keeps http 1_1 primary protocol and adds retry interceptor`() {
        val base = PlayerPlaybackNetworking.playbackHttpClient
        val wrapped = PlayerPlaybackNetworking.withHttp2On403Fallback(base)
        assertEquals(listOf(Protocol.HTTP_1_1), wrapped.protocols)
        assertEquals(base.interceptors.size + 1, wrapped.interceptors.size)
    }
}
