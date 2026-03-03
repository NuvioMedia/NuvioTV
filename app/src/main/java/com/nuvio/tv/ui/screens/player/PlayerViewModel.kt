package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
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
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
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
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
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
