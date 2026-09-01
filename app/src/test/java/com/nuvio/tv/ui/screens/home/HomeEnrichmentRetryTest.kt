package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The regression this branch exists for: an external meta fetch that failed used to mark the item
 * prefetched anyway, so the focus gate skipped it for the rest of the session and it never
 * recovered once the addon came back.
 *
 * Only the enrichment collaborators are stubbed with behaviour; everything else the ViewModel
 * takes is a relaxed mock. Its init block suspends on profileManager.activeProfileReady, which a
 * relaxed mock never completes, so nothing beyond the pipeline under test runs.
 *
 * Uses runBlocking and polling rather than virtual time: onItemFocusPipeline launches on
 * Dispatchers.IO and debounces for 220ms, so the assertions wait for a monotonic call count
 * instead of advancing a scheduler the pipeline does not use.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeEnrichmentRetryTest {

    private val itemId = "tt0944947"
    private val otherId = "tt0903747"

    /**
     * A focus can reach getMetaFromAllAddons twice: the enrichment fetch passes the item's source
     * addon, and the background detail prefetch takes the parameter's default. Both reach the same
     * method, so the counters below separate them by that argument rather than lumping them
     * together, which also makes an accidental extra enrichment call visible.
     */
    private val sourceUrl = "https://catalog.example"

    private val created = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    /**
     * The pipeline leaves background prefetch coroutines running on the view model's scope, and
     * nothing here calls onCleared. Cancel them before the Main dispatcher is reset, so they
     * cannot outlive the test and dispatch into whatever runs next.
     */
    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `a failed external fetch is retried on a later focus and the success is recorded`() = runBlocking {
        val calls = AtomicInteger()
        val reachable = java.util.concurrent.atomic.AtomicBoolean(false)
        val backgroundCalls = AtomicInteger()
        val repository = metaRepository(calls, reachable, backgroundCalls)
        val viewModel = newViewModel(repository)
        viewModel.seedCatalog(item(itemId))

        viewModel.onItemFocusPipeline(item(itemId))
        awaitCalls(calls, expected = 1, what = "the first focus should have attempted one fetch")

        // The addon comes back. Focus has to move away and back, which is what a remote does.
        reachable.set(true)
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        awaitCalls(calls, expected = 2, what = "a failed fetch should be retried on a later focus")
        assertTrue(
            "the successful retry should be recorded, so it is not fetched a third time",
            itemId in viewModel.prefetchedExternalMetaIds
        )
        assertTrue(
            "the item should not be left marked as failed",
            itemId !in viewModel.failedEnrichmentIds.value
        )
        assertEquals(
            "the retried metadata should reach the enriched preview, not just the bookkeeping",
            ENRICHED_DESCRIPTION,
            viewModel.enrichedPreviews.value[itemId]?.description
        )

        // A further focus must not refetch now that the item resolved.
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))
        assertEquals("a resolved item must not be refetched", 2, calls.get())
        assertEquals(
            "the background detail prefetch runs once per distinct item, not once per focus",
            2,
            backgroundCalls.get()
        )
    }

    /**
     * The gate used to short-circuit when either enrichment cache held the item, so a TMDB success
     * that supplied a logo suppressed the external retry. The two sources are independent: a
     * resolved TMDB fetch must not stand in for an external fetch that failed, and re-entering
     * must not refetch TMDB.
     */
    @Test
    fun `a TMDB success does not suppress the external retry`() = runBlocking {
        val calls = AtomicInteger()
        val reachable = java.util.concurrent.atomic.AtomicBoolean(false)
        val repository = metaRepository(calls, reachable, AtomicInteger())
        // Distinct ids per item: focusing the other item also fetches TMDB, so the verification
        // below has to name the one under test rather than counting every enrichment call.
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            coEvery { ensureTmdbId(eq(itemId), any()) } returns "1399"
            coEvery { ensureTmdbId(eq(otherId), any()) } returns "2000"
        }
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true) {
            coEvery { fetchEnrichment(any(), any(), any()) } returns mockk<TmdbEnrichment>(relaxed = true)
        }
        val viewModel = newViewModel(repository, tmdbService, tmdbMetadataService)
        viewModel.currentTmdbSettings = TmdbSettings(enabled = true, modernHomeEnabled = true)
        viewModel.seedCatalog(item(itemId))

        viewModel.onItemFocusPipeline(item(itemId))
        awaitCalls(calls, 1, "TMDB succeeding should not stop the external fetch being attempted")

        // The state the second gate reads: TMDB resolved and is cached, external failed and is not.
        assertTrue("TMDB should be cached after it resolved", itemId in viewModel.prefetchedTmdbIds)
        assertTrue(
            "a failed external fetch must not be cached",
            itemId !in viewModel.prefetchedExternalMetaIds
        )

        reachable.set(true)
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        awaitCalls(calls, 2, "a TMDB success must not suppress the external retry")
        assertTrue(
            "the external retry resolved, so it should now be cached too",
            itemId in viewModel.prefetchedExternalMetaIds
        )
        coVerify(exactly = 1) { tmdbMetadataService.fetchEnrichment(eq("1399"), any(), any()) }
    }

    /**
     * fetchExternalMetaOutcome promises to turn a thrown exception into Failed. The repository
     * reports request failures as NetworkResult.Error, so this covers what it does not promise.
     */
    /**
     * The in-flight id is claimed before the fetch is launched and released when it completes. A
     * focus that moves on during the debounce cancels the job before its body runs, so releasing
     * inside the body would strand the id and block every later fetch for that item.
     */
    @Test
    fun `a focus cancelled during the debounce does not block later fetches`() = runBlocking {
        val calls = AtomicInteger()
        val repository = metaRepository(calls, java.util.concurrent.atomic.AtomicBoolean(true), AtomicInteger())
        val viewModel = newViewModel(repository)
        viewModel.seedCatalog(item(itemId))

        // Claim the id, then move on before the debounce elapses so the fetch never runs.
        viewModel.onItemFocusPipeline(item(itemId))
        viewModel.onItemFocusPipeline(item(otherId))
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS + 400)
        assertEquals("the cancelled focus should not have fetched", 0, calls.get())

        focusAndSettle(viewModel, item(itemId))

        awaitCalls(calls, 1, "the item must still be fetchable after a cancelled focus")
    }

    @Test
    fun `an exception thrown by the repository is retried`() = runBlocking {
        val calls = AtomicInteger()
        val repository = newMetaRepository()
        coEvery { repository.getMetaFromAllAddons(any(), eq(itemId), eq(sourceUrl)) } answers {
            calls.incrementAndGet()
            throw IllegalStateException("boom")
        }
        val viewModel = newViewModel(repository)
        viewModel.seedCatalog(item(itemId))

        viewModel.onItemFocusPipeline(item(itemId))
        awaitCalls(calls, 1, "the first focus should have attempted one fetch")

        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        awaitCalls(calls, 2, "a thrown exception should be retried like any other failure")
        assertTrue(
            "a thrown exception must not be cached as a resolved lookup",
            itemId !in viewModel.prefetchedExternalMetaIds
        )
    }

    @Test
    fun `the failed-enrichment set is cleared on success and stays bounded`() = runBlocking {
        val viewModel = newViewModel(newMetaRepository())

        viewModel.markEnrichmentFailed(itemId)
        assertTrue("a failure should be recorded", itemId in viewModel.failedEnrichmentIds.value)

        viewModel.clearEnrichmentFailure(itemId)
        assertTrue(
            "enrichment landing should drop the marker",
            itemId !in viewModel.failedEnrichmentIds.value
        )

        // One past the bound: the oldest entry is dropped, the newest is kept.
        repeat(65) { viewModel.markEnrichmentFailed("id-$it") }
        assertEquals(64, viewModel.failedEnrichmentIds.value.size)
        assertTrue("the oldest entry should be evicted", "id-0" !in viewModel.failedEnrichmentIds.value)
        assertTrue("the newest entry should be kept", "id-64" in viewModel.failedEnrichmentIds.value)

        viewModel.clearEnrichmentFailures()
        assertTrue("a reset should empty the set", viewModel.failedEnrichmentIds.value.isEmpty())
    }

    /**
     * The inverse of the test above, pinning the asymmetry rather than leaving it to be
     * rediscovered: a cached external result closes the gate even when TMDB has not run. TMDB is
     * only ever marked prefetched on a successful enrichment, so gating on it as well would
     * re-enter on every focus for any item TMDB has no match for.
     */
    @Test
    fun `a cached external result does not reopen the gate for TMDB`() = runBlocking {
        val calls = AtomicInteger()
        val repository = metaRepository(calls, java.util.concurrent.atomic.AtomicBoolean(true), AtomicInteger())
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            coEvery { ensureTmdbId(any(), any()) } returns "1399"
        }
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true) {
            coEvery { fetchEnrichment(any(), any(), any()) } returns mockk<TmdbEnrichment>(relaxed = true)
        }
        val viewModel = newViewModel(repository, tmdbService, tmdbMetadataService)
        viewModel.currentTmdbSettings = TmdbSettings(enabled = true, modernHomeEnabled = true)
        viewModel.prefetchedExternalMetaIds.add(itemId)
        viewModel.seedCatalog(item(itemId))

        focusAndSettle(viewModel, item(itemId))

        assertEquals("external already resolved, so it is not fetched again", 0, calls.get())
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `external enrichment disabled means a TMDB-prefetched item does no external work`() = runBlocking {
        val calls = AtomicInteger()
        val repository = metaRepository(calls, java.util.concurrent.atomic.AtomicBoolean(true), AtomicInteger())
        val viewModel = newViewModel(repository)
        viewModel.externalMetaPrefetchEnabled = false
        viewModel.prefetchedTmdbIds.add(itemId)
        viewModel.seedCatalog(item(itemId))

        focusAndSettle(viewModel, item(itemId))

        assertEquals("external enrichment is off, so nothing should be fetched", 0, calls.get())
    }

    @Test
    fun `an addon reporting nothing to add is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val repository = newMetaRepository()
        coEvery { repository.getMetaFromAllAddons(any(), eq(itemId), eq(sourceUrl)) } answers {
            calls.incrementAndGet()
            flowOf(NetworkResult.Error("source sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE))
        }
        val viewModel = newViewModel(repository)

        focusAndSettle(viewModel, item(itemId))
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        assertEquals("a final answer must not be refetched", 1, calls.get())
    }

    @Test
    fun `an item no addon carries is not retried`() = runBlocking {
        val calls = AtomicInteger()
        val repository = newMetaRepository()
        coEvery { repository.getMetaFromAllAddons(any(), eq(itemId), eq(sourceUrl)) } answers {
            calls.incrementAndGet()
            flowOf(NetworkResult.Error("not found", NetworkResult.META_NOT_FOUND_CODE))
        }
        val viewModel = newViewModel(repository)

        focusAndSettle(viewModel, item(itemId))
        focusAndSettle(viewModel, item(otherId))
        focusAndSettle(viewModel, item(itemId))

        assertEquals("a not-found answer must not be refetched", 1, calls.get())
    }

    private suspend fun focusAndSettle(viewModel: HomeViewModel, item: MetaPreview) {
        viewModel.onItemFocusPipeline(item)
        // Debounce plus the fetch itself; the assertions that need a specific count poll instead.
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS + 400)
    }

    /**
     * The pipeline debounces by 220ms and then fetches on Dispatchers.IO, so the count is polled
     * rather than sampled after a fixed wait.
     */
    private suspend fun awaitCalls(calls: AtomicInteger, expected: Int, what: String) {
        val reached = runCatching {
            withTimeout(5_000) {
                while (calls.get() < expected) delay(25)
            }
        }.isSuccess
        assertEquals(what, expected, if (reached) expected else calls.get())
    }

    /**
     * The background detail prefetch calls the same method with the parameter's default. It is not
     * counted, but it still needs a flow that terminates, or its first {} throws into the view
     * model scope and kotlinx-coroutines-test reports it against the next test to run.
     */
    private fun newMetaRepository(backgroundCalls: AtomicInteger = AtomicInteger()): MetaRepository =
        mockk(relaxed = true) {
            coEvery { getMetaFromAllAddons(any(), any(), isNull()) } answers {
                backgroundCalls.incrementAndGet()
                flowOf(NetworkResult.Error("background prefetch, not part of this test"))
            }
        }

    private fun metaRepository(
        calls: AtomicInteger,
        reachable: java.util.concurrent.atomic.AtomicBoolean,
        backgroundCalls: AtomicInteger
    ): MetaRepository = newMetaRepository(backgroundCalls).apply {
        coEvery { getMetaFromAllAddons(any(), eq(itemId), eq(sourceUrl)) } answers {
            calls.incrementAndGet()
            if (reachable.get()) {
                flowOf(NetworkResult.Success(meta()))
            } else {
                flowOf(NetworkResult.Error("Failed to connect"))
            }
        }
    }

    private fun meta() = Meta(
        id = itemId,
        type = ContentType.SERIES,
        name = "Test Item",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = ENRICHED_DESCRIPTION,
        releaseInfo = null,
        imdbRating = 8.5f,
        genres = listOf("Drama"),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    /**
     * updateCatalogItemWithMeta publishes through findCatalogItemById, so an item that is not in
     * the catalog index enriches its bookkeeping but never its preview.
     */
    private fun HomeViewModel.seedCatalog(item: MetaPreview) {
        synchronized(catalogStateLock) {
            val rowKey = "seeded-row"
            catalogsMap[rowKey] = CatalogRow(
                addonId = "test.addon",
                addonName = "Test Addon",
                addonBaseUrl = sourceUrl,
                catalogId = "test.catalog",
                catalogName = "Test Catalog",
                type = ContentType.SERIES,
                items = listOf(item)
            )
            catalogItemKeyIndex.getOrPut(item.id) { mutableSetOf() }.add(rowKey)
        }
    }

    private fun item(id: String) = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        name = "Test Item",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        sourceAddonBaseUrl = sourceUrl
    )

    private fun newViewModel(
        metaRepository: MetaRepository,
        tmdbService: TmdbService = mockk(relaxed = true),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true)
    ): HomeViewModel {
        // Relaxed mocks hand back flows that complete without emitting, and the pipeline calls
        // first {} on several of them from launches that do not catch. Those throw
        // NoSuchElementException into the view model scope, which kotlinx-coroutines-test then
        // reports against whichever test runs next. Stub the ones this pipeline touches.
        val profileManager = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed = true) {
            // Never ready, so the init chain parks instead of running the whole home pipeline.
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val cwEnrichmentCache =
            mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed = true) {
                every { cacheCleared } returns MutableStateFlow(0)
            }
        val watchProgressRepository =
            mockk<com.nuvio.tv.domain.repository.WatchProgressRepository>(relaxed = true) {
                every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
            }
        val viewModel = HomeViewModel(
            appContext = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            metaRepository = metaRepository,
            collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            mdbListRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = mockk(relaxed = true),
            cwEnrichmentCache = cwEnrichmentCache,
            profileManager = profileManager,
            tvRecommendationManager = mockk(relaxed = true)
        )
        // The pipeline defers everything while the startup grace period is active, and TMDB is
        // switched off so the external addon is the only enrichment source under test.
        viewModel.startupGracePeriodActive = false
        viewModel.externalMetaPrefetchEnabled = true
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }

    private companion object {
        const val ENRICHED_DESCRIPTION = "Enriched from the addon"
    }
}
