package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigServerSupportTest {
    private class TestServer : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response =
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    @Test
    fun `startServerOnAvailablePort retries until a port works`() {
        val triedPorts = mutableListOf<Int>()
        val server = startServerOnAvailablePort(startPort = 100, maxAttempts = 3) { port ->
            triedPorts += port
            if (triedPorts.size < 3) error("port busy")
            TestServer()
        }
        try {
            assertNotNull(server)
            assertEquals(listOf(100, 101, 102), triedPorts)
        } finally {
            server?.stop()
        }
    }

    @Test
    fun `startServerOnAvailablePort returns null when every port fails`() {
        var attempts = 0
        val server = startServerOnAvailablePort<TestServer>(startPort = 8000, maxAttempts = 4) { _ ->
            attempts++
            error("port busy")
        }
        assertNull(server)
        assertEquals(4, attempts)
    }

    @Test
    fun `htmlResponse is utf8 html`() {
        val response = htmlResponse("<p>héllo</p>")
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("text/html; charset=utf-8", response.mimeType)
    }

    @Test
    fun `pngLogoResponse serves png or 404`() {
        val found = pngLogoResponse(byteArrayOf(1, 2, 3))
        assertEquals(NanoHTTPD.Response.Status.OK, found.status)
        assertEquals("image/png", found.mimeType)

        val missing = pngLogoResponse(null)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, missing.status)
    }
}
