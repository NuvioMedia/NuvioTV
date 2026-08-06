package com.nuvio.tv.data.simkl

import java.io.IOException
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpSimklEngineTest {
    @Test
    fun `documented bootstrap payloads above eight MiB are accepted`() {
        val payloadSize = 8 * 1024 * 1024 + 1
        val source = Buffer().write(ByteArray(payloadSize) { 'a'.code.toByte() })

        val body = readSimklResponseBody(source, payloadSize.toLong())

        assertEquals(payloadSize, body.length)
        assertTrue(SIMKL_MAX_RESPONSE_BODY_BYTES > payloadSize)
    }

    @Test
    fun `declared oversized responses are rejected before reading`() {
        val source = Buffer().writeUtf8("unread")

        assertThrows(IOException::class.java) {
            readSimklResponseBody(
                source = source,
                contentLength = 1_025L,
                maxResponseBodyBytes = 1_024L
            )
        }

        assertEquals("unread", source.readUtf8())
    }

    @Test
    fun `unknown length responses are bounded while streaming`() {
        val source = Buffer().write(ByteArray(1_025))

        assertThrows(IOException::class.java) {
            readSimklResponseBody(
                source = source,
                contentLength = -1L,
                maxResponseBodyBytes = 1_024L
            )
        }
    }
}
