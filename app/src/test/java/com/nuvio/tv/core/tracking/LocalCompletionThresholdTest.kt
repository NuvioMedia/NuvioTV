package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCompletionThresholdTest {

    @Test
    fun `a source without a bar keeps the app's own`() {
        assertNull(localCompletionThresholdFor(null))
        assertNull(localCompletionThresholdFor(Double.NaN))
    }

    @Test
    fun `a source bar at or under the app's own keeps the app's own`() {
        assertNull(localCompletionThresholdFor(80.0))
        assertNull(localCompletionThresholdFor(90.0))
    }

    @Test
    fun `a higher source bar raises the local one, never past the whole scale`() {
        assertEquals(0.96f, localCompletionThresholdFor(96.0)!!, 0.0001f)
        assertEquals(1f, localCompletionThresholdFor(100.0)!!, 0f)
        assertEquals(1f, localCompletionThresholdFor(140.0)!!, 0f)
    }

    @Test
    fun `a stop between the two bars stays in progress`() {
        val stoppedAt92 = progress(position = 92_000L, threshold = localCompletionThresholdFor(96.0))

        assertFalse(stoppedAt92.isCompleted())
        assertTrue(stoppedAt92.isInProgress())
        assertTrue(stoppedAt92.copy(position = 96_000L).isCompleted())
        // The app's own bar is unchanged without a source bar.
        assertTrue(progress(position = 92_000L, threshold = null).isCompleted())
    }

    private fun progress(position: Long, threshold: Float?) = WatchProgress(
        contentId = "tt0000001",
        contentType = "movie",
        name = "Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt0000001",
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = 100_000L,
        lastWatched = 1L,
        completionThresholdOverride = threshold
    )
}
