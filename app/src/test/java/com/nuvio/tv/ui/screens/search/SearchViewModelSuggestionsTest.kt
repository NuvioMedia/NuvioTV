package com.nuvio.tv.ui.screens.search

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TITLE = "The Wolf of Wall Street"

/**
 * Suggestions are pushed to the keyboard's own suggestion strip while the user types, so they
 * have to survive live search. Live search runs the same performSearch() that a submit runs,
 * and that used to retire the strip roughly 200ms after each fetch filled it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelSuggestionsTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `live search leaves the suggestions it just fetched in place`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()

        // Live search has run by now: it owns the results, not the suggestion strip.
        assertEquals("wolf", viewModel.uiState.value.submittedQuery)
        assertEquals(listOf(TITLE), viewModel.uiState.value.suggestions)
    }

    /**
     * Walks the two debounces one at a time rather than asserting the settled state, so it fails
     * on the original mechanism: the strip filling at 150ms and the live search emptying it at
     * 350ms. An end-state assertion alone would pass even if the ordering were wrong.
     */
    @Test
    fun `the strip fills before live search runs and survives it`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))

        // Past SUGGESTION_DEBOUNCE_MS, short of LIVE_SEARCH_DEBOUNCE_MS. An empty submittedQuery
        // is what pins the ordering: the strip is full while live search is still pending.
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(TITLE), viewModel.uiState.value.suggestions)
        assertEquals("", viewModel.uiState.value.submittedQuery)

        // Past LIVE_SEARCH_DEBOUNCE_MS. This is the run that used to empty the strip.
        advanceTimeBy(200)
        runCurrent()
        assertEquals("wolf", viewModel.uiState.value.submittedQuery)
        assertEquals(listOf(TITLE), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `suggestions survive every keystroke of a word`() = runTest {
        val viewModel = newViewModel()

        listOf("wo", "wol", "wolf").forEach { typed ->
            viewModel.onEvent(SearchEvent.QueryChanged(typed))
            advanceUntilIdle()
            assertEquals("cleared while typing \"$typed\"", listOf(TITLE), viewModel.uiState.value.suggestions)
        }
    }

    @Test
    fun `submitting retires the suggestions`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.suggestions.isNotEmpty())

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `dropping below the minimum query length retires the suggestions`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.suggestions.isNotEmpty())

        viewModel.onEvent(SearchEvent.QueryChanged("w"))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `clearing the field retires the suggestions and the submitted query`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.suggestions.isNotEmpty())

        viewModel.onEvent(SearchEvent.QueryChanged(""))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
        assertEquals("", viewModel.uiState.value.submittedQuery)
    }

    @Test
    fun `a query that matches nothing retires the previous strip`() = runTest {
        val viewModel = newViewModel()

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertEquals(listOf(TITLE), viewModel.uiState.value.suggestions)

        // Long enough to keep searching, but nothing answers it. The strip must not go on
        // captioning the earlier query's results.
        viewModel.onEvent(SearchEvent.QueryChanged("wolfx"))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }

    private fun newViewModel(): SearchViewModel {
        val addon = searchableAddon()

        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(com.nuvio.tv.domain.model.DiscoverLocation.OFF)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = SingleAddonRepository(addon),
            catalogRepository = TitleCatalogRepository(addon),
            metaRepository = mockk(relaxed = true),
            discoverSelectionDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            context = mockk<Context>(relaxed = true)
        )
    }

    private class SingleAddonRepository(private val addon: Addon) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(listOf(addon))
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    /** Answers with one title, and only for queries that title contains, so both the filled
     *  and the empty strip are reachable from a test. */
    private class TitleCatalogRepository(private val addon: Addon) : CatalogRepository {
        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs["search"].orEmpty()
            val matches = query.isNotBlank() && TITLE.contains(query, ignoreCase = true)
            emit(NetworkResult.Loading)
            emit(NetworkResult.Success(row(matches)))
        }

        private fun row(matches: Boolean): CatalogRow = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = addon.catalogs.single().id,
            catalogName = addon.catalogs.single().name,
            type = ContentType.MOVIE,
            items = if (!matches) emptyList() else listOf(
                MetaPreview(
                    id = "tt0993846",
                    type = ContentType.MOVIE,
                    name = TITLE,
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            )
        )
    }

    private fun searchableAddon(): Addon {
        val catalog = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "top",
            name = "Top",
            extra = listOf(CatalogExtra(name = "search"))
        )
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.test",
            catalogs = listOf(catalog),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )
    }
}
