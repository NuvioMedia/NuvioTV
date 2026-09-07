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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CATALOG_ID = "top"
private const val ADDON_ID = "addon"

/**
 * A page belongs to the search run that requested it. These cover both ways that used to break:
 * paging read the live text field rather than the submitted query, and a late page merged into
 * whatever rows were current when it landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelPaginationTest {

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
    fun `paging asks for the submitted query, not what is currently typed`() = runTest {
        val repository = RecordingCatalogRepository()
        val viewModel = newViewModel(repository)

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()
        assertEquals("wolf", viewModel.uiState.value.submittedQuery)

        // Typed after the run started and never submitted. Paging must ignore it.
        viewModel.onEvent(SearchEvent.QueryChanged("wolf of wall street"))
        repository.queries.clear()

        viewModel.onEvent(SearchEvent.LoadMoreCatalog(CATALOG_ID, ADDON_ID, "movie"))
        advanceUntilIdle()

        assertTrue(
            "paging made no request",
            repository.queries.isNotEmpty()
        )
        assertTrue(
            "paged the live field instead of the submitted query: ${repository.queries}",
            repository.queries.all { it == "wolf" }
        )
    }

    @Test
    fun `a page that arrives after a new search does not merge into the new rows`() = runTest {
        val repository = RecordingCatalogRepository(pageDelayMs = 500)
        val viewModel = newViewModel(repository)

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.LoadMoreCatalog(CATALOG_ID, ADDON_ID, "movie"))
        // Start the paging job and let it suspend inside the fake's delay. Without this the
        // dispatcher would still have it queued, and the query change below would cancel a request
        // that never ran, which is not the case being covered.
        runCurrent()

        // A new search supersedes the run the page belongs to while it is genuinely in flight.
        viewModel.onEvent(SearchEvent.QueryChanged("alpha"))
        advanceUntilIdle()

        assertEquals("alpha", viewModel.uiState.value.submittedQuery)
        val titles = viewModel.uiState.value.catalogRows.flatMap { row -> row.items.map { it.name } }
        assertFalse(
            "a page from the previous query merged into the current rows: $titles",
            titles.any { it.startsWith("wolf page") }
        )
    }

    /** Answers every query, recording what it was asked for and paging indefinitely. */
    private class RecordingCatalogRepository(
        private val pageDelayMs: Long = 0L
    ) : CatalogRepository {
        val queries = mutableListOf<String>()

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
            emit(NetworkResult.Loading)
            if (skip > 0) {
                queries.add(query)
                if (pageDelayMs > 0) delay(pageDelayMs)
            }
            emit(NetworkResult.Success(row(catalogId, query, skip)))
        }

        private fun row(catalogId: String, query: String, skip: Int): CatalogRow = CatalogRow(
            addonId = ADDON_ID,
            addonName = "Addon",
            addonBaseUrl = "https://example.test",
            catalogId = catalogId,
            catalogName = catalogId,
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "${query}_$skip",
                    type = ContentType.MOVIE,
                    name = if (skip > 0) "$query page $skip" else query,
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            ),
            hasMore = true,
            nextSkip = skip + 100,
            supportsSkip = true
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

    private fun newViewModel(repository: CatalogRepository): SearchViewModel {
        val addon = Addon(
            id = ADDON_ID,
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.test",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = CATALOG_ID,
                    name = CATALOG_ID,
                    extra = listOf(CatalogExtra(name = "search"))
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )

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
            catalogRepository = repository,
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
}
