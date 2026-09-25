package com.nuvio.tv.core.plugin

import com.google.gson.JsonParser
import java.util.Base64
import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginBinaryFetchTest {
    @Test
    fun `binary request and response preserve exact bytes`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

            val response = fetch(
                url = server.url("/binary").toString(),
                bodyKind = "base64",
                body = Base64.getEncoder().encodeToString(bytes)
            )

            assertArrayEquals(bytes, server.takeRequest().body.readByteArray())
            assertArrayEquals(
                bytes,
                Base64.getDecoder().decode(response.get("bodyBase64").asString)
            )
        }
    }

    @Test
    fun `text request remains UTF8 encoded`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))

            val response = fetch(
                url = server.url("/text").toString(),
                bodyKind = "text",
                body = "café"
            )

            assertArrayEquals(
                "café".toByteArray(Charsets.UTF_8),
                server.takeRequest().body.readByteArray()
            )
            assertEquals("ok", response.get("body").asString)
        }
    }

    private fun fetch(url: String, bodyKind: String, body: String) =
        PluginRuntime::class.java.getDeclaredMethod(
            "performNativeFetch",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            MutableSet::class.java
        ).apply { isAccessible = true }
            .invoke(
                PluginRuntime(),
                url,
                "POST",
                """{"Content-Type":"application/octet-stream"}""",
                bodyKind,
                body,
                mutableSetOf<Call>()
            )
            .let { JsonParser.parseString(it as String).asJsonObject }
}
