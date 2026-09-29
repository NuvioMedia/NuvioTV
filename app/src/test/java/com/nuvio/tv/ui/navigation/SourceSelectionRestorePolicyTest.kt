package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSelectionRestorePolicyTest {
    @Test
    fun `manual player return from stream arms restoration`() {
        assertTrue(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = "stream/{videoId}/{contentType}/{title}"
            )
        )
    }

    @Test
    fun `autoplay and non-stream returns do not arm restoration`() {
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = true,
                previousRoute = "stream/{videoId}/{contentType}/{title}"
            )
        )
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = "detail/{itemId}/{itemType}"
            )
        )
        assertFalse(
            shouldArmSourceSelectionRestore(
                autoPlayNavigation = false,
                previousRoute = null
            )
        )
    }

    @Test
    fun `restore target is clamped to the current stream list`() {
        assertEquals(0, sourceSelectionRestoreTarget(-1, 10))
        assertEquals(4, sourceSelectionRestoreTarget(4, 10))
        assertEquals(9, sourceSelectionRestoreTarget(40, 10))
        assertNull(sourceSelectionRestoreTarget(4, 0))
    }
}
