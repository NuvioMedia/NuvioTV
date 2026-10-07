package com.nuvio.tv.ui.screens.player

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpLanesTest {

    private val pools = List(HttpLanes.MAX_LANES) { ConnectionPool(4, 1, TimeUnit.MINUTES) }

    private fun client(vararg protocols: Protocol) = OkHttpClient.Builder()
        .connectionPool(pools[0])
        .protocols(protocols.toList())
        .build()

    @Test
    fun `http 1 only keeps the single client`() {
        val base = client(Protocol.HTTP_1_1)
        assertSame(base, HttpLanes.callFactory(base, 4, pools::get))
    }

    @Test
    fun `one connection keeps the single client`() {
        val base = client(Protocol.HTTP_2, Protocol.HTTP_1_1)
        assertSame(base, HttpLanes.callFactory(base, 1, pools::get))
    }

    @Test
    fun `each lane gets its own pool and lane 0 keeps the warm one`() {
        val base = client(Protocol.HTTP_2, Protocol.HTTP_1_1)
        val lanes = (HttpLanes.callFactory(base, 3, pools::get) as LaneCallFactory).lanes
        assertEquals(3, lanes.size)
        assertSame(base, lanes[0])
        assertSame(pools[1], lanes[1].connectionPool)
        assertSame(pools[2], lanes[2].connectionPool)
        assertNotSame(lanes[1].connectionPool, lanes[2].connectionPool)
    }

    @Test
    fun `lane count is capped`() {
        val base = client(Protocol.HTTP_2, Protocol.HTTP_1_1)
        val lanes = (HttpLanes.callFactory(base, 9, pools::get) as LaneCallFactory).lanes
        assertEquals(HttpLanes.MAX_LANES, lanes.size)
    }

    @Test
    fun `restart sends the next request to lane 0`() {
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            repeat(8) { server.enqueue(MockResponse().setBody("x")) }
            server.start()
            val url = server.url("/f")
            val lanes = HttpLanes.callFactory(client(Protocol.H2_PRIOR_KNOWLEDGE), 4, pools::get) as LaneCallFactory
            repeat(3) { lanes.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            lanes.restart()
            lanes.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
            repeat(3) { server.takeRequest(5, TimeUnit.SECONDS) }
            // Lane 0's connection carried the first request, so the request after restart is its second stream.
            assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
        }
    }

    @Test
    fun `lane clients keep the base client's settings`() {
        val base = client(Protocol.HTTP_2, Protocol.HTTP_1_1).newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .addInterceptor { it.proceed(it.request()) }
            .build()
        val lane = (HttpLanes.callFactory(base, 2, pools::get) as LaneCallFactory).lanes[1]
        assertEquals(base.connectTimeoutMillis, lane.connectTimeoutMillis)
        assertEquals(base.interceptors, lane.interceptors)
        assertSame(base.dispatcher, lane.dispatcher)
        assertSame(base.dns, lane.dns)
        assertSame(base.eventListenerFactory, lane.eventListenerFactory)
    }

    @Test
    fun `http 2 requests spread over one connection per lane`() {
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            repeat(16) { server.enqueue(MockResponse().setBody("x")) }
            server.start()
            val base = client(Protocol.H2_PRIOR_KNOWLEDGE)
            val url = server.url("/f")

            repeat(4) { base.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            val single = List(4) { server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber }
            assertEquals(listOf(0, 1, 2, 3), single)

            val lanes = HttpLanes.callFactory(base, 4, pools::get)
            repeat(8) { lanes.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            val laned = List(8) { server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber }
            // Lane 0 reuses the warm connection; lanes 1-3 each open their own.
            assertEquals(listOf(4, 0, 0, 0, 5, 1, 1, 1), laned)
            assertTrue(pools.all { it.connectionCount() == 1 })
        }
    }
}
