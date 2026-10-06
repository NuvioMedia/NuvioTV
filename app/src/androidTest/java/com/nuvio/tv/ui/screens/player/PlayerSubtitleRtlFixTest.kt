package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mirrors androidx/media `BidiUtilsTest` from pull request 2553.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleRtlFixTest {

    @Test
    fun containsRtl_nullInput_returnsFalse() {
        assertFalse(PlayerSubtitleRtlFix.containsRtl(null))
    }

    @Test
    fun containsRtl_emptyString_returnsFalse() {
        assertFalse(PlayerSubtitleRtlFix.containsRtl(""))
    }

    @Test
    fun containsRtl_ltrOnly_returnsFalse() {
        assertFalse(PlayerSubtitleRtlFix.containsRtl("Hello, world!"))
    }

    @Test
    fun containsRtl_rtlOnly_returnsTrue() {
        // Hebrew "שלום"
        assertTrue(PlayerSubtitleRtlFix.containsRtl("שלום"))
    }

    @Test
    fun containsRtl_mixedText_returnsTrue() {
        // Mixed English and Arabic
        assertTrue(PlayerSubtitleRtlFix.containsRtl("Hello مرحبا"))
    }

    @Test
    fun wrapText_plainText_wrapsEachLineWithUnicodeWrap() {
        val input = "להתראות.\nשלום\nשלום!"
        val wrapped = PlayerSubtitleRtlFix.wrapText(input)
        val lines = utilSplit(wrapped.toString(), "\n")

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("להתראות."))
        assertTrue(lines[1].contains("שלום"))
        assertTrue(lines[2].contains("שלום!"))

        // Unicode LRM control characters are added at both ends of each line.
        assertEquals(0x200E, lines[0][0].code)
        assertEquals(0x200E, lines[0][lines[0].length - 1].code)
        assertEquals(0x200E, lines[1][0].code)
        assertEquals(0x200E, lines[1][lines[1].length - 1].code)
        assertEquals(0x200E, lines[2][0].code)
        assertEquals(0x200E, lines[2][lines[2].length - 1].code)
    }

    @Test
    fun wrapText_plainText_wrapsEachLineWithUnicodeWrap_crlf() {
        val input = "נסיון\r\nבחלונות"
        val wrapped = PlayerSubtitleRtlFix.wrapText(input)
        val lines = utilSplit(wrapped.toString(), "\n")

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("נסיון"))
        assertTrue(lines[1].contains("בחלונות"))

        assertEquals(0x200E, lines[0][0].code)
        assertEquals(0x200E, lines[0][lines[0].length - 1].code)
        assertEquals(0x200E, lines[1][0].code)
        assertEquals(0x200E, lines[1][lines[1].length - 1].code)
    }

    @Test
    fun wrapText_spansArePreserved() {
        val builder = SpannableStringBuilder("שלום\nעולם")
        val boldSpan = StyleSpan(Typeface.BOLD)
        builder.setSpan(boldSpan, 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned
        val boldSpans = wrapped.getSpans(0, wrapped.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }

        assertEquals(1, boldSpans.size)
        // BiDi marks shift the span from [0, 7) to [1, 10).
        assertEquals(1, wrapped.getSpanStart(boldSpans[0]))
        assertEquals(10, wrapped.getSpanEnd(boldSpans[0]))
    }

    /** Same as media3 [androidx.media3.common.util.Util.split]: keep trailing empty pieces. */
    private fun utilSplit(value: String, regex: String): List<String> =
        Pattern.compile(regex).split(value, -1).toList()
}
