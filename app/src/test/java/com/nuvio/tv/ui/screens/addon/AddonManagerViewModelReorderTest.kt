package com.nuvio.tv.ui.screens.addon

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.TrackingAddonConsentController
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddonManagerViewModelReorderTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun testAddon(url: String): Addon = Addon(
        id = url,
        name = url,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = url,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
    )

    private fun createViewModel(
        addonsFlow: MutableStateFlow<List<Addon>>,
        addonRepository: AddonRepository = mockk(relaxed = true),
        usenetSettings: UsenetSourceSettings = mockUsenetSettings(),
    ): Pair<AddonManagerViewModel, AddonRepository> {
        every { addonRepository.getInstalledAddons() } returns addonsFlow
        val layoutPrefs = mockk<LayoutPreferenceDataStore>(relaxed = true) {
            every { homeCatalogOrderKeys } returns flowOf(emptyList())
            every { disabledHomeCatalogKeys } returns flowOf(emptyList())
            every { followAddonsOrder } returns flowOf(false)
        }
        val experiencePrefs = mockk<ExperienceModeDataStore>(relaxed = true) {
            every { mode } returns flowOf(ExperienceMode.ADVANCED)
        }
        val collectionsStore = mockk<CollectionsDataStore>(relaxed = true) {
            every { collections } returns flowOf(emptyList())
        }
        val vm = AddonManagerViewModel(
            addonRepository = addonRepository,
            layoutPreferenceDataStore = layoutPrefs,
            experienceModeDataStore = experiencePrefs,
            collectionsDataStore = collectionsStore,
            collectionSyncService = mockk<CollectionSyncService>(relaxed = true),
            homeCatalogSettingsSyncService = mockk<HomeCatalogSettingsSyncService>(relaxed = true),
            startupSyncService = mockk<StartupSyncService>(relaxed = true),
            profileManager = mockk<ProfileManager>(relaxed = true) {
                every { activeProfile } returns null
                every { activeProfileId } returns MutableStateFlow(1)
            },
            tmdbCollectionSourceResolver = mockk<TmdbCollectionSourceResolver>(relaxed = true),
            traktPublicListSourceResolver = mockk<TraktPublicListSourceResolver>(relaxed = true),
            usenetSourceSettings = usenetSettings,
            trackingAddonConsentController = mockk<TrackingAddonConsentController>(relaxed = true),
            context = mockk<Context>(relaxed = true)
        )
        return vm to addonRepository
    }

    private fun mockUsenetSettings(configuration: UsenetSourceConfiguration = UsenetSourceConfiguration()) =
        mockk<UsenetSourceSettings>(relaxed = true) {
            every { snapshots } returns flowOf(1 to Result.success(configuration))
            every { read(1) } returns configuration
        }

    private fun configuredUsenet() = UsenetSourceConfiguration(
        providers = listOf(UsenetProvider(name = "Provider", host = "nntp.example")),
        indexers = listOf(UsenetIndexer(name = "Indexer", apiUrl = "https://indexer.example")),
        addonsAbove = listOf("a")
    )

    @Test
    fun `enterAddonMoveMode ignores single-addon list`() = runTest {
        val addonsFlow = MutableStateFlow(listOf(testAddon("a")))
        val (vm, _) = createViewModel(addonsFlow)
        runCurrent()

        vm.enterAddonMoveMode("a")

        assertNull(vm.uiState.value.movingAddonKey)
    }

    @Test
    fun `confirmAddonMove skips persisting when addon order did not change`() = runTest {
        val addonsFlow = MutableStateFlow(listOf(testAddon("a"), testAddon("b")))
        val (vm, repo) = createViewModel(addonsFlow)
        runCurrent()

        vm.enterAddonMoveMode("a")
        vm.confirmAddonMove()
        runCurrent()

        assertNull(vm.uiState.value.movingAddonKey)
        coVerify(exactly = 0) { repo.setAddonOrder(any()) }
    }

    @Test
    fun `confirmAddonMove holds pending order across stale upstream emission until disk catches up`() = runTest {
        val a = testAddon("a")
        val b = testAddon("b")
        val c = testAddon("c")
        val addonsFlow = MutableStateFlow(listOf(a, b, c))
        val writeGate = CompletableDeferred<Unit>()
        val repo = mockk<AddonRepository>(relaxed = true)
        coEvery { repo.setAddonOrder(any()) } coAnswers { writeGate.await() }
        val (vm, _) = createViewModel(addonsFlow, repo)
        runCurrent()

        vm.enterAddonMoveMode("a")
        assertEquals(1, vm.moveAddonDuringDrag("a", 1))
        vm.confirmAddonMove()
        runCurrent()

        // Stale upstream emission arrives while write is still in flight (e.g. from another field change)
        addonsFlow.value = listOf(a.copy(version = "1.0.1"), b, c)
        runCurrent()

        assertEquals(listOf("b", "a", "c"), vm.uiState.value.installedAddons.map { it.baseUrl })

        writeGate.complete(Unit)
        addonsFlow.value = listOf(b, a.copy(version = "1.0.1"), c)
        runCurrent()

        assertEquals(listOf("b", "a", "c"), vm.uiState.value.installedAddons.map { it.baseUrl })
    }

    @Test
    fun `confirmAddonMove finishes persisting even when viewModel is cleared immediately`() = runTest {
        val addonsFlow = MutableStateFlow(listOf(testAddon("a"), testAddon("b")))
        val writeGate = CompletableDeferred<Unit>()
        var writeCompleted = false
        val repo = mockk<AddonRepository>(relaxed = true)
        coEvery { repo.setAddonOrder(any()) } coAnswers {
            writeGate.await()
            writeCompleted = true
        }
        val (vm, _) = createViewModel(addonsFlow, repo)
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
        })[AddonManagerViewModel::class.java]
        runCurrent()

        vm.enterAddonMoveMode("a")
        vm.moveAddonDuringDrag("a", 1)
        vm.confirmAddonMove()
        runCurrent()

        store.clear()
        writeGate.complete(Unit)
        runCurrent()

        assertTrue("setAddonOrder should finish uncancellably after ViewModel clear", writeCompleted)
    }

    @Test
    fun `cancelAddonMove restores baseline order without persisting`() = runTest {
        val addonsFlow = MutableStateFlow(listOf(testAddon("a"), testAddon("b"), testAddon("c")))
        val (vm, repo) = createViewModel(addonsFlow)
        runCurrent()

        vm.enterAddonMoveMode("a")
        vm.moveAddonDuringDrag("a", 1)
        assertEquals(listOf("b", "a", "c"), vm.uiState.value.installedAddons.map { it.baseUrl })

        vm.cancelAddonMove()
        runCurrent()

        assertNull(vm.uiState.value.movingAddonKey)
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.installedAddons.map { it.baseUrl })
        coVerify(exactly = 0) { repo.setAddonOrder(any()) }
    }

    @Test
    fun `moving Usenet by itself saves its position locally`() = runTest {
        val configuration = configuredUsenet()
        val settings = mockUsenetSettings(configuration)
        val addonsFlow = MutableStateFlow(listOf(testAddon("a")))
        val (vm, repo) = createViewModel(addonsFlow, usenetSettings = settings)
        vm.uiState.first { it.addonOrderKeys == listOf("a", BUILT_IN_USENET_KEY) }

        vm.enterAddonMoveMode(BUILT_IN_USENET_KEY)
        assertEquals(0, vm.moveAddonDuringDrag(BUILT_IN_USENET_KEY, -1))
        verify(exactly = 0) { settings.update(any(), any()) }
        vm.confirmAddonMove()
        runCurrent()

        assertEquals(listOf(BUILT_IN_USENET_KEY, "a"), vm.uiState.value.addonOrderKeys)
        verify(exactly = 1) { settings.update(configuration.copy(addonsAbove = emptyList()), 1) }
        coVerify(exactly = 0) { repo.setAddonOrder(any()) }
    }

    @Test
    fun `moving an addon across Usenet confirms both orders`() = runTest {
        val configuration = configuredUsenet()
        val settings = mockUsenetSettings(configuration)
        val addonsFlow = MutableStateFlow(listOf(testAddon("a"), testAddon("b")))
        val (vm, repo) = createViewModel(addonsFlow, usenetSettings = settings)
        vm.uiState.first { it.addonOrderKeys == listOf("a", BUILT_IN_USENET_KEY, "b") }

        vm.enterAddonMoveMode("b")
        assertEquals(1, vm.moveAddonDuringDrag("b", -1))
        assertEquals(0, vm.moveAddonDuringDrag("b", -1))
        vm.cancelAddonMove()
        assertEquals(listOf("a", BUILT_IN_USENET_KEY, "b"), vm.uiState.value.addonOrderKeys)
        verify(exactly = 0) { settings.update(any(), any()) }
        coVerify(exactly = 0) { repo.setAddonOrder(any()) }

        vm.enterAddonMoveMode("b")
        vm.moveAddonDuringDrag("b", -1)
        vm.moveAddonDuringDrag("b", -1)
        vm.confirmAddonMove()
        runCurrent()

        assertEquals(listOf("b", "a", BUILT_IN_USENET_KEY), vm.uiState.value.addonOrderKeys)
        verify(exactly = 1) { settings.update(configuration.copy(addonsAbove = listOf("b", "a")), 1) }
        coVerify(exactly = 1) { repo.setAddonOrder(listOf("b", "a")) }
    }
}
