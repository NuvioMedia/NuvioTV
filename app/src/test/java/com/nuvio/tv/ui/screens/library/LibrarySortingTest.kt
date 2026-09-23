package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.tracking.TrackingLibrarySorter
import com.nuvio.tv.data.mdblist.MdbListLibraryProjection
import com.nuvio.tv.data.mdblist.MdbListLibrarySnapshot
import com.nuvio.tv.data.mdblist.decodeMdbListLibraryPage
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibrarySortingTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `MDBList provider order keeps the zero ranked title first`() = runTest {
        val f = LibraryViewModelTestFixture()
        val items = decodeMdbListLibraryPage("""[
            {"id":1,"mediatype":"movie","title":"Zulu","rank":0},
            {"id":2,"mediatype":"movie","title":"Alpha","rank":1}
        ]""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(itemsByList = mapOf(f.tab.key to items))).entries
        runCurrent()
        assertEquals(listOf("Zulu", "Alpha"), f.viewModel.uiState.value.visibleItems.map { it.name })
    }

    @Test
    fun `provider added orders override missing dates and remain valid after filtering`() = runTest {
        val requested = mutableListOf<Pair<String, Boolean>>()
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = flowOf(
                listOf("movie:tmdb:2", "series:tmdb:1", "movie:tmdb:1").let { if (descending) it else it.reversed() }
            ).also { requested += listKey to descending }
        }
        val f = populatedFixture(sorter)
        runCurrent()
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_DESC)
        runCurrent()
        assertEquals(listOf("Bravo", "Alpha", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onSelectTypeTab(LibraryTypeTab("movie", "Movies"))
        runCurrent()
        assertEquals(listOf("Bravo", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_ASC)
        runCurrent()
        assertEquals(listOf("Zulu", "Bravo"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(listOf(f.tab.key to true, f.tab.key to false), requested)
        f.viewModel.onSelectSortOption(LibrarySortOption.TITLE_ASC)
        runCurrent()
        assertEquals(listOf("Bravo", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(2, requested.size)
    }

    @Test
    fun `late provider order cannot replace a newly selected title sort`() = runTest {
        val order = MutableStateFlow<List<String>?>(null)
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = order
        }
        val f = populatedFixture(sorter)
        runCurrent()
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_DESC)
        runCurrent()
        f.viewModel.onSelectSortOption(LibrarySortOption.TITLE_ASC)
        runCurrent()
        order.value = listOf("movie:tmdb:1", "movie:tmdb:2", "series:tmdb:1")
        runCurrent()
        assertEquals(listOf("Alpha", "Bravo", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertNull(f.viewModel.uiState.value.providerAddedOrder)
    }

    @Test
    fun `switching lists subscribes to that list added order`() = runTest {
        val first = MutableStateFlow<List<String>?>(null)
        val second = MutableStateFlow<List<String>?>(listOf("movie:tmdb:2", "series:tmdb:1", "movie:tmdb:1"))
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = if (listKey.endsWith(":8")) second else first
        }
        val f = populatedFixture(sorter)
        val other = f.tab.copy(key = "mdblist:list:8")
        f.tabs.value += other
        f.items.value = f.items.value.map { it.copy(listKeys = it.listKeys + other.key) }
        runCurrent()
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_DESC)
        runCurrent()
        f.viewModel.onSelectListTab(other.key)
        runCurrent()
        first.value = listOf("movie:tmdb:1", "movie:tmdb:2", "series:tmdb:1")
        runCurrent()
        assertEquals(listOf("Bravo", "Alpha", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(other.key, f.viewModel.uiState.value.providerAddedOrder!!.listKey)
    }

    @Test
    fun `failed provider sort returns to provider order and can be retried`() = runTest {
        var attempts = 0
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = flow {
                if (++attempts == 1) throw java.io.IOException("Offline")
                emit(listOf("movie:tmdb:2", "series:tmdb:1", "movie:tmdb:1"))
            }
        }
        val f = populatedFixture(sorter)
        runCurrent()
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_DESC)
        runCurrent()
        assertEquals(LibrarySortOption.DEFAULT, f.viewModel.uiState.value.selectedSortOption)
        assertEquals(listOf("Zulu", "Alpha", "Bravo"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onSelectSortOption(LibrarySortOption.ADDED_DESC)
        runCurrent()
        assertEquals(listOf("Bravo", "Alpha", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(2, attempts)
    }

    @Test
    fun `persisted added sort restores provider ordering and observes refreshed orders`() = runTest {
        val order = MutableStateFlow<List<String>?>(listOf("movie:tmdb:2", "series:tmdb:1", "movie:tmdb:1"))
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = order
        }
        val f = populatedFixture(sorter)
        f.persistedSort.value = LibrarySortOption.ADDED_DESC.key
        runCurrent()
        assertEquals(LibrarySortOption.ADDED_DESC, f.viewModel.uiState.value.selectedSortOption)
        assertEquals(listOf("Bravo", "Alpha", "Zulu"), f.viewModel.uiState.value.visibleItems.map { it.name })
        order.value = order.value!!.reversed()
        runCurrent()
        assertEquals(listOf("Zulu", "Alpha", "Bravo"), f.viewModel.uiState.value.visibleItems.map { it.name })
        assertEquals(LibrarySortOption.ADDED_DESC, f.viewModel.uiState.value.selectedSortOption)
    }

    private fun populatedFixture(sorter: TrackingLibrarySorter): LibraryViewModelTestFixture {
        val f = LibraryViewModelTestFixture(sorter)
        val items = decodeMdbListLibraryPage("""[
            {"id":1,"mediatype":"movie","title":"Zulu","rank":0},
            {"id":1,"mediatype":"show","title":"Alpha","rank":1},
            {"id":2,"mediatype":"movie","title":"Bravo","rank":2}
        ]""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(itemsByList = mapOf(f.tab.key to items))).entries
        return f
    }

    @Test
    fun `all MDBList sort options reorder decoded entries when rank and added dates exist`() = runTest {
        val f = LibraryViewModelTestFixture()
        val items = decodeMdbListLibraryPage("""{"items":[
            {"id":1,"mediatype":"movie","title":"Zulu","rank":2,"added_at":"2026-09-01T00:00:00Z"},
            {"id":2,"mediatype":"show","title":"The Alpha","rank":3,"added_at":"2026-09-03T00:00:00Z"},
            {"id":3,"mediatype":"movie","title":"Bravo","rank":1,"added_at":"2026-09-02T00:00:00Z"}
        ]}""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(
            itemsByList = mapOf(f.tab.key to items)
        )).entries
        runCurrent()
        val expected = mapOf(
            LibrarySortOption.DEFAULT to listOf("Bravo", "Zulu", "The Alpha"),
            LibrarySortOption.ADDED_DESC to listOf("The Alpha", "Bravo", "Zulu"),
            LibrarySortOption.ADDED_ASC to listOf("Zulu", "Bravo", "The Alpha"),
            LibrarySortOption.TITLE_ASC to listOf("The Alpha", "Bravo", "Zulu"),
            LibrarySortOption.TITLE_DESC to listOf("Zulu", "Bravo", "The Alpha")
        )
        assertEquals(expected.keys.toList(), f.viewModel.uiState.value.availableSortOptions)
        for ((option, names) in expected) {
            f.viewModel.onSelectSortOption(option)
            runCurrent()
            assertEquals(option.name, names, f.viewModel.uiState.value.visibleItems.map { it.name })
            assertEquals(option, f.viewModel.uiState.value.selectedSortOption)
        }
    }

    @Test
    fun `MDBList response without added dates makes both added sorts identical while title and rank work`() = runTest {
        val f = LibraryViewModelTestFixture()
        val items = decodeMdbListLibraryPage("""{
            "movies":[{"id":278,"mediatype":"movie","title":"The Shawshank Redemption","rank":1}],
            "shows":[{"id":1396,"mediatype":"show","title":"Breaking Bad","rank":2}]
        }""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(
            itemsByList = mapOf(f.tab.key to items)
        )).entries
        runCurrent()
        assertTrue(f.items.value.all { it.listedAt == 0L })
        val providerOrder = listOf("The Shawshank Redemption", "Breaking Bad")
        val alphabetical = providerOrder.reversed()
        for ((option, names) in mapOf(
            LibrarySortOption.DEFAULT to providerOrder,
            LibrarySortOption.ADDED_DESC to alphabetical,
            LibrarySortOption.ADDED_ASC to alphabetical,
            LibrarySortOption.TITLE_ASC to alphabetical,
            LibrarySortOption.TITLE_DESC to providerOrder
        )) {
            f.viewModel.onSelectSortOption(option)
            runCurrent()
            assertEquals(option.name, names, f.viewModel.uiState.value.visibleItems.map { it.name })
        }
    }

    @Test
    fun `MDBList provider order preserves response order when rank and added date are absent`() = runTest {
        val f = LibraryViewModelTestFixture()
        val items = decodeMdbListLibraryPage("""{"items":[
            {"id":1,"mediatype":"movie","title":"Zulu"},
            {"id":2,"mediatype":"show","title":"Alpha"},
            {"id":3,"mediatype":"movie","title":"Bravo"}
        ]}""").items
        f.items.value = MdbListLibraryProjection(MdbListLibrarySnapshot(
            itemsByList = mapOf(f.tab.key to items)
        )).entries
        runCurrent()
        assertEquals(LibrarySortOption.DEFAULT, f.viewModel.uiState.value.selectedSortOption)
        assertEquals(listOf("Zulu", "Alpha", "Bravo"), f.viewModel.uiState.value.visibleItems.map { it.name })
    }

}
