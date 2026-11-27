package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamClearKeysTest {

    private val kid = "00112233445566778899aabbccddeeff"
    private val key = "ffeeddccbbaa99887766554433221100"

    @Test
    fun `a kid to key object is normalized to hex pairs`() {
        assertEquals("$kid:$key", mapOf(kid.uppercase() to key).toStreamClearKeys())
    }

    @Test
    fun `an array of kid and k entries accepts base64url and UUID forms`() {
        val keys = listOf(
            mapOf("kid" to "ABEiM0RVZneImaq7zN3u_w", "k" to "_-7dzLuqmYh3ZlVEMyIRAA"),
            mapOf("kid" to "00112233-4455-6677-8899-aabbccddeef0", "k" to "/+7dzLuqmYh3ZlVEMyIRAA=="),
            mapOf("kid" to "broken", "k" to key),
            "not an entry"
        )
        assertEquals(
            "$kid:$key,00112233445566778899aabbccddeef0:$key",
            keys.toStreamClearKeys()
        )
    }

    @Test
    fun `a kodi style string keeps every valid pair once`() {
        assertEquals("$kid:$key", " $kid:$key , $kid:$key;nokey ".toStreamClearKeys())
    }

    @Test
    fun `nothing valid gives no keys`() {
        assertNull(mapOf(kid to "0011").toStreamClearKeys())
        assertNull("".toStreamClearKeys())
        assertNull(42.toStreamClearKeys())
        assertNull(null.toStreamClearKeys())
    }
}
