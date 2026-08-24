package com.nuvio.tv.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrServerEndpointTest {

    @Test
    fun `blank input clears the endpoint`() {
        assertEquals("", normalizeTorrServerEndpoint("   "))
        assertEquals("", normalizeTorrServerEndpoint(""))
    }

    @Test
    fun `missing scheme is prefixed with http`() {
        assertEquals("http://10.0.0.5:8090", normalizeTorrServerEndpoint("10.0.0.5:8090"))
        assertEquals("http://torrserver.lan", normalizeTorrServerEndpoint("torrserver.lan"))
    }

    @Test
    fun `valid http url is kept`() {
        assertEquals("http://192.168.1.100:8090", normalizeTorrServerEndpoint("http://192.168.1.100:8090"))
    }

    @Test
    fun `valid https url is kept`() {
        assertEquals("https://ts.example.com", normalizeTorrServerEndpoint("https://ts.example.com"))
    }

    @Test
    fun `trailing slash is trimmed`() {
        assertEquals("http://10.0.0.5:8090", normalizeTorrServerEndpoint("http://10.0.0.5:8090/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("http://10.0.0.5:8090", normalizeTorrServerEndpoint("  http://10.0.0.5:8090  "))
    }

    @Test
    fun `non http scheme is rejected`() {
        assertNull(normalizeTorrServerEndpoint("ftp://10.0.0.5:8090"))
    }

    @Test
    fun `missing host is rejected`() {
        assertNull(normalizeTorrServerEndpoint("http://"))
        assertNull(normalizeTorrServerEndpoint("https:///path"))
    }

    @Test
    fun `unparseable input is rejected`() {
        assertNull(normalizeTorrServerEndpoint("not a url at all"))
        assertNull(normalizeTorrServerEndpoint("http://exa mple.com"))
    }
}