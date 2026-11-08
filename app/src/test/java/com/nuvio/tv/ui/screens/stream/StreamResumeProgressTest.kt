package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResumeProgressTest {
    private val progress = WatchProgress(
        contentId = "tt123", contentType = "movie", name = "Movie",
        poster = null, backdrop = null, logo = null, videoId = "tt123",
        season = null, episode = null, episodeTitle = null,
        position = 816_000L, duration = 3_600_000L, lastWatched = 0L
    )

    @Test fun `saved position is retained even without a known duration`() {
        val saved = progress.copy(duration = 0L)
        assertEquals(saved, streamResumeProgress(saved, false))
    }

    @Test fun `explicit play from beginning hides saved progress`() {
        assertNull(streamResumeProgress(progress, true))
    }

    @Test fun `missing and unstarted progress are hidden`() {
        assertNull(streamResumeProgress(null, false))
        assertNull(streamResumeProgress(progress.copy(position = 0L), false))
    }

    @Test fun `completed local content is hidden`() {
        assertNull(streamResumeProgress(progress.copy(position = 3_240_000L), false))
    }

    @Test fun `remote percentage without a timestamp is retained`() {
        val remote = progress.copy(position = 0L, duration = 0L, progressPercent = 42f)
        assertEquals(remote, streamResumeProgress(remote, false))
    }

    @Test fun `provider specific completion threshold is respected`() {
        val remote = progress.copy(position = 0L, progressPercent = 85f, source = WatchProgress.SOURCE_SIMKL_PLAYBACK)
        assertNull(streamResumeProgress(remote, false))
        assertEquals(remote.copy(completionThresholdOverride = 0.95f),
            streamResumeProgress(remote.copy(completionThresholdOverride = 0.95f), false))
    }

    @Test fun `episode progress is preserved`() {
        val episode = progress.copy(contentType = "series", videoId = "tt123:2:3", season = 2, episode = 3)
        assertEquals(episode, streamResumeProgress(episode, false))
    }
}
