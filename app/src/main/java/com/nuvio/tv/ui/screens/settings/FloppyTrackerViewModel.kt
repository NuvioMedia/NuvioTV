package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.floppy.FloppyApiClient
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.floppy.FloppyConnectionResult
import com.nuvio.tv.data.floppy.FloppyCredentials
import com.nuvio.tv.data.floppy.normalizeFloppyBaseUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FloppyConnectionError {
    INVALID_ADDRESS,
    MISSING_TOKEN,
    REJECTED,
    UNREACHABLE,
    NOT_FLOPPY,
    SAVE_FAILED
}

data class FloppyTrackerUiState(
    val isConnected: Boolean = false,
    val baseUrl: String? = null,
    val isLoading: Boolean = false,
    val error: FloppyConnectionError? = null
)

private data class FloppyTrackerAction(
    val isLoading: Boolean = false,
    val error: FloppyConnectionError? = null
)

@HiltViewModel
class FloppyTrackerViewModel @Inject constructor(
    private val authStore: FloppyAuthStore,
    private val api: FloppyApiClient
) : ViewModel() {
    private val action = MutableStateFlow(FloppyTrackerAction())
    private var connectionJob: Job? = null

    val uiState = combine(authStore.state, action) { auth, action ->
        FloppyTrackerUiState(
            isConnected = auth.isConnected,
            baseUrl = auth.baseUrl,
            isLoading = action.isLoading,
            error = action.error
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FloppyTrackerUiState(isConnected = authStore.state.value.isConnected, baseUrl = authStore.state.value.baseUrl))

    fun onDialogOpened() {
        if (connectionJob?.isActive != true) action.value = FloppyTrackerAction()
    }

    fun onConnect(address: String, token: String) {
        if (connectionJob?.isActive == true) return
        val baseUrl = normalizeFloppyBaseUrl(address)
        val trimmedToken = token.trim()
        val invalid = when {
            baseUrl == null -> FloppyConnectionError.INVALID_ADDRESS
            trimmedToken.isEmpty() -> FloppyConnectionError.MISSING_TOKEN
            else -> null
        }
        if (invalid != null || baseUrl == null) {
            action.value = FloppyTrackerAction(error = invalid)
            return
        }
        val credentials = FloppyCredentials(baseUrl, trimmedToken)
        action.value = FloppyTrackerAction(isLoading = true)
        connectionJob = viewModelScope.launch {
            val error = when (api.checkConnection(credentials)) {
                FloppyConnectionResult.CONNECTED -> runCatching { authStore.save(credentials) }
                    .fold(onSuccess = { null }, onFailure = { FloppyConnectionError.SAVE_FAILED })
                FloppyConnectionResult.REJECTED -> FloppyConnectionError.REJECTED
                FloppyConnectionResult.UNREACHABLE -> FloppyConnectionError.UNREACHABLE
                FloppyConnectionResult.NOT_FLOPPY -> FloppyConnectionError.NOT_FLOPPY
            }
            action.value = FloppyTrackerAction(error = error)
        }
    }

    fun onCancel() {
        connectionJob?.cancel()
        action.value = FloppyTrackerAction()
    }

    fun onDisconnect() {
        connectionJob?.cancel()
        action.value = FloppyTrackerAction(
            error = if (runCatching { authStore.disconnect() }.isFailure) FloppyConnectionError.SAVE_FAILED else null
        )
    }
}
