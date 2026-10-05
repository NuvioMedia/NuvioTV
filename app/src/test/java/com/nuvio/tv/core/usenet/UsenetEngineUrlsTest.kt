package com.nuvio.tv.core.usenet

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsenetEngineUrlsTest {
    private val id = "0123456789abcdef0123456789abcdef0123456789abcdef"

    @Before fun remember() {
        UsenetEngineUrls.clear()
        UsenetEngineUrls.remember(41234)
    }

    @After fun reset() = UsenetEngineUrls.clear()

    @Test fun `engine session and subtitle URLs are recognized`() {
        assertTrue(UsenetEngineUrls.isSession("http://127.0.0.1:41234/stream/$id/Movie.2025.mkv"))
        assertTrue(UsenetEngineUrls.isSubtitle("http://127.0.0.1:41234/subtitle/$id/3/Movie.en.srt"))
    }

    @Test fun `torrent engine URLs on loopback are not Usenet`() {
        assertFalse(UsenetEngineUrls.isSession("http://127.0.0.1:38211/stream/5f3c2a1b"))
        assertFalse(UsenetEngineUrls.isSession("http://127.0.0.1:38211/stream/$id/Movie.mkv"))
        assertFalse(UsenetEngineUrls.isSession("http://127.0.0.1:41234/stream/5f3c2a1b"))
        assertFalse(UsenetEngineUrls.isSubtitle("http://127.0.0.1:38211/subtitle/$id/3/Movie.en.srt"))
    }

    @Test fun `other hosts and paths are not Usenet`() {
        assertFalse(UsenetEngineUrls.isSession("https://cdn.example.test/stream/$id/Movie.mkv"))
        assertFalse(UsenetEngineUrls.isSession("http://127.0.0.1:412340/stream/$id/Movie.mkv"))
        assertFalse(UsenetEngineUrls.isSession("http://127.0.0.1:41234/subtitle/$id/3/Movie.en.srt"))
        assertFalse(UsenetEngineUrls.isSubtitle("http://127.0.0.1:41234/stream/$id/Movie.mkv"))
    }

    @Test fun `ports from earlier engine starts stay recognized`() {
        UsenetEngineUrls.remember(42000)
        assertTrue(UsenetEngineUrls.isSession("http://127.0.0.1:41234/stream/$id/Movie.mkv"))
        assertTrue(UsenetEngineUrls.isSession("http://127.0.0.1:42000/stream/$id/Movie.mkv"))
    }
}
