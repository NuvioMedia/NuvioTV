package com.nuvio.tv.core.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NntpServerConfigTest {
    @Test
    fun `parses TLS credentials and connection count`() {
        val config = NntpServerConfig.parse(
            "nntps://user%40example.com:p%2Bass%3Aword@news.example.com:563/20"
        )

        assertEquals(NntpTransport.TLS, config.transport)
        assertEquals("news.example.com", config.host)
        assertEquals(563, config.port)
        assertEquals("user@example.com", config.username)
        assertEquals("p+ass:word", config.password)
        assertEquals(20, config.connections)
        assertEquals("nntps://news.example.com:563/20", config.redactedUri)
    }

    @Test
    fun `uses protocol defaults without credentials`() {
        val config = NntpServerConfig.parse("nntp://news.example.com")

        assertEquals(NntpTransport.PLAIN, config.transport)
        assertEquals(119, config.port)
        assertEquals(1, config.connections)
        assertNull(config.username)
        assertNull(config.password)
    }

    @Test
    fun `string representation never contains credentials`() {
        val config = NntpServerConfig.parse("nntps://secret-user:secret-pass@news.example.com/8")
        val rendered = config.toString()

        assertFalse(rendered.contains("secret-user"))
        assertFalse(rendered.contains("secret-pass"))
        assertEquals("NntpServerConfig(uri=nntps://news.example.com:563/8)", rendered)
    }

    @Test
    fun `rejects unsupported schemes and excessive connections`() {
        assertThrows(IllegalArgumentException::class.java) {
            NntpServerConfig.parse("https://news.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NntpServerConfig.parse("nntps://news.example.com/101")
        }
    }
}
