package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedAsOneItemTest {

    @Test
    fun `shows with episodes are series-like whatever the case or padding`() {
        listOf("series", "tv", "anime", " Series ", "TV").forEach { type ->
            assertTrue("'$type' should be series-like", isSeriesLikeType(type))
        }
    }

    @Test
    fun `movies and other add-on types are not series-like`() {
        listOf("movie", "youtube", "channel", "other", "", null).forEach { type ->
            assertFalse("'$type' should not be series-like", isSeriesLikeType(type))
        }
    }

    @Test
    fun `an add-on video without an episode list is watched as one item`() {
        assertTrue(meta(ContentType.UNKNOWN, rawType = "youtube").isWatchedAsOneItem())
    }

    @Test
    fun `a movie is watched as one item even when it lists videos`() {
        assertTrue(meta(ContentType.MOVIE).isWatchedAsOneItem())
        assertTrue(meta(ContentType.MOVIE, videos = listOf(video())).isWatchedAsOneItem())
    }

    @Test
    fun `shows and titles with an episode list are watched per episode`() {
        assertFalse(meta(ContentType.SERIES).isWatchedAsOneItem())
        assertFalse(meta(ContentType.TV).isWatchedAsOneItem())
        assertFalse(meta(ContentType.UNKNOWN, rawType = "anime").isWatchedAsOneItem())
        assertFalse(meta(ContentType.UNKNOWN, rawType = "other", videos = listOf(video())).isWatchedAsOneItem())
    }

    private fun meta(
        type: ContentType,
        rawType: String = type.toApiString(),
        videos: List<Video> = emptyList()
    ) = Meta(
        id = "yt_id:abc123",
        type = type,
        rawType = rawType,
        name = "Sample",
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = videos,
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun video() = Video(
        id = "yt_id:abc123:1:1",
        title = "Part 1",
        released = null,
        thumbnail = null,
        season = 1,
        episode = 1,
        overview = null
    )
}
