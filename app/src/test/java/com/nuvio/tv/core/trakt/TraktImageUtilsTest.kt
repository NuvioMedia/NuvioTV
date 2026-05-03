package com.nuvio.tv.core.trakt

import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktImageUtilsTest {

    @Test
    fun `normalizes trakt image hosts to https`() {
        assertEquals(
            "https://media.trakt.tv/images/movies/poster.jpg.webp",
            listOf("media.trakt.tv/images/movies/poster.jpg.webp").firstTraktImageUrl()
        )
        assertEquals(
            "https://media.trakt.tv/images/movies/poster.jpg.webp",
            listOf("//media.trakt.tv/images/movies/poster.jpg.webp").firstTraktImageUrl()
        )
        assertEquals(
            "https://media.trakt.tv/images/movies/poster.jpg.webp",
            listOf("http://media.trakt.tv/images/movies/poster.jpg.webp").firstTraktImageUrl()
        )
    }

    @Test
    fun `selects best trakt artwork`() {
        val images = TraktImagesDto(
            fanart = listOf("media.trakt.tv/images/movies/fanart.jpg.webp"),
            logo = listOf("media.trakt.tv/images/movies/logo.png.webp"),
            thumb = listOf("media.trakt.tv/images/movies/thumb.jpg.webp")
        )

        assertEquals("https://media.trakt.tv/images/movies/fanart.jpg.webp", images.traktBestPosterUrl())
        assertEquals("https://media.trakt.tv/images/movies/fanart.jpg.webp", images.traktBestBackdropUrl())
        assertEquals("https://media.trakt.tv/images/movies/thumb.jpg.webp", images.traktBestLandscapeUrl())
        assertEquals("https://media.trakt.tv/images/movies/logo.png.webp", images.traktBestLogoUrl())
    }

    @Test
    fun `keeps missing artwork empty`() {
        assertNull(emptyList<String>().firstTraktImageUrl())
        assertNull(TraktImagesDto().traktBestPosterUrl())
    }
}
