package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleReferenceCaptureTest {
    @Test
    fun `accepts non-forced English PGS SDH as a timing reference`() {
        val format = Format.Builder()
            .setId("3")
            .setLanguage("eng")
            .setLabel("SDH")
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .build()

        assertTrue(format.isEligibleEnglishSubtitleReference())
    }

    @Test
    fun `accepts transcoded PGS while retaining its source mime type`() {
        val format = Format.Builder()
            .setLanguage("en")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_PGS)
            .build()

        assertTrue(format.isEligibleEnglishSubtitleReference())
    }

    @Test
    fun `still rejects forced English PGS`() {
        val format = Format.Builder()
            .setLanguage("eng")
            .setLabel("English forced")
            .setSelectionFlags(C.SELECTION_FLAG_FORCED)
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .build()

        assertFalse(format.isEligibleEnglishSubtitleReference())
    }

    @Test
    fun `accepts MP4 mov text as a timing reference`() {
        val format = Format.Builder()
            .setLanguage("eng")
            .setLabel("English")
            .setSampleMimeType(MimeTypes.APPLICATION_TX3G)
            .build()

        assertTrue(format.isEligibleEnglishSubtitleReference())
    }

    @Test
    fun `accepts MP4 WebVTT as a timing reference`() {
        val format = Format.Builder()
            .setLanguage("en-US")
            .setSampleMimeType(MimeTypes.APPLICATION_MP4VTT)
            .build()

        assertTrue(format.isEligibleEnglishSubtitleReference())
    }

    @Test
    fun `distinguishes PGS display and clear packets`() {
        val display = byteArrayOf(
            0x16, 0x00, 0x0B, 0x07, 0x80.toByte(), 0x04, 0x38, 0x10,
            0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x01
        )
        val clear = display.copyOf().also { it[it.lastIndex] = 0x00 }

        assertTrue(isSubtitleDisplaySample(MimeTypes.APPLICATION_PGS, display, 0, display.size))
        assertFalse(isSubtitleDisplaySample(MimeTypes.APPLICATION_PGS, clear, 0, clear.size))
    }

    @Test
    fun `ignores empty MP4 mov text samples`() {
        val empty = byteArrayOf(0x00, 0x00)
        val dialogue = byteArrayOf(0x00, 0x02, 0x48, 0x69)

        assertFalse(isSubtitleDisplaySample(MimeTypes.APPLICATION_TX3G, empty, 0, empty.size))
        assertTrue(isSubtitleDisplaySample(MimeTypes.APPLICATION_TX3G, dialogue, 0, dialogue.size))
    }
}
