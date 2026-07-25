package com.nuvio.tv.core.plugin

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Test

class FetchResponseHeadersTest {

    @Test
    fun `response headers preserve duplicate values`() {
        val headers = Headers.Builder()
            .add("X-Test", "Hello")
            .add("X-Test", "World")
            .build()

        assertEquals("Hello,World", headers.toPluginResponseHeaders()["x-test"])
    }
}
