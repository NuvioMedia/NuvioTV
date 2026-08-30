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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelConcurrencyTest {

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
    fun `older search outer job cannot leave newer multi-word search loading`() = runTest {
        val firstAddonLookup = CompletableDeferred<Unit>()
        val addon = searchableAddon()
        val addonRepository = GatedAddonRepository(addon, firstAddonLookup)
        val catalogRepository = ImmediateCatalogRepository(addon)
        val viewModel = newViewModel(addonRepository, catalogRepository)

        // This is the Fire TV keyboard/voice sequence: the field receives the text and the
        // submitted query immediately follows it.
        viewModel.onEvent(SearchEvent.QueryChanged("Deep"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()

        // Q1 is suspended in the addon lookup; before the fix, the outer coroutine owning this
        // lookup was untracked and could resume after Q2.
        viewModel.onEvent(SearchEvent.QueryChanged("Deep Cover"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()
        advanceUntilIdle()

        // Q2 has completed and is no longer loading before the stale Q1 outer coroutine resumes.
        assertEquals("Deep Cover", viewModel.uiState.value.submittedQuery)
        assertFalse(viewModel.uiState.value.isSearching)
        assertTrue(catalogRepository.queries.contains("Deep Cover"))

        // Completing the stale Q1 lookup must not revive work for the old generation.
        firstAddonLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals("Deep Cover", viewModel.uiState.value.submittedQuery)
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(listOf("Deep Cover"), catalogRepository.queries.distinct())
    }

    @Test
    fun `moving from text input to live-search results remembers the query`() = runTest {
        val addon = searchableAddon()
        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        every { history.recentSearches } returns flowOf(emptyList())
        val viewModel = newViewModel(
            addonRepository = GatedAddonRepository(addon, CompletableDeferred(Unit)),
            catalogRepository = ImmediateCatalogRepository(addon),
            history = history
        )

        viewModel.onEvent(SearchEvent.QueryChanged("Deep Cover"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
        advanceUntilIdle()

        coVerify(exactly = 1) { history.saveRecentSearch("Deep Cover", 8) }
    }

    private fun newViewModel(
        addonRepository: AddonRepository,
        catalogRepository: CatalogRepository,
        history: SearchHistoryDataStore = mockk(relaxed = true)
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(com.nuvio.tv.domain.model.DiscoverLocation.OFF)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
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

    private class GatedAddonRepository(
        private val addon: Addon,
        private val firstLookupGate: CompletableDeferred<Unit>
    ) : AddonRepository {
        private val lookups = AtomicInteger()

        override fun getInstalledAddons(): Flow<List<Addon>> = flow {
            if (lookups.getAndIncrement() == 0) {
                firstLookupGate.await()
            }
            emit(listOf(addon))
        }

        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private class ImmediateCatalogRepository(
        private val addon: Addon
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
            val query = extraArgs.getValue("search")
            queries += query
            emit(NetworkResult.Loading)
            emit(NetworkResult.Success(row(addon, query)))
        }

        private fun row(addon: Addon, query: String): CatalogRow = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = addon.catalogs.single().id,
            catalogName = addon.catalogs.single().name,
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = query,
                    type = ContentType.MOVIE,
                    name = query,
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
