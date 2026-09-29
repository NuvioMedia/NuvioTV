package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.mdblist.MdbListAuthError
import com.nuvio.tv.data.mdblist.MdbListAuthException
import com.nuvio.tv.data.mdblist.MdbListAuthRepository
import com.nuvio.tv.data.mdblist.MdbListAuthScope
import com.nuvio.tv.data.mdblist.MdbListAuthStore
import com.nuvio.tv.data.mdblist.MdbListDevicePollResult
import com.nuvio.tv.data.mdblist.MdbListDeviceSession
import com.nuvio.tv.data.mdblist.MdbListSyncError
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.mdblist.toMdbListSyncError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MdbListTrackerUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val session: MdbListDeviceSession? = null,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class MdbListTrackerAction(
    val scope: MdbListAuthScope? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MdbListTrackerViewModel @Inject constructor(
    private val auth: MdbListAuthRepository,
    private val authStore: MdbListAuthStore,
    private val sync: MdbListSyncRepository,
    private val profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(MdbListTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null
    val uiState = combine(authStore.state, sync.state, action, profiles.activeProfileId) { authorization, syncState, action, profileId ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: MdbListTrackerAction()
        val currentSync = syncState.takeIf { it.scope == authorization.scope && current }
        val connected = current && authorization.isAuthenticated
        MdbListTrackerUiState(
            isConnected = connected,
            username = authorization.user?.username.takeIf { current },
            session = authorization.session.takeIf { current },
            credentialsConfigured = auth.hasRequiredCredentials(),
            isLoading = transient.loading,
            isPolling = transient.polling,
            isSyncing = currentSync?.isLoading == true,
            statusMessage = when {
                currentSync?.isLoading == true -> context.getString(R.string.mdblist_status_syncing)
                connected && currentSync?.snapshot?.checkedAtEpochMs != null -> context.getString(
                    R.string.mdblist_last_synced,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(currentSync.snapshot.checkedAtEpochMs))
                )
                current && authorization.session != null -> context.getString(R.string.mdblist_status_enter_code)
                else -> null
            },
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.message()
                ?: currentSync?.error?.message()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MdbListTrackerUiState(credentialsConfigured = auth.hasRequiredCredentials()))

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    action.value = MdbListTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    fun onConnect() {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = MdbListTrackerAction(requestedScope, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                authStore.checkScope(scope)
                if (scope.profileId != profiles.activeProfileId.value) return@launch
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) auth.startDeviceAuthorization(scope)
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = MdbListTrackerAction(scope, polling = true)
                while (isActive && authStore.isCurrent(scope) && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (scope.profileId != profiles.activeProfileId.value) break
                    when (auth.pollDeviceAuthorization(scope)) {
                        MdbListDevicePollResult.Pending -> Unit
                        MdbListDevicePollResult.Authorized -> {
                            action.value = MdbListTrackerAction(authStore.scope())
                            sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                            break
                        }
                        MdbListDevicePollResult.Expired, MdbListDevicePollResult.Denied -> break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authStore.isCurrent(scope)) action.value = MdbListTrackerAction(scope, error = error.messageForUser())
            } finally {
                val current = action.value
                if (current.scope == scope) action.value = current.copy(loading = false, polling = false)
            }
        }
    }

    fun onRetryPolling() = onConnect()

    fun onCancel() {
        connectionJob?.cancel()
        val scope = authStore.scope()
        action.value = MdbListTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = MdbListTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (profiles.activeProfileId.value != profileId) return@launch
                val revoked = auth.disconnect(requestedScope)
                sync.ensureLoaded()
                if (profiles.activeProfileId.value == profileId) action.value = MdbListTrackerAction(
                    authStore.scope(), error = if (revoked) null else context.getString(R.string.mdblist_revoke_failed)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) action.value = MdbListTrackerAction(authStore.scope(), error = error.messageForUser())
            }
        }
    }

    fun onSyncNow() = sync.refreshAsync(TrackingRefreshIntent.USER_INITIATED)
    fun onAccountOpened() = sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)

    private fun Exception.messageForUser(): String =
        if (this is MdbListAuthException) error.message() else toMdbListSyncError().message()

    private fun MdbListAuthError.message(): String = context.getString(when (this) {
        MdbListAuthError.MISSING_CLIENT_ID -> R.string.mdblist_missing_client
        MdbListAuthError.INVALID_RESPONSE -> R.string.mdblist_invalid_response
        MdbListAuthError.INSUFFICIENT_SCOPE -> R.string.mdblist_insufficient_scope
        MdbListAuthError.CODE_EXPIRED -> R.string.mdblist_code_expired
        MdbListAuthError.ACCESS_DENIED -> R.string.mdblist_access_denied
        MdbListAuthError.AUTHORIZATION_REVOKED -> R.string.mdblist_authorization_revoked
    })

    private fun MdbListSyncError.message(): String = context.getString(when (this) {
        MdbListSyncError.UNAVAILABLE -> R.string.mdblist_unavailable
        MdbListSyncError.INVALID_RESPONSE -> R.string.mdblist_invalid_response
        MdbListSyncError.RATE_LIMIT -> R.string.mdblist_rate_limited
        MdbListSyncError.AUTHORIZATION_REVOKED -> R.string.mdblist_authorization_revoked
    })
}
