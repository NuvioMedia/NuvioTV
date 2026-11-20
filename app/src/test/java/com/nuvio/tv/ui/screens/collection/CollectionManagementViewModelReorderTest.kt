package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Collection
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionManagementViewModelReorderTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun testCollection(id: String, title: String = "Col $id"): Collection = Collection(
        id = id,
        title = title,
        folders = emptyList()
    )

    private fun createViewModel(
        collectionsFlow: MutableStateFlow<List<Collection>>,
        dataStore: CollectionsDataStore = mockk(relaxed = true),
        syncService: CollectionSyncService = mockk(relaxed = true),
    ): Triple<CollectionManagementViewModel, CollectionsDataStore, CollectionSyncService> {
        every { dataStore.collections } returns collectionsFlow
        val vm = CollectionManagementViewModel(
            collectionsDataStore = dataStore,
            collectionSyncService = syncService,
            context = mockk<Context>(relaxed = true)
        )
        return Triple(vm, dataStore, syncService)
    }

    @Test
    fun `enterCollectionMoveMode ignores single-collection list`() = runTest {
        val flow = MutableStateFlow(listOf(testCollection("c1")))
        val (vm, _, _) = createViewModel(flow)
        runCurrent()

        vm.enterCollectionMoveMode("c1")

        assertNull(vm.uiState.value.movingCollectionKey)
    }

    @Test
    fun `confirmCollectionMove skips persisting and push when order did not change`() = runTest {
        val flow = MutableStateFlow(listOf(testCollection("c1"), testCollection("c2")))
        val (vm, store, sync) = createViewModel(flow)
        runCurrent()

        vm.enterCollectionMoveMode("c1")
        vm.confirmCollectionMove()
        runCurrent()

        assertNull(vm.uiState.value.movingCollectionKey)
        coVerify(exactly = 0) { store.setCollections(any()) }
        coVerify(exactly = 0) { sync.triggerPush() }
    }

    @Test
    fun `confirmCollectionMove holds pending order across stale emission until datastore catches up`() = runTest {
        val c1 = testCollection("c1")
        val c2 = testCollection("c2")
        val c3 = testCollection("c3")
        val flow = MutableStateFlow(listOf(c1, c2, c3))
        val writeGate = CompletableDeferred<Unit>()
        val store = mockk<CollectionsDataStore>(relaxed = true)
        coEvery { store.setCollections(any()) } coAnswers { writeGate.await() }
        val (vm, _, _) = createViewModel(flow, dataStore = store)
        runCurrent()

        vm.enterCollectionMoveMode("c1")
        assertEquals(1, vm.moveCollectionDuringDrag("c1", 1))
        vm.confirmCollectionMove()
        runCurrent()

        // Stale emission with old order while write is still in progress
        flow.value = listOf(c1.copy(title = "Updated 1"), c2, c3)
        runCurrent()

        assertEquals(listOf("c2", "c1", "c3"), vm.uiState.value.collections.map { it.id })

        writeGate.complete(Unit)
        flow.value = listOf(c2, c1.copy(title = "Updated 1"), c3)
        runCurrent()

        assertEquals(listOf("c2", "c1", "c3"), vm.uiState.value.collections.map { it.id })
    }

    @Test
    fun `confirmCollectionMove finishes write and push even when viewModel is cleared immediately`() = runTest {
        val flow = MutableStateFlow(listOf(testCollection("c1"), testCollection("c2")))
        val writeGate = CompletableDeferred<Unit>()
        var pushCalled = false
        val store = mockk<CollectionsDataStore>(relaxed = true)
        val sync = mockk<CollectionSyncService>(relaxed = true)
        coEvery { store.setCollections(any()) } coAnswers { writeGate.await() }
        every { sync.triggerPush() } answers { pushCalled = true }
        val (vm, _, _) = createViewModel(flow, dataStore = store, syncService = sync)

        val vmStore = ViewModelStore()
        ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[CollectionManagementViewModel::class.java]
        runCurrent()

        vm.enterCollectionMoveMode("c1")
        vm.moveCollectionDuringDrag("c1", 1)
        vm.confirmCollectionMove()
        runCurrent()

        vmStore.clear()
        writeGate.complete(Unit)
        runCurrent()

        assertTrue("triggerPush should run uncancellably after ViewModel clear", pushCalled)
    }

    @Test
    fun `cancelCollectionMove restores baseline order without persisting`() = runTest {
        val flow = MutableStateFlow(listOf(testCollection("c1"), testCollection("c2"), testCollection("c3")))
        val (vm, store, sync) = createViewModel(flow)
        runCurrent()

        vm.enterCollectionMoveMode("c1")
        vm.moveCollectionDuringDrag("c1", 1)
        assertEquals(listOf("c2", "c1", "c3"), vm.uiState.value.collections.map { it.id })

        vm.cancelCollectionMove()
        runCurrent()

        assertNull(vm.uiState.value.movingCollectionKey)
        assertEquals(listOf("c1", "c2", "c3"), vm.uiState.value.collections.map { it.id })
        coVerify(exactly = 0) { store.setCollections(any()) }
        coVerify(exactly = 0) { sync.triggerPush() }
    }
}
