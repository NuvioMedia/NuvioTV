package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLimitsTest {
    @Test
    fun `missing or malformed content length is not rejected`() {
        assertFalse(isRequestBodyTooLarge(null))
        assertFalse(isRequestBodyTooLarge(emptyMap()))
        assertFalse(isRequestBodyTooLarge(mapOf("content-length" to "abc")))
        assertFalse(isRequestBodyTooLarge(mapOf("content-length" to "-5")))
    }

    @Test
    fun `bodies up to the limit are accepted`() {
        assertFalse(isRequestBodyTooLarge(mapOf("content-length" to "0")))
        assertFalse(isRequestBodyTooLarge(mapOf("content-length" to "2048")))
        assertFalse(isRequestBodyTooLarge(mapOf("content-length" to MAX_REQUEST_BODY_BYTES.toString())))
    }

    @Test
    fun `bodies over the limit are rejected`() {
        assertTrue(isRequestBodyTooLarge(mapOf("content-length" to (MAX_REQUEST_BODY_BYTES + 1).toString())))
        assertTrue(isRequestBodyTooLarge(mapOf("content-length" to "2147483647")))
        assertTrue(isRequestBodyTooLarge(mapOf("content-length" to "99999999999")))
    }

    @Test
    fun `rejection response is a 413 and closes the connection`() {
        val response = payloadTooLargeResponse()
        assertEquals(413, response.status.requestStatus)
        assertTrue(response.isCloseConnection)
    }
}
