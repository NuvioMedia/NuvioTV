package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibrarySorter
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
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class LibraryViewModelTestFixture(sorter: TrackingLibrarySorter? = null) {
    val profile = MutableStateFlow(1)
    val source = MutableStateFlow(LibrarySourceMode.MDBLIST)
    val authenticated = MutableStateFlow(true)
    val tab = LibraryListTab("mdblist:list:7", "Favourites", LibraryListTab.Type.PERSONAL,
        description = "Existing provider description", privacy = LibraryListPrivacy.PRIVATE)
    val tabs = MutableStateFlow(listOf(tab))
    val items = MutableStateFlow(emptyList<LibraryEntry>())
    val persistedSort = MutableStateFlow<String?>(null)
    val repository = mockk<LibraryRepository>(relaxed = true) {
        every { sourceMode } returns source
        every { isSyncing } returns flowOf(false)
        every { libraryItems } returns items
        every { listTabs } returns tabs
    }
    private val manager = mockk<TrackingListManager> {
        every { capabilities } returns TrackingListManagementCapabilities(listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC))
    }
    val provider = mockk<TrackingLibraryProvider> {
        every { providerId } returns TrackingProviderId.MDBLIST
        every { isAuthenticated } returns authenticated
        every { listManager } returns manager
        every { listSorter } returns sorter
    }
    val viewModel = LibraryViewModel(
        libraryRepository = repository, cloudLibraryRepository = mockk(relaxed = true), cloudPlaybackSessionStore = mockk(),
        externalPlaybackTracker = mockk(), playerSettingsDataStore = mockk(), metaRepository = mockk(),
        debridSettingsDataStore = mockk<DebridSettingsDataStore> { every { settings } returns flowOf(DebridSettings(cloudLibraryEnabled = false)) },
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore> {
            every { posterCardWidthDp } returns flowOf(126)
            every { posterCardCornerRadiusDp } returns flowOf(12)
            every { customPosterUrlPattern } returns flowOf("")
            every { customPosterEnabledScreens } returns flowOf(com.nuvio.tv.core.poster.CustomPosterScreen.ALL)
        },
        libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
            every { sortOption } returns persistedSort
            coEvery { setSortOption(any()) } coAnswers { persistedSort.value = firstArg() }
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
