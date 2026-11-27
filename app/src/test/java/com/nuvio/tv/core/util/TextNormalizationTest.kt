package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizationTest {
    @Test
    fun `latin titles are lower-cased and punctuation collapsed`() {
        assertEquals("episode 1 the end", "Episode 1: The End!".normalizeForMatching())
    }

    @Test
    fun `diacritics are ignored`() {
        assertEquals("amelie", "Amélie".normalizeForMatching())
    }

    @Test
    fun `hebrew titles are kept and niqqud is ignored`() {
        assertEquals("שלום עולם", "שָׁלוֹם, עולם!".normalizeForMatching())
        assertEquals("שובר שורות", "שובר שורות".normalizeForMatching())
    }

    @Test
    fun `other scripts are kept`() {
        assertEquals("игра престолов", "Игра престолов".normalizeForMatching())
        assertEquals("鬼滅の刃", "鬼滅の刃".normalizeForMatching())
    }

    @Test
    fun `blank and symbol-only input becomes empty`() {
        assertEquals("", "  --  ".normalizeForMatching())
        assertEquals("", "".normalizeForMatching())
    }
}
