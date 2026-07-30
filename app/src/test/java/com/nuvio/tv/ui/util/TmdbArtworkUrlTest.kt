package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbArtworkUrlTest {
    @Test
    fun `upgrades sized tmdb images to original`() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.jpg",
            "https://image.tmdb.org/t/p/w1280/example.jpg".preferOriginalTmdbArtwork()
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.png",
            "https://image.tmdb.org/t/p/h632/example.png".preferOriginalTmdbArtwork()
        )
    }

    @Test
    fun `leaves addon and original urls unchanged`() {
        assertEquals(
            "https://cdn.example.com/w1280/example.jpg",
            "https://cdn.example.com/w1280/example.jpg".preferOriginalTmdbArtwork()
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/example.jpg",
            "https://image.tmdb.org/t/p/original/example.jpg".preferOriginalTmdbArtwork()
        )
    }

    @Test
    fun `disabled preference preserves sized tmdb url`() {
        val url = "https://image.tmdb.org/t/p/w1280/example.jpg"

        assertEquals(url, url.preferOriginalTmdbArtwork(enabled = false))
    }

    @Test
    fun `handles missing artwork`() {
        assertNull(null.preferOriginalTmdbArtwork())
        assertNull(" ".preferOriginalTmdbArtwork())
    }
}
