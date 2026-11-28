package com.nuvio.tv.ui.util

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDirectionUtilsTest {
    @Test
    fun `pure latin text is LTR`() {
        assertEquals(TextDirection.Ltr, "Breaking Bad".contentTextDirection())
    }

    @Test
    fun `pure hebrew text is RTL`() {
        assertEquals(TextDirection.Rtl, "שובר שורות".contentTextDirection())
    }

    @Test
    fun `hebrew first stays RTL even with latin words later`() {
        assertEquals(TextDirection.Rtl, "יש לי HDR10+ בטלוויזיה".contentTextDirection())
    }

    @Test
    fun `latin first but mostly hebrew is RTL`() {
        assertEquals(TextDirection.Rtl, "Netflix מציגה סדרה חדשה".contentTextDirection())
    }

    @Test
    fun `latin first and mostly latin stays LTR`() {
        assertEquals(TextDirection.Ltr, "Fast X: מהיר".contentTextDirection())
    }

    @Test
    fun `emoji and digits are skipped`() {
        assertEquals(TextDirection.Rtl, "🇮🇱 2024 שלום".contentTextDirection())
        assertEquals(TextDirection.Ltr, "1080p".contentTextDirection())
        assertEquals(TextDirection.Ltr, "".contentTextDirection())
    }
}
