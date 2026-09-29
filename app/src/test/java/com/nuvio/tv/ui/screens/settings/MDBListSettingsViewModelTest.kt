package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MDBListSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `connected account can save and clear a separate ratings key`() = runTest {
        val harness = MdbListTestHarness()
        val preferences = MutableStateFlow(MDBListSettings(enabled = true))
        val store = mockk<MDBListSettingsDataStore> {
            every { settings } returns preferences
            coEvery { setApiKey(any()) } coAnswers {
                preferences.value = preferences.value.copy(apiKey = firstArg())
            }
        }
        val api = mockk<MDBListApi> {
            coEvery { getUser("separate-key") } returns Response.success(Unit)
        }
        val viewModel = MDBListSettingsViewModel(store, api, harness.store)
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)

        harness.connected()
        runCurrent()
        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals("", viewModel.uiState.value.apiKey)

        var saves = 0
        viewModel.validateAndSaveApiKey(" separate-key ") { saves++ }
        runCurrent()
        assertEquals(1, saves)
        assertEquals("separate-key", viewModel.uiState.value.apiKey)
        assertTrue(viewModel.uiState.value.isConnected)
        coVerify(exactly = 1) { api.getUser("separate-key") }

        viewModel.validateAndSaveApiKey("") { saves++ }
        runCurrent()
        assertEquals(2, saves)
        assertEquals("", viewModel.uiState.value.apiKey)
        assertTrue(viewModel.uiState.value.isConnected)
        coVerify(exactly = 1) { api.getUser(any()) }

        harness.store.clearAuth()
        runCurrent()
        assertFalse(viewModel.uiState.value.isConnected)
    }
}
