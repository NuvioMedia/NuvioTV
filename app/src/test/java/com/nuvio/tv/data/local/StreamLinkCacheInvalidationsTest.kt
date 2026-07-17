package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLinkCacheInvalidationsTest {
    private val invalidations = StreamLinkCacheInvalidations()

    @Test
    fun `invalidation blocks the matching content key only`() {
        invalidations.invalidate("series|episode-1")

        assertTrue(invalidations.isInvalidated("series|episode-1"))
        assertFalse(invalidations.isInvalidated("series|episode-2"))
    }

    @Test
    fun `fresh save clears its observed invalidation`() {
        val token = invalidations.invalidate("series|episode-1")

        assertTrue(invalidations.clearIfCurrent("series|episode-1", token))
        assertFalse(invalidations.isInvalidated("series|episode-1"))
    }

    @Test
    fun `older save cannot clear a newer invalidation`() {
        val oldToken = invalidations.invalidate("series|episode-1")
        val newToken = invalidations.invalidate("series|episode-1")

        assertNotEquals(oldToken, newToken)
        assertFalse(invalidations.clearIfCurrent("series|episode-1", oldToken))
        assertTrue(invalidations.isInvalidated("series|episode-1"))
    }
}
