package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.DebugSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val dataStore: DebugSettingsDataStore,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugSettingsUiState())
    val uiState: StateFlow<DebugSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.accountTabEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(accountTabEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            dataStore.syncCodeFeaturesEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(syncCodeFeaturesEnabled = enabled) }
            }
        }
    }

    fun onEvent(event: DebugSettingsEvent) {
        when (event) {
            is DebugSettingsEvent.ToggleAccountTab -> {
                viewModelScope.launch { dataStore.setAccountTabEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleSyncCodeFeatures -> {
                viewModelScope.launch { dataStore.setSyncCodeFeaturesEnabled(event.enabled) }
            }
            is DebugSettingsEvent.SignIn -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(signInLoading = true, signInResult = null) }
                    val result = authManager.signInWithEmail(event.email, event.password)
                    _uiState.update {
                        it.copy(
                            signInLoading = false,
                            signInResult = if (result.isSuccess) "Signed in successfully" else "Failed: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
        }
    }
}

data class DebugSettingsUiState(
    val accountTabEnabled: Boolean = false,
    val syncCodeFeaturesEnabled: Boolean = false,
    val signInLoading: Boolean = false,
    val signInResult: String? = null
)

sealed class DebugSettingsEvent {
    data class ToggleAccountTab(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleSyncCodeFeatures(val enabled: Boolean) : DebugSettingsEvent()
    data class SignIn(val email: String, val password: String) : DebugSettingsEvent()
}
