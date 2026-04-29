package com.omnio.phone.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.omnio.tv.data.local.AudioLanguageOption
import com.omnio.tv.data.local.PlayerSettings
import com.omnio.tv.data.local.PlayerSettingsDataStore
import com.omnio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.omnio.tv.data.local.SubtitleLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerDefaultsUiState(
    val preferredSubtitleLanguage: String = "en",
    val secondarySubtitleLanguage: String? = null,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryAudioLanguage: String? = null
)

@HiltViewModel
class PhonePlayerDefaultsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    val uiState: StateFlow<PlayerDefaultsUiState> = playerSettingsDataStore.playerSettings
        .map { settings -> settings.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlayerDefaultsUiState()
        )

    val subtitleLanguageOptions: List<SubtitleLanguage> = AVAILABLE_SUBTITLE_LANGUAGES

    fun setPreferredSubtitleLanguage(code: String) {
        viewModelScope.launch { playerSettingsDataStore.setSubtitlePreferredLanguage(code) }
    }

    fun setSecondarySubtitleLanguage(code: String?) {
        viewModelScope.launch { playerSettingsDataStore.setSubtitleSecondaryLanguage(code) }
    }

    fun setPreferredAudioLanguage(code: String) {
        viewModelScope.launch { playerSettingsDataStore.setPreferredAudioLanguage(code) }
    }

    fun setSecondaryAudioLanguage(code: String?) {
        viewModelScope.launch { playerSettingsDataStore.setSecondaryPreferredAudioLanguage(code) }
    }

    private fun PlayerSettings.toUiState() = PlayerDefaultsUiState(
        preferredSubtitleLanguage = subtitleStyle.preferredLanguage,
        secondarySubtitleLanguage = subtitleStyle.secondaryPreferredLanguage,
        preferredAudioLanguage = preferredAudioLanguage,
        secondaryAudioLanguage = secondaryPreferredAudioLanguage
    )

    companion object {
        const val SUBTITLE_FORCED_CODE = SUBTITLE_LANGUAGE_FORCED
        const val AUDIO_DEVICE_CODE = AudioLanguageOption.DEVICE
        const val AUDIO_DEFAULT_CODE = AudioLanguageOption.DEFAULT
    }
}
