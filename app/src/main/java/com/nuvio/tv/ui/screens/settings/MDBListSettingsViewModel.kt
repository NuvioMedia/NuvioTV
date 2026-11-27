package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.MDBListSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val PENDING_RATING_ORDER_TIMEOUT_MS = 2_500L

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

    private var ratingMoveBaseline: List<String>? = null
    private var ratingDraftOrder: List<String>? = null
    private var pendingRatingOrder: List<String>? = null
    private var ratingPersistGeneration: Int = 0

    init {
        viewModelScope.launch {
            combine(dataStore.settings, authStore.state) { settings, auth ->
                settings to auth.isAuthenticated
            }.collectLatest { (settings, isConnected) ->
                if (!settings.enabled && ratingDraftOrder != null) {
                    ratingDraftOrder = null
                    ratingMoveBaseline = null
                }
                val observedOrder = settings.ratingOrder.distinct()
                val resolvedOrder = resolveObservedRatingOrder(observedOrder)
                val currentMovingKey = if (settings.enabled && ratingDraftOrder != null) {
                    _uiState.value.movingRatingKey?.takeIf { it in resolvedOrder }
                } else {
                    null
                }
                if (currentMovingKey == null && ratingDraftOrder != null) {
                    ratingDraftOrder = null
                    ratingMoveBaseline = null
                }
                _uiState.update { state ->
                    state.copy(
                        isConnected = isConnected,
                        movingRatingKey = currentMovingKey
                    ).fromSettings(settings.copy(ratingOrder = resolvedOrder))
                }
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
        }
    }

    fun enterRatingMoveMode(provider: String) {
        val currentState = _uiState.value
        val current = currentState.ratingOrder
        if (!currentState.enabled || current.size <= 1 || provider !in current) return
        ratingMoveBaseline = current
        ratingDraftOrder = current
        _uiState.update { it.copy(movingRatingKey = provider) }
    }

    fun moveRatingDuringDrag(provider: String, direction: Int): Int? {
        val draft = ratingDraftOrder ?: return null
        val fromIndex = draft.indexOf(provider)
        val toIndex = fromIndex + direction
        if (fromIndex < 0 || toIndex !in draft.indices) return null
        val reordered = draft.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        ratingDraftOrder = reordered
        _uiState.update { it.copy(ratingOrder = reordered) }
        return toIndex
    }

    fun confirmRatingMove() {
        val draft = ratingDraftOrder
        val baseline = ratingMoveBaseline
        ratingDraftOrder = null
        ratingMoveBaseline = null
        _uiState.update { it.copy(movingRatingKey = null) }
        if (draft == null || draft == baseline) return

        val generation = ++ratingPersistGeneration
        pendingRatingOrder = draft
        viewModelScope.launch {
            var wrote = false
            try {
                withContext(NonCancellable) {
                    dataStore.setRatingOrder(draft)
                    wrote = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                wrote = false
            }
            if (!wrote && generation == ratingPersistGeneration) {
                if (pendingRatingOrder == draft) {
                    pendingRatingOrder = null
                }
                if (baseline != null && _uiState.value.movingRatingKey == null) {
                    _uiState.update { it.copy(ratingOrder = baseline) }
                }
            }
        }
        viewModelScope.launch {
            delay(PENDING_RATING_ORDER_TIMEOUT_MS)
            if (generation == ratingPersistGeneration && pendingRatingOrder == draft) {
                pendingRatingOrder = null
            }
        }
    }

    fun cancelRatingMove() {
        if (ratingDraftOrder == null && _uiState.value.movingRatingKey == null) return
        val baseline = ratingMoveBaseline
        ratingDraftOrder = null
        ratingMoveBaseline = null
        _uiState.update {
            it.copy(
                movingRatingKey = null,
                ratingOrder = baseline ?: it.ratingOrder
            )
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

    private fun resolveObservedRatingOrder(observed: List<String>): List<String> {
        val draft = ratingDraftOrder
        if (draft != null) return orderRatingsByKeys(observed, draft)
        val pending = pendingRatingOrder
        if (pending != null) {
            if (observed == pending || observed.toSet() != pending.toSet()) {
                pendingRatingOrder = null
                return observed
            }
            return orderRatingsByKeys(observed, pending)
        }
        return observed
    }

    private fun orderRatingsByKeys(
        observed: List<String>,
        orderKeys: List<String>
    ): List<String> {
        val available = observed.toSet()
        val seen = LinkedHashSet<String>()
        val ordered = ArrayList<String>(observed.size)
        for (key in orderKeys) {
            if (key in available && seen.add(key)) {
                ordered.add(key)
            }
        }
        for (key in observed) {
            if (seen.add(key)) {
                ordered.add(key)
            }
        }
        return ordered
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
    val ratingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    val movingRatingKey: String? = null
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
}
