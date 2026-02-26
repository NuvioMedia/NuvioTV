package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettingsUiState(
    val selectedTheme: AppTheme = AppTheme.WHITE,
    val availableThemes: List<AppTheme> = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE }
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            themeDataStore.selectedTheme
                .distinctUntilChanged()
                .collectLatest { theme ->
                    _uiState.update { state ->
                        if (state.selectedTheme == theme) state else state.copy(selectedTheme = theme)
                    }
                }
        }
    }

    private fun currentTheme(): AppTheme {
        return _uiState.value.selectedTheme
    }

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            themeDataStore.setTheme(theme)
        }
    }
    
    /**
     * Synchronizes TMDB language with app language when user changes the app language.
     * Maps locale tags to TMDB language codes (ISO 639-1).
     */
    fun syncTmdbLanguageWithAppLocale(localeTag: String?) {
        viewModelScope.launch {
            val tmdbLanguage = when (localeTag) {
                null, "" -> "en" // System default or empty -> English
                "en" -> "en"
                "fr" -> "fr"
                "it" -> "it"
                "pl" -> "pl"
                "pt-PT" -> "pt"
                "tr" -> "tr"
                "sk" -> "sk"
                "sl" -> "sl"
                "ro" -> "ro"
                else -> "en" // Fallback to English for unsupported locales
            }
            tmdbSettingsDataStore.setLanguage(tmdbLanguage)
        }
    }
}
