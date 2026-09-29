package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.DiscoverLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character remains in discover without becoming a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
        assertTrue(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "a",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `minimum query leaves discover while live search is pending`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "ab",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location off never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.OFF,
                query = "",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `stored discover catalog is restored across media types`() {
        val current = discoverCatalog("current", "movie")
        val stored = discoverCatalog("stored", "series")

        val selected = resolveDiscoverCatalog(
            catalogs = listOf(current, stored),
            preferredKey = stored.key,
            currentKey = current.key
        )

        assertEquals(stored, selected)
    }

    @Test
    fun `current discover catalog is retained when stored catalog is unavailable`() {
        val current = discoverCatalog("current", "movie")

        val selected = resolveDiscoverCatalog(
            catalogs = listOf(discoverCatalog("first", "movie"), current),
            preferredKey = "missing",
            currentKey = current.key
        )

        assertEquals(current, selected)
    }

    private fun discoverCatalog(key: String, type: String) = DiscoverCatalog(
        key = key,
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.com",
        catalogId = key,
        catalogName = key,
        type = type,
        genres = emptyList(),
        supportsSkip = false,
        skipStep = 100
    )
}
