package com.nuvio.tv.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchResponseBodyTest {

    @Test
    fun `response above one megabyte is preserved within production limit`() {
        val payload = ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }

        val result = readAtMostBytes(payload.inputStream(), MAX_FETCH_RESPONSE_BYTES)
        val decoded = decodeBodyToSafeString(result.bytes, Charsets.UTF_8)

        assertEquals(payload.size, result.bytes.size)
        assertEquals(payload.size, decoded.length)
        assertFalse(result.truncated)
    }

    @Test
    fun `response above production limit is truncated and reported`() {
        val payload = ByteArray(MAX_FETCH_RESPONSE_BYTES + 1) { 'a'.code.toByte() }

        val result = readAtMostBytes(payload.inputStream(), MAX_FETCH_RESPONSE_BYTES)

        assertEquals(MAX_FETCH_RESPONSE_BYTES, result.bytes.size)
        assertTrue(result.truncated)
    }
}
