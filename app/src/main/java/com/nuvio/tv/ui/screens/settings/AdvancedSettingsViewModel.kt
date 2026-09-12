package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean = false,
    val smoothBringIntoViewEnabled: Boolean = true,
    val composeHighlighterEnabled: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val playerStatsHudEnabled: Boolean = false,
    val rgb565Enabled: Boolean = true,
    val sentryEnabled: Boolean = true,
    val customUserAgent: String = ""
)

sealed class AdvancedSettingsEvent {
    data class SetFastHorizontalNavigationEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmoothBringIntoViewEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetComposeHighlighterEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlaybackIssueReportsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlayerStatsHudEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetRgb565Enabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSentryEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetCustomUserAgent(val value: String) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val sentrySettingsDataStore: SentrySettingsDataStore,
    private val imagePerformancePreferences: ImagePerformancePreferences,
    private val appRestarter: AppRestarter
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(rgb565Enabled = imagePerformancePreferences.rgb565Enabled) }
        viewModelScope.launch {
            layoutPreferenceDataStore.fastHorizontalNavigationEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(fastHorizontalNavigationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.smoothBringIntoViewEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(smoothBringIntoViewEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.composeHighlighterEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(composeHighlighterEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { it.copy(playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled) }
            }
        }
        viewModelScope.launch {
            deviceLocalPlayerPreferences.playerStatsHudEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(playerStatsHudEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            sentrySettingsDataStore.enabled.collectLatest { enabled ->
                _uiState.update { it.copy(sentryEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            deviceLocalPlayerPreferences.customUserAgent.collectLatest { value ->
                _uiState.update { it.copy(customUserAgent = value) }
            }
        }
    }

    fun onEvent(event: AdvancedSettingsEvent) {
        when (event) {
            is AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setFastHorizontalNavigationEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setSmoothBringIntoViewEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetComposeHighlighterEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlaybackIssueReportsEnabled -> {
                viewModelScope.launch {
                    playerSettingsDataStore.setPlaybackIssueReportsEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlayerStatsHudEnabled -> {
                viewModelScope.launch {
                    deviceLocalPlayerPreferences.setPlayerStatsHudEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetRgb565Enabled -> {
                if (imagePerformancePreferences.setRgb565Enabled(event.enabled)) {
                    _uiState.update { it.copy(rgb565Enabled = event.enabled) }
                    appRestarter.restart()
                }
            }
            is AdvancedSettingsEvent.SetSentryEnabled -> {
                viewModelScope.launch {
                    sentrySettingsDataStore.setEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetCustomUserAgent -> {
                viewModelScope.launch {
                    deviceLocalPlayerPreferences.setCustomUserAgent(event.value)
                }
            }
        }
    }
}
