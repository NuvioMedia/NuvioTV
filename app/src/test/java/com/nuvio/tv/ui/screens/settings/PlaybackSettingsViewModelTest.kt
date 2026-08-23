package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrServerApi
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsViewModelTest {

    @Test
    fun `empty endpoint clears the setting without a reachability check`() = runTest {
        val torrentSettings = mockk<TorrentSettings>(relaxed = true)
        val api = mockk<TorrServerApi>(relaxed = true)
        val viewModel = viewModel(torrentSettings, api)

        val result = viewModel.saveCustomTorrServerUrl("   ")

        assertTrue(result)
        verify { torrentSettings.setCustomTorrServerUrl("") }
        coVerify(exactly = 0) { api.isEndpointReachable(any()) }
    }

    @Test
    fun `invalid endpoint is rejected and not saved`() = runTest {
        val torrentSettings = mockk<TorrentSettings>(relaxed = true)
        val api = mockk<TorrServerApi>(relaxed = true)
        val viewModel = viewModel(torrentSettings, api)

        val result = viewModel.saveCustomTorrServerUrl("not a url")

        assertFalse(result)
        verify(exactly = 0) { torrentSettings.setCustomTorrServerUrl(any()) }
        coVerify(exactly = 0) { api.isEndpointReachable(any()) }
    }

    @Test
    fun `reachable endpoint is saved`() = runTest {
        val torrentSettings = mockk<TorrentSettings>(relaxed = true)
        val api = mockk<TorrServerApi>(relaxed = true)
        coEvery { api.isEndpointReachable("http://10.0.0.5:8090") } returns true
        val viewModel = viewModel(torrentSettings, api)

        val result = viewModel.saveCustomTorrServerUrl("http://10.0.0.5:8090")

        assertTrue(result)
        verify { torrentSettings.setCustomTorrServerUrl("http://10.0.0.5:8090") }
    }

    @Test
    fun `unreachable endpoint is not saved`() = runTest {
        val torrentSettings = mockk<TorrentSettings>(relaxed = true)
        val api = mockk<TorrServerApi>(relaxed = true)
        coEvery { api.isEndpointReachable(any()) } returns false
        val viewModel = viewModel(torrentSettings, api)

        val result = viewModel.saveCustomTorrServerUrl("http://10.0.0.5:8090")

        assertFalse(result)
        verify(exactly = 0) { torrentSettings.setCustomTorrServerUrl(any()) }
    }

    private fun viewModel(
        torrentSettings: TorrentSettings,
        torrentServerApi: TorrServerApi
    ): PlaybackSettingsViewModel {
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.pluginsEnabled } returns flowOf(false)
        every { pluginManager.scrapers } returns flowOf(emptyList())
        return PlaybackSettingsViewModel(
            playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true),
            trailerSettingsDataStore = mockk<TrailerSettingsDataStore>(relaxed = true),
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            torrentSettings = torrentSettings,
            torrentServerApi = torrentServerApi
        )
    }
}
