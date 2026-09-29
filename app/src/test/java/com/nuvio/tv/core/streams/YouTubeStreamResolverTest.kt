package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStreamResolverTest {

    private val manifestUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/1/file/index.m3u8"

    @Test
    fun `a ytId stream resolves to the extracted url`() = runTest {
        val requested = mutableListOf<String>()
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { url ->
            requested += url
            manifestUrl
        }

        val resolved = resolver.resolve(youTubeStream())

        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), requested)
        assertEquals(manifestUrl, resolved?.url)
        assertEquals(manifestUrl, resolved?.getStreamUrl())
        assertFalse(resolved!!.isExternal())
        assertNull(resolved.youTubeIdToResolve())
    }

    @Test
    fun `the resolved stream keeps everything else from the original`() = runTest {
        val original = youTubeStream()
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { manifestUrl }

        val resolved = resolver.resolve(original)

        assertEquals(original.copy(url = manifestUrl), resolved)
    }

    @Test
    fun `a video that can't be extracted resolves to null`() = runTest {
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { null }

        assertNull(resolver.resolve(youTubeStream()))
    }

    @Test
    fun `a blank extracted url resolves to null`() = runTest {
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { "  " }

        assertNull(resolver.resolve(youTubeStream()))
    }

    @Test
    fun `without in-app playback the stream opens the watch page externally`() = runTest {
        var extractorCalled = false
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = false) {
            extractorCalled = true
            manifestUrl
        }

        val resolved = resolver.resolve(youTubeStream())

        assertFalse(extractorCalled)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", resolved?.externalUrl)
        assertNull(resolved?.url)
        assertTrue(resolved!!.isExternal())
        assertNull(resolved.youTubeIdToResolve())
    }

    @Test
    fun `streams that don't need resolving are returned unchanged`() = runTest {
        var extractorCalled = false
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) {
            extractorCalled = true
            manifestUrl
        }
        val direct = youTubeStream().copy(url = "https://example.com/video.mp4")
        val plain = youTubeStream().copy(ytId = null, url = "https://example.com/other.mp4")

        assertSame(direct, resolver.resolve(direct))
        assertSame(plain, resolver.resolve(plain))
        assertFalse(extractorCalled)
    }

    @Test
    fun `the watch url is built from the video id`() {
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", YouTubeStreamResolver.watchUrl("aqz-KE-bpKQ"))
    }

    private fun youTubeStream(): Stream = Stream(
        name = "News",
        title = "Headline",
        description = "Channel",
        url = null,
        ytId = "dQw4w9WgXcQ",
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )
}
