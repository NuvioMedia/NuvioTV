package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWatchlistRowTest {

    private val watchlistTab = LibraryListTab(
        key = "trakt:watchlist",
        title = "Watchlist",
        type = LibraryListTab.Type.WATCHLIST
    )
    private val personalTab = LibraryListTab(
        key = "trakt:list:7",
        title = "Favourites",
        type = LibraryListTab.Type.PERSONAL
    )

    private val watchlisted = entry("tt1", "trakt:watchlist")
    private val personalOnly = entry("tt2", "trakt:list:7")
    private val inBoth = entry("tt3", "trakt:list:7", "trakt:watchlist")

    private fun entry(id: String, vararg listKeys: String) = LibraryEntry(
        id = id,
        type = "movie",
        name = id,
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = listKeys.toSet()
    )

    private fun select(
        items: List<LibraryEntry>,
        sourceMode: LibrarySourceMode = LibrarySourceMode.TRAKT,
        tabs: List<LibraryListTab> = listOf(personalTab, watchlistTab)
    ) = selectHomeWatchlistEntries(sourceMode, items, tabs)

    @Test
    fun `remote source keeps entries in the watchlist tab, in repository order`() {
        assertEquals(
            listOf(inBoth, watchlisted),
            select(listOf(inBoth, personalOnly, watchlisted))
        )
    }

    @Test
    fun `the tab key decides, not a hard coded one`() {
        val simklTab = LibraryListTab("simkl:plantowatch", "Plan to watch", LibraryListTab.Type.WATCHLIST)
        val planned = entry("tt4", "simkl:plantowatch")

        assertEquals(
            listOf(planned),
            select(listOf(watchlisted, planned), sourceMode = LibrarySourceMode.SIMKL, tabs = listOf(simklTab))
        )
    }

    @Test
    fun `local source uses every saved item`() {
        val saved = listOf(entry("tt5"), entry("tt6"))

        assertEquals(saved, select(saved, sourceMode = LibrarySourceMode.LOCAL, tabs = emptyList()))
    }

    @Test
    fun `personal lists are excluded`() {
        assertTrue(select(listOf(personalOnly)).isEmpty())
    }

    @Test
    fun `no match gives an empty result`() {
        assertTrue(select(emptyList()).isEmpty())
        assertTrue(select(listOf(watchlisted), tabs = emptyList()).isEmpty())
        assertTrue(select(listOf(watchlisted), tabs = listOf(personalTab)).isEmpty())
    }

    @Test
    fun `row keeps the first 100 entries for any source`() {
        val entries = (1..150).map { entry("tt$it", "trakt:watchlist") }

        assertEquals(entries.take(100), select(entries))
        assertEquals(entries.take(100), select(entries, sourceMode = LibrarySourceMode.LOCAL))
    }

    @Test
    fun `row is not paginated and is recognised as the watchlist row`() {
        val row = buildHomeWatchlistRow("Watchlist", listOf(watchlisted.toMetaPreview()))

        assertFalse(row.hasMore)
        assertFalse(row.supportsSkip)
        assertFalse(row.isLoading)
        assertEquals("Watchlist", row.catalogName)
        assertTrue(row.isHomeWatchlistRow())
        assertEquals("Watchlist", catalogRowTitle(row, showCatalogTypeSuffix = true))
    }

    @Test
    fun `setting syncs through the profile settings blob`() {
        assertFalse(
            shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "home_watchlist_row_enabled")
        )
    }
}
