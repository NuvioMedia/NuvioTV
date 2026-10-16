package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.wetrakr.WeTrakrAuthError
import com.nuvio.tv.data.wetrakr.WeTrakrAuthException
import com.nuvio.tv.data.wetrakr.WeTrakrAuthRepository
import com.nuvio.tv.data.wetrakr.WeTrakrAuthScope
import com.nuvio.tv.data.wetrakr.WeTrakrAuthStore
import com.nuvio.tv.data.wetrakr.WeTrakrDevicePollResult
import com.nuvio.tv.data.wetrakr.WeTrakrLocalUploader
import com.nuvio.tv.data.wetrakr.WeTrakrDeviceSession
import com.nuvio.tv.data.wetrakr.WeTrakrSyncError
import com.nuvio.tv.data.wetrakr.WeTrakrSyncPause
import com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository
import com.nuvio.tv.data.wetrakr.WeTrakrUploadKind
import com.nuvio.tv.data.wetrakr.WeTrakrUploadPreview
import com.nuvio.tv.data.wetrakr.WeTrakrUploadRun
import com.nuvio.tv.data.wetrakr.WeTrakrUploadStatus
import com.nuvio.tv.data.wetrakr.pause
import com.nuvio.tv.data.wetrakr.toWeTrakrSyncError
import com.nuvio.tv.data.wetrakr.weTrakrUploadPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WeTrakrTrackerUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val session: WeTrakrDeviceSession? = null,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val continueWatchingDaysCap: Int = TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP
)

data class WeTrakrLocalUploadUiState(
    val isOpen: Boolean = false,
    val isScanning: Boolean = false,
    val preview: WeTrakrUploadPreview? = null,
    val selected: Set<WeTrakrUploadKind> = WeTrakrUploadKind.entries.toSet(),
    val errorMessage: String? = null,
    val run: WeTrakrUploadRun? = null,
    val runMessage: String? = null
) {
    val requestCount: Int
        get() = preview?.requestCount(selected) ?: 0

    val uploadCount: Int
        get() = preview?.groups?.filter { it.kind in selected }?.sumOf { it.toUpload } ?: 0
}

private data class WeTrakrTrackerAction(
    val scope: WeTrakrAuthScope? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WeTrakrTrackerViewModel @Inject constructor(
    private val auth: WeTrakrAuthRepository,
    private val authStore: WeTrakrAuthStore,
    private val sync: WeTrakrSyncRepository,
    private val profiles: ProfileManager,
    private val settings: TraktSettingsDataStore,
    private val watchedItems: WatchedItemsPreferences,
    private val library: LibraryPreferences,
    private val watchProgress: WatchProgressPreferences,
    private val uploader: WeTrakrLocalUploader,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(WeTrakrTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null
    private var localUploadJob: Job? = null
    private val mutableLocalUpload = MutableStateFlow(WeTrakrLocalUploadUiState())
    val localUpload: StateFlow<WeTrakrLocalUploadUiState> = combine(mutableLocalUpload, uploader.run) { state, run ->
        val current = run?.takeIf { it.scope == authStore.scope() && it.scope.profileId == profiles.activeProfileId.value }
        state.copy(run = current, runMessage = current?.message())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WeTrakrLocalUploadUiState())
    val uiState = combine(
        authStore.state,
        sync.state,
        action,
        profiles.activeProfileId,
        settings.continueWatchingDaysCap
    ) { authorization, syncState, action, profileId, daysCap ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: WeTrakrTrackerAction()
        val currentSync = syncState.takeIf { it.scope == authorization.scope && current }
        val connected = current && authorization.isAuthenticated
        val pause = currentSync?.pause(System.currentTimeMillis())?.takeIf { connected }
        WeTrakrTrackerUiState(
            isConnected = connected,
            username = authorization.user?.let { it.displayName ?: it.username }.takeIf { current },
            session = authorization.session.takeIf { current },
            credentialsConfigured = auth.hasRequiredCredentials(),
            isLoading = transient.loading,
            isPolling = transient.polling,
            isSyncing = currentSync?.isLoading == true,
            statusMessage = when {
                currentSync?.isLoading == true -> context.getString(R.string.wetrakr_status_syncing)
                pause != null -> pause.message()
                connected && currentSync?.snapshot?.checkedAtEpochMs != null -> context.getString(
                    R.string.wetrakr_last_synced,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(currentSync.snapshot.checkedAtEpochMs))
                )
                current && authorization.session != null -> context.getString(R.string.wetrakr_status_enter_code)
                else -> null
            },
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.message()
                ?: currentSync?.error?.takeIf { pause == null }?.message(),
            continueWatchingDaysCap = daysCap
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WeTrakrTrackerUiState(credentialsConfigured = auth.hasRequiredCredentials()))

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    onLocalUploadClosed()
                    action.value = WeTrakrTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    fun onConnect() {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = WeTrakrTrackerAction(requestedScope, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                authStore.checkScope(scope)
                if (scope.profileId != profiles.activeProfileId.value) return@launch
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) auth.startDeviceAuthorization(scope)
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = WeTrakrTrackerAction(scope, polling = true)
                while (isActive && authStore.isCurrent(scope) && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (scope.profileId != profiles.activeProfileId.value) break
                    when (auth.pollDeviceAuthorization(scope)) {
                        WeTrakrDevicePollResult.Pending -> Unit
                        WeTrakrDevicePollResult.Authorized -> {
                            action.value = WeTrakrTrackerAction(authStore.scope())
                            sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                            break
                        }
                        WeTrakrDevicePollResult.Expired, WeTrakrDevicePollResult.Denied -> break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authStore.isCurrent(scope)) action.value = WeTrakrTrackerAction(scope, error = error.messageForUser())
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
        action.value = WeTrakrTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = WeTrakrTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (profiles.activeProfileId.value != profileId) return@launch
                val revoked = auth.disconnect(requestedScope)
                sync.ensureLoaded()
                if (profiles.activeProfileId.value == profileId) action.value = WeTrakrTrackerAction(
                    authStore.scope(), error = if (revoked) null else context.getString(R.string.wetrakr_revoke_failed)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) action.value = WeTrakrTrackerAction(authStore.scope(), error = error.messageForUser())
            }
        }
    }

    fun onSyncNow() = sync.refreshAsync(TrackingRefreshIntent.USER_INITIATED)

    fun onContinueWatchingDaysCapSelected(days: Int) {
        viewModelScope.launch { settings.setContinueWatchingDaysCap(days) }
    }
    fun onAccountOpened() = sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)

    fun onLocalUploadOpened() {
        val scope = authStore.scope()
        if (!authStore.state.value.isAuthenticated || scope.profileId != profiles.activeProfileId.value) return
        localUploadJob?.cancel()
        if (uploader.run.value?.let { it.isActive && it.scope == scope } == true) {
            mutableLocalUpload.value = WeTrakrLocalUploadUiState(isOpen = true)
            return
        }
        uploader.clearFinished()
        mutableLocalUpload.value = WeTrakrLocalUploadUiState(isOpen = true, isScanning = true)
        localUploadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { sync.refresh(TrackingRefreshIntent.AUTOMATIC) }
                    .onFailure { if (it is CancellationException) throw it }
                authStore.checkScope(scope)
                val snapshot = sync.currentSnapshot()?.takeIf { it.isInitialized }
                if (snapshot == null) {
                    mutableLocalUpload.value = mutableLocalUpload.value.copy(
                        isScanning = false,
                        errorMessage = context.getString(R.string.wetrakr_local_upload_not_synced)
                    )
                    return@launch
                }
                val watched = watchedItems.getAllItems(scope.profileId)
                val saved = library.getAllItems(scope.profileId)
                val progress = watchProgress.getAllRawEntries(scope.profileId).values.toList()
                val preview = withContext(Dispatchers.Default) { weTrakrUploadPreview(watched, saved, snapshot, progress) }
                authStore.checkScope(scope)
                mutableLocalUpload.value = mutableLocalUpload.value.copy(isScanning = false, preview = preview)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableLocalUpload.value = mutableLocalUpload.value.copy(isScanning = false, errorMessage = error.messageForUser())
            }
        }
    }

    fun onLocalUploadKindToggled(kind: WeTrakrUploadKind) {
        val current = mutableLocalUpload.value
        mutableLocalUpload.value = current.copy(
            selected = if (kind in current.selected) current.selected - kind else current.selected + kind
        )
    }

    fun onLocalUploadStarted() {
        val state = mutableLocalUpload.value
        val preview = state.preview ?: return
        val scope = authStore.scope()
        if (scope.profileId != profiles.activeProfileId.value) return
        uploader.start(scope, preview, state.selected)
    }

    fun onLocalUploadCancelled() = uploader.cancel()

    fun onLocalUploadClosed() {
        localUploadJob?.cancel()
        uploader.clearFinished()
        mutableLocalUpload.value = WeTrakrLocalUploadUiState()
    }

    private fun WeTrakrUploadRun.message(): String = when (status) {
        WeTrakrUploadStatus.RUNNING -> context.resources.getQuantityString(
            R.plurals.wetrakr_local_upload_progress, left, percent, left
        )
        WeTrakrUploadStatus.PAUSED -> pause?.message() ?: context.resources.getQuantityString(
            R.plurals.wetrakr_local_upload_progress, left, percent, left
        )
        WeTrakrUploadStatus.DONE -> if (failed > 0) {
            context.getString(R.string.wetrakr_local_upload_done_failed, added, alreadyOnWeTrakr, notFound, failed)
        } else {
            context.getString(R.string.wetrakr_local_upload_done, added, alreadyOnWeTrakr, notFound)
        }
        WeTrakrUploadStatus.CANCELLED -> context.getString(R.string.wetrakr_local_upload_cancelled, added)
        WeTrakrUploadStatus.FAILED -> context.getString(
            R.string.wetrakr_local_upload_failed,
            added,
            (error ?: WeTrakrSyncError.UNAVAILABLE).message()
        )
    }

    private fun WeTrakrSyncPause.message(): String {
        val zone = ZoneId.systemDefault()
        val until = Instant.ofEpochMilli(untilEpochMs)
        val formatter = if (until.atZone(zone).toLocalDate() == LocalDate.now(zone)) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
        return context.getString(
            R.string.wetrakr_paused_until,
            formatter.withZone(zone).format(until),
            context.getString(
                if (reason == WeTrakrSyncError.QUOTA_EXCEEDED) R.string.wetrakr_pause_reason_daily
                else R.string.wetrakr_pause_reason_rate
            )
        )
    }

    private fun Exception.messageForUser(): String =
        if (this is WeTrakrAuthException) error.message() else toWeTrakrSyncError().message()

    private fun WeTrakrAuthError.message(): String = context.getString(when (this) {
        WeTrakrAuthError.MISSING_CLIENT_ID -> R.string.wetrakr_missing_client
        WeTrakrAuthError.INVALID_CLIENT -> R.string.wetrakr_invalid_client
        WeTrakrAuthError.INVALID_RESPONSE -> R.string.wetrakr_invalid_response
        WeTrakrAuthError.CODE_EXPIRED -> R.string.wetrakr_code_expired
        WeTrakrAuthError.ACCESS_DENIED -> R.string.wetrakr_access_denied
        WeTrakrAuthError.TOO_MANY_CODES -> R.string.wetrakr_too_many_codes
        WeTrakrAuthError.AUTHORIZATION_REVOKED -> R.string.wetrakr_authorization_revoked
    })

    private fun WeTrakrSyncError.message(): String = context.getString(when (this) {
        WeTrakrSyncError.UNAVAILABLE -> R.string.wetrakr_unavailable
        WeTrakrSyncError.INVALID_RESPONSE -> R.string.wetrakr_invalid_response
        WeTrakrSyncError.RATE_LIMIT -> R.string.wetrakr_rate_limited
        WeTrakrSyncError.QUOTA_EXCEEDED -> R.string.wetrakr_quota_exceeded
        WeTrakrSyncError.AUTHORIZATION_REVOKED -> R.string.wetrakr_authorization_revoked
    })
}
