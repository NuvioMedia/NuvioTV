package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.usenet.IndexerCounts
import com.nuvio.tv.core.usenet.ProviderCounts
import org.junit.Assert.*
import org.junit.Test

class UsenetStatsCardTest {
    @Test fun `search yield counts answered searches only`() {
        // 10 searches: 2 failed and 1 paused say nothing; 7 answered, 1 of them empty.
        val counts = IndexerCounts(searches = 10, failedSearches = 2, pausedSearches = 1, emptySearches = 1, releases = 185)
        assertEquals(SearchYield(percent = 86, averageReleases = 31), counts.searchYield())
        assertEquals(SearchYield(0, 0), IndexerCounts(searches = 2, emptySearches = 2).searchYield())
        assertNull(IndexerCounts(searches = 3, failedSearches = 2, pausedSearches = 1).searchYield())
        assertNull(IndexerCounts().searchYield())
    }

    @Test fun `average response needs an answered request`() {
        assertEquals(640L, IndexerCounts(answered = 3, responseMs = 1920).averageResponseMs())
        assertNull(IndexerCounts(requests = 4, failedRequests = 4).averageResponseMs())
    }

    @Test fun `bars go largest first, as a share of the largest, and only when they compare`() {
        val bars = statsBars(listOf(StatsBarSource("a", "beta", 3), StatsBarSource("b", "Alpha", 3),
            StatsBarSource("c", "Gamma", 12), StatsBarSource("d", "Delta", 0)))
        assertEquals(listOf("c", "b", "a", "d"), bars.map { it.id })
        assertEquals(listOf(1f, 0.25f, 0.25f, 0f), bars.map { it.fraction })
        assertTrue(statsBars(listOf(StatsBarSource("a", "A", 5))).isEmpty())
        assertTrue(statsBars(listOf(StatsBarSource("a", "A", 0), StatsBarSource("b", "B", 0))).isEmpty())
        assertTrue(statsBars(emptyList()).isEmpty())
    }

    @Test fun `completion is the share of asked articles a provider had`() {
        assertEquals(99.2, ProviderCounts(articles = 992, missing = 8, errors = 50).completion()!!, 0.0)
        assertEquals(0.0, ProviderCounts(missing = 3).completion()!!, 0.0)
        assertNull(ProviderCounts(errors = 4).completion())
        assertEquals(35L, ProviderCounts(ttfbMs = 70, ttfbSessions = 2).averageFirstByteMs())
        assertNull(ProviderCounts(sessions = 3).averageFirstByteMs())
    }
}
