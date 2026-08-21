package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.availableAppThemes
import com.nuvio.tv.domain.model.resolveAppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettingsUiState(
    val themesLoaded: Boolean = false,
    val selectedTheme: AppTheme = AppTheme.WHITE,
    val availableThemes: List<AppTheme> = availableAppThemes(CosmeticEntitlements.None),
    val selectedFont: AppFont = AppFont.INTER,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val availableSettingsUiStyles: List<SettingsUiStyle> = SettingsUiStyle.entries.toList()
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class ToggleAmoledMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleAmoledSurfacesMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class SelectSettingsUiStyle(val style: SettingsUiStyle) : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore,
    memberAccessRepository: MemberAccessRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()

    private var restoreStyleFocus = false

    fun consumeStyleFocusRestore(): Boolean {
        val pending = restoreStyleFocus
        restoreStyleFocus = false
        return pending
    }

    init {
        viewModelScope.launch {
            combine(
                themeDataStore.selectedThemePreference,
                memberAccessRepository.access
            ) { selectedTheme, memberAccess ->
                val entitlements = memberAccess.entitlements
                resolveAppTheme(selectedTheme, entitlements) to availableAppThemes(entitlements)
            }
                .distinctUntilChanged()
                .collectLatest { (theme, availableThemes) ->
                    _uiState.update { state ->
                        state.copy(
                            themesLoaded = true,
                            selectedTheme = theme,
                            availableThemes = availableThemes
                        )
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
        viewModelScope.launch {
            themeDataStore.settingsUiStyle
                .distinctUntilChanged()
                .collectLatest { style ->
                    _uiState.update { state ->
                        if (state.settingsUiStyle == style) state else state.copy(settingsUiStyle = style)
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
            is ThemeSettingsEvent.SelectSettingsUiStyle -> selectSettingsUiStyle(event.style)
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

    private fun selectSettingsUiStyle(style: SettingsUiStyle) {
        if (_uiState.value.settingsUiStyle == style) return
        restoreStyleFocus = true
        viewModelScope.launch {
            themeDataStore.setSettingsUiStyle(style)
        }
    }
}
