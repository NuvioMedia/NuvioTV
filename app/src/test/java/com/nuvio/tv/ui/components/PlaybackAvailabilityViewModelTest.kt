package com.nuvio.tv.ui.components

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
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
import kotlinx.coroutines.launch
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
            val viewModel = PlaybackAvailabilityViewModel(addonRepository, pluginDataStore, metaRepository)
            viewModelStore.put("availability", viewModel)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.availability.collect {}
            }
            runCurrent()
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
}
