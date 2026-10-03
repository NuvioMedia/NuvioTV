package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {

    @Test
    fun `resolveResumePosition returns position when duration and position are both valid`() {
        val wp = watchProgress(
            position = 60000,
            duration = 180000
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition clamps position to actualDuration`() {
        val wp = watchProgress(
            position = 300000,
            duration = 180000
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(200000, result)
    }

    @Test
    fun `resolveResumePosition returns position when duration is zero`() {
        // Regression test for #2580: resume-after-pause bug
        // When saved duration is 0 but position is valid, position must be honored.
        val wp = watchProgress(
            position = 60000,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition returns position when duration is zero and progressPercent is set`() {
        // Regression test for #2580: exact bug scenario
        // When exiting while paused, getEffectiveDuration returns 0,
        // saveWatchProgressInternal sets fallbackPercent = 5f.
        // Position must take priority over progressPercent.
        val wp = watchProgress(
            position = 60000,
            duration = 0,
            progressPercent = 5f
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        // Should use position (60000), not 5% of 200000 (= 10000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition uses progressPercent when position is zero`() {
        val wp = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 50f
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(100000, result) // 50% of 200000
    }

    @Test
    fun `resolveResumePosition returns position when actualDuration is zero`() {
        val wp = watchProgress(
            position = 60000,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 0)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition returns zero when everything is zero`() {
        val wp = watchProgress(
            position = 0,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 0)
        assertEquals(0, result)
    }

    @Test
    fun `a provider playback row at ninety percent is not completed by a threshold of eighty five`() {
        val wp = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 90f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
            completionThresholdFraction = 0.85f,
            isProviderPlaybackPosition = true
        )

        // The write side reported this stop as a pause, because 90 percent sat under the credits
        // marker, so the account holds the row open. shouldTreatAsInProgressForContinueWatching drops
        // a row the moment isCompleted() is true, so reading the row against the user threshold of 85
        // alone would drop the episode out of Continue Watching. The row is exempt and stays a
        // position to resume.
        assertFalse(wp.isCompleted())
        assertTrue(wp.isInProgress())
    }

    @Test
    fun `a provider playback row at the credits marker threshold is still not completed`() {
        val wp = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 96f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
            completionThresholdFraction = 0.96f,
            isProviderPlaybackPosition = true
        )

        // Where a playback ends is the credits marker of the release, which a row the provider
        // publishes does not carry, so even the resolved threshold is only a reading number here.
        assertFalse(wp.isCompleted())
        assertTrue(wp.isInProgress())
        assertEquals(0.96f, wp.progressPercentage, 0.0005f)
    }

    @Test
    fun `a watch history recorded still completes although it carries the playback source`() {
        val wp = watchProgress(
            position = 1,
            duration = 1,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK
        )

        // A Simkl playback row and a Simkl watch recorded into history share SOURCE_SIMKL_PLAYBACK,
        // so the exemption travels with the row the playback projection builds and not with the
        // source: real completed state still completes.
        assertFalse(wp.isProviderPlaybackPosition)
        assertTrue(wp.isCompleted())
        assertFalse(wp.isInProgress())
    }

    @Test
    fun `a row from a source that is not a provider playback completes as before`() {
        val local = watchProgress(position = 0, duration = 0, progressPercent = 92f)
        val traktPlayback = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 95f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )

        assertTrue(local.isCompleted())
        assertFalse(local.isInProgress())
        assertTrue(traktPlayback.isCompleted())
        assertFalse(traktPlayback.isInProgress())
    }

    private fun watchProgress(
        position: Long,
        duration: Long,
        progressPercent: Float? = null,
        source: String = WatchProgress.SOURCE_LOCAL,
        completionThresholdFraction: Float? = null,
        isProviderPlaybackPosition: Boolean = false
    ): WatchProgress {
        return WatchProgress(
            contentId = "tt1234567",
            contentType = "movie",
            name = "Test",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1234567",
            season = null,
            episode = null,
            episodeTitle = null,
            position = position,
            duration = duration,
            lastWatched = 1000L,
            progressPercent = progressPercent,
            source = source,
            completionThresholdFraction = completionThresholdFraction,
            isProviderPlaybackPosition = isProviderPlaybackPosition
        )
    }
}
