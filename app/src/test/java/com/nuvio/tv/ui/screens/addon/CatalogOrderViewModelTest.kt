package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun testAddon(
        id: String,
        displayName: String = id,
        catalogIds: List<String>
    ): Addon = Addon(
        id = id,
        name = displayName,
        displayName = displayName,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example.com",
        catalogs = catalogIds.map { catId ->
            CatalogDescriptor(
                type = ContentType.MOVIE,
                rawType = "movie",
                id = catId,
                name = catId
            )
        },
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    private fun testCollection(id: String): Collection = Collection(
        id = id,
        title = "Collection $id",
        folders = emptyList()
    )

    private data class Harness(
        val vm: CatalogOrderViewModel,
        val layoutPrefs: LayoutPreferenceDataStore,
        val syncService: HomeCatalogSettingsSyncService,
        val diskOrderFlow: MutableStateFlow<List<String>>,
        val followFlow: MutableStateFlow<Boolean>,
    )

    private fun createHarness(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        initialOrderKeys: List<String> = emptyList(),
        initialFollowAddons: Boolean = false,
        layoutPrefs: LayoutPreferenceDataStore = mockk(relaxed = true),
    ): Harness {
        val diskOrderFlow = MutableStateFlow(initialOrderKeys)
        val followFlow = MutableStateFlow(initialFollowAddons)
        val addonRepo = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons)
        }
        val collectionsStore = mockk<CollectionsDataStore>(relaxed = true) {
            every { this@mockk.collections } returns flowOf(collections)
        }
        val serverCatalog = mockk<ServerCatalog>(relaxed = true) {
            every { this@mockk.addons } returns MutableStateFlow(emptyList())
        }
        every { layoutPrefs.homeCatalogOrderKeys } returns diskOrderFlow
        every { layoutPrefs.disabledHomeCatalogKeys } returns flowOf(emptyList())
        every { layoutPrefs.customCatalogTitles } returns flowOf(emptyMap())
        every { layoutPrefs.followAddonsOrder } returns followFlow

        val syncService = mockk<HomeCatalogSettingsSyncService>(relaxed = true)
        val vm = CatalogOrderViewModel(
            addonRepository = addonRepo,
            collectionsDataStore = collectionsStore,
            layoutPreferenceDataStore = layoutPrefs,
            homeCatalogSettingsSyncService = syncService,
            serverCatalog = serverCatalog
        )
        return Harness(vm, layoutPrefs, syncService, diskOrderFlow, followFlow)
    }

    @Test
    fun `toggleFollowAddonsOrder true optimistically preserves collections between addon blocks`() = runTest {
        val addonA = testAddon("addonA", catalogIds = listOf("a1", "a2"))
        val addonB = testAddon("addonB", catalogIds = listOf("b1"))
        val col1 = testCollection("c1")

        val keyA1 = homeCatalogKey("addonA", "movie", "a1")
        val keyA2 = homeCatalogKey("addonA", "movie", "a2")
        val keyB1 = homeCatalogKey("addonB", "movie", "b1")
        val keyCol1 = "collection_c1"

        val writeGate = CompletableDeferred<Unit>()
        val layoutPrefs = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layoutPrefs.setFollowAddonsOrder(true) } coAnswers { writeGate.await() }

        val h = createHarness(
            addons = listOf(addonA, addonB),
            collections = listOf(col1),
            initialOrderKeys = listOf(keyA2, keyA1, keyCol1, keyB1),
            initialFollowAddons = false,
            layoutPrefs = layoutPrefs
        )
        runCurrent()

        h.vm.toggleFollowAddonsOrder(true)
        runCurrent()

        // Before disk emits followAddonsOrder=true, optimistic order must already match
        // buildOrderedCatalogItems (keeping collection_c1 between addonA and addonB).
        assertEquals(
            listOf(keyA1, keyA2, keyCol1, keyB1),
            h.vm.uiState.value.items.map { it.key }
        )

        writeGate.complete(Unit)
        h.followFlow.value = true
        runCurrent()

        assertEquals(
            listOf(keyA1, keyA2, keyCol1, keyB1),
            h.vm.uiState.value.items.map { it.key }
        )
    }

    @Test
    fun `toggleFollowAddonsOrder true optimistically pushes mid-block collection after its addon block`() = runTest {
        val addonA = testAddon("addonA", catalogIds = listOf("a1", "a2"))
        val addonB = testAddon("addonB", catalogIds = listOf("b1"))
        val col1 = testCollection("c1")

        val keyA1 = homeCatalogKey("addonA", "movie", "a1")
        val keyA2 = homeCatalogKey("addonA", "movie", "a2")
        val keyB1 = homeCatalogKey("addonB", "movie", "b1")
        val keyCol1 = "collection_c1"

        val writeGate = CompletableDeferred<Unit>()
        val layoutPrefs = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layoutPrefs.setFollowAddonsOrder(true) } coAnswers { writeGate.await() }

        val h = createHarness(
            addons = listOf(addonA, addonB),
            collections = listOf(col1),
            initialOrderKeys = listOf(keyA1, keyCol1, keyA2, keyB1),
            initialFollowAddons = false,
            layoutPrefs = layoutPrefs
        )
        runCurrent()

        h.vm.toggleFollowAddonsOrder(true)
        runCurrent()

        assertEquals(
            listOf(keyA1, keyA2, keyCol1, keyB1),
            h.vm.uiState.value.items.map { it.key }
        )

        writeGate.complete(Unit)
    }

    @Test
    fun `confirmMove skips disk write when order did not change`() = runTest {
        val addonA = testAddon("addonA", catalogIds = listOf("a1", "a2"))
        val keyA1 = homeCatalogKey("addonA", "movie", "a1")
        val h = createHarness(addons = listOf(addonA))
        runCurrent()

        h.vm.enterMoveMode(keyA1)
        h.vm.confirmMove()
        runCurrent()

        assertNull(h.vm.uiState.value.movingKey)
        coVerify(exactly = 0) { h.layoutPrefs.setHomeCatalogOrderKeys(any()) }
        coVerify(exactly = 0) { h.syncService.triggerPush() }
    }

    @Test
    fun `confirmMove reverts UI to baseline when disk write throws`() = runTest {
        val addonA = testAddon("addonA", catalogIds = listOf("a1", "a2"))
        val keyA1 = homeCatalogKey("addonA", "movie", "a1")
        val keyA2 = homeCatalogKey("addonA", "movie", "a2")
        val layoutPrefs = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layoutPrefs.setHomeCatalogOrderKeys(any()) } throws IllegalStateException("disk full")

        val h = createHarness(addons = listOf(addonA), layoutPrefs = layoutPrefs)
        runCurrent()

        h.vm.enterMoveMode(keyA1)
        assertEquals(1, h.vm.moveDuringDrag(keyA1, 1))
        h.vm.confirmMove()
        runCurrent()

        assertNull(h.vm.uiState.value.movingKey)
        assertEquals(listOf(keyA1, keyA2), h.vm.uiState.value.items.map { it.key })
        coVerify(exactly = 0) { h.syncService.triggerPush() }
    }

    @Test
    fun `follow addons mode moves collection across addon block grouped by addonId not displayName`() = runTest {
        val addonA = testAddon("addonA", displayName = "Shared Name", catalogIds = listOf("a1", "a2"))
        val addonB = testAddon("addonB", displayName = "Shared Name", catalogIds = listOf("b1"))
        val col1 = testCollection("c1")

        val keyA1 = homeCatalogKey("addonA", "movie", "a1")
        val keyA2 = homeCatalogKey("addonA", "movie", "a2")
        val keyB1 = homeCatalogKey("addonB", "movie", "b1")
        val keyCol1 = "collection_c1"

        val h = createHarness(
            addons = listOf(addonA, addonB),
            collections = listOf(col1),
            initialOrderKeys = listOf(keyA1, keyA2, keyB1, keyCol1),
            initialFollowAddons = true
        )
        runCurrent()

        h.vm.enterMoveMode(keyCol1)
        assertEquals(2, h.vm.moveDuringDrag(keyCol1, -1))
        assertEquals(listOf(keyA1, keyA2, keyCol1, keyB1), h.vm.uiState.value.items.map { it.key })

        assertEquals(0, h.vm.moveDuringDrag(keyCol1, -1))
        assertEquals(listOf(keyCol1, keyA1, keyA2, keyB1), h.vm.uiState.value.items.map { it.key })
    }
}
