package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
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
    val showMetaComments: Boolean = TraktSettingsDataStore.DEFAULT_SHOW_META_COMMENTS,
    val connectedStats: TraktProgressService.TraktCachedStats? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val moreLikeThisSource: MoreLikeThisSourcePreference = TraktSettingsDataStore.DEFAULT_MORE_LIKE_THIS_SOURCE
)

@HiltViewModel
class TraktViewModel @Inject constructor(
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(TraktUiState())
    val uiState: StateFlow<TraktUiState> = _uiState.asStateFlow()

    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var lastMode: TraktConnectionMode? = null
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
                    statusMessage = context.getString(R.string.trakt_status_cw_window_updated)
                )
            }
        }
    }

    fun onShowMetaCommentsChanged(enabled: Boolean) {
        viewModelScope.launch {
            traktSettingsDataStore.setShowMetaComments(enabled)
            _uiState.update {
                it.copy(
                    showMetaComments = enabled,
                    statusMessage = if (enabled) {
                        context.getString(R.string.trakt_comments_now_shown)
                    } else {
                        context.getString(R.string.trakt_comments_now_hidden)
                    }
                )
            }
        }
    }

    fun onMoreLikeThisSourceSelected(source: MoreLikeThisSourcePreference) {
        viewModelScope.launch {
            traktSettingsDataStore.setMoreLikeThisSource(source)
            _uiState.update { it.copy(moreLikeThisSource = source) }
        }
    }

    fun onConnectClick() {
        if (!traktAuthService.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    errorMessage = context.getString(R.string.trakt_missing_credentials),
                    credentialsConfigured = false
                )
            }
            return
        }

        // Guard against rapid re-entry — each double-tap would otherwise fire a
        // fresh /oauth/device/code request and can trip Trakt's rate limiter (#1197).
        // Flip isLoading synchronously here, before the launch, so two main-thread
        // clicks can't both observe isLoading == false and start parallel
        // coroutines (thanks Copilot).
        if (connectJob?.isActive == true || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }

        connectJob = viewModelScope.launch {
            try {
                val result = traktAuthService.startDeviceAuth()
                _uiState.update { state ->
                    if (result.isSuccess) {
                        state.copy(
                            isLoading = false,
                            statusMessage = context.getString(R.string.trakt_status_enter_activation_code)
                        )
                    } else {
                        state.copy(
                            isLoading = false,
                            errorMessage = result.exceptionOrNull()?.message
                                ?: context.getString(R.string.trakt_error_failed_start)
                        )
                    }
                }
            } finally {
                connectJob = null
                _uiState.update { state ->
                    if (state.isLoading) state.copy(isLoading = false) else state
                }
            }
        }
    }

    fun onRetryPolling() {
        startPollingIfNeeded(force = true)
    }

    fun onCancelDeviceFlow() {
        connectJob?.cancel()
        connectJob = null
        viewModelScope.launch {
            pollJob?.cancel()
            traktAuthDataStore.clearDeviceFlow()
            _uiState.update {
                it.copy(
                    mode = TraktConnectionMode.DISCONNECTED,
                    isLoading = false,
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
                    statusMessage = context.getString(R.string.trakt_status_disconnected)
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
                    statusMessage = context.getString(R.string.trakt_status_syncing)
                )
            }
            traktProgressService.refreshNow()
            traktAuthService.fetchUserSettings()
            val stats = traktProgressService.getCachedStats(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isStatsLoading = false,
                    connectedStats = stats ?: it.connectedStats,
                    statusMessage = context.getString(R.string.trakt_status_sync_completed)
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
                traktSettingsDataStore.showMetaComments,
                traktSettingsDataStore.moreLikeThisSource
            ) { daysCap, showMetaComments, moreLikeThisSource ->
                SettingsSnapshot(
                    continueWatchingDaysCap = daysCap,
                    showMetaComments = showMetaComments,
                    moreLikeThisSource = moreLikeThisSource
                )
            }.collectLatest { snapshot ->
                _uiState.update {
                    it.copy(
                        continueWatchingDaysCap = snapshot.continueWatchingDaysCap,
                        showMetaComments = snapshot.showMetaComments,
                        moreLikeThisSource = snapshot.moreLikeThisSource
                    )
                }
            }
        }
    }

    private data class SettingsSnapshot(
        val continueWatchingDaysCap: Int,
        val showMetaComments: Boolean,
        val moreLikeThisSource: MoreLikeThisSourcePreference
    )

    private fun applyAuthState(authState: TraktAuthState) {
        val expiresAtSeconds = (authState.createdAt ?: 0L) + (authState.expiresIn ?: 0)
        val tokenExpiresAtMillis = if (expiresAtSeconds > 0L) expiresAtSeconds * 1000L else null

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.deviceCode.isNullOrBlank() -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        val previousState = _uiState.value
        val connectedIdentityChanged = mode == TraktConnectionMode.CONNECTED &&
            previousState.mode == TraktConnectionMode.CONNECTED &&
            previousState.username != authState.username

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
                connectedStats = if (mode == TraktConnectionMode.CONNECTED && !connectedIdentityChanged) {
                    current.connectedStats
                } else {
                    null
                },
                isStatsLoading = if (mode == TraktConnectionMode.CONNECTED && !connectedIdentityChanged) {
                    current.isStatsLoading
                } else {
                    false
                }
            )
        }

        if (mode == TraktConnectionMode.CONNECTED && connectedIdentityChanged) {
            loadConnectedStats(forceRefresh = true)
        } else if (mode == TraktConnectionMode.CONNECTED && lastMode == null) {
            loadConnectedStats(forceRefresh = false)
        } else if (mode == TraktConnectionMode.CONNECTED &&
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
                            errorMessage = context.getString(R.string.trakt_error_code_expired),
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
                                statusMessage = context.getString(R.string.trakt_waiting_approval)
                            )
                        }
                    }

                    TraktTokenPollResult.AlreadyUsed -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = context.getString(R.string.trakt_error_code_used),
                                statusMessage = null
                            )
                        }
                        break
                    }

                    TraktTokenPollResult.Expired -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = context.getString(R.string.trakt_error_code_expired),
                                statusMessage = null
                            )
                        }
                        break
                    }

                    TraktTokenPollResult.Denied -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = context.getString(R.string.trakt_error_denied),
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
                                statusMessage = context.getString(R.string.trakt_status_rate_limited_slowing_polling)
                            )
                        }
                    }

                    is TraktTokenPollResult.Approved -> {
                        traktProgressService.refreshNow()
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                statusMessage = context.getString(
                                    R.string.trakt_connected_as,
                                    poll.username ?: context.getString(R.string.trakt_user_fallback)
                                ),
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
