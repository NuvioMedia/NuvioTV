package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingLibraryMembershipTest {
    private val watching = statusTab("watching", setOf("series"))
    private val planned = statusTab("planned", setOf("movie", "series"))
    private val completed = statusTab("completed", setOf("movie", "series"), false)
    private val tabs = listOf(watching, planned, completed)

    @Test
    fun `merge keeps local membership with provider destinations`() {
        val result = mergeTrackingMembershipWithTabs(
            tabs,
            mapOf(LOCAL_LIBRARY_LIST_KEY to true, planned.key to true, "stale" to true)
        )

        assertTrue(result.getValue(LOCAL_LIBRARY_LIST_KEY))
        assertTrue(result.getValue(planned.key))
        assertFalse(result.getValue(watching.key))
        assertFalse("stale" in result)
    }

    @Test
    fun `selecting one provider status clears its sibling`() {
        val result = toggleTrackingMembershipSelection(
            tabs = tabs,
            membership = mapOf(watching.key to true, planned.key to false),
            listKey = planned.key,
            contentType = "series"
        )

        assertEquals(false, result?.get(watching.key))
        assertEquals(true, result?.get(planned.key))
    }

    @Test
    fun `movie cannot select a series only status`() {
        val result = toggleTrackingMembershipSelection(
            tabs = tabs,
            membership = emptyMap(),
            listKey = watching.key,
            contentType = "movie"
        )

        assertNull(result)
    }

    @Test
    fun `read only provider status cannot be selected`() {
        val result = toggleTrackingMembershipSelection(
            tabs = tabs,
            membership = emptyMap(),
            listKey = completed.key,
            contentType = "movie"
        )

        assertNull(result)
    }

    @Test
    fun `local membership toggles independently`() {
        val result = toggleTrackingMembershipSelection(
            tabs = tabs,
            membership = mapOf(planned.key to true, LOCAL_LIBRARY_LIST_KEY to false),
            listKey = LOCAL_LIBRARY_LIST_KEY,
            contentType = "movie"
        )

        assertEquals(true, result?.get(LOCAL_LIBRARY_LIST_KEY))
        assertEquals(true, result?.get(planned.key))
    }

    @Test
    fun `membership dispatch attempts every provider and isolates failures`() = runTest {
        val trakt = FakeLibraryProvider(TrackingProviderId.TRAKT, failure = IllegalStateException("offline"))
        val simkl = FakeLibraryProvider(TrackingProviderId.SIMKL)
        val changes = ListMembershipChanges(mapOf("list" to true))

        val failures = dispatchTrackingMembershipChanges(
            item = LibraryEntryInput("tt123", "movie", "Movie"),
            providerChanges = listOf(trakt to changes, simkl to changes),
            confirmedRemovalProviders = setOf(TrackingProviderId.SIMKL)
        )

        assertEquals(1, trakt.applyCalls)
        assertEquals(1, simkl.applyCalls)
        assertEquals(listOf(TrackingProviderId.TRAKT), failures.map { it.providerId })
        assertFalse(trakt.lastConfirmed)
        assertTrue(simkl.lastConfirmed)
    }

    private fun statusTab(
        key: String,
        contentTypes: Set<String>,
        destination: Boolean = true
    ) = LibraryListTab(
        key = key,
        title = key,
        type = LibraryListTab.Type.STATUS,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        selectionGroup = "simkl:status",
        supportedContentTypes = contentTypes,
        isMembershipDestination = destination
    )

    private class FakeLibraryProvider(
        override val providerId: TrackingProviderId,
        private val failure: Throwable? = null
    ) : TrackingLibraryProvider {
        override val isAuthenticated = flowOf(true)
        override val isRefreshing = flowOf(false)
        override val items = flowOf(emptyList<LibraryEntry>())
        override val tabs = flowOf(emptyList<LibraryListTab>())
        var applyCalls = 0
        var lastConfirmed = false

        override fun recognizesListKey(key: String): Boolean = true
        override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
            flowOf(emptySet())

        override fun toggledDefaultMembership(
            currentMembership: Map<String, Boolean>
        ): Map<String, Boolean> = currentMembership
        override suspend fun getMembershipSnapshot(item: LibraryEntryInput) = ListMembershipSnapshot()

        override suspend fun applyMembershipChanges(
            item: LibraryEntryInput,
            changes: ListMembershipChanges,
            destructiveRemovalConfirmed: Boolean
        ) {
            applyCalls += 1
            lastConfirmed = destructiveRemovalConfirmed
            failure?.let { throw it }
        }

        override suspend fun refresh(intent: TrackingRefreshIntent) = Unit
    }
}
