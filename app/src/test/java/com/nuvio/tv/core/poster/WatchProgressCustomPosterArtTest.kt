package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressCustomPosterArtTest {

    @Test
    fun shapePatternWritesLandscapeToBackdrop() {
        val progress = sampleProgress(contentId = "tmdb:1396", contentType = "series")
        val pattern =
            "https://example.com/poster?id={id}&type={type}&shape={shape}"

        val result = progress.withCustomPosterArt(pattern)

        assertEquals(
            "https://example.com/poster?id=tmdb:1396&type=series&shape=poster",
            result.poster
        )
        assertEquals(
            "https://example.com/poster?id=tmdb:1396&type=series&shape=landscape",
            result.backdrop
        )
    }

    @Test
    fun blankPatternLeavesArtUnchanged() {
        val progress = sampleProgress(
            poster = "https://cdn.example/poster.jpg",
            backdrop = "https://cdn.example/backdrop.jpg"
        )
        val result = progress.withCustomPosterArt("")
        assertEquals(progress.poster, result.poster)
        assertEquals(progress.backdrop, result.backdrop)
    }

    @Test
    fun withoutShapeKeepsOriginalBackdrop() {
        val progress = sampleProgress(
            contentId = "tt0137523",
            contentType = "movie",
            poster = "https://cdn.example/poster.jpg",
            backdrop = "https://cdn.example/backdrop.jpg"
        )
        val pattern = "https://example.com/poster?imdb={imdb_id}&type={type}"

        val result = progress.withCustomPosterArt(pattern)

        assertEquals("https://example.com/poster?imdb=tt0137523&type=movie", result.poster)
        assertEquals("https://cdn.example/backdrop.jpg", result.backdrop)
    }

    private fun sampleProgress(
        contentId: String = "tt0137523",
        contentType: String = "movie",
        poster: String? = "https://cdn.example/poster.jpg",
        backdrop: String? = "https://cdn.example/backdrop.jpg"
    ) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "Sample",
        poster = poster,
        backdrop = backdrop,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = 1L
    )
}
