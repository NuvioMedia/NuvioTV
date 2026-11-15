package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailer player played the audio over a black screen when the extractor picked a stream it
 * couldn't show: 4K AV1 on the SHIELD (no AV1 decoder) or 10-bit VP9 HDR (decoder error, and a
 * TextureView has no HDR output anyway). Such streams are dropped.
 */
class InAppYouTubeExtractorDecoderSupportTest {

    // A SHIELD-like device: H.264 and VP9 up to 4K, no AV1 at all.
    private val extractor = InAppYouTubeExtractor { codecs, _, _, _ ->
        codecs.startsWith("avc1") || codecs.startsWith("vp9") || codecs.startsWith("vp09")
    }

    @Test
    fun `reads the codecs from youtube mime types`() {
        assertEquals("av01.0.12M.10.0.110.09.16.09.0", videoCodecsParam("video/mp4; codecs=\"av01.0.12M.10.0.110.09.16.09.0\""))
        assertEquals("vp9", videoCodecsParam("video/webm; codecs=\"vp9\""))
        assertNull(videoCodecsParam("video/mp4"))
    }

    @Test
    fun `maps codecs to decoder mime types`() {
        assertEquals("video/av01", videoCodecMimeType("av01.0.12M.10"))
        assertEquals("video/x-vnd.on2.vp9", videoCodecMimeType("vp9"))
        assertEquals("video/x-vnd.on2.vp9", videoCodecMimeType("vp09.02.51.10.01.09.16.09.00"))
        assertEquals("video/avc", videoCodecMimeType("avc1.640028"))
        assertNull(videoCodecMimeType("mystery"))
    }

    @Test
    fun `drops streams the device has no decoder for`() {
        val streams = listOf(
            video(url = "av1-2160p", codecs = "av01.0.12M.08"),
            video(url = "vp9-2160p", codecs = "vp9"),
            video(url = "avc-1080p", codecs = "avc1.640028")
        )

        assertEquals(listOf("vp9-2160p", "avc-1080p"), extractor.playableVideo(streams).map { it.url })
    }

    @Test
    fun `drops hdr streams even when the device can decode them`() {
        val streams = listOf(
            video(url = "vp9-hdr-2160p", codecs = "vp09.02.51.10.01.09.16.09.00", isHdr = true),
            video(url = "vp9-sdr-2160p", codecs = "vp9")
        )

        assertEquals(listOf("vp9-sdr-2160p"), extractor.playableVideo(streams).map { it.url })
    }

    @Test
    fun `keeps a stream whose codec is unknown`() {
        val streams = listOf(video(url = "unknown", codecs = null))

        assertEquals(listOf("unknown"), extractor.playableVideo(streams).map { it.url })
    }

    @Test
    fun `leaves nothing when no stream can be shown, so HLS is used instead`() {
        val streams = listOf(video(url = "av1-2160p", codecs = "av01.0.12M.08"))

        assertTrue(extractor.playableVideo(streams).isEmpty())
    }

    private fun video(url: String, codecs: String?, isHdr: Boolean = false) = StreamCandidate(
        client = "visionos",
        priority = 0,
        url = url,
        score = 0.0,
        hasN = false,
        itag = "0",
        height = 2160,
        fps = 24,
        ext = "mp4",
        width = 3840,
        codecs = codecs,
        isHdr = isHdr
    )
}
