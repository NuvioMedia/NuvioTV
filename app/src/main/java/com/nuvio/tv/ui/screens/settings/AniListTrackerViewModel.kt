package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.anilist.AniListAuthError
import com.nuvio.tv.data.anilist.AniListAuthRepository
import com.nuvio.tv.data.anilist.AniListQrError
import com.nuvio.tv.data.anilist.AniListQrException
import com.nuvio.tv.data.anilist.AniListQrSession
import com.nuvio.tv.data.anilist.AniListSyncError
import com.nuvio.tv.data.anilist.AniListSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AniListTrackerUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val session: AniListQrSession? = null,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class AniListTrackerViewModel @Inject constructor(
    private val auth: AniListAuthRepository,
    private val sync: AniListSyncRepository,
    profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val actionError = MutableStateFlow<String?>(null)
    private val starting = MutableStateFlow(false)

    val uiState = combine(
        combine(auth.state, auth.session, profiles.activeProfileId, ::Triple),
        sync.state,
        actionError,
        starting
    ) { (authorization, session, profileId), syncState, error, isStarting ->
        val current = authorization.scope.profileId == profileId
        val currentSync = syncState.takeIf { it.scope == authorization.scope && current }
        val connected = current && authorization.isAuthenticated
        val currentSession = session?.takeIf { current && it.scope == authorization.scope && !connected }
        AniListTrackerUiState(
            isConnected = connected,
            username = authorization.viewer?.name.takeIf { current },
            session = currentSession,
            credentialsConfigured = auth.hasRequiredCredentials(),
            isLoading = isStarting || (authorization.isBusy && current),
            isSyncing = currentSync?.isLoading == true,
            statusMessage = when {
                currentSync?.isLoading == true -> context.getString(R.string.anilist_status_syncing)
                connected && currentSync?.snapshot?.checkedAtEpochMs != null -> context.getString(
                    R.string.anilist_last_synced,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(currentSync.snapshot.checkedAtEpochMs))
                )
                currentSession != null -> context.getString(R.string.anilist_status_waiting)
                else -> null
            },
            errorMessage = error ?: authorization.error?.takeIf { current }?.message()
                ?: currentSync?.error?.message()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AniListTrackerUiState(credentialsConfigured = auth.hasRequiredCredentials()))

    fun onConnect() {
        if (starting.value || auth.state.value.isAuthenticated) return
        actionError.value = null
        starting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                auth.startQrLogin()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AniListQrException) {
                actionError.value = error.error.message()
            } catch (_: Exception) {
                actionError.value = context.getString(R.string.anilist_server_unavailable)
            } finally {
                starting.value = false
            }
        }
    }

    fun onCancel() {
        actionError.value = null
        viewModelScope.launch(Dispatchers.IO) { auth.stopQrLogin() }
    }

    fun onDisconnect() {
        actionError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            auth.disconnect()
            sync.ensureLoaded()
        }
    }

    fun onSyncNow() = sync.refreshAsync(TrackingRefreshIntent.USER_INITIATED)

    fun onAccountOpened() {
        auth.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }

    override fun onCleared() {
        if (!auth.state.value.isAuthenticated) auth.stopQrLogin()
        super.onCleared()
    }

    private fun AniListQrError.message(): String = context.getString(when (this) {
        AniListQrError.MISSING_CLIENT_ID -> R.string.anilist_missing_client
        AniListQrError.NO_NETWORK -> R.string.anilist_no_network
        AniListQrError.SERVER_UNAVAILABLE -> R.string.anilist_server_unavailable
    })

    private fun AniListAuthError.message(): String = context.getString(when (this) {
        AniListAuthError.MISSING_CLIENT_ID -> R.string.anilist_missing_client
        AniListAuthError.ACCESS_DENIED -> R.string.anilist_auth_denied
        AniListAuthError.INVALID_CALLBACK -> R.string.anilist_auth_invalid
        AniListAuthError.AUTHORIZATION_EXPIRED,
        AniListAuthError.AUTHORIZATION_REVOKED -> R.string.anilist_auth_revoked
    })

    private fun AniListSyncError.message(): String = context.getString(when (this) {
        AniListSyncError.UNAVAILABLE -> R.string.anilist_unavailable
        AniListSyncError.RATE_LIMIT -> R.string.anilist_rate_limited
        AniListSyncError.AUTHORIZATION_REVOKED -> R.string.anilist_auth_revoked
    })
}
