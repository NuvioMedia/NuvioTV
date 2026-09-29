package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSubtitleUtilsMimeTest {

    @Test
    fun mimeTypeFromUrl_detectsCommonExtensions() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, PlayerSubtitleUtils.mimeTypeFromUrl("https://x/a.srt"))
        assertEquals(MimeTypes.TEXT_VTT, PlayerSubtitleUtils.mimeTypeFromUrl("https://x/a.vtt?token=1"))
        assertEquals(MimeTypes.TEXT_SSA, PlayerSubtitleUtils.mimeTypeFromUrl("https://x/a.ass"))
        assertEquals(MimeTypes.APPLICATION_TTML, PlayerSubtitleUtils.mimeTypeFromUrl("https://x/a.ttml"))
        // Extension-less addon download links default to SRT.
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://opensubtitles.example/download/12345")
        )
    }

    @Test
    fun sniffSubtitleMimeType_detectsWebVttHeader() {
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi"
        assertEquals(
            MimeTypes.TEXT_VTT,
            PlayerSubtitleUtils.sniffSubtitleMimeType(body, "https://x/download/1")
        )
    }

    @Test
    fun sniffSubtitleMimeType_detectsAssScriptInfo() {
        val body = """
            [Script Info]
            Title: test
            [V4+ Styles]
            Format: Name
            [Events]
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
        """.trimIndent()
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.sniffSubtitleMimeType(body, "https://x/download/1")
        )
    }

    @Test
    fun sniffSubtitleMimeType_detectsSrtTiming() {
        val body = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello
        """.trimIndent()
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            PlayerSubtitleUtils.sniffSubtitleMimeType(body, "https://x/download/1")
        )
    }

    @Test
    fun sidecarMimeCandidates_putsSniffedFirstAndDedupes() {
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi"
        val candidates = PlayerSubtitleUtils.sidecarMimeCandidates(body, "https://x/file.srt")
        assertEquals(MimeTypes.TEXT_VTT, candidates.first())
        assertTrue(candidates.contains(MimeTypes.APPLICATION_SUBRIP))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun mergeOverlappingCues_mergesMultipleUnpositionedCues() {
        val cue1 = androidx.media3.common.text.Cue.Builder().setText(">> (sputtering)").build()
        val cue2 = androidx.media3.common.text.Cue.Builder().setText(">> HEY!").build()
        val merged = PlayerSubtitleUtils.mergeOverlappingCues(listOf(cue1, cue2))
        assertEquals(1, merged.size)
        assertEquals(">> (sputtering)\n>> HEY!", merged[0].text.toString())
    }

    @Test
    fun mergeOverlappingCues_preservesPositionedCues() {
        val cue1 = androidx.media3.common.text.Cue.Builder().setText("TOP").setLine(0.1f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()
        val cue2 = androidx.media3.common.text.Cue.Builder().setText("BOTTOM").setLine(0.9f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION).build()
        val result = PlayerSubtitleUtils.mergeOverlappingCues(listOf(cue1, cue2))
        assertEquals(2, result.size)
        assertEquals("TOP", result[0].text.toString())
        assertEquals("BOTTOM", result[1].text.toString())
    }

    @Test
    fun mergeOverlappingCues_singleCue_returnsSame() {
        val list = listOf(androidx.media3.common.text.Cue.Builder().setText("Single").build())
        org.junit.Assert.assertSame(list, PlayerSubtitleUtils.mergeOverlappingCues(list))
    }

    @Test
    fun normalizeLanguageCode_mapsEnglishLanguageNames() {
        assertEquals("tr", PlayerSubtitleUtils.normalizeLanguageCode("TURKISH"))
        assertEquals("no", PlayerSubtitleUtils.normalizeLanguageCode("Norwegian"))
        assertEquals("pl", PlayerSubtitleUtils.normalizeLanguageCode("POLISH"))
        assertEquals("ro", PlayerSubtitleUtils.normalizeLanguageCode("ROMANIAN"))
        assertEquals("th", PlayerSubtitleUtils.normalizeLanguageCode("THAI"))
        assertEquals("vi", PlayerSubtitleUtils.normalizeLanguageCode("VIETNAMESE"))
        assertEquals("sv", PlayerSubtitleUtils.normalizeLanguageCode("swedish"))
        assertEquals("tr", PlayerSubtitleUtils.normalizeLanguageCode("Turkish (Forced)"))
    }
}
