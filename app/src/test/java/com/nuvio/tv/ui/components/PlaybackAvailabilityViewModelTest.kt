package com.nuvio.tv.ui.components

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackAvailabilityViewModelTest {
    @Test
    fun `source changes update availability without fetching metadata or executing sources`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModelStore = ViewModelStore()
        try {
            val addons = MutableStateFlow<List<Addon>>(emptyList())
            val scrapers = MutableStateFlow<List<ScraperInfo>>(emptyList())
            val pluginsEnabled = MutableStateFlow(true)
            val addonRepository = mockk<AddonRepository>()
            val pluginDataStore = mockk<PluginDataStore>()
            val metaRepository = mockk<MetaRepository>()
            every { addonRepository.getInstalledAddons() } returns addons
            every { pluginDataStore.scrapers } returns scrapers
            every { pluginDataStore.pluginsEnabled } returns pluginsEnabled
            every { metaRepository.getCachedMeta(any(), any()) } returns null
            val usenetSettings = mockk<UsenetSourceSettings>()
            every { usenetSettings.snapshots } returns flowOf(0 to Result.success(UsenetSourceConfiguration()))
            val serverRepository = mockk<ServerRepository> {
                every { uiState } returns MutableStateFlow(ServersUiState())
            }
            val serverStreams = mockk<ServerStreams> {
                every { canServe(any(), any()) } returns false
            }
            val viewModel = PlaybackAvailabilityViewModel(
                addonRepository,
                pluginDataStore,
                metaRepository,
                usenetSettings,
                serverRepository,
                serverStreams
            )
            viewModelStore.put("availability", viewModel)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.availability.collect {}
            }
            settle { viewModel.availability.value.isLoaded }
            assertTrue(viewModel.availability.value.isLoaded)
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))

            val addon = Addon(
                id = "addon", name = "Addon", version = "1", description = null, logo = null,
                baseUrl = "https://example.com", catalogs = emptyList(), types = emptyList(),
                resources = listOf(AddonResource("stream", listOf("movie"), null))
            )
            addons.value = listOf(addon)
            runCurrent()
            assertTrue(viewModel.availability.value.canStream("movie", "tt123"))
            addons.value = listOf(addon.copy(enabled = false))
            runCurrent()
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))

            scrapers.value = listOf(ScraperInfo(
                id = "scraper", name = "Scraper", description = "", version = "1", filename = "scraper.js",
                supportedTypes = listOf("movie"), enabled = true, manifestEnabled = true, logo = null,
                contentLanguage = emptyList(), repositoryId = "repo", formats = null
            ))
            runCurrent()
            assertTrue(viewModel.availability.value.canStream("movie", "tt123") == AppFeaturePolicy.pluginsEnabled)
            pluginsEnabled.value = false
            runCurrent()
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))
            addons.value = emptyList()
            scrapers.value = emptyList()
            runCurrent()
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))

            coVerify(exactly = 0) { addonRepository.fetchAddon(any()) }
            verify(exactly = 0) { metaRepository.getMetaFromAllAddons(any(), any(), any()) }
            verify(exactly = 0) { metaRepository.getMeta(any(), any(), any()) }
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ready usenet sources make movies playable and a failed read does not`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModelStore = ViewModelStore()
        try {
            val addonRepository = mockk<AddonRepository>()
            val pluginDataStore = mockk<PluginDataStore>()
            val metaRepository = mockk<MetaRepository>()
            every { addonRepository.getInstalledAddons() } returns MutableStateFlow(emptyList())
            every { pluginDataStore.scrapers } returns MutableStateFlow(emptyList())
            every { pluginDataStore.pluginsEnabled } returns MutableStateFlow(true)
            every { metaRepository.getCachedMeta(any(), any()) } returns null
            val ready = UsenetSourceConfiguration(enabled = true,
                providers = listOf(UsenetProvider(name = "News", host = "news.test")),
                indexers = listOf(UsenetIndexer(name = "Indexer", apiUrl = "https://indexer.test/api")))
            val snapshots = MutableStateFlow<Pair<Int, Result<UsenetSourceConfiguration>>>(0 to Result.success(ready))
            val usenetSettings = mockk<UsenetSourceSettings>()
            every { usenetSettings.snapshots } returns snapshots
            val serverRepository = mockk<ServerRepository> {
                every { uiState } returns MutableStateFlow(ServersUiState())
            }
            val serverStreams = mockk<ServerStreams> {
                every { canServe(any(), any()) } returns false
                every { canServe("movie", "server-item") } returns true
            }
            val viewModel = PlaybackAvailabilityViewModel(
                addonRepository, pluginDataStore, metaRepository, usenetSettings, serverRepository, serverStreams
            )
            viewModelStore.put("availability", viewModel)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.availability.collect {}
            }
            settle { viewModel.availability.value.canStream("movie", "tt123") }
            assertTrue(viewModel.availability.value.canStream("movie", "tt123"))
            assertFalse(viewModel.availability.value.canStream("series", "tt123"))

            // Settings that cannot be read (a keystore failure) are not ready.
            snapshots.value = 0 to Result.failure(IllegalStateException("keystore"))
            settle { !viewModel.availability.value.canStream("movie", "tt123") }
            assertFalse(viewModel.availability.value.canStream("movie", "tt123"))
            assertTrue(viewModel.availability.value.canStream("movie", "server-item"))
        } finally {
            viewModelStore.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    /** The Usenet settings are read on Dispatchers.IO, outside the test scheduler: wait for them. */
    private fun TestScope.settle(condition: () -> Boolean) {
        repeat(200) {
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        runCurrent()
    }
}
