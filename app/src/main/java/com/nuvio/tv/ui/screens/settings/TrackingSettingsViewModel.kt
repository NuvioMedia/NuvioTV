package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.core.tracking.TrackingSourceSelection
import com.nuvio.tv.core.tracking.availableLibrarySourceModes
import com.nuvio.tv.core.tracking.availableWatchProgressSources
import com.nuvio.tv.core.tracking.effectiveTrackingSourceSelection
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklRewatchMode
import com.nuvio.tv.data.simkl.SimklRewatchNextUpMode
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.coerceSimklWatchedThresholdPercent
import com.nuvio.tv.data.simkl.isSimklRewatchModeSelectable
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingSettingsUiState(
    val watchProgressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val connectedProviderIds: Set<TrackingProviderId> = emptySet(),
    val simklAnimeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    /**
     * Rewatch recording is off until the user asks for it, exactly as on mobile: Simkl creates
     * rewatch sessions only when the app opts in, and a default that writes them would change what
     * the account holds without being asked.
     */
    val simklRewatchMode: SimklRewatchMode = SimklRewatchMode.Default,
    val simklRewatchNextUpMode: SimklRewatchNextUpMode = SimklRewatchNextUpMode.Default,
    val simklWatchedThresholdPercent: Int =
        TraktSettingsDataStore.DEFAULT_SIMKL_WATCHED_THRESHOLD_PERCENT,
    val isReady: Boolean = false
) {
    val availableWatchProgressSources: List<WatchProgressSource>
        get() = availableWatchProgressSources(connectedProviderIds)

    val availableLibrarySourceModes: List<LibrarySourceMode>
        get() = availableLibrarySourceModes(connectedProviderIds)
}

/**
 * The Simkl preferences the tracking screen shows, read as one value.
 *
 * Held together because `combine` is overloaded only up to five flows: five screen state flows plus
 * three rewatch flows and the threshold is eight, so the Simkl preferences are combined in an inner
 * `combine` and enter the outer one as a single value.
 */
internal data class SimklTrackingPreferences(
    val animeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    val rewatchMode: SimklRewatchMode = SimklRewatchMode.Default,
    val rewatchNextUpMode: SimklRewatchNextUpMode = SimklRewatchNextUpMode.Default,
    val watchedThresholdPercent: Int = TraktSettingsDataStore.DEFAULT_SIMKL_WATCHED_THRESHOLD_PERCENT
)

/**
 * The screen state for one set of emitted values.
 *
 * Pure on purpose: the only logic the state carries is the fallback of the requested sources to the
 * connected providers, so it can be reasoned about and tested with plain values instead of a data
 * store. A null [connectedProviderIds] means the stored credentials do not belong to the profile the
 * app is showing, so nothing can be read yet and the state stays [TrackingSettingsUiState.isReady]
 * false; every other caller reaches it once the providers answered.
 */
internal fun trackingSettingsUiState(
    watchProgressSource: WatchProgressSource,
    librarySourceMode: LibrarySourceMode,
    connectedProviderIds: Set<TrackingProviderId>?,
    simklPreferences: SimklTrackingPreferences
): TrackingSettingsUiState {
    if (connectedProviderIds == null) return TrackingSettingsUiState()
    val effective = effectiveTrackingSourceSelection(
        requested = TrackingSourceSelection(watchProgressSource, librarySourceMode),
        connectedProviderIds = connectedProviderIds
    )
    return TrackingSettingsUiState(
        watchProgressSource = effective.watchProgressSource,
        librarySourceMode = effective.librarySourceMode,
        connectedProviderIds = connectedProviderIds,
        simklAnimeIdPreference = simklPreferences.animeIdPreference,
        simklRewatchMode = simklPreferences.rewatchMode,
        simklRewatchNextUpMode = simklPreferences.rewatchNextUpMode,
        simklWatchedThresholdPercent = simklPreferences.watchedThresholdPercent,
        isReady = true
    )
}

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val sourceController: TrackingSourceController,
    private val settingsDataStore: TraktSettingsDataStore,
    private val simklSyncRepository: SimklSyncRepository,
    traktAuthDataStore: TraktAuthDataStore,
    private val simklAuthRepository: SimklAuthRepository,
    mdbListAuth: MdbListAuthStore,
    profiles: ProfileManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()

    /**
     * Set when the user picked a rewatch mode the account plan cannot record.
     *
     * Deliberately outside [uiState]: that state is built again from the combined flows on every
     * emission, so a flag in it would close the dialog on the first message from the sync.
     */
    private val _rewatchUpgradeRequested = MutableStateFlow(false)
    val rewatchUpgradeRequested: StateFlow<Boolean> = _rewatchUpgradeRequested.asStateFlow()

    init {
        viewModelScope.launch {
            val connected = combine(traktAuthDataStore.state, simklAuthRepository.state, mdbListAuth.state, profiles.activeProfileId) { trakt, simkl, mdblist, profileId ->
                if (mdblist.scope.profileId != profileId) null else buildSet<TrackingProviderId> {
                    if (trakt.isAuthenticated) add(TrackingProviderId.TRAKT)
                    if (simkl.isAuthenticated) add(TrackingProviderId.SIMKL)
                    if (mdblist.isAuthenticated) add(TrackingProviderId.MDBLIST)
                }
            }
            val simklPreferences = combine(
                settingsDataStore.simklAnimeIdPreference,
                settingsDataStore.simklRewatchMode,
                settingsDataStore.simklRewatchNextUpMode,
                settingsDataStore.simklWatchedThresholdPercent
            ) { animeIdPreference, rewatchMode, rewatchNextUpMode, watchedThresholdPercent ->
                SimklTrackingPreferences(
                    animeIdPreference = animeIdPreference,
                    rewatchMode = rewatchMode,
                    rewatchNextUpMode = rewatchNextUpMode,
                    watchedThresholdPercent = watchedThresholdPercent
                )
            }
            combine(
                sourceController.watchProgressSource,
                sourceController.librarySourceMode,
                connected,
                simklPreferences
            ) { watchProgressSource, librarySourceMode, connectedProviderIds, preferences ->
                trackingSettingsUiState(
                    watchProgressSource = watchProgressSource,
                    librarySourceMode = librarySourceMode,
                    connectedProviderIds = connectedProviderIds,
                    simklPreferences = preferences
                )
            }.collect { state ->
                _uiState.value = state
                if (state.isReady && mdbListAuth.scope().profileId == profiles.activeProfileId.value) {
                    sourceController.reconcileConnectedProviders(state.connectedProviderIds)
                }
            }
        }
    }

    fun selectWatchProgressSource(source: WatchProgressSource) {
        viewModelScope.launch {
            sourceController.selectWatchProgressSource(source)
        }
    }

    fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        viewModelScope.launch {
            sourceController.selectLibrarySourceMode(mode)
        }
    }

    fun selectSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        viewModelScope.launch {
            settingsDataStore.setSimklAnimeIdPreference(preference)
            simklSyncRepository.invalidateProjections(preference)
        }
    }

    /**
     * Applies a rewatch mode, but only once the account plan is known to allow it.
     *
     * Simkl records rewatches for Pro and VIP accounts only, so the plan is read at the moment the
     * mode is picked instead of at the first write: a mode the account cannot act on would look
     * saved and do nothing. `OFF` needs no plan and is never refused, which is what keeps the way
     * back always open.
     *
     * TV has no `LocalUriHandler`, which mobile uses to open the page with the Pro offer. The
     * ViewModel therefore only says the plan is not enough and the screen shows a dialog with the link.
     */
    fun setSimklRewatchMode(mode: SimklRewatchMode) {
        viewModelScope.launch {
            if (mode == SimklRewatchMode.OFF) {
                settingsDataStore.setSimklRewatchMode(mode)
                return@launch
            }
            val plan = simklAuthRepository.ensurePlanLoaded()
            if (isSimklRewatchModeSelectable(mode, plan)) {
                settingsDataStore.setSimklRewatchMode(mode)
            } else {
                _rewatchUpgradeRequested.value = true
            }
        }
    }

    fun dismissRewatchUpgrade() {
        _rewatchUpgradeRequested.value = false
    }

    /**
     * How much of a rewatch run has to be on the account before it offers the next episode.
     *
     * The runs are re-derived from the sessions the app already read, so the row follows the choice
     * right away; without this the setting would only show up at the next sync and look broken.
     */
    suspend fun setSimklRewatchNextUpMode(mode: SimklRewatchNextUpMode) {
        settingsDataStore.setSimklRewatchNextUpMode(mode)
        simklSyncRepository.refreshRewatchRuns()
    }

    fun setSimklWatchedThresholdPercent(percent: Int) {
        viewModelScope.launch {
            settingsDataStore.setSimklWatchedThresholdPercent(
                coerceSimklWatchedThresholdPercent(percent)
            )
        }
    }
}
