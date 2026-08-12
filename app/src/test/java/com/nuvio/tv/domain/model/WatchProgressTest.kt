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
    fun `isInProgress is true when position is saved but duration is unknown`() {
        val wp = watchProgress(
            position = 60000,
            duration = 0
        )
        assertTrue(wp.isInProgress())
        assertFalse(wp.isCompleted())
    }

    @Test
    fun `isInProgress is true for trakt percent-only playback`() {
        val wp = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 42f
        )
        assertTrue(wp.isInProgress())
    }

    @Test
    fun `isInProgress is false for unstarted content`() {
        val wp = watchProgress(
            position = 0,
            duration = 0
        )
        assertFalse(wp.isInProgress())
    }

    private fun watchProgress(
        position: Long,
        duration: Long,
        progressPercent: Float? = null
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
            progressPercent = progressPercent
        )
    }
}
