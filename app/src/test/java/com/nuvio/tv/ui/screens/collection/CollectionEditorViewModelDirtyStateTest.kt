package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionEditorViewModelDirtyStateTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun source(id: String): CollectionSource = AddonCatalogCollectionSource(
        addonId = "addon-$id",
        type = "movie",
        catalogId = "cat-$id",
        genre = null
    )

    private fun folder(
        id: String,
        title: String = "Folder $id",
        sources: List<CollectionSource> = listOf(source("1"))
    ): CollectionFolder = CollectionFolder(
        id = id,
        title = title,
        tileShape = PosterShape.POSTER,
        sources = sources
    )

    private data class Harness(
        val vm: CollectionEditorViewModel,
        val store: CollectionsDataStore,
        val sync: CollectionSyncService,
        val viewModelStore: ViewModelStore
    )

    private fun createHarness(
        existing: Collection?,
        store: CollectionsDataStore = mockk(relaxed = true)
    ): Harness {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Untitled"
        }
        every { store.collections } returns flowOf(listOfNotNull(existing))
        every { store.generateId() } returns "generated-id"
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(emptyList())
        }
        val sync = mockk<CollectionSyncService>(relaxed = true)
        val viewModelStore = ViewModelStore()
        val vm = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CollectionEditorViewModel(
                    appContext = context,
                    savedStateHandle = SavedStateHandle(
                        mapOf("collectionId" to (existing?.id ?: ""))
                    ),
                    collectionsDataStore = store,
                    addonRepository = addonRepository,
                    tmdbCollectionSourceResolver = mockk<TmdbCollectionSourceResolver>(relaxed = true),
                    traktPublicListSourceResolver = mockk<TraktPublicListSourceResolver>(relaxed = true),
                    collectionSyncService = sync
                ) as T
            }
        })[CollectionEditorViewModel::class.java]
        return Harness(vm, store, sync, viewModelStore)
    }

    @Test
    fun `existing and new collections start clean without unsaved changes`() = runTest {
        val existingHarness = createHarness(
            Collection(id = "c1", title = "Marvel", pinToTop = true, folders = listOf(folder("f1")))
        )
        val newHarness = createHarness(existing = null)
        runCurrent()

        assertFalse(existingHarness.vm.uiState.value.hasUnsavedCollectionChanges)
        assertFalse(existingHarness.vm.uiState.value.hasUnsavedFolderChanges)
        assertTrue(existingHarness.vm.uiState.value.canSaveCollection)

        assertFalse(newHarness.vm.uiState.value.hasUnsavedCollectionChanges)
        assertFalse(newHarness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(newHarness.vm.uiState.value.canSaveCollection)
    }

    @Test
    fun `toggling collection pinToTop marks dirty and reverting clears dirty`() = runTest {
        val harness = createHarness(
            Collection(id = "c1", title = "Marvel", pinToTop = true, folders = listOf(folder("f1")))
        )
        runCurrent()

        harness.vm.setPinToTop(false)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        harness.vm.setPinToTop(true)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)
    }

    @Test
    fun `other collection properties mark dirty and blank backdrop matches null`() = runTest {
        val harness = createHarness(
            Collection(
                id = "c1",
                title = "Marvel",
                backdropImageUrl = null,
                focusGlowEnabled = true,
                viewMode = FolderViewMode.TABBED_GRID,
                showAllTab = true,
                folders = listOf(folder("f1"))
            )
        )
        runCurrent()

        harness.vm.setBackdropImageUrl("   ")
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        harness.vm.setFocusGlowEnabled(false)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)
        harness.vm.setFocusGlowEnabled(true)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        harness.vm.setViewMode(FolderViewMode.ROWS)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)
        harness.vm.setViewMode(FolderViewMode.TABBED_GRID)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        harness.vm.setShowAllTab(false)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)
        harness.vm.setShowAllTab(true)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)
    }

    @Test
    fun `folder editor tracks dirty state and propagates to collection only when saved`() = runTest {
        val harness = createHarness(
            Collection(id = "c1", title = "Marvel", folders = listOf(folder("f1", title = "Movies")))
        )
        runCurrent()

        harness.vm.editFolder("f1")
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertTrue(harness.vm.uiState.value.canSaveFolder)

        // Switching to image mode without typing a URL should not mark dirty.
        harness.vm.switchToImageMode()
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)

        // Editing title marks folder dirty, collection not yet dirty.
        harness.vm.updateFolderTitle("Updated Movies")
        assertTrue(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Discarding folder clears folder dirty and leaves collection clean.
        harness.vm.cancelFolderEdit()
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Editing folder and saving marks collection dirty.
        harness.vm.editFolder("f1")
        harness.vm.updateFolderTitle("Updated Movies")
        assertTrue(harness.vm.uiState.value.hasUnsavedFolderChanges)
        harness.vm.saveFolderEdit()
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Re-opening folder and reverting title back to original clears collection dirty.
        harness.vm.editFolder("f1")
        harness.vm.updateFolderTitle("Movies")
        harness.vm.saveFolderEdit()
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)
    }

    @Test
    fun `new folder starts clean and cannot save without sources`() = runTest {
        val harness = createHarness(
            Collection(id = "c1", title = "Marvel", folders = listOf(folder("f1")))
        )
        runCurrent()

        harness.vm.addFolder()
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(harness.vm.uiState.value.canSaveFolder)

        harness.vm.updateFolderTitle("New Folder")
        assertTrue(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(harness.vm.uiState.value.canSaveFolder)

        // Attempting to save folder without sources does nothing.
        harness.vm.saveFolderEdit()
        assertTrue(harness.vm.uiState.value.showFolderEditor)
        assertEquals(1, harness.vm.uiState.value.folders.size)

        // Discarding leaves collection clean.
        harness.vm.cancelFolderEdit()
        assertFalse(harness.vm.uiState.value.showFolderEditor)
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)
    }

    @Test
    fun `folder and source reorder affect dirty state accurately`() = runTest {
        val s1 = source("1")
        val s2 = source("2")
        val harness = createHarness(
            Collection(
                id = "c1",
                title = "Marvel",
                folders = listOf(folder("f1", sources = listOf(s1, s2)), folder("f2"))
            )
        )
        runCurrent()

        // Cancel folder drag leaves collection clean.
        harness.vm.enterFolderMoveMode("f1")
        harness.vm.moveFolderDuringDrag("f1", 1)
        harness.vm.cancelFolderMove()
        assertFalse(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Confirm folder drag marks collection dirty.
        harness.vm.enterFolderMoveMode("f1")
        harness.vm.moveFolderDuringDrag("f1", 1)
        harness.vm.confirmFolderMove()
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Cancel source drag leaves folder clean.
        harness.vm.editFolder("f1")
        val keys = collectionSourceKeys(listOf(s1, s2))
        harness.vm.enterSourceMoveMode(keys[0])
        harness.vm.moveSourceDuringDrag(keys[0], 1)
        harness.vm.cancelSourceMove()
        assertFalse(harness.vm.uiState.value.hasUnsavedFolderChanges)

        // Confirm source drag marks folder dirty.
        harness.vm.enterSourceMoveMode(keys[0])
        harness.vm.moveSourceDuringDrag(keys[0], 1)
        harness.vm.confirmSourceMove()
        assertTrue(harness.vm.uiState.value.hasUnsavedFolderChanges)
    }

    @Test
    fun `save persists unpinned collection non-cancellably and guards invalid or duplicate saves`() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        val store = mockk<CollectionsDataStore>(relaxed = true)
        coEvery { store.updateCollection(any()) } coAnswers { writeGate.await() }

        val harness = createHarness(
            existing = Collection(
                id = "c1",
                title = "Marvel",
                pinToTop = true,
                folders = listOf(folder("f1"))
            ),
            store = store
        )
        runCurrent()

        // Blank title cannot be saved.
        harness.vm.setTitle("   ")
        assertFalse(harness.vm.uiState.value.canSaveCollection)
        var completed = false
        harness.vm.save { completed = true }
        runCurrent()
        assertFalse(completed)
        coVerify(exactly = 0) { store.updateCollection(any()) }

        // Restore title and turn off pinToTop.
        harness.vm.setTitle("Marvel")
        harness.vm.setPinToTop(false)
        assertTrue(harness.vm.uiState.value.canSaveCollection)
        assertTrue(harness.vm.uiState.value.hasUnsavedCollectionChanges)

        // Start save and trigger a duplicate save while first is in flight.
        harness.vm.save { completed = true }
        harness.vm.save { completed = true }
        runCurrent()

        // Even if ViewModel is cleared mid-write, NonCancellable finishes the write + sync push.
        harness.viewModelStore.clear()
        writeGate.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) {
            store.updateCollection(match { it.id == "c1" && !it.pinToTop })
        }
        verify(exactly = 1) { harness.sync.triggerPush() }
    }

    @Test
    fun `collection editor route encodes special characters in collectionId`() {
        assertEquals(
            "collection_editor?collectionId=",
            Screen.CollectionEditor.createRoute(null)
        )
        assertEquals(
            "collection_editor?collectionId=my%20col%231%26a%3Db",
            Screen.CollectionEditor.createRoute("my col#1&a=b")
        )
    }
}
