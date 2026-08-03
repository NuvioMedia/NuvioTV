package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncReducerTest {
    @Test
    fun deletingFinalItemCreatesExplicitDeleteMutation() {
        val state = LibrarySyncState(
            items = listOf(libraryItem("item")),
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.deleteLocal(
            state = state,
            contentId = "item",
            contentType = "movie"
        )

        assertTrue(result.items.isEmpty())
        assertTrue(result.pendingUpsertKeys.isEmpty())
        assertEquals(listOf("item"), result.pendingDeleteKeys.map { it.contentId })
    }

    @Test
    fun snapshotOverlaysPendingMutationsOnRemoteState() {
        val localChanged = libraryItem("changed", addedAt = 30L)
        val state = LibrarySyncState(
            items = listOf(localChanged),
            pendingUpsertKeys = listOf(localChanged.toLibrarySyncKey()),
            pendingDeleteKeys = listOf(
                LibrarySyncKey(contentId = "deleted", contentType = "movie")
            ),
            deltaInitialized = false,
            mutationRevision = 2L
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = listOf(
                libraryItem("changed", addedAt = 10L),
                libraryItem("kept", addedAt = 20L),
                libraryItem("deleted", addedAt = 15L)
            ),
            cursorEventId = 8L
        )

        assertEquals(
            setOf("changed", "kept"),
            result.state.items.mapTo(mutableSetOf(), SavedLibraryItem::id)
        )
        assertEquals(30L, result.state.items.first { it.id == "changed" }.addedAt)
        assertEquals(8L, result.state.deltaCursorEventId)
        assertTrue(result.preservedLocalItems)
    }

    @Test
    fun emptyLegacySnapshotQueuesEveryDesiredLocalItem() {
        val legacy = libraryItem("legacy")
        val newlyAdded = libraryItem("new")
        val state = LibrarySyncState(
            items = listOf(legacy, newlyAdded),
            pendingUpsertKeys = listOf(newlyAdded.toLibrarySyncKey()),
            deltaInitialized = false,
            mutationRevision = 1L
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = emptyList(),
            cursorEventId = 12L
        )

        assertEquals(
            setOf("legacy", "new"),
            result.state.items.mapTo(mutableSetOf(), SavedLibraryItem::id)
        )
        assertEquals(
            setOf("legacy", "new"),
            result.state.pendingUpsertKeys.mapTo(mutableSetOf()) { it.contentId }
        )
        assertEquals(2L, result.state.mutationRevision)
        assertTrue(result.preservedLocalItems)
    }

    @Test
    fun deltaAppliesInEventOrderAndPreservesLocalLogo() {
        val existing = libraryItem("existing", logo = "local-logo")
        val remoteExisting = libraryItem("existing", addedAt = 2L)
        val added = libraryItem("added", addedAt = 3L)
        val state = LibrarySyncState(
            items = listOf(existing),
            deltaCursorEventId = 5L,
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.applyDelta(
            state = state,
            events = listOf(
                LibraryDeltaEvent(7L, "upsert", added),
                LibraryDeltaEvent(6L, "upsert", remoteExisting)
            )
        )

        assertEquals(setOf("existing", "added"), result.state.items.map { it.id }.toSet())
        assertEquals("local-logo", result.state.items.first { it.id == "existing" }.logo)
        assertEquals(7L, result.state.deltaCursorEventId)
        assertEquals(2, result.appliedUpserts)
    }

    @Test
    fun deltaPreservesLocalGenresAndReleaseInfoWhenRemoteSparse() {
        val existing = libraryItem(
            id = "existing",
            logo = "local-logo",
            releaseInfo = "2014",
            genres = listOf("Action", "Drama")
        )
        val remoteSparse = libraryItem("existing", addedAt = 2L)
        val state = LibrarySyncState(
            items = listOf(existing),
            deltaCursorEventId = 5L,
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.applyDelta(
            state = state,
            events = listOf(LibraryDeltaEvent(6L, "upsert", remoteSparse))
        )

        val merged = result.state.items.single { it.id == "existing" }
        assertEquals("local-logo", merged.logo)
        assertEquals("2014", merged.releaseInfo)
        assertEquals(listOf("Action", "Drama"), merged.genres)
    }

    @Test
    fun snapshotPreservesLocalFilterMetadataWhenRemoteSparse() {
        val local = libraryItem(
            id = "old",
            releaseInfo = "1999",
            genres = listOf("Comedy"),
            imdbRating = 8.2f
        )
        val state = LibrarySyncState(
            items = listOf(local),
            deltaInitialized = true
        )

        val result = LibrarySyncReducer.applySnapshot(
            state = state,
            remoteItems = listOf(libraryItem("old", addedAt = 10L)),
            cursorEventId = 3L
        )

        val merged = result.state.items.single { it.id == "old" }
        assertEquals("1999", merged.releaseInfo)
        assertEquals(listOf("Comedy"), merged.genres)
        assertEquals(8.2f, merged.imdbRating)
    }

    @Test
    fun withPreservedLocalMetadataPrefersRemoteWhenRemoteHasValues() {
        val local = libraryItem(
            id = "item",
            releaseInfo = "2001",
            genres = listOf("Local")
        )
        val remote = libraryItem(
            id = "item",
            releaseInfo = "2020",
            genres = listOf("Remote")
        )

        val merged = remote.withPreservedLocalMetadata(local)
        assertEquals("2020", merged.releaseInfo)
        assertEquals(listOf("Remote"), merged.genres)
    }

    @Test
    fun deltaPreservesPendingLocalMutationAndAdvancesCursor() {
        val local = libraryItem("local", addedAt = 20L)
        val state = LibrarySyncState(
            items = listOf(local),
            deltaCursorEventId = 10L,
            deltaInitialized = true,
            pendingUpsertKeys = listOf(local.toLibrarySyncKey()),
            mutationRevision = 1L
        )

        val result = LibrarySyncReducer.applyDelta(
            state = state,
            events = listOf(
                LibraryDeltaEvent(11L, "delete", local)
            )
        )

        assertEquals(listOf("local"), result.state.items.map { it.id })
        assertEquals(11L, result.state.deltaCursorEventId)
        assertEquals(0, result.appliedDeletes)
    }

    @Test
    fun stalePushAcknowledgementCannotClearNewerMutation() {
        val first = LibrarySyncReducer.upsertLocal(
            state = LibrarySyncState(deltaInitialized = true),
            item = libraryItem("first"),
            nowEpochMs = 1L
        )
        val second = LibrarySyncReducer.upsertLocal(
            state = first,
            item = libraryItem("second"),
            nowEpochMs = 2L
        )

        val acknowledged = LibrarySyncReducer.acknowledgePush(
            state = second,
            expectedMutationRevision = first.mutationRevision
        )

        assertNull(acknowledged)
        assertEquals(2, second.pendingUpsertKeys.size)
    }

    @Test
    fun sanitizingPersistedDeleteKeepsItemRemoved() {
        val state = LibrarySyncReducer.sanitize(
            LibrarySyncState(
                items = listOf(libraryItem("item")),
                pendingDeleteKeys = listOf(
                    LibrarySyncKey(contentId = "item", contentType = "movie")
                )
            )
        )

        assertTrue(state.items.isEmpty())
        assertFalse(state.pendingDeleteKeys.isEmpty())
    }

    private fun libraryItem(
        id: String,
        addedAt: Long = 1L,
        logo: String? = null,
        releaseInfo: String? = null,
        genres: List<String> = emptyList(),
        imdbRating: Float? = null
    ): SavedLibraryItem {
        return SavedLibraryItem(
            id = id,
            type = "movie",
            name = id,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            description = null,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = null,
            logo = logo,
            addedAt = addedAt
        )
    }
}
