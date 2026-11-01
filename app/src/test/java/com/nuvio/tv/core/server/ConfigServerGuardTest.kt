package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ConfigServerGuardTest {

    @Test
    fun postFromAnotherOriginIsRejected() {
        val response = ConfigServerGuard.rejection(
            Session(
                method = NanoHTTPD.Method.POST,
                host = "192.168.1.10:8091",
                origin = "https://evil.example"
            )
        )

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response?.status)
    }

    @Test
    fun postFromTheSameOriginIsAllowed() {
        val response = ConfigServerGuard.rejection(
            Session(
                method = NanoHTTPD.Method.POST,
                host = "192.168.1.10:8091",
                origin = "http://192.168.1.10:8091"
            )
        )

        assertNull(response)
    }

    @Test
    fun postWithoutAnOriginHeaderIsAllowed() {
        val response = ConfigServerGuard.rejection(
            Session(method = NanoHTTPD.Method.POST, host = "192.168.1.10:8091")
        )

        assertNull(response)
    }

    @Test
    fun opaqueNullOriginIsRejected() {
        val response = ConfigServerGuard.rejection(
            Session(
                method = NanoHTTPD.Method.POST,
                host = "192.168.1.10:8091",
                origin = "null"
            )
        )

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response?.status)
    }

    @Test
    fun dnsNameInHostHeaderIsRejected() {
        // A DNS-rebinding request reaches the server with the attacker's domain in Host.
        val response = ConfigServerGuard.rejection(
            Session(method = NanoHTTPD.Method.GET, host = "attacker.example:8091")
        )

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response?.status)
    }

    @Test
    fun ipAndLocalhostHostHeadersAreAllowed() {
        assertNull(ConfigServerGuard.rejection(Session(method = NanoHTTPD.Method.GET, host = "10.0.0.5:8080")))
        assertNull(ConfigServerGuard.rejection(Session(method = NanoHTTPD.Method.GET, host = "localhost:8080")))
        assertNull(ConfigServerGuard.rejection(Session(method = NanoHTTPD.Method.GET, host = "[::1]:8080")))
    }

    @Test
    fun oversizedDeclaredBodyIsRejected() {
        val response = ConfigServerGuard.rejection(
            Session(
                method = NanoHTTPD.Method.POST,
                host = "192.168.1.10:8092",
                contentLength = ConfigServerGuard.MAX_BODY_BYTES + 1
            )
        )

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response?.status)
    }

    @Test
    fun readBodyRefusesToAllocateForAnOversizedLength() {
        val body = "x".repeat(64)
        val session = Session(
            method = NanoHTTPD.Method.POST,
            host = "192.168.1.10:8092",
            body = body,
            declaredContentLength = Int.MAX_VALUE
        )

        assertEquals("", ConfigServerGuard.readBody(session))
    }

    @Test
    fun readBodyReadsASmallBody() {
        val session = Session(
            method = NanoHTTPD.Method.POST,
            host = "192.168.1.10:8092",
            body = """{"pattern":"a"}"""
        )

        assertEquals("""{"pattern":"a"}""", ConfigServerGuard.readBody(session))
    }

    private class Session(
        private val method: NanoHTTPD.Method,
        private val host: String? = null,
        private val origin: String? = null,
        body: String = "",
        private val contentLength: Int? = null,
        declaredContentLength: Int? = null,
    ) : NanoHTTPD.IHTTPSession {
        private val bytes = body.toByteArray(Charsets.UTF_8)
        private val declaredLength = declaredContentLength ?: contentLength ?: bytes.size

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = buildMap {
            put("content-length", declaredLength.toString())
            host?.let { put("host", it) }
            origin?.let { put("origin", it) }
        }
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getMethod(): NanoHTTPD.Method = method
        @Deprecated("Deprecated in NanoHTTPD")
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = "/"
        @Deprecated("Deprecated in NanoHTTPD")
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}
