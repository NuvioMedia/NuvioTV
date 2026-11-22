package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingNoNextUpSeedsTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun `a series with nothing announced is looked up again after a day`() {
        assertEquals(now + NO_NEXT_UP_RECHECK_MS, noNextUpRecheckAt(nextReleaseMs = null, nowMs = now))
    }

    @Test
    fun `a series is looked up again when its next episode is released`() {
        assertEquals(now + 3 * hour, noNextUpRecheckAt(nextReleaseMs = now + 3 * hour, nowMs = now))
    }

    @Test
    fun `a release far ahead still gets looked up after a day`() {
        assertEquals(now + NO_NEXT_UP_RECHECK_MS, noNextUpRecheckAt(nextReleaseMs = now + 90 * 24 * hour, nowMs = now))
    }

    @Test
    fun `a release already past is looked up right away`() {
        assertEquals(now, noNextUpRecheckAt(nextReleaseMs = now - hour, nowMs = now))
    }

    @Test
    fun `the lookup is skipped only until the recheck time`() {
        assertTrue(isNoNextUpStillConfirmed(recheckAt = now + hour, nowMs = now))
        assertFalse(isNoNextUpStillConfirmed(recheckAt = now, nowMs = now))
        assertFalse(isNoNextUpStillConfirmed(recheckAt = null, nowMs = now))
    }
}
