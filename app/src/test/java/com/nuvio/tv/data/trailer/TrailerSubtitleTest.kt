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
        val picked = pickTrailerSubtitle(listOf(track("fr"), track("en", auto = true), author), "en", null)
        assertEquals(author, picked)
    }

    @Test
    fun `secondary language is used when the preferred one is missing`() {
        val auto = track("fr", auto = true)
        val author = track("fr-CA")
        assertEquals(author, pickTrailerSubtitle(listOf(track("de"), auto, author), "pl", "fr"))
    }

    @Test
    fun `other languages are never picked`() {
        assertNull(pickTrailerSubtitle(listOf(track("en")), "pl", null))
        assertNull(pickTrailerSubtitle(listOf(track("fr"), track("de")), "en", null))
        assertNull(pickTrailerSubtitle(emptyList(), "en", "fr"))
    }

    @Test
    fun `none as preferred language picks the spoken language first`() {
        val author = track("en-US")
        assertEquals(author, pickTrailerSubtitle(listOf(track("de"), track("en", auto = true), author), "none", null))
    }

    @Test
    fun `none as preferred language still honours the secondary language`() {
        val french = track("fr")
        assertEquals(french, pickTrailerSubtitle(listOf(track("en", auto = true), french), "none", "fr"))
    }

    @Test
    fun `none as preferred language falls back to the first listed track`() {
        val german = track("de")
        assertEquals(german, pickTrailerSubtitle(listOf(german, track("fr")), "none", null))
        assertEquals(german, pickTrailerSubtitle(listOf(german, track("fr")), "none", "pl"))
        assertNull(pickTrailerSubtitle(emptyList(), "none", null))
    }

    @Test
    fun `caption urls always ask for vtt`() {
        assertEquals("https://y/t?v=1&fmt=vtt", captionUrlAsVtt("https://y/t?v=1"))
        assertEquals("https://y/t?v=1&fmt=vtt&x=2", captionUrlAsVtt("https://y/t?v=1&fmt=srv3&x=2"))
        assertEquals("https://y/t?fmt=vtt&x=2", captionUrlAsVtt("https://y/t?fmt=srv3&x=2"))
        assertEquals("https://y/t?xfmt=a&fmt=vtt", captionUrlAsVtt("https://y/t?xfmt=a"))
    }
}
