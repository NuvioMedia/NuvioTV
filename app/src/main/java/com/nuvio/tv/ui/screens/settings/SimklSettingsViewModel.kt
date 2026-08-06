package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.simkl.SimklAuthError
import com.nuvio.tv.data.simkl.SimklAuthException
import com.nuvio.tv.data.simkl.SimklAuthRepository
import com.nuvio.tv.data.simkl.SimklConnectionMode
import com.nuvio.tv.data.simkl.SimklPinPollResult
import com.nuvio.tv.data.simkl.SimklSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SimklSettingsUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val username: String? = null,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val expiresAtEpochMs: Long? = null,
    val pollIntervalSeconds: Int = 5,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SimklSettingsViewModel @Inject constructor(
    private val authRepository: SimklAuthRepository,
    private val syncRepository: SimklSyncRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SimklSettingsUiState(credentialsConfigured = authRepository.hasRequiredCredentials())
    )
    val uiState: StateFlow<SimklSettingsUiState> = _uiState.asStateFlow()
    private var connectJob: Job? = null
    private var identityRefreshJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.state.collectLatest { state ->
                _uiState.update { current ->
                    current.copy(
                        mode = state.mode,
                        credentialsConfigured = authRepository.hasRequiredCredentials(),
                        isPolling = state.mode == SimklConnectionMode.AWAITING_APPROVAL && current.isPolling,
                        username = state.username,
                        userCode = state.pinSession?.userCode,
                        verificationUri = state.pinSession?.verificationUri,
                        expiresAtEpochMs = state.pinSession?.expiresAtEpochMs,
                        pollIntervalSeconds = state.pinSession?.intervalSeconds ?: 5,
                        errorMessage = state.error?.message()
                    )
                }
                if (
                    shouldRefreshMissingSimklIdentity(
                        state = state,
                        isRefreshBlocked = identityRefreshJob?.isActive == true || pollJob?.isActive == true
                    )
                ) {
                    identityRefreshJob = viewModelScope.launch {
                        try {
                            authRepository.refreshUserSettings()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            Unit
                        } finally {
                            identityRefreshJob = null
                        }
                    }
                }
                if (state.mode == SimklConnectionMode.AWAITING_APPROVAL) {
                    startPolling(false)
                } else if (shouldCancelSimklPolling(state.mode)) {
                    pollJob?.cancel()
                    pollJob = null
                }
            }
        }
    }

    fun onConnect() {
        if (connectJob?.isActive == true || _uiState.value.isLoading) return
        if (!authRepository.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    credentialsConfigured = false,
                    errorMessage = context.getString(R.string.simkl_missing_credentials)
                )
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
        connectJob = viewModelScope.launch {
            try {
                authRepository.startPinAuth()
                _uiState.update {
                    it.copy(
                        statusMessage = context.getString(R.string.simkl_status_enter_code)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = error.toUserMessage())
                }
            } finally {
                connectJob = null
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onRetryPolling() = startPolling(true)

    fun onCancel() {
        connectJob?.cancel()
        connectJob = null
        pollJob?.cancel()
        pollJob = null
        authRepository.cancelPinAuth()
        _uiState.update {
            it.copy(
                mode = SimklConnectionMode.DISCONNECTED,
                isLoading = false,
                isPolling = false,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    fun onDisconnect() {
        viewModelScope.launch {
            pollJob?.cancel()
            authRepository.disconnect()
            syncRepository.clearCurrentProfile()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isPolling = false,
                    statusMessage = context.getString(R.string.simkl_status_disconnected),
                    errorMessage = null
                )
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    statusMessage = context.getString(R.string.simkl_status_syncing)
                )
            }
            syncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            val error = syncRepository.state.value.errorMessage
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = if (error == null) context.getString(R.string.simkl_status_synced) else null,
                    errorMessage = error
                )
            }
        }
    }

    private fun startPolling(force: Boolean) {
        if (pollJob?.isActive == true && !force) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _uiState.update { it.copy(isPolling = true, errorMessage = null) }
            while (isActive) {
                val session = authRepository.state.value.pinSession ?: break
                val remaining = session.expiresAtEpochMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    authRepository.cancelPinAuth()
                    _uiState.update {
                        it.copy(
                            isPolling = false,
                            errorMessage = context.getString(R.string.simkl_error_code_expired)
                        )
                    }
                    break
                }
                delay((session.intervalSeconds * 1_000L).coerceAtMost(remaining))
                try {
                    when (authRepository.pollPin()) {
                        SimklPinPollResult.Pending -> _uiState.update {
                            it.copy(statusMessage = context.getString(R.string.simkl_status_waiting))
                        }
                        SimklPinPollResult.Authorized -> {
                            syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
                            _uiState.update {
                                it.copy(
                                    isPolling = false,
                                    statusMessage = context.getString(R.string.simkl_status_connected)
                                )
                            }
                            break
                        }
                        SimklPinPollResult.Expired,
                        SimklPinPollResult.Invalidated -> {
                            _uiState.update {
                                it.copy(
                                    isPolling = false,
                                    errorMessage = context.getString(R.string.simkl_error_code_expired)
                                )
                            }
                            break
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(isPolling = false, errorMessage = error.toUserMessage())
                    }
                    break
                }
            }
        }
    }

    private fun SimklAuthError.message(): String = when (this) {
        SimklAuthError.MISSING_CLIENT_ID -> context.getString(R.string.simkl_missing_credentials)
        SimklAuthError.PIN_EXPIRED,
        SimklAuthError.PIN_INVALIDATED -> context.getString(R.string.simkl_error_code_expired)
        SimklAuthError.AUTHORIZATION_REVOKED -> context.getString(R.string.simkl_error_authorization_revoked)
        SimklAuthError.INVALID_PIN_RESPONSE,
        SimklAuthError.NETWORK -> context.getString(R.string.simkl_error_network)
    }

    private fun Throwable.toUserMessage(): String =
        (this as? SimklAuthException)?.error?.message()
            ?: message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.simkl_error_network)
}
