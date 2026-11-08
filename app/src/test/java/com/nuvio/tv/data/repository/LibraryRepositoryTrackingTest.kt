package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingListManager
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTrackingTest {
    @Test
    fun `screen tabs follow active provider while membership tabs include every connection`() = runTest {
        val sourceMode = MutableStateFlow(LibrarySourceMode.SIMKL)
        val traktTab = tab("trakt:watchlist", TrackingProviderId.TRAKT)
        val simklTab = tab("simkl:plantowatch", TrackingProviderId.SIMKL)
        val repository = repository(
            sourceMode = sourceMode,
            providers = setOf(
                FakeLibraryProvider(TrackingProviderId.TRAKT, traktTab),
                FakeLibraryProvider(TrackingProviderId.SIMKL, simklTab)
            )
        )

        assertEquals(listOf(simklTab), repository.listTabs.first())
        assertEquals(listOf(traktTab, simklTab), repository.membershipListTabs.first())

        sourceMode.value = LibrarySourceMode.TRAKT

        assertEquals(listOf(traktTab), repository.listTabs.first())
        assertEquals(listOf(traktTab, simklTab), repository.membershipListTabs.first())
    }

    @Test
    fun `manual refresh targets only the active tracking provider`() = runTest {
        val sourceMode = MutableStateFlow(LibrarySourceMode.SIMKL)
        val trakt = FakeLibraryProvider(
            TrackingProviderId.TRAKT,
            tab("trakt:watchlist", TrackingProviderId.TRAKT)
        )
        val simkl = FakeLibraryProvider(
            TrackingProviderId.SIMKL,
            tab("simkl:plantowatch", TrackingProviderId.SIMKL)
        )
        val repository = repository(sourceMode, setOf(trakt, simkl))

        repository.refreshNow()

        assertEquals(emptyList<TrackingRefreshIntent>(), trakt.refreshIntents)
        assertEquals(listOf(TrackingRefreshIntent.USER_INITIATED), simkl.refreshIntents)

        sourceMode.value = LibrarySourceMode.TRAKT
        repository.refreshNow()

        assertEquals(listOf(TrackingRefreshIntent.USER_INITIATED), trakt.refreshIntents)
        assertEquals(listOf(TrackingRefreshIntent.USER_INITIATED), simkl.refreshIntents)
    }

    @Test
    fun `default toggle waits for destructive removal confirmation`() = runTest {
        val sourceMode = MutableStateFlow(LibrarySourceMode.SIMKL)
        val simklTab = tab("simkl:status:plantowatch", TrackingProviderId.SIMKL)
        val confirmation = TrackingMembershipRemovalConfirmation(
            providerId = TrackingProviderId.SIMKL,
            impacts = setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY)
        )
        val simkl = FakeLibraryProvider(
            providerId = TrackingProviderId.SIMKL,
            tab = simklTab,
            membership = mapOf(simklTab.key to true),
            removalConfirmation = confirmation
        )
        val repository = repository(sourceMode, setOf(simkl))
        val item = LibraryEntryInput("tt123", "series", "Series")

        val preflight = repository.toggleDefault(item)

        assertTrue(preflight.requiresRemovalConfirmation)
        assertEquals(listOf(confirmation), preflight.requiredRemovalConfirmations)
        assertEquals(0, simkl.applyCalls)

        val confirmed = repository.toggleDefault(
            item = item,
            confirmedRemovalProviders = setOf(TrackingProviderId.SIMKL)
        )

        assertFalse(confirmed.requiresRemovalConfirmation)
        assertEquals(1, simkl.applyCalls)
        assertEquals(mapOf(simklTab.key to false), simkl.lastChanges?.desiredMembership)
        assertTrue(simkl.lastConfirmed)
    }

    @Test
    fun `default toggle applies active provider immediately when confirmation is not required`() = runTest {
        val sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT)
        val traktTab = tab("watchlist", TrackingProviderId.TRAKT)
        val trakt = FakeLibraryProvider(
            providerId = TrackingProviderId.TRAKT,
            tab = traktTab,
            membership = mapOf(
                traktTab.key to true,
                "personal:42" to true
            )
        )
        val repository = repository(sourceMode, setOf(trakt))

        val result = repository.toggleDefault(
            LibraryEntryInput("tt456", "movie", "Movie")
        )

        assertFalse(result.requiresRemovalConfirmation)
        assertEquals(1, trakt.applyCalls)
        assertEquals(
            mapOf(
                traktTab.key to false,
                "personal:42" to true
            ),
            trakt.lastChanges?.desiredMembership
        )
        assertFalse(trakt.lastConfirmed)
    }

    @Test
    fun `MDBList management routes through its selected provider without Trakt calls`() = runTest {
        val source = MutableStateFlow(LibrarySourceMode.MDBLIST)
        val mdblistManager = mockk<TrackingListManager>(relaxed = true)
        val traktManager = mockk<TrackingListManager>(relaxed = true)
        val mdblist = FakeLibraryProvider(TrackingProviderId.MDBLIST, tab("mdblist:watchlist", TrackingProviderId.MDBLIST), listManager = mdblistManager)
        val trakt = FakeLibraryProvider(TrackingProviderId.TRAKT, tab("watchlist", TrackingProviderId.TRAKT), listManager = traktManager)
        val repository = repository(source, setOf(mdblist, trakt))
        repository.createPersonalList("List", null, LibraryListPrivacy.PRIVATE, LibrarySourceMode.MDBLIST)
        repository.updatePersonalList("mdblist:list:7", "Renamed", null, LibraryListPrivacy.PUBLIC, LibrarySourceMode.MDBLIST)
        repository.deletePersonalList("mdblist:list:7", LibrarySourceMode.MDBLIST)
        coVerify(exactly = 1) { mdblistManager.createList("List", null, LibraryListPrivacy.PRIVATE) }
        coVerify(exactly = 1) { mdblistManager.updateList("mdblist:list:7", "Renamed", null, LibraryListPrivacy.PUBLIC) }
        coVerify(exactly = 1) { mdblistManager.deleteList("mdblist:list:7") }
        coVerify(exactly = 0) { traktManager.createList(any(), any(), any()) }
        coVerify(exactly = 0) { traktManager.updateList(any(), any(), any(), any()) }
        coVerify(exactly = 0) { traktManager.deleteList(any()) }
    }

    @Test
    fun `source changes and foreign list keys are rejected before management writes`() = runTest {
        val source = MutableStateFlow(LibrarySourceMode.MDBLIST)
        val manager = mockk<TrackingListManager>(relaxed = true)
        val provider = FakeLibraryProvider(TrackingProviderId.MDBLIST, tab("mdblist:watchlist", TrackingProviderId.MDBLIST), listManager = manager)
        val repository = repository(source, setOf(provider))
        val foreign = runCatching { repository.deletePersonalList("personal:7", LibrarySourceMode.MDBLIST) }
        assertTrue(foreign.exceptionOrNull() is IllegalArgumentException)
        source.value = LibrarySourceMode.LOCAL
        val changed = runCatching { repository.createPersonalList("List", null, LibraryListPrivacy.PRIVATE, LibrarySourceMode.MDBLIST) }
        assertTrue(changed.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { manager.createList(any(), any(), any()) }
        coVerify(exactly = 0) { manager.deleteList(any()) }
    }

    @Test
    fun `MDBList participates in membership alongside both existing providers`() = runTest {
        val tabs = listOf(tab("watchlist", TrackingProviderId.TRAKT), tab("simkl:plantowatch", TrackingProviderId.SIMKL), tab("mdblist:watchlist", TrackingProviderId.MDBLIST))
        val providers = TrackingProviderId.entries.zip(tabs).map { (id, tab) -> FakeLibraryProvider(id, tab) }
        val source = MutableStateFlow(LibrarySourceMode.MDBLIST)
        val repository = repository(source, providers.toSet())
        assertEquals(listOf(tabs.last()), repository.listTabs.first())
        assertEquals(tabs, repository.membershipListTabs.first())
        repository.refreshNow()
        assertTrue(providers.take(2).all { it.refreshIntents.isEmpty() })
        assertEquals(listOf(TrackingRefreshIntent.USER_INITIATED), providers.last().refreshIntents)
    }

    private fun repository(
        sourceMode: MutableStateFlow<LibrarySourceMode>,
        providers: Set<TrackingLibraryProvider>
    ): LibraryRepositoryImpl {
        val settings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { librarySourceMode } returns sourceMode
        }
        return LibraryRepositoryImpl(
            appContext = mockk<Context>(relaxed = true),
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            traktSettingsDataStore = settings,
            librarySyncService = mockk<LibrarySyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            metaRepository = mockk<MetaRepository>(relaxed = true),
            trackingProviders = TrackingLibraryProviderRegistry(providers),
            profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        )
    }

    private fun tab(key: String, providerId: TrackingProviderId) = LibraryListTab(
        key = key,
        title = key,
        type = LibraryListTab.Type.WATCHLIST,
        trackingProviderId = providerId.storageId
    )

    private class FakeLibraryProvider(
        override val providerId: TrackingProviderId,
        private val tab: LibraryListTab,
        private val membership: Map<String, Boolean> = emptyMap(),
        private val removalConfirmation: TrackingMembershipRemovalConfirmation? = null,
        override val listManager: TrackingListManager? = null
    ) : TrackingLibraryProvider {
        val refreshIntents = mutableListOf<TrackingRefreshIntent>()
        var applyCalls = 0
        var lastChanges: ListMembershipChanges? = null
        var lastConfirmed = false
        override val isAuthenticated = flowOf(true)
        override val isRefreshing = flowOf(false)
        override val items = flowOf(emptyList<LibraryEntry>())
        override val tabs = flowOf(listOf(tab))

        override fun recognizesListKey(key: String): Boolean = key == tab.key || key.startsWith("${providerId.storageId}:")

        override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
            flowOf(emptySet())

        override fun toggledDefaultMembership(
            currentMembership: Map<String, Boolean>
        ): Map<String, Boolean> =
            currentMembership + (tab.key to (currentMembership[tab.key] != true))

        override suspend fun getMembershipSnapshot(item: LibraryEntryInput) =
            ListMembershipSnapshot(membership)

        override suspend fun membershipRemovalConfirmation(
            item: LibraryEntryInput,
            changes: ListMembershipChanges
        ) = removalConfirmation

        override suspend fun applyMembershipChanges(
            item: LibraryEntryInput,
            changes: ListMembershipChanges,
            destructiveRemovalConfirmed: Boolean
        ) {
            applyCalls += 1
            lastChanges = changes
            lastConfirmed = destructiveRemovalConfirmed
        }

        override suspend fun refresh(intent: TrackingRefreshIntent) {
            refreshIntents += intent
        }
    }
}
