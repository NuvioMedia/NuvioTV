package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerSubtitleTest {
    private fun track(language: String, auto: Boolean = false) =
        TrailerSubtitleTrack(language, "https://youtube.com/$language/$auto", auto)

    @Test
    fun `preferred language wins and author captions beat auto-generated`() {
        val author = track("en-US")
        val picked = pickTrailerSubtitle(listOf(track("fr"), track("en", auto = true), author), "en")
        assertEquals(author, picked)
    }

    @Test
    fun `single other language is used as a fallback`() {
        val auto = track("fr", auto = true)
        val author = track("fr-CA")
        assertEquals(author, pickTrailerSubtitle(listOf(auto, author), "en"))
    }

    @Test
    fun `several other languages or no tracks pick nothing`() {
        assertNull(pickTrailerSubtitle(listOf(track("fr"), track("de")), "en"))
        assertNull(pickTrailerSubtitle(emptyList(), "en"))
    }

    @Test
    fun `caption urls always ask for vtt`() {
        assertEquals("https://y/t?v=1&fmt=vtt", captionUrlAsVtt("https://y/t?v=1"))
        assertEquals("https://y/t?v=1&fmt=vtt&x=2", captionUrlAsVtt("https://y/t?v=1&fmt=srv3&x=2"))
        assertEquals("https://y/t?fmt=vtt&x=2", captionUrlAsVtt("https://y/t?fmt=srv3&x=2"))
        assertEquals("https://y/t?xfmt=a&fmt=vtt", captionUrlAsVtt("https://y/t?xfmt=a"))
    }
}
