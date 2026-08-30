package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContinueWatchingImageModelTest {

    @Before
    fun clearBrokenUrls() {
        brokenImageUrls.clear()
    }

    private fun inProgress(
        poster: String? = null,
        backdrop: String? = null,
        thumbnail: String? = null
    ) = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = "tt123",
            contentType = "series",
            name = "Show",
            poster = poster,
            backdrop = backdrop,
            logo = null,
            videoId = "tt123:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Pilot",
            position = 60_000L,
            duration = 600_000L,
            lastWatched = 100L
        ),
        episodeThumbnail = thumbnail
    )

    private fun nextUp(
        poster: String? = null,
        backdrop: String? = null,
        thumbnail: String? = null,
        hasAired: Boolean = true
    ) = ContinueWatchingItem.NextUp(
        info = NextUpInfo(
            contentId = "tt123",
            contentType = "series",
            name = "Show",
            poster = poster,
            backdrop = backdrop,
            logo = null,
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Next",
            thumbnail = thumbnail,
            hasAired = hasAired,
            lastWatched = 100L,
            sortTimestamp = 100L
        )
    )

    @Test
    fun `poster style does not fall back to the episode thumbnail before artwork hydrates`() {
        val item = inProgress(poster = null, backdrop = null, thumbnail = "thumb.jpg")

        assertNull(
            continueWatchingImageModel(item, useEpisodeThumbnails = false, preferPosterArtwork = true)
        )
    }

    @Test
    fun `poster style does not fall back to the thumbnail when the poster url is broken`() {
        brokenImageUrls.add("poster.jpg")
        val item = inProgress(poster = "poster.jpg", backdrop = null, thumbnail = "thumb.jpg")

        assertNull(
            continueWatchingImageModel(item, useEpisodeThumbnails = false, preferPosterArtwork = true)
        )
    }

    @Test
    fun `landscape style with thumbnails off does not fall back to the thumbnail`() {
        val item = inProgress(poster = null, backdrop = null, thumbnail = "thumb.jpg")

        assertNull(
            continueWatchingImageModel(item, useEpisodeThumbnails = false, preferPosterArtwork = false)
        )
    }

    @Test
    fun `next up with thumbnails off does not fall back to the thumbnail`() {
        val item = nextUp(poster = null, backdrop = null, thumbnail = "thumb.jpg")

        assertNull(
            continueWatchingImageModel(item, useEpisodeThumbnails = false, preferPosterArtwork = false)
        )
    }

    @Test
    fun `unaired next up with thumbnails off does not fall back to the thumbnail`() {
        val item = nextUp(poster = null, backdrop = null, thumbnail = "thumb.jpg", hasAired = false)

        assertNull(
            continueWatchingImageModel(item, useEpisodeThumbnails = false, preferPosterArtwork = false)
        )
    }

    @Test
    fun `poster style still prefers poster then backdrop`() {
        val withPoster = inProgress(poster = "poster.jpg", backdrop = "backdrop.jpg", thumbnail = "thumb.jpg")
        assertEquals(
            "poster.jpg",
            continueWatchingImageModel(withPoster, useEpisodeThumbnails = false, preferPosterArtwork = true)
        )

        val backdropOnly = inProgress(poster = null, backdrop = "backdrop.jpg", thumbnail = "thumb.jpg")
        assertEquals(
            "backdrop.jpg",
            continueWatchingImageModel(backdropOnly, useEpisodeThumbnails = false, preferPosterArtwork = true)
        )
    }

    @Test
    fun `thumbnails on still outrank poster art`() {
        val item = inProgress(poster = "poster.jpg", backdrop = "backdrop.jpg", thumbnail = "thumb.jpg")

        assertEquals(
            "thumb.jpg",
            continueWatchingImageModel(item, useEpisodeThumbnails = true, preferPosterArtwork = true)
        )
    }

    @Test
    fun `landscape style with thumbnails on still prefers the thumbnail`() {
        val item = inProgress(poster = "poster.jpg", backdrop = "backdrop.jpg", thumbnail = "thumb.jpg")

        assertEquals(
            "thumb.jpg",
            continueWatchingImageModel(item, useEpisodeThumbnails = true, preferPosterArtwork = false)
        )
    }

    @Test
    fun `unaired next up with thumbnails on keeps the thumbnail as a last resort`() {
        val item = nextUp(poster = null, backdrop = null, thumbnail = "thumb.jpg", hasAired = false)

        assertEquals(
            "thumb.jpg",
            continueWatchingImageModel(item, useEpisodeThumbnails = true, preferPosterArtwork = false)
        )
    }

    @Test
    fun `poster style disables episode thumbnails regardless of the setting`() {
        assertFalse(
            continueWatchingUsesEpisodeThumbnails(ContinueWatchingCardStyle.POSTER, useEpisodeThumbnails = true)
        )
        assertTrue(
            continueWatchingUsesEpisodeThumbnails(ContinueWatchingCardStyle.CARD, useEpisodeThumbnails = true)
        )
        assertTrue(
            continueWatchingUsesEpisodeThumbnails(ContinueWatchingCardStyle.WIDE, useEpisodeThumbnails = true)
        )
    }

    @Test
    fun `poster cards never blur because the thumbnail is never resolved`() {
        val item = nextUp(poster = null, backdrop = null, thumbnail = "thumb.jpg")
        val gated = continueWatchingUsesEpisodeThumbnails(
            ContinueWatchingCardStyle.POSTER,
            useEpisodeThumbnails = true
        )

        assertFalse(
            continueWatchingShouldBlur(
                item = item,
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = gated,
                preferPosterArtwork = true
            )
        )
    }

    @Test
    fun `in progress episode thumbnails remain blurred until watched`() {
        val item = inProgress(poster = "poster.jpg", backdrop = "backdrop.jpg", thumbnail = "thumb.jpg")

        assertTrue(
            continueWatchingShouldBlur(
                item = item,
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                preferPosterArtwork = false
            )
        )
    }

    @Test
    fun `completed in progress episodes are not blurred`() {
        val item = inProgress(thumbnail = "thumb.jpg").let { inProgress ->
            inProgress.copy(
                progress = inProgress.progress.copy(
                    position = 540_000L,
                    duration = 600_000L
                )
            )
        }

        assertFalse(
            continueWatchingShouldBlur(
                item = item,
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                preferPosterArtwork = false
            )
        )
    }

    @Test
    fun `fallback artwork is not blurred when the episode thumbnail is unavailable`() {
        val item = nextUp(poster = "poster.jpg", backdrop = "backdrop.jpg", thumbnail = null)

        assertFalse(
            continueWatchingShouldBlur(
                item = item,
                blurUnwatchedEpisodes = true,
                useEpisodeThumbnails = true,
                preferPosterArtwork = false
            )
        )
    }
}
