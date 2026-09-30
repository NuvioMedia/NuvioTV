package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.MDBListSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MDBListSettingsViewModel @Inject constructor(
    private val dataStore: MDBListSettingsDataStore,
    private val mdbListApi: MDBListApi,
    authStore: MdbListAuthStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(MDBListSettingsUiState())
    val uiState: StateFlow<MDBListSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError: SharedFlow<Unit> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(dataStore.settings, authStore.state) { settings, auth ->
                MDBListSettingsUiState(isConnected = auth.isAuthenticated).fromSettings(settings)
            }.collectLatest { state ->
                _uiState.value = state
            }
        }
    }

    fun onEvent(event: MDBListSettingsEvent) {
        when (event) {
            is MDBListSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is MDBListSettingsEvent.ToggleTrakt -> update { dataStore.setShowTrakt(event.enabled) }
            is MDBListSettingsEvent.ToggleImdb -> update { dataStore.setShowImdb(event.enabled) }
            is MDBListSettingsEvent.ToggleTmdb -> update { dataStore.setShowTmdb(event.enabled) }
            is MDBListSettingsEvent.ToggleLetterboxd -> update { dataStore.setShowLetterboxd(event.enabled) }
            is MDBListSettingsEvent.ToggleTomatoes -> update { dataStore.setShowTomatoes(event.enabled) }
            is MDBListSettingsEvent.ToggleAudience -> update { dataStore.setShowAudience(event.enabled) }
            is MDBListSettingsEvent.ToggleMetacritic -> update { dataStore.setShowMetacritic(event.enabled) }
            is MDBListSettingsEvent.ToggleMal -> update { dataStore.setShowMal(event.enabled) }
            is MDBListSettingsEvent.ToggleShowOnHero -> update { dataStore.setShowOnHero(event.enabled) }
            is MDBListSettingsEvent.MoveRatingUp -> moveRating(event.provider, -1)
            is MDBListSettingsEvent.MoveRatingDown -> moveRating(event.provider, 1)
        }
    }

    fun validateAndSaveApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                mdbListApi.getUser(trimmed).isSuccessful
            } catch (e: Exception) { false }
            _validating.value = false
            if (valid) {
                dataStore.setApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(Unit)
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun moveRating(provider: String, direction: Int) {
        val currentOrder = _uiState.value.ratingOrder.toMutableList()
        val index = currentOrder.indexOf(provider)
        if (index < 0) return
        val targetIndex = (index + direction).coerceIn(0, currentOrder.lastIndex)
        if (targetIndex == index) return
        currentOrder.removeAt(index)
        currentOrder.add(targetIndex, provider)
        update { dataStore.setRatingOrder(currentOrder) }
    }
}

data class MDBListSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val isConnected: Boolean = false,
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true,
    val showMal: Boolean = true,
    val showOnHero: Boolean = false,
    val ratingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER
) {
    fun fromSettings(settings: MDBListSettings): MDBListSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        showTrakt = settings.showTrakt,
        showImdb = settings.showImdb,
        showTmdb = settings.showTmdb,
        showLetterboxd = settings.showLetterboxd,
        showTomatoes = settings.showTomatoes,
        showAudience = settings.showAudience,
        showMetacritic = settings.showMetacritic,
        showMal = settings.showMal,
        showOnHero = settings.showOnHero,
        ratingOrder = settings.ratingOrder
    )
}

sealed class MDBListSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTrakt(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleImdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTmdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleLetterboxd(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTomatoes(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleAudience(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMetacritic(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMal(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleShowOnHero(val enabled: Boolean) : MDBListSettingsEvent()
    data class MoveRatingUp(val provider: String) : MDBListSettingsEvent()
    data class MoveRatingDown(val provider: String) : MDBListSettingsEvent()
}
