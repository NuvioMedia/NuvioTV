package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which saved dismissals a closed rewatch run may clear: every id form except the one the dismissal
 * the user just made is stored under.
 */
class NextUpDismissalKeysTest {

    @Test
    fun `the dismissal just made is kept and the stale forms are cleared`() {
        val keys = nextUpDismissalKeysToClear(
            matchKeys = listOf("tt0111161", "imdb:tt0111161", "simkl:123", " "),
            keptKey = " tt0111161 "
        )

        assertEquals(listOf("imdb:tt0111161", "simkl:123"), keys)
    }

    @Test
    fun `nothing is cleared when the run carries only the dismissed content id`() {
        assertTrue(nextUpDismissalKeysToClear(listOf("tt0111161"), "tt0111161").isEmpty())
    }
}
