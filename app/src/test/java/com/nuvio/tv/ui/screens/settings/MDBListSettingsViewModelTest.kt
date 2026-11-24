package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MDBListSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun createViewModel(
        preferences: MutableStateFlow<MDBListSettings>,
        store: MDBListSettingsDataStore = mockk(relaxed = true),
        api: MDBListApi = mockk(relaxed = true),
        harness: MdbListTestHarness = MdbListTestHarness()
    ): Triple<MDBListSettingsViewModel, MDBListSettingsDataStore, MdbListTestHarness> {
        every { store.settings } returns preferences
        val viewModel = MDBListSettingsViewModel(store, api, harness.store)
        return Triple(viewModel, store, harness)
    }

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

    @Test
    fun `enterRatingMoveMode ignores disabled settings single item or unknown provider`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = false, ratingOrder = listOf("imdb", "trakt"))
        )
        val (vm, _, _) = createViewModel(preferences)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        assertNull(vm.uiState.value.movingRatingKey)

        preferences.value = MDBListSettings(enabled = true, ratingOrder = listOf("imdb"))
        runCurrent()
        vm.enterRatingMoveMode("imdb")
        assertNull(vm.uiState.value.movingRatingKey)

        preferences.value = MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt"))
        runCurrent()
        vm.enterRatingMoveMode("unknown")
        assertNull(vm.uiState.value.movingRatingKey)
    }

    @Test
    fun `moveRatingDuringDrag reorders within bounds and respects edges`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt", "tmdb"))
        )
        val (vm, _, _) = createViewModel(preferences)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        assertEquals("imdb", vm.uiState.value.movingRatingKey)
        assertNull(vm.moveRatingDuringDrag("imdb", -1))
        assertEquals(1, vm.moveRatingDuringDrag("imdb", 1))
        assertEquals(listOf("trakt", "imdb", "tmdb"), vm.uiState.value.ratingOrder)
        assertEquals(2, vm.moveRatingDuringDrag("imdb", 1))
        assertEquals(listOf("trakt", "tmdb", "imdb"), vm.uiState.value.ratingOrder)
        assertNull(vm.moveRatingDuringDrag("imdb", 1))
    }

    @Test
    fun `confirmRatingMove skips persisting when rating order did not change`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt"))
        )
        val (vm, store, _) = createViewModel(preferences)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        vm.confirmRatingMove()
        runCurrent()

        assertNull(vm.uiState.value.movingRatingKey)
        coVerify(exactly = 0) { store.setRatingOrder(any()) }
    }

    @Test
    fun `confirmRatingMove holds pending order across stale emission until datastore catches up`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt", "tmdb"))
        )
        val writeGate = CompletableDeferred<Unit>()
        val store = mockk<MDBListSettingsDataStore>(relaxed = true)
        coEvery { store.setRatingOrder(any()) } coAnswers { writeGate.await() }
        val (vm, _, _) = createViewModel(preferences, store = store)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        assertEquals(1, vm.moveRatingDuringDrag("imdb", 1))
        vm.confirmRatingMove()
        runCurrent()

        // Stale upstream emission with old order while write is still in flight
        preferences.value = preferences.value.copy(showOnHero = true)
        runCurrent()

        assertEquals(listOf("trakt", "imdb", "tmdb"), vm.uiState.value.ratingOrder)
        assertTrue(vm.uiState.value.showOnHero)

        writeGate.complete(Unit)
        preferences.value = preferences.value.copy(ratingOrder = listOf("trakt", "imdb", "tmdb"))
        runCurrent()

        assertEquals(listOf("trakt", "imdb", "tmdb"), vm.uiState.value.ratingOrder)
    }

    @Test
    fun `confirmRatingMove finishes persisting even when viewModel is cleared immediately`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt"))
        )
        val writeGate = CompletableDeferred<Unit>()
        var savedOrder: List<String>? = null
        val store = mockk<MDBListSettingsDataStore>(relaxed = true)
        coEvery { store.setRatingOrder(any()) } coAnswers {
            writeGate.await()
            savedOrder = firstArg()
        }
        val (vm, _, _) = createViewModel(preferences, store = store)
        val vmStore = ViewModelStore()
        ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[MDBListSettingsViewModel::class.java]
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        vm.moveRatingDuringDrag("imdb", 1)
        vm.confirmRatingMove()
        runCurrent()

        vmStore.clear()
        writeGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("trakt", "imdb"), savedOrder)
    }

    @Test
    fun `confirmRatingMove rolls back to baseline if persistence fails`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt", "tmdb"))
        )
        val store = mockk<MDBListSettingsDataStore>(relaxed = true)
        coEvery { store.setRatingOrder(any()) } throws IllegalStateException("disk error")
        val (vm, _, _) = createViewModel(preferences, store = store)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        vm.moveRatingDuringDrag("imdb", 1)
        vm.confirmRatingMove()
        runCurrent()

        assertNull(vm.uiState.value.movingRatingKey)
        assertEquals(listOf("imdb", "trakt", "tmdb"), vm.uiState.value.ratingOrder)
    }

    @Test
    fun `cancelRatingMove restores baseline order without persisting and is safe when idle`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt", "tmdb"))
        )
        val (vm, store, _) = createViewModel(preferences)
        runCurrent()

        // Calling cancel while idle should be a no-op
        vm.cancelRatingMove()
        assertEquals(listOf("imdb", "trakt", "tmdb"), vm.uiState.value.ratingOrder)

        vm.enterRatingMoveMode("imdb")
        vm.moveRatingDuringDrag("imdb", 1)
        assertEquals(listOf("trakt", "imdb", "tmdb"), vm.uiState.value.ratingOrder)

        vm.cancelRatingMove()
        runCurrent()

        assertNull(vm.uiState.value.movingRatingKey)
        assertEquals(listOf("imdb", "trakt", "tmdb"), vm.uiState.value.ratingOrder)
        coVerify(exactly = 0) { store.setRatingOrder(any()) }
    }

    @Test
    fun `unrelated upstream emission preserves active drag and disabling cancels move mode`() = runTest {
        val preferences = MutableStateFlow(
            MDBListSettings(enabled = true, ratingOrder = listOf("imdb", "trakt", "tmdb"))
        )
        val (vm, _, harness) = createViewModel(preferences)
        runCurrent()

        vm.enterRatingMoveMode("imdb")
        vm.moveRatingDuringDrag("imdb", 1)

        // Auth state emission while dragging must not reset move session or draft order
        harness.connected()
        runCurrent()

        assertEquals("imdb", vm.uiState.value.movingRatingKey)
        assertEquals(listOf("trakt", "imdb", "tmdb"), vm.uiState.value.ratingOrder)
        assertTrue(vm.uiState.value.isConnected)

        // Disabling MDBList externally cancels move mode and returns to persisted order
        preferences.value = preferences.value.copy(enabled = false)
        runCurrent()

        assertNull(vm.uiState.value.movingRatingKey)
        assertEquals(listOf("imdb", "trakt", "tmdb"), vm.uiState.value.ratingOrder)
    }
}
