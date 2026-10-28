package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPosterEncodedShapeTest {

    private val encoded = "https://example.com/%7Btype%7D/%7Bshape%7D/%7Bimdb_id%7D.jpg"

    @Test
    fun percentEncodedShapeCountsAsShape() {
        assertTrue(CustomPosterUrlResolver.supportsShape(encoded))
        assertTrue(CustomPosterUrlResolver.supportsShape("https://example.com/%7bshape%7d.jpg"))
        assertTrue(CustomPosterUrlResolver.supportsShape("https://example.com/{shape}.jpg"))
        assertFalse(CustomPosterUrlResolver.supportsShape("https://example.com/{imdb_id}.jpg"))
    }

    @Test
    fun landscapeCardUsesEncodedShapePattern() {
        val result = preview(PosterShape.LANDSCAPE).withCustomPosterUrl(encoded)

        assertEquals("https://example.com/movie/landscape/tt0137523.jpg", result.poster)
        assertEquals("https://example.com/movie/landscape/tt0137523.jpg", result.landscapePoster)
    }

    @Test
    fun watchNextArtUsesEncodedShapePattern() {
        val progress = WatchProgress(
            contentId = "tt0137523",
            contentType = "movie",
            name = "Sample",
            poster = "https://cdn.example/poster.jpg",
            backdrop = "https://cdn.example/backdrop.jpg",
            logo = null,
            videoId = "tt0137523",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1_000L,
            duration = 10_000L,
            lastWatched = 1L
        )

        val result = progress.withCustomPosterArt(encoded)

        assertEquals("https://example.com/movie/poster/tt0137523.jpg", result.poster)
        assertEquals("https://example.com/movie/landscape/tt0137523.jpg", result.backdrop)
    }

    private fun preview(shape: PosterShape) = MetaPreview(
        id = "tt0137523",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Sample",
        poster = "https://cdn.example/poster.jpg",
        posterShape = shape,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
