package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
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
        assertTrue(PlayerSubtitleRtlFix.containsRtl("שלום"))
    }

    @Test
    fun containsRtl_mixedText_returnsTrue() {
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

        assertEquals(0x200E, lines[0][0].code)
        assertEquals(0x200E, lines[0][lines[0].length - 1].code)
        assertEquals(0x200E, lines[1][0].code)
        assertEquals(0x200E, lines[1][lines[1].length - 1].code)
        assertEquals(0x200E, lines[2][0].code)
        assertEquals(0x200E, lines[2][lines[2].length - 1].code)

        assertEquals(0x202B, lines[0][1].code)
        assertEquals(0x202C, lines[0][lines[0].length - 2].code)
        assertEquals(0x202B, lines[1][1].code)
        assertEquals(0x202C, lines[1][lines[1].length - 2].code)
        assertEquals(0x202B, lines[2][1].code)
        assertEquals(0x202C, lines[2][lines[2].length - 2].code)
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
        assertEquals(2, wrapped.getSpanStart(boldSpans[0]))
        assertEquals(13, wrapped.getSpanEnd(boldSpans[0]))
    }

    @Test
    fun wrapText_crlf_spansArePreserved() {
        val builder = SpannableStringBuilder("שלום\r\nעולם")
        val boldSpan = StyleSpan(Typeface.BOLD)
        builder.setSpan(boldSpan, 0, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned
        val boldSpans = wrapped.getSpans(0, wrapped.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }

        assertEquals(1, boldSpans.size)
        assertEquals(2, wrapped.getSpanStart(boldSpans[0]))
        assertEquals(13, wrapped.getSpanEnd(boldSpans[0]))
    }

    @Test
    fun wrapText_asymmetricSuffixOnly_spansArePreserved() {
        val builder = SpannableStringBuilder("Hello مرحبا")
        val boldSpan = StyleSpan(Typeface.BOLD)
        val underlineSpan = UnderlineSpan()
        builder.setSpan(boldSpan, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(underlineSpan, 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

        assertEquals("Hello مرحبا\u200E", wrapped.toString())
        assertEquals(0, wrapped.getSpanStart(boldSpan))
        assertEquals(5, wrapped.getSpanEnd(boldSpan))
        assertEquals(6, wrapped.getSpanStart(underlineSpan))
        assertEquals(11, wrapped.getSpanEnd(underlineSpan))
    }

    @Test
    fun wrapText_asymmetricPrefixOnlyMultiline_spansArePreserved() {
        val builder = SpannableStringBuilder("\u2067مرحبا\u2069 Hello\nWorld")
        val boldSpan = StyleSpan(Typeface.BOLD)
        val underlineSpan = UnderlineSpan()
        builder.setSpan(boldSpan, 8, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(underlineSpan, 14, 19, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

        assertEquals("\u200E\u2067مرحبا\u2069 Hello\nWorld", wrapped.toString())
        assertEquals(9, wrapped.getSpanStart(boldSpan))
        assertEquals(14, wrapped.getSpanEnd(boldSpan))
        assertEquals(15, wrapped.getSpanStart(underlineSpan))
        assertEquals(20, wrapped.getSpanEnd(underlineSpan))
    }

    @Test
    fun wrapText_rtlFirstLineThenLtrLine_spansArePreserved() {
        val builder = SpannableStringBuilder("مرحبا Hello\nWorld")
        val boldSpan = StyleSpan(Typeface.BOLD)
        val underlineSpan = UnderlineSpan()
        builder.setSpan(boldSpan, 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(underlineSpan, 12, 17, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

        assertEquals("\u200E\u202Bمرحبا Hello\u202C\u200E\nWorld", wrapped.toString())
        assertEquals(8, wrapped.getSpanStart(boldSpan))
        assertEquals(13, wrapped.getSpanEnd(boldSpan))
        assertEquals(16, wrapped.getSpanStart(underlineSpan))
        assertEquals(21, wrapped.getSpanEnd(underlineSpan))
    }

    @Test
    fun wrapText_mixedCrlfAndLfWithEmptyLine_spansArePreserved() {
        val builder = SpannableStringBuilder("שלום\r\n\nעולם")
        val boldSpan = StyleSpan(Typeface.BOLD)
        val underlineSpan = UnderlineSpan()
        builder.setSpan(boldSpan, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(underlineSpan, 7, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

        assertEquals(
            "\u200E\u202Bשלום\u202C\u200E\n\n\u200E\u202Bעולם\u202C\u200E",
            wrapped.toString()
        )
        assertEquals(2, wrapped.getSpanStart(boldSpan))
        assertEquals(6, wrapped.getSpanEnd(boldSpan))
        assertEquals(12, wrapped.getSpanStart(underlineSpan))
        assertEquals(16, wrapped.getSpanEnd(underlineSpan))
    }

    @Test
    fun wrapText_rtlDefaultLocaleAndExplicitDirectionalControls_forcesLtrContextAndPreservesSpans() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("he-IL"))
            val builder = SpannableStringBuilder("\u202Bשלום\u202C\n\nעולם")
            val boldSpan = StyleSpan(Typeface.BOLD)
            val underlineSpan = UnderlineSpan()
            builder.setSpan(boldSpan, 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(underlineSpan, 8, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

            assertEquals(
                "\u200E\u202B\u202Bשלום\u202C\u202C\u200E\n\n\u200E\u202Bעולם\u202C\u200E",
                wrapped.toString()
            )
            assertEquals(3, wrapped.getSpanStart(boldSpan))
            assertEquals(7, wrapped.getSpanEnd(boldSpan))
            assertEquals(14, wrapped.getSpanStart(underlineSpan))
            assertEquals(18, wrapped.getSpanEnd(underlineSpan))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun wrapText_paragraphSpans_arePreservedAtParagraphBoundaries() {
        val builder = SpannableStringBuilder("שלום\r\nעולם")
        val firstParagraphSpan = AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)
        val secondParagraphSpan = AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)
        val fullTextSpan = AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
        builder.setSpan(firstParagraphSpan, 0, 6, Spanned.SPAN_PARAGRAPH)
        builder.setSpan(secondParagraphSpan, 6, 10, Spanned.SPAN_PARAGRAPH)
        builder.setSpan(fullTextSpan, 0, 10, Spanned.SPAN_PARAGRAPH)

        val wrapped = PlayerSubtitleRtlFix.wrapText(builder) as Spanned

        assertEquals(0, wrapped.getSpanStart(firstParagraphSpan))
        assertEquals(9, wrapped.getSpanEnd(firstParagraphSpan))
        assertEquals(Spanned.SPAN_PARAGRAPH, wrapped.getSpanFlags(firstParagraphSpan))

        assertEquals(9, wrapped.getSpanStart(secondParagraphSpan))
        assertEquals(wrapped.length, wrapped.getSpanEnd(secondParagraphSpan))
        assertEquals(Spanned.SPAN_PARAGRAPH, wrapped.getSpanFlags(secondParagraphSpan))

        assertEquals(0, wrapped.getSpanStart(fullTextSpan))
        assertEquals(wrapped.length, wrapped.getSpanEnd(fullTextSpan))
        assertEquals(Spanned.SPAN_PARAGRAPH, wrapped.getSpanFlags(fullTextSpan))
    }

    private fun utilSplit(value: String, regex: String): List<String> =
        Pattern.compile(regex).split(value, -1).toList()
}
