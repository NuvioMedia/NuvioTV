package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingListManagementCapabilities
import com.nuvio.tv.core.tracking.TrackingListManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryListManagementTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `MDBList editor saves the provider key and omits unsupported existing descriptions`() = runTest {
        val f = Fixture()
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartEditList()
        assertEquals("mdblist:list:7", f.viewModel.uiState.value.listEditorState!!.listId)
        f.viewModel.onUpdateEditorName("Renamed")
        f.viewModel.onUpdateEditorPrivacy(LibraryListPrivacy.FRIENDS)
        assertEquals(LibraryListPrivacy.PRIVATE, f.viewModel.uiState.value.listEditorState!!.privacy)
        f.viewModel.onUpdateEditorPrivacy(LibraryListPrivacy.PUBLIC)
        f.viewModel.onSubmitEditor()
        runCurrent()
        coVerify(exactly = 1) {
            f.repository.updatePersonalList("mdblist:list:7", "Renamed", null, LibraryListPrivacy.PUBLIC, LibrarySourceMode.MDBLIST)
        }
        assertNull(f.viewModel.uiState.value.listEditorState)
    }

    @Test
    fun `profile switch clears the editor and prevents submitting to the new profile`() = runTest {
        val f = Fixture()
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("Old profile")
        f.profile.value = 2
        f.viewModel.onSubmitEditor()
        runCurrent()
        assertFalse(f.viewModel.uiState.value.showManageDialog)
        assertNull(f.viewModel.uiState.value.listEditorState)
        coVerify(exactly = 0) { f.repository.createPersonalList(any(), any(), any(), any()) }
    }

    @Test
    fun `disconnect and library source changes dismiss management`() = runTest {
        for (disconnect in listOf(true, false)) {
            val f = Fixture()
            runCurrent()
            f.viewModel.onOpenManageLists()
            f.viewModel.onStartEditList()
            if (disconnect) f.authenticated.value = false else f.source.value = LibrarySourceMode.LOCAL
            runCurrent()
            assertFalse(f.viewModel.uiState.value.showManageDialog)
            f.viewModel.onDeleteSelectedList()
            coVerify(exactly = 0) { f.repository.deletePersonalList(any(), any()) }
        }
    }

    @Test
    fun `duplicate save is suppressed and an old completion cannot close a newly opened editor`() = runTest {
        val f = Fixture()
        val finished = CompletableDeferred<Unit>()
        coEvery { f.repository.createPersonalList(any(), any(), any(), any()) } coAnswers { finished.await() }
        runCurrent()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("First")
        f.viewModel.onSubmitEditor()
        f.viewModel.onSubmitEditor()
        runCurrent()
        assertTrue(f.viewModel.uiState.value.pendingOperation)
        f.viewModel.onCloseManageLists()
        f.viewModel.onOpenManageLists()
        f.viewModel.onStartCreateList()
        f.viewModel.onUpdateEditorName("New draft")
        finished.complete(Unit)
        runCurrent()
        assertEquals("New draft", f.viewModel.uiState.value.listEditorState!!.name)
        coVerify(exactly = 1) { f.repository.createPersonalList(any(), any(), any(), any()) }
    }

    @Test
    fun `MDBList never offers list reordering and defaults to each selected list rank`() = runTest {
        val f = Fixture()
        val second = f.tab.copy(key = "mdblist:list:8", title = "Other")
        f.tabs.value = listOf(f.tab, second)
        fun item(id: String, first: Int, other: Int) = LibraryEntry(
            id, "movie", id, null, background = null, logo = null, description = null, releaseInfo = null,
            imdbRating = null, genres = emptyList(), addonBaseUrl = null,
            listKeys = setOf(f.tab.key, second.key), listRanks = mapOf(f.tab.key to first, second.key to other)
        )
        f.items.value = listOf(item("First", 1, 2), item("Second", 2, 1))
        runCurrent()
        assertEquals(listOf("First", "Second"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onSelectListTab(second.key)
        assertEquals(listOf("Second", "First"), f.viewModel.uiState.value.visibleItems.map { it.name })
        f.viewModel.onOpenManageLists()
        f.viewModel.onMoveSelectedListDown()
        runCurrent()
        coVerify(exactly = 0) { f.repository.reorderPersonalLists(any(), any()) }
    }

    private class Fixture {
        val profile = MutableStateFlow(1)
        val source = MutableStateFlow(LibrarySourceMode.MDBLIST)
        val authenticated = MutableStateFlow(true)
        val tab = LibraryListTab("mdblist:list:7", "Favourites", LibraryListTab.Type.PERSONAL,
            description = "Existing provider description", privacy = LibraryListPrivacy.PRIVATE)
        val tabs = MutableStateFlow(listOf(tab))
        val items = MutableStateFlow(emptyList<LibraryEntry>())
        val repository = mockk<LibraryRepository>(relaxed = true) {
            every { sourceMode } returns source
            every { isSyncing } returns flowOf(false)
            every { libraryItems } returns items
            every { listTabs } returns tabs
        }
        private val manager = mockk<TrackingListManager> {
            every { capabilities } returns TrackingListManagementCapabilities(listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC))
        }
        private val provider = mockk<TrackingLibraryProvider> {
            every { providerId } returns TrackingProviderId.MDBLIST
            every { isAuthenticated } returns authenticated
            every { listManager } returns manager
        }
        val viewModel = LibraryViewModel(
            libraryRepository = repository, cloudLibraryRepository = mockk(relaxed = true), cloudPlaybackSessionStore = mockk(),
            externalPlaybackTracker = mockk(), playerSettingsDataStore = mockk(), metaRepository = mockk(),
            debridSettingsDataStore = mockk<DebridSettingsDataStore> { every { settings } returns flowOf(DebridSettings(cloudLibraryEnabled = false)) },
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore> {
                every { posterCardWidthDp } returns flowOf(126)
                every { posterCardCornerRadiusDp } returns flowOf(12)
            },
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
                every { sortOption } returns flowOf(null)
                every { lastSelectedList } returns flowOf(null)
                every { lastSelectedType } returns flowOf(null)
            },
            authManager = mockk<AuthManager> { every { authState } returns MutableStateFlow(AuthState.SignedOut) },
            trackingProviderRegistry = TrackingLibraryProviderRegistry(setOf(provider)),
            watchProgressRepository = mockk<WatchProgressRepository> { every { observeWatchedMovieIds() } returns flowOf(emptySet()) },
            watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder> { every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet()) },
            profileManager = mockk<ProfileManager> { every { activeProfileId } returns profile },
            posterOptions = mockk(relaxed = true), context = mockk(relaxed = true)
        )
    }
}
