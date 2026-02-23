package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.repository.TraktTokenPollResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TraktConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED
}

data class TraktUiState(
    val mode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isStatsLoading: Boolean = false,
    val isPolling: Boolean = false,
    val username: String? = null,
    val tokenExpiresAtMillis: Long? = null,
    val deviceUserCode: String? = null,
    val verificationUrl: String? = null,
    val pollIntervalSeconds: Int = 5,
    val deviceCodeExpiresAtMillis: Long? = null,
    val continueWatchingDaysCap: Int = TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP,
    val showUnairedNextUp: Boolean = TraktSettingsDataStore.DEFAULT_SHOW_UNAIRED_NEXT_UP,
    val connectedStats: TraktProgressService.TraktCachedStats? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class TraktViewModel @Inject constructor(
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService,
    private val traktSettingsDataStore: TraktSettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(TraktUiState())
    val uiState: StateFlow<TraktUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var lastMode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED
    private var lastAutoSyncAtMs: Long = 0L

    init {
        _uiState.update {
            it.copy(credentialsConfigured = traktAuthService.hasRequiredCredentials())
        }
        observeSettings()
        observeAuthState()
    }

    fun onContinueWatchingDaysCapSelected(days: Int) {
        viewModelScope.launch {
            traktSettingsDataStore.setContinueWatchingDaysCap(days)
            traktProgressService.refreshNow()
            _uiState.update {
                it.copy(
                    continueWatchingDaysCap = days,
                    statusMessage = "Continue watching window updated"
                )
            }
        }
    }

    fun onShowUnairedNextUpChanged(enabled: Boolean) {
        viewModelScope.launch {
            traktSettingsDataStore.setShowUnairedNextUp(enabled)
            _uiState.update {
                it.copy(
                    showUnairedNextUp = enabled,
                    statusMessage = if (enabled) {
                        "Unaired Next Up episodes are now shown"
                    } else {
                        "Unaired Next Up episodes are now hidden"
                    }
                )
            }
        }
    }

    fun onConnectClick() {
        if (!traktAuthService.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Missing TRAKT_CLIENT_ID or TRAKT_CLIENT_SECRET in local.properties",
                    credentialsConfigured = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            val result = traktAuthService.startDeviceAuth()
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        isLoading = false,
                        statusMessage = "Enter code on trakt.tv/activate"
                    )
                } else {
                    state.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to start Trakt auth"
                    )
                }
            }
        }
    }

    fun onRetryPolling() {
        startPollingIfNeeded(force = true)
    }

    fun onCancelDeviceFlow() {
        viewModelScope.launch {
            pollJob?.cancel()
            traktAuthDataStore.clearDeviceFlow()
            _uiState.update {
                it.copy(
                    mode = TraktConnectionMode.DISCONNECTED,
                    isPolling = false,
                    statusMessage = null,
                    errorMessage = null
                )
            }
        }
    }

    fun onDisconnectClick() {
        viewModelScope.launch {
            pollJob?.cancel()
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            traktAuthService.revokeAndLogout()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    mode = TraktConnectionMode.DISCONNECTED,
                    isPolling = false,
                    isStatsLoading = false,
                    connectedStats = null,
                    statusMessage = "Disconnected from Trakt"
                )
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Syncing...") }
            traktProgressService.refreshNow()
            traktAuthService.fetchUserSettings()
            val stats = traktProgressService.getCachedStats(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isStatsLoading = false,
                    connectedStats = stats ?: it.connectedStats,
                    statusMessage = "Sync completed"
                )
            }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            traktAuthDataStore.state.collectLatest { authState ->
                applyAuthState(authState)
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.showUnairedNextUp
            ) { daysCap, showUnairedNextUp ->
                daysCap to showUnairedNextUp
            }.collectLatest { (daysCap, showUnairedNextUp) ->
                _uiState.update {
                    it.copy(
                        continueWatchingDaysCap = daysCap,
                        showUnairedNextUp = showUnairedNextUp
                    )
                }
            }
        }
    }

    private fun applyAuthState(authState: TraktAuthState) {
        val expiresAtSeconds = (authState.createdAt ?: 0L) + (authState.expiresIn ?: 0)
        val tokenExpiresAtMillis = if (expiresAtSeconds > 0L) expiresAtSeconds * 1000L else null

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.deviceCode.isNullOrBlank() -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _uiState.update { current ->
            current.copy(
                mode = mode,
                username = authState.username,
                tokenExpiresAtMillis = tokenExpiresAtMillis,
                deviceUserCode = authState.userCode,
                verificationUrl = authState.verificationUrl,
                pollIntervalSeconds = authState.pollInterval ?: 5,
                deviceCodeExpiresAtMillis = authState.expiresAt,
                credentialsConfigured = traktAuthService.hasRequiredCredentials(),
                isPolling = if (mode == TraktConnectionMode.CONNECTED) false else current.isPolling,
                connectedStats = if (mode == TraktConnectionMode.CONNECTED) current.connectedStats else null,
                isStatsLoading = if (mode == TraktConnectionMode.CONNECTED) current.isStatsLoading else false
            )
        }

        if (mode == TraktConnectionMode.CONNECTED &&
            (lastMode != TraktConnectionMode.CONNECTED || shouldAutoSyncNow())
        ) {
            autoSyncAfterConnected()
        } else if (mode == TraktConnectionMode.CONNECTED && _uiState.value.connectedStats == null) {
            loadConnectedStats(forceRefresh = false)
        }
        lastMode = mode

        if (mode == TraktConnectionMode.AWAITING_APPROVAL) {
            startPollingIfNeeded(force = false)
        } else {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun shouldAutoSyncNow(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastAutoSyncAtMs >= 15_000L
    }

    private fun autoSyncAfterConnected() {
        lastAutoSyncAtMs = System.currentTimeMillis()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isStatsLoading = true, errorMessage = null, statusMessage = null) }
            traktProgressService.refreshNow()
            traktAuthService.fetchUserSettings()
            val stats = traktProgressService.getCachedStats(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isStatsLoading = false,
                    connectedStats = stats ?: it.connectedStats,
                    statusMessage = null
                )
            }
        }
    }

    private fun loadConnectedStats(forceRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isStatsLoading = true) }
            val stats = traktProgressService.getCachedStats(forceRefresh = forceRefresh)
            _uiState.update { current ->
                current.copy(
                    isStatsLoading = false,
                    connectedStats = stats ?: current.connectedStats
                )
            }
        }
    }

    private fun startPollingIfNeeded(force: Boolean) {
        if (pollJob?.isActive == true && !force) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _uiState.update { it.copy(isPolling = true, errorMessage = null) }

            while (true) {
                val state = traktAuthService.getCurrentAuthState()
                val expiresAt = state.expiresAt
                if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                    _uiState.update {
                        it.copy(
                            isPolling = false,
                            errorMessage = "Device code expired. Start again.",
                            statusMessage = null
                        )
                    }
                    traktAuthDataStore.clearDeviceFlow()
                    break
                }

                when (val poll = traktAuthService.pollDeviceToken()) {
                    TraktTokenPollResult.Pending -> {
                        _uiState.update {
                            it.copy(
                                isPolling = true,
                                statusMessage = "Waiting for approval..."
                            )
                        }
                    }

                    TraktTokenPollResult.Expired -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = "Device code expired. Start again.",
                                statusMessage = null
                            )
                        }
                        break
                    }

                    TraktTokenPollResult.Denied -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = "Authorization denied on Trakt.",
                                statusMessage = null
                            )
                        }
                        break
                    }

                    is TraktTokenPollResult.SlowDown -> {
                        _uiState.update {
                            it.copy(
                                isPolling = true,
                                pollIntervalSeconds = poll.pollIntervalSeconds,
                                statusMessage = "Rate limited, slowing down polling..."
                            )
                        }
                    }

                    is TraktTokenPollResult.Approved -> {
                        traktProgressService.refreshNow()
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                statusMessage = "Connected as ${poll.username ?: "Trakt user"}",
                                errorMessage = null
                            )
                        }
                        break
                    }

                    is TraktTokenPollResult.Failed -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = poll.reason,
                                statusMessage = null
                            )
                        }
                        break
                    }
                }

                val delaySeconds = (_uiState.value.pollIntervalSeconds).coerceAtLeast(1)
                delay(delaySeconds * 1000L)
            }
        }
    }
}
