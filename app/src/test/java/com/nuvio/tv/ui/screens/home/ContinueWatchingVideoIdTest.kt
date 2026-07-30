package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingVideoIdTest {

    @Test
    fun `remote progress prefers meta episode video id`() {
        assertEquals(
            "tt1234567:1:4",
            resolveContinueWatchingVideoId(
                progressVideoId = "tt1234567",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
                metaVideoId = "tt1234567:1:4"
            )
        )
    }

    @Test
    fun `local progress with series-level video id prefers meta episode id`() {
        // Legacy snapshots often stored series contentId as videoId.
        assertEquals(
            "tt1234567:1:5",
            resolveContinueWatchingVideoId(
                progressVideoId = "tt1234567",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = "tt1234567:1:5"
            )
        )
    }

    @Test
    fun `local progress with blank video id prefers meta episode id`() {
        assertEquals(
            "mal:63375:12",
            resolveContinueWatchingVideoId(
                progressVideoId = "",
                contentId = "mal:63375",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = "mal:63375:12"
            )
        )
    }

    @Test
    fun `local progress keeps stored episode video id when present`() {
        assertEquals(
            "tt1234567:2:10",
            resolveContinueWatchingVideoId(
                progressVideoId = "tt1234567:2:10",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = "tt1234567:2:11"
            )
        )
    }

    @Test
    fun `mal kitsu style meta ids are used without inventing season segments`() {
        // Meta already provides the absolute-episode form; do not require SxE rebuild.
        assertEquals(
            "kitsu:6448:68",
            resolveContinueWatchingVideoId(
                progressVideoId = "kitsu:6448",
                contentId = "kitsu:6448",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = "kitsu:6448:68"
            )
        )
    }

    @Test
    fun `falls back to progress or content id when meta video id is unavailable`() {
        assertEquals(
            "tt1234567:1:4",
            resolveContinueWatchingVideoId(
                progressVideoId = "tt1234567:1:4",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = null
            )
        )
        assertEquals(
            "tt1234567",
            resolveContinueWatchingVideoId(
                progressVideoId = "",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = null
            )
        )
        assertEquals(
            "tt1234567",
            resolveContinueWatchingVideoId(
                progressVideoId = "tt1234567",
                contentId = "tt1234567",
                source = WatchProgress.SOURCE_LOCAL,
                metaVideoId = "   "
            )
        )
    }
}
