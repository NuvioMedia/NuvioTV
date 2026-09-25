package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.core.tracking.TrackingSourceSelection
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.mdblist.MdbListTestHarness
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklAuthState
import com.nuvio.tv.data.simkl.SimklRewatchMode
import com.nuvio.tv.data.simkl.SimklRewatchNextUpMode
import com.nuvio.tv.domain.model.LibrarySourceMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the tracking screen state does with the values it is handed.
 *
 * The state is built by a pure function, so the rewatch preferences and the source fallback can be
 * checked without a data store or a running screen: the combine that reads the store needs coroutines
 * and a device, this does not.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackingSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `source reconciliation waits for credentials of the selected profile`() = runTest {
        val harness = MdbListTestHarness()
        harness.store.selectProfile(2)
        harness.connected()
        harness.store.selectProfile(1)
        harness.connected()
        val activeProfile = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        val reconciliations = mutableListOf<Set<TrackingProviderId>>()
        val controller = mockk<TrackingSourceController> {
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.MDBLIST)
            every { librarySourceMode } returns MutableStateFlow(LibrarySourceMode.LOCAL)
            coEvery { reconcileConnectedProviders(any()) } coAnswers {
                reconciliations += firstArg<Set<TrackingProviderId>>()
                TrackingSourceSelection(WatchProgressSource.MDBLIST, LibrarySourceMode.LOCAL)
            }
        }
        val settings = mockk<TraktSettingsDataStore> {
            every { simklAnimeIdPreference } returns MutableStateFlow(SimklAnimeIdPreference.DEFAULT)
            every { simklRewatchMode } returns MutableStateFlow(SimklRewatchMode.Default)
            every { simklRewatchNextUpMode } returns MutableStateFlow(SimklRewatchNextUpMode.Default)
            every { simklWatchedThresholdPercent } returns MutableStateFlow(
                TraktSettingsDataStore.DEFAULT_SIMKL_WATCHED_THRESHOLD_PERCENT
            )
        }
        val trakt = mockk<TraktAuthDataStore> { every { state } returns flowOf(TraktAuthState()) }
        val simkl = mockk<SimklAuthRepository> { every { state } returns MutableStateFlow(SimklAuthState()) }
        val viewModel = TrackingSettingsViewModel(controller, settings, mockk(), trakt, simkl, harness.store, profiles)
        runCurrent()
        assertEquals(setOf(TrackingProviderId.MDBLIST), viewModel.uiState.value.connectedProviderIds)
        val previousCount = reconciliations.size
        activeProfile.value = 2
        runCurrent()
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals(previousCount, reconciliations.size)
        harness.store.selectProfile(2)
        runCurrent()
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals(WatchProgressSource.MDBLIST, viewModel.uiState.value.watchProgressSource)
        assertTrue(reconciliations.all { TrackingProviderId.MDBLIST in it })
    }

    @Test
    fun `rewatch settings reach the state exactly as they are stored`() {
        val state = trackingSettingsUiState(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.SIMKL,
            connectedProviderIds = setOf(TrackingProviderId.SIMKL),
            simklPreferences = SimklTrackingPreferences(
                animeIdPreference = SimklAnimeIdPreference.MAL,
                rewatchMode = SimklRewatchMode.MANUAL,
                rewatchNextUpMode = SimklRewatchNextUpMode.AFTER_TWO,
                watchedThresholdPercent = 92
            )
        )

        assertEquals(SimklAnimeIdPreference.MAL, state.simklAnimeIdPreference)
        assertEquals(SimklRewatchMode.MANUAL, state.simklRewatchMode)
        assertEquals(SimklRewatchNextUpMode.AFTER_TWO, state.simklRewatchNextUpMode)
        assertEquals(92, state.simklWatchedThresholdPercent)
        assertTrue(state.isReady)
    }

    @Test
    fun `rewatch defaults stay off on the threshold mobile ships`() {
        val state = trackingSettingsUiState(
            watchProgressSource = WatchProgressSource.NUVIO_SYNC,
            librarySourceMode = LibrarySourceMode.LOCAL,
            connectedProviderIds = emptySet(),
            simklPreferences = SimklTrackingPreferences()
        )

        // Off is the default on both platforms. A device that never opened this screen must not
        // start writing rewatch sessions into the account.
        assertEquals(SimklRewatchMode.OFF, state.simklRewatchMode)
        assertEquals(SimklRewatchNextUpMode.ALWAYS, state.simklRewatchNextUpMode)
        assertEquals(
            TraktSettingsDataStore.DEFAULT_SIMKL_WATCHED_THRESHOLD_PERCENT,
            state.simklWatchedThresholdPercent
        )
    }

    @Test
    fun `a source whose provider is not signed in falls back to the local one`() {
        val state = trackingSettingsUiState(
            watchProgressSource = WatchProgressSource.SIMKL,
            librarySourceMode = LibrarySourceMode.SIMKL,
            connectedProviderIds = emptySet(),
            simklPreferences = SimklTrackingPreferences()
        )

        assertEquals(WatchProgressSource.NUVIO_SYNC, state.watchProgressSource)
        assertEquals(LibrarySourceMode.LOCAL, state.librarySourceMode)
        assertTrue(state.connectedProviderIds.isEmpty())
        assertEquals(listOf(WatchProgressSource.NUVIO_SYNC), state.availableWatchProgressSources)
        assertEquals(listOf(LibrarySourceMode.LOCAL), state.availableLibrarySourceModes)
    }

    @Test
    fun `a connected provider keeps the stored source and is reported as connected`() {
        val state = trackingSettingsUiState(
            watchProgressSource = WatchProgressSource.TRAKT,
            librarySourceMode = LibrarySourceMode.SIMKL,
            connectedProviderIds = setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL),
            simklPreferences = SimklTrackingPreferences()
        )

        assertEquals(WatchProgressSource.TRAKT, state.watchProgressSource)
        assertEquals(LibrarySourceMode.SIMKL, state.librarySourceMode)
        assertEquals(
            setOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL),
            state.connectedProviderIds
        )
    }

    @Test
    fun `trakt being signed out falls back even while simkl is connected`() {
        // This is the case the fallback exists for: the stored source belongs to a provider that is
        // not signed in, so it cannot be read, and the row has to point at the source that can.
        val state = trackingSettingsUiState(
            watchProgressSource = WatchProgressSource.TRAKT,
            librarySourceMode = LibrarySourceMode.TRAKT,
            connectedProviderIds = setOf(TrackingProviderId.SIMKL),
            simklPreferences = SimklTrackingPreferences()
        )

        assertEquals(WatchProgressSource.NUVIO_SYNC, state.watchProgressSource)
        assertEquals(LibrarySourceMode.LOCAL, state.librarySourceMode)
        assertEquals(setOf(TrackingProviderId.SIMKL), state.connectedProviderIds)
    }
}
