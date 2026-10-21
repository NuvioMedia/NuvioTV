package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedIdAliasesTest {
    @Test
    fun `extracts the numeric id from tmdb content ids only`() {
        assertEquals(1329016, tmdbNumericId("tmdb:1329016"))
        assertEquals(236235, tmdbNumericId("TMDB:236235"))
        assertNull(tmdbNumericId("tt33095251"))
        assertNull(tmdbNumericId("trakt:42"))
        assertNull(tmdbNumericId("tmdb:"))
        assertNull(tmdbNumericId("tmdb:abc"))
    }

    @Test
    fun `tmdb item matches history through its imdb alias`() {
        val watched = setOf("tt33095251")
        assertTrue(isWatchedWithAlias("tmdb:1329016", "tt33095251", watched))
    }

    @Test
    fun `tmdb item without an alias does not match imdb history`() {
        assertFalse(isWatchedWithAlias("tmdb:1329016", null, setOf("tt33095251")))
    }

    @Test
    fun `direct id match is unchanged`() {
        assertTrue(isWatchedWithAlias("tt33095251", null, setOf("tt33095251")))
        assertTrue(isWatchedWithAlias("tmdb:1329016", null, setOf("tmdb:1329016")))
    }

    @Test
    fun `a different alias does not match`() {
        assertFalse(isWatchedWithAlias("tmdb:1329016", "tt0000001", setOf("tt33095251")))
    }
}
