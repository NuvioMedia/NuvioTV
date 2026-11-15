package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class ReceiveWindowTest {
    private val mib = 1024 * 1024

    @Test
    fun `fire tv grant above its 2 MiB tuning ceiling is used`() {
        assertTrue(ReceiveWindow.shouldAsk(grantedBytes = 16 * mib, tuningCeilingBytes = 2 * mib))
    }

    @Test
    fun `a small rmem_max is left to the kernel`() {
        // Stock Amlogic firmware: rmem_max 256 KiB, so asking would cap the socket below its tuning.
        assertFalse(ReceiveWindow.shouldAsk(grantedBytes = 512 * 1024, tuningCeilingBytes = 4 * mib))
    }

    @Test
    fun `a grant no larger than the tuning ceiling is left to the kernel`() {
        assertFalse(ReceiveWindow.shouldAsk(grantedBytes = 16 * mib, tuningCeilingBytes = 16 * mib))
        assertFalse(ReceiveWindow.shouldAsk(grantedBytes = 8 * mib, tuningCeilingBytes = 16 * mib))
    }

    @Test
    fun `unknown ceiling needs the largest grant`() {
        assertTrue(ReceiveWindow.shouldAsk(grantedBytes = 16 * mib, tuningCeilingBytes = null))
        assertFalse(ReceiveWindow.shouldAsk(grantedBytes = 8 * mib, tuningCeilingBytes = null))
        assertFalse(ReceiveWindow.shouldAsk(grantedBytes = 0, tuningCeilingBytes = null))
    }

    @Test
    fun `decide asks for the request when the fire tv grant beats its ceiling`() {
        assertEquals(ReceiveWindow.REQUEST_BYTES, ReceiveWindow.decide({ it * 2 }, { 2 * mib }))
    }

    @Test
    fun `decide leaves the kernel alone on a small grant or an unreadable box`() {
        assertEquals(0, ReceiveWindow.decide({ 512 * 1024 }, { 4 * mib }))
        assertEquals(0, ReceiveWindow.decide({ it }, { null }))
        assertEquals(0, ReceiveWindow.decide({ throw java.io.IOException("refused") }, { 2 * mib }))
    }

    @Test
    fun `decide without a readable ceiling needs the whole request granted`() {
        assertEquals(ReceiveWindow.REQUEST_BYTES, ReceiveWindow.decide({ it * 2 }, { null }))
    }

    @Test
    fun `connected overloads keep the plain buffer and no delay`() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val plain = java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort).use { it.receiveBufferSize }
            PlaybackSocketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort).use {
                assertTrue(it.isConnected)
                assertTrue(it.tcpNoDelay)
                assertEquals(plain, it.receiveBufferSize)
            }
        }
    }
}
