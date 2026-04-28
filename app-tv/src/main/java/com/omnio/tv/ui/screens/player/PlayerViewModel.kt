package com.omnio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.omnio.tv.core.plugin.PluginManager
import com.omnio.tv.data.local.PlayerSettingsDataStore
import com.omnio.tv.data.local.StreamLinkCacheDataStore
import com.omnio.tv.data.repository.ParentalGuideRepository
import com.omnio.tv.data.repository.SkipIntroRepository
import com.omnio.tv.data.repository.EmbySessionService
import com.omnio.tv.data.repository.TraktEpisodeMappingService
import com.omnio.tv.data.repository.TraktScrobbleService
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.MetaRepository
import com.omnio.tv.domain.repository.StreamRepository
import com.omnio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.omnio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val embySessionService: EmbySessionService,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.omnio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.omnio.tv.data.local.WatchedItemsPreferences,
    private val trackPreferenceDataStore: com.omnio.tv.data.local.TrackPreferenceDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        traktScrobbleService = traktScrobbleService,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        embySessionService = embySessionService,
        playerSettingsDataStore = playerSettingsDataStore,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        trackPreferenceDataStore = trackPreferenceDataStore,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun stopAndRelease() {
        controller.stopAndRelease()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: OmnioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    override fun onCleared() {
        controller.onCleared()
        super.onCleared()
    }
}
