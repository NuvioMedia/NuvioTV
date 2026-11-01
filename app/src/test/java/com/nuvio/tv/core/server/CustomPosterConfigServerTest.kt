package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class CustomPosterConfigServerTest {

    private val gson = Gson()

    @Test
    fun patternWithBackslashAndQuoteRoundTrips() {
        val pattern = """https://example.com/{id}\{type}"q"""
        var saved: String? = null
        val server = server(current = "", onSaved = { saved = it })

        val response = server.serve(post(gson.toJson(mapOf("pattern" to pattern))))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(pattern, saved)
    }

    @Test
    fun savedPatternIsReadableJson() {
        val pattern = """https://example.com/poster\{id}.jpg"""
        val server = server(current = pattern, onSaved = {})

        val response = server.serve(get("/api/pattern"))
        val body = response.data.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = gson.fromJson(body, JsonObject::class.java)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(pattern, parsed.get("pattern").asString)
    }

    @Test
    fun missingPatternFieldIsABadRequest() {
        var saved: String? = null
        val server = server(current = "", onSaved = { saved = it })

        val response = server.serve(post("""{"nope":"x"}"""))

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals(null, saved)
    }

    private fun server(current: String, onSaved: (String) -> Unit): CustomPosterConfigServer =
        CustomPosterConfigServer(
            currentPatternProvider = { current },
            onPatternChanged = onSaved,
            context = null,
            port = 0
        )

    private fun post(body: String): NanoHTTPD.IHTTPSession = session(NanoHTTPD.Method.POST, "/api/pattern", body)

    private fun get(uri: String): NanoHTTPD.IHTTPSession = session(NanoHTTPD.Method.GET, uri, "")

    private fun session(method: NanoHTTPD.Method, uri: String, body: String): NanoHTTPD.IHTTPSession {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() = Unit
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = mapOf("content-length" to bytes.size.toString())
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun getMethod(): NanoHTTPD.Method = method
            @Deprecated("Deprecated in NanoHTTPD")
            override fun getParms(): Map<String, String> = emptyMap()
            override fun getParameters(): Map<String, List<String>> = emptyMap()
            override fun getQueryParameterString(): String? = null
            override fun getUri(): String = uri
            @Deprecated("Deprecated in NanoHTTPD")
            override fun parseBody(files: MutableMap<String, String>) {
                error("parseBody should not be used")
            }
            override fun getRemoteIpAddress(): String = "127.0.0.1"
            override fun getRemoteHostName(): String = "localhost"
        }
    }
}
