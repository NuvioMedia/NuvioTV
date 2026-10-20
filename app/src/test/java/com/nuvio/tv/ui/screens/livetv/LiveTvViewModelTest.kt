package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.livetv.LiveTvSourceImporter
import com.nuvio.tv.data.local.LiveTvSourceStore
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class LiveTvViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun addingSourcePersistsConfigurationAndPublishesImportedChannels() = runTest(mainDispatcher.dispatcher) {
        val store = mockk<LiveTvSourceStore>()
        val importer = mockk<LiveTvSourceImporter>()
        val source = LiveTvSource("source-1", "Brasil", "https://example.org/list.m3u")
        val channel = LiveTvChannel("source-1:channel", "source-1", "Canal Brasil", "https://example.org/live.m3u8")
        coEvery { store.list() } returns emptyList()
        coEvery { store.add("Brasil", source.playlistUrl, null) } returns source
        coEvery { importer.import(source) } returns LiveTvImportResult(listOf(channel))
        val viewModel = LiveTvViewModel(store, importer)

        runCurrent()
        viewModel.addSource("Brasil", source.playlistUrl, null)
        runCurrent()

        assertEquals(listOf(source), viewModel.state.value.sources)
        assertEquals(listOf(channel), viewModel.state.value.channels)
        assertFalse(viewModel.state.value.isLoading)
        coVerify(exactly = 1) { store.add("Brasil", source.playlistUrl, null) }
        coVerify(exactly = 1) { importer.import(source) }
    }

    @Test
    fun disabledSourceIsNotFetched() = runTest(mainDispatcher.dispatcher) {
        val store = mockk<LiveTvSourceStore>()
        val importer = mockk<LiveTvSourceImporter>()
        val source = LiveTvSource("source-1", "Brasil", "https://example.org/list.m3u", enabled = false)
        coEvery { store.list() } returns listOf(source)
        val viewModel = LiveTvViewModel(store, importer)

        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf(source), viewModel.state.value.sources)
        assertEquals(emptyList<LiveTvChannel>(), viewModel.state.value.channels)
        coVerify(exactly = 0) { importer.import(any()) }
    }
}
