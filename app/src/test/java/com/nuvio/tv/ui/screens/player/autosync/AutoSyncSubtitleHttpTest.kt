package com.nuvio.tv.ui.screens.player.autosync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.Charset

class AutoSyncSubtitleHttpTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nNão sei se é verdade, coração.\n"

    @Test
    fun decodesWindows1252PortugueseServedWithoutCharset() {
        // Subscene-style download: legacy encoding, generic content type, no charset parameter.
        val body = srt.toByteArray(Charset.forName("windows-1252"))
            .toResponseBody("application/octet-stream".toMediaType())

        val decoded = AutoSyncSubtitleHttp.readResponseBodyLimited(body, maxBytes = 1 shl 20, languageHint = "pt")

        assertEquals(srt, decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun decodesWindows1252PortugueseWithoutLanguageHint() {
        val body = srt.toByteArray(Charset.forName("windows-1252")).toResponseBody(null)

        assertEquals(srt, AutoSyncSubtitleHttp.readResponseBodyLimited(body, maxBytes = 1 shl 20, languageHint = null))
    }

    @Test
    fun keepsUtf8SubtitlesUnchanged() {
        val body = srt.toByteArray(Charsets.UTF_8).toResponseBody("text/plain".toMediaType())

        assertEquals(srt, AutoSyncSubtitleHttp.readResponseBodyLimited(body, maxBytes = 1 shl 20, languageHint = "pt"))
    }
}
