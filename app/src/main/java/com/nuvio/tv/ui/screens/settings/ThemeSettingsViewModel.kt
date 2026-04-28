package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.domain.model.AppFont
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
    val availableThemes: List<AppTheme> = listOf(AppTheme.WHITE) + AppTheme.entries.filterNot { it == AppTheme.WHITE },
    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class ToggleAmoledMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleAmoledSurfacesMode(val enabled: Boolean) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore
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
        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledMode == enabled) state else state.copy(amoledMode = enabled)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledSurfacesMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledSurfacesMode == enabled) state else state.copy(amoledSurfacesMode = enabled)
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
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.ToggleAmoledMode -> setAmoledMode(event.enabled)
            is ThemeSettingsEvent.ToggleAmoledSurfacesMode -> setAmoledSurfacesMode(event.enabled)
        }
    }

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            themeDataStore.setTheme(theme)
        }
    }

    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }

    private fun setAmoledMode(enabled: Boolean) {
        if (_uiState.value.amoledMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledMode(enabled)
        }
    }

    private fun setAmoledSurfacesMode(enabled: Boolean) {
        if (_uiState.value.amoledSurfacesMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledSurfacesMode(enabled)
        }
    }
}
