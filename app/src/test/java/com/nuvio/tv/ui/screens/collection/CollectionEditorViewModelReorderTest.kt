package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionEditorViewModelReorderTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun source(id: String): CollectionSource = AddonCatalogCollectionSource(
        addonId = "addon-$id",
        type = "movie",
        catalogId = "cat-$id",
        genre = null
    )

    private fun folder(id: String, sources: List<CollectionSource> = listOf(source("1"))): CollectionFolder =
        CollectionFolder(
            id = id,
            title = "Folder $id",
            tileShape = PosterShape.POSTER,
            sources = sources
        )

    private fun createViewModel(existing: Collection): CollectionEditorViewModel {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Untitled"
        }
        val collectionsDataStore = mockk<CollectionsDataStore>(relaxed = true) {
            every { collections } returns flowOf(listOf(existing))
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(emptyList())
        }
        return CollectionEditorViewModel(
            appContext = context,
            savedStateHandle = SavedStateHandle(mapOf("collectionId" to existing.id)),
            collectionsDataStore = collectionsDataStore,
            addonRepository = addonRepository,
            tmdbCollectionSourceResolver = mockk<TmdbCollectionSourceResolver>(relaxed = true),
            traktPublicListSourceResolver = mockk<TraktPublicListSourceResolver>(relaxed = true),
            collectionSyncService = mockk<CollectionSyncService>(relaxed = true)
        )
    }

    @Test
    fun `enterFolderMoveMode ignores single-folder collection`() = runTest {
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1")))
        )
        runCurrent()

        vm.enterFolderMoveMode("f1")

        assertNull(vm.uiState.value.movingFolderKey)
    }

    @Test
    fun `enterSourceMoveMode ignores folder with single source`() = runTest {
        val s1 = source("1")
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1", listOf(s1))))
        )
        runCurrent()

        vm.editFolder("f1")
        val key = collectionSourceKeys(listOf(s1)).single()
        vm.enterSourceMoveMode(key)

        assertNull(vm.uiState.value.movingSourceKey)
    }

    @Test
    fun `saveFolderEdit clears active source move state`() = runTest {
        val s1 = source("1")
        val s2 = source("2")
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1", listOf(s1, s2))))
        )
        runCurrent()

        vm.editFolder("f1")
        val keys = collectionSourceKeys(listOf(s1, s2))
        vm.enterSourceMoveMode(keys[0])
        assertEquals(keys[0], vm.uiState.value.movingSourceKey)

        vm.saveFolderEdit()

        assertNull(vm.uiState.value.movingSourceKey)
        assertNull(vm.uiState.value.editingFolder)
    }

    @Test
    fun `cancelFolderEdit clears active source move state`() = runTest {
        val s1 = source("1")
        val s2 = source("2")
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1", listOf(s1, s2))))
        )
        runCurrent()

        vm.editFolder("f1")
        val keys = collectionSourceKeys(listOf(s1, s2))
        vm.enterSourceMoveMode(keys[0])
        assertEquals(keys[0], vm.uiState.value.movingSourceKey)

        vm.cancelFolderEdit()

        assertNull(vm.uiState.value.movingSourceKey)
        assertNull(vm.uiState.value.editingFolder)
    }

    @Test
    fun `removeFolder clears active folder move state`() = runTest {
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1"), folder("f2")))
        )
        runCurrent()

        vm.enterFolderMoveMode("f1")
        assertEquals("f1", vm.uiState.value.movingFolderKey)

        vm.removeFolder("f1")

        assertNull(vm.uiState.value.movingFolderKey)
        assertEquals(listOf("f2"), vm.uiState.value.folders.map { it.id })
    }

    @Test
    fun `folder move confirm keeps reordered list and cancel restores baseline`() = runTest {
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1"), folder("f2"), folder("f3")))
        )
        runCurrent()

        vm.enterFolderMoveMode("f1")
        assertEquals(1, vm.moveFolderDuringDrag("f1", 1))
        vm.confirmFolderMove()
        assertNull(vm.uiState.value.movingFolderKey)
        assertEquals(listOf("f2", "f1", "f3"), vm.uiState.value.folders.map { it.id })

        vm.enterFolderMoveMode("f3")
        assertEquals(1, vm.moveFolderDuringDrag("f3", -1))
        vm.cancelFolderMove()
        assertNull(vm.uiState.value.movingFolderKey)
        assertEquals(listOf("f2", "f1", "f3"), vm.uiState.value.folders.map { it.id })
    }

    @Test
    fun `source move confirm keeps reordered sources and cancel restores baseline`() = runTest {
        val s1 = source("1")
        val s2 = source("2")
        val s3 = source("3")
        val vm = createViewModel(
            Collection(id = "c1", title = "Col", folders = listOf(folder("f1", listOf(s1, s2, s3))))
        )
        runCurrent()

        vm.editFolder("f1")
        val keys = collectionSourceKeys(listOf(s1, s2, s3))

        vm.enterSourceMoveMode(keys[0])
        assertEquals(1, vm.moveSourceDuringDrag(keys[0], 1))
        vm.confirmSourceMove()
        assertNull(vm.uiState.value.movingSourceKey)
        assertEquals(listOf(s2, s1, s3), vm.uiState.value.editingFolder?.sources)

        val updatedKeys = collectionSourceKeys(listOf(s2, s1, s3))
        vm.enterSourceMoveMode(updatedKeys[2])
        assertEquals(1, vm.moveSourceDuringDrag(updatedKeys[2], -1))
        vm.cancelSourceMove()
        assertNull(vm.uiState.value.movingSourceKey)
        assertEquals(listOf(s2, s1, s3), vm.uiState.value.editingFolder?.sources)
    }
}
