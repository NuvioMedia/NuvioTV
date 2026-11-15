package com.nuvio.tv.core.network

import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NoDelaySocketFactoryTest {

    @Test
    fun `unconnected socket has no delay`() {
        NoDelaySocketFactory.createSocket().use { assertTrue(it.tcpNoDelay) }
    }

    @Test
    fun `connected sockets have no delay`() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val loopback = InetAddress.getLoopbackAddress()
            NoDelaySocketFactory.createSocket(loopback, server.localPort).use { assertTrue(it.tcpNoDelay) }
            NoDelaySocketFactory.createSocket(loopback.hostAddress, server.localPort).use { assertTrue(it.tcpNoDelay) }
        }
    }

    @Test
    fun `okhttp connection socket has no delay`() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        flush()
                    }
                }
            }
            val noDelay = AtomicReference<Boolean>()
            val client = OkHttpClient.Builder()
                .socketFactory(NoDelaySocketFactory)
                .eventListener(object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        noDelay.set(connection.socket().tcpNoDelay)
                    }
                })
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            val url = "http://${server.inetAddress.hostAddress}:${server.localPort}/"
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ok", it.body?.string()) }
            worker.join(5_000)
            assertEquals(true, noDelay.get())
        }
    }

    @Test
    fun `playback clients and their copies use it`() {
        assertSame(PlaybackSocketFactory, PlayerPlaybackNetworking.playbackHttpClient.socketFactory)
        assertSame(PlaybackSocketFactory, PlayerPlaybackNetworking.trustAllPlaybackHttpClient.socketFactory)
        assertSame(PlaybackSocketFactory, PlayerPlaybackNetworking.createHttpClient(mapOf("Authorization" to "x")).socketFactory)
        PlaybackSocketFactory.createSocket().use { assertTrue(it.tcpNoDelay) }
    }
}
