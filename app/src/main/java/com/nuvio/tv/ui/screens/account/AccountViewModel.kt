package com.nuvio.tv.ui.screens.account

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.repository.SyncRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncRepository: SyncRepository,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var qrLoginPollJob: Job? = null

    init {
        observeAuthState()
        observeProfileNames()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                _uiState.update {
                    it.copy(
                        authState = state,
                        effectiveOwnerId = if (state is AuthState.SignedOut || state is AuthState.Loading) null else it.effectiveOwnerId,
                        connectedStats = if (state is AuthState.FullAccount) it.connectedStats else null,
                        isStatsLoading = if (state is AuthState.FullAccount) it.isStatsLoading else false
                    )
                }
                updateEffectiveOwnerId(state)
                if (state is AuthState.FullAccount) {
                    loadConnectedStats()
                    loadSyncOverview()
                }
            }
        }
    }

    private fun observeProfileNames() {
        viewModelScope.launch {
            profileManager.profiles.collect { profiles ->
                val current = _uiState.value.syncOverview ?: return@collect
                val updated = current.copy(
                    perProfile = current.perProfile.map { stat ->
                        val local = profiles.firstOrNull { it.id == stat.profileId }
                        if (local != null) {
                            stat.copy(profileName = local.name, avatarColorHex = local.avatarColorHex)
                        } else stat
                    }
                )
                _uiState.update { it.copy(syncOverview = updated) }
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authManager.signUpWithEmail(email, password).fold(
                onSuccess = {
                    pushLocalDataToRemote()
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authManager.signInWithEmail(email, password).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "signIn: pullRemoteData failed, continuing signed-in flow", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun generateSyncCode(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (!authManager.isAuthenticated) {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.account_error_signin_required)) }
                return@launch
            }
            pushLocalDataToRemote()
            syncRepository.generateSyncCode(pin).fold(
                onSuccess = { code ->
                    _uiState.update { it.copy(isLoading = false, generatedSyncCode = code) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun getSyncCode(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            syncRepository.getSyncCode(pin).fold(
                onSuccess = { code ->
                    _uiState.update { it.copy(isLoading = false, generatedSyncCode = code) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun claimSyncCode(code: String, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (!authManager.isAuthenticated) {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.account_error_signin_required)) }
                return@launch
            }
            syncRepository.claimSyncCode(code, pin, Build.MODEL).fold(
                onSuccess = { result ->
                    if (result.success) {
                        authManager.clearEffectiveUserIdCache()
                        pullRemoteData().onFailure { e ->
                            Log.e("AccountViewModel", "claimSyncCode: pullRemoteData failed, continuing", e)
                        }
                        updateEffectiveOwnerId(_uiState.value.authState)
                        _uiState.update { it.copy(isLoading = false, syncClaimSuccess = true) }
                    } else {
                        authManager.signOut(explicit = false)
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                },
                onFailure = { e ->
                    authManager.signOut(explicit = false)
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _uiState.update { it.copy(connectedStats = null, isStatsLoading = false) }
        }
    }

    fun loadLinkedDevices() {
        viewModelScope.launch {
            syncRepository.getLinkedDevices().fold(
                onSuccess = { devices ->
                    _uiState.update { it.copy(linkedDevices = devices) }
                },
                onFailure = { /* silently handle */ }
            )
        }
    }

    fun unlinkDevice(deviceUserId: String) {
        viewModelScope.launch {
            syncRepository.unlinkDevice(deviceUserId)
            loadLinkedDevices()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSyncClaimSuccess() {
        _uiState.update { it.copy(syncClaimSuccess = false) }
    }

    fun clearGeneratedSyncCode() {
        _uiState.update { it.copy(generatedSyncCode = null) }
    }

    fun startQrLogin() {
        viewModelScope.launch {
            cancelQrLoginPolling()
            val nonce = generateDeviceNonce()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    qrLoginCode = null,
                    qrLoginUrl = null,
                    qrLoginNonce = nonce,
                    qrLoginBitmap = null,
                    qrLoginStatus = context.getString(R.string.qr_login_preparing),
                    qrLoginExpiresAtMillis = null
                )
            }
            authManager.ensureQrSessionAuthenticated().onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = userFriendlyError(e),
                        qrLoginStatus = context.getString(R.string.qr_login_device_auth_failed)
                    )
                }
                return@launch
            }
            authManager.startTvLoginSession(
                deviceNonce = nonce,
                deviceName = Build.MODEL,
                redirectBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL
            ).fold(
                onSuccess = { result ->
                    val expiresAtMillis = runCatching { Instant.parse(result.expiresAt).toEpochMilli() }.getOrNull()
                    val qrBitmap = runCatching { QrCodeGenerator.generate(result.webUrl, 420) }.getOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            qrLoginCode = result.code,
                            qrLoginUrl = result.webUrl,
                            qrLoginBitmap = qrBitmap,
                            qrLoginStatus = context.getString(R.string.qr_login_scan_prompt),
                            qrLoginExpiresAtMillis = expiresAtMillis,
                            qrLoginPollIntervalSeconds = result.pollIntervalSeconds.coerceAtLeast(2)
                        )
                    }
                    startQrLoginPolling()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = context.getString(R.string.qr_login_start_failed)
                        )
                    }
                }
            )
        }
    }

    fun pollQrLogin() {
        viewModelScope.launch {
            pollQrLoginOnce()
        }
    }

    fun exchangeQrLogin() {
        viewModelScope.launch {
            val current = _uiState.value
            val code = current.qrLoginCode ?: return@launch
            val nonce = current.qrLoginNonce ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null, qrLoginStatus = context.getString(R.string.qr_login_signing_in)) }
            authManager.exchangeTvLoginSession(code = code, deviceNonce = nonce).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "exchangeQrLogin: pullRemoteData failed, continuing", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false, qrLoginStatus = context.getString(R.string.qr_login_success)) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = context.getString(R.string.qr_login_exchange_failed)
                        )
                    }
                }
            )
        }
    }

    fun clearQrLoginSession() {
        cancelQrLoginPolling()
        _uiState.update {
            it.copy(
                qrLoginCode = null,
                qrLoginUrl = null,
                qrLoginNonce = null,
                qrLoginBitmap = null,
                qrLoginStatus = null,
                qrLoginExpiresAtMillis = null
            )
        }
    }

    private suspend fun updateEffectiveOwnerId(state: AuthState) {
        val currentUserId = when (state) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }
        if (currentUserId == null) return

        val effectiveOwnerId = authManager.getEffectiveUserId() ?: currentUserId
        _uiState.update { it.copy(effectiveOwnerId = effectiveOwnerId) }
    }

    private fun loadConnectedStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isStatsLoading = true) }

            val stats = runCatching {
                val addonsCount = addonRepository.getInstalledAddons().first().size
                val pluginsCount = pluginManager.repositories.first().size
                val libraryCount = libraryPreferences.getAllItems().size
                val watchProgressCount = watchProgressRepository.allProgress.first().size
                AccountConnectedStats(
                    addons = addonsCount,
                    plugins = pluginsCount,
                    library = libraryCount,
                    watchProgress = watchProgressCount
                )
            }.getOrNull()

            _uiState.update {
                it.copy(
                    connectedStats = stats ?: it.connectedStats,
                    isStatsLoading = false
                )
            }
        }
    }

    @Serializable
    private data class SyncOverviewResponse(
        val addons: Map<String, Int> = emptyMap(),
        val plugins: Map<String, Int> = emptyMap(),
        @SerialName("library_items") val libraryItems: Map<String, Int> = emptyMap(),
        @SerialName("watch_progress") val watchProgress: Map<String, Int> = emptyMap(),
        @SerialName("watched_items") val watchedItems: Map<String, Int> = emptyMap(),
        val profiles: Map<String, ProfileInfo> = emptyMap()
    ) {
        @Serializable
        data class ProfileInfo(
            val name: String,
            val color: String
        )
    }

    fun loadSyncOverview() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncOverviewLoading = true) }

            val overview = runCatching {
                val response = postgrest.rpc("get_sync_overview")
                    .decodeAs<SyncOverviewResponse>()

                val allProfileIds = (response.addons.keys + response.plugins.keys +
                    response.libraryItems.keys + response.watchProgress.keys +
                    response.watchedItems.keys + response.profiles.keys)
                    .mapNotNull { it.toIntOrNull() }
                    .distinct()
                    .sorted()

                val localProfiles = profileManager.profiles.value
                val perProfile = allProfileIds.map { pid ->
                    val pidStr = pid.toString()
                    val local = localProfiles.firstOrNull { it.id == pid }
                    val remote = response.profiles[pidStr]
                    ProfileSyncStats(
                        profileId = pid,
                        profileName = local?.name ?: remote?.name ?: "Profile $pid",
                        avatarColorHex = local?.avatarColorHex ?: remote?.color ?: "#1E88E5",
                        addons = response.addons[pidStr] ?: 0,
                        plugins = response.plugins[pidStr] ?: 0,
                        library = response.libraryItems[pidStr] ?: 0,
                        watchProgress = response.watchProgress[pidStr] ?: 0,
                        watchedItems = response.watchedItems[pidStr] ?: 0
                    )
                }

                SyncOverview(
                    profileCount = response.profiles.size,
                    totalAddons = response.addons.values.sum(),
                    totalPlugins = response.plugins.values.sum(),
                    totalLibrary = response.libraryItems.values.sum(),
                    totalWatchProgress = response.watchProgress.values.sum(),
                    totalWatchedItems = response.watchedItems.values.sum(),
                    perProfile = perProfile
                )
            }.getOrNull()

            _uiState.update {
                it.copy(
                    syncOverview = overview ?: it.syncOverview,
                    isSyncOverviewLoading = false
                )
            }
        }
    }

    private fun userFriendlyError(e: Throwable): String {
        val raw = e.message ?: ""
        val message = raw.lowercase()
        val compactRaw = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        Log.w("AccountViewModel", "Raw error: $compactRaw")

        val resId = when {
            // PIN errors (from PG RAISE EXCEPTION or any wrapper)
            message.contains("incorrect pin") || message.contains("invalid pin") || message.contains("wrong pin") -> R.string.account_error_incorrect_pin

            // Sync code errors
            message.contains("expired") -> R.string.account_error_sync_code_expired
            message.contains("invalid") && message.contains("code") -> R.string.account_error_invalid_sync_code
            message.contains("not found") || message.contains("no sync code") -> R.string.account_error_sync_code_not_found
            message.contains("already linked") -> R.string.account_error_device_already_linked
            message.contains("empty response") -> R.string.account_error_generic_retry

            // Auth errors
            message.contains("invalid login credentials") -> R.string.account_error_invalid_credentials
            message.contains("email not confirmed") -> R.string.account_error_email_not_confirmed
            message.contains("user already registered") -> R.string.account_error_email_already_registered
            message.contains("invalid email") -> R.string.account_error_invalid_email
            message.contains("password") && message.contains("short") -> R.string.account_error_password_too_short
            message.contains("password") && message.contains("weak") -> R.string.account_error_password_too_weak
            message.contains("signup is disabled") -> R.string.account_error_signup_disabled
            message.contains("rate limit") || message.contains("too many requests") -> R.string.account_error_rate_limited
            message.contains("tv login") && message.contains("expired") -> R.string.account_error_qr_login_expired
            message.contains("tv login") && message.contains("invalid") -> R.string.account_error_invalid_qr_login
            message.contains("tv login") && message.contains("nonce") -> R.string.account_error_qr_login_other_device
            message.contains("start_tv_login_session") && message.contains("could not find the function") ->
                R.string.account_error_qr_login_outdated
            message.contains("gen_random_bytes") && message.contains("does not exist") ->
                R.string.account_error_qr_login_missing_setup
            message.contains("invalid tv login redirect base url") ->
                R.string.account_error_qr_login_misconfigured
            message.contains("invalid device nonce") ->
                R.string.account_error_qr_login_invalid_request

            // Network errors
            message.contains("unable to resolve host") || message.contains("no address associated") -> R.string.account_error_no_internet
            message.contains("timeout") || message.contains("timed out") -> R.string.account_error_connection_timeout
            message.contains("connection refused") || message.contains("connect failed") -> R.string.account_error_connection_refused

            // Auth state
            message.contains("not authenticated") -> R.string.account_error_not_authenticated

            // Supabase HTTP errors (e.g. 404 for missing RPC, 400 for bad params)
            message.contains("404") || message.contains("could not find") -> R.string.account_error_service_unavailable
            message.contains("400") || message.contains("bad request") -> R.string.account_error_invalid_request

            // Fallback
            else -> R.string.account_error_unexpected
        }
        return context.getString(resId)
    }

    private fun startQrLoginPolling() {
        cancelQrLoginPolling()
        qrLoginPollJob = viewModelScope.launch {
            while (isActive) {
                val interval = _uiState.value.qrLoginPollIntervalSeconds.coerceAtLeast(2)
                delay(interval * 1000L)
                pollQrLoginOnce()
            }
        }
    }

    private fun cancelQrLoginPolling() {
        qrLoginPollJob?.cancel()
        qrLoginPollJob = null
    }

    private fun generateDeviceNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private suspend fun pollQrLoginOnce() {
        val current = _uiState.value
        val code = current.qrLoginCode ?: return
        val nonce = current.qrLoginNonce ?: return
        authManager.pollTvLoginSession(code = code, deviceNonce = nonce).fold(
            onSuccess = { result ->
                val normalizedStatus = result.status.lowercase()
                val expiresAtMillis = result.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                _uiState.update {
                    it.copy(
                        qrLoginStatus = when (normalizedStatus) {
                            "approved" -> context.getString(R.string.qr_login_approved)
                            "pending" -> context.getString(R.string.qr_login_pending)
                            "expired" -> context.getString(R.string.qr_login_expired)
                            else -> "Status: ${result.status}"
                        },
                        qrLoginExpiresAtMillis = expiresAtMillis ?: it.qrLoginExpiresAtMillis,
                        qrLoginPollIntervalSeconds = (result.pollIntervalSeconds ?: it.qrLoginPollIntervalSeconds).coerceAtLeast(2)
                    )
                }
                when (normalizedStatus) {
                    "approved" -> {
                        cancelQrLoginPolling()
                        exchangeQrLogin()
                    }
                    "expired", "used", "cancelled" -> cancelQrLoginPolling()
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = userFriendlyError(e)) }
            }
        )
    }

    private suspend fun pushLocalDataToRemote() {
        profileSettingsSyncService.pushCurrentProfileToRemote()
        pluginSyncService.pushToRemote()
        addonSyncService.pushToRemote()
        watchProgressSyncService.pushToRemote()
        librarySyncService.pushToRemote()
        watchedItemsSyncService.pushToRemote()
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            profileSettingsSyncService.pullCurrentProfileFromRemote()
            pluginManager.isSyncingFromRemote = true
            val remotePlugins = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
            pluginManager.reconcileWithRemoteRepoUrls(
                remotePlugins = remotePlugins,
                removeMissingLocal = true
            )
            pluginManager.isSyncingFromRemote = false
            pluginManager.flushPendingSync()

            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = true
            )
            addonRepository.isSyncingFromRemote = false

            val isTraktConnected = traktAuthDataStore.isEffectivelyAuthenticated.first()
            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            Log.d(
                "AccountViewModel",
                "pullRemoteData: isTraktConnected=$isTraktConnected shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (!isTraktConnected) {
                watchProgressRepository.isSyncingFromRemote = true
                val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteEntries.size} watch progress entries")
                watchProgressPreferences.replaceWithRemoteEntries(remoteEntries.toMap())
                Log.d("AccountViewModel", "pullRemoteData: reconciled local watch progress with ${remoteEntries.size} remote entries")
                watchProgressRepository.isSyncingFromRemote = false

                libraryRepository.isSyncingFromRemote = true
                librarySyncService.pullFromRemote().fold(
                    onSuccess = { remoteLibraryItems ->
                        Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteLibraryItems.size} library items")
                        libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                        Log.d("AccountViewModel", "pullRemoteData: reconciled local library with ${remoteLibraryItems.size} remote items")
                    },
                    onFailure = { e ->
                        Log.e("AccountViewModel", "pullRemoteData: failed to pull library items", e)
                    }
                )
                libraryRepository.isSyncingFromRemote = false

                val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteWatchedItems.size} watched items")
                watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                Log.d("AccountViewModel", "pullRemoteData: reconciled local watched items with ${remoteWatchedItems.size} remote items")
            } else if (shouldUseSupabaseWatchProgressSync) {
                watchProgressRepository.isSyncingFromRemote = true
                val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteEntries.size} watch progress entries in Trakt mode")
                watchProgressPreferences.replaceWithRemoteEntries(remoteEntries.toMap())
                Log.d("AccountViewModel", "pullRemoteData: replaced local watch progress with ${remoteEntries.size} remote entries")
                watchProgressRepository.isSyncingFromRemote = false

                val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteWatchedItems.size} watched items in Trakt mode")
                watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                Log.d("AccountViewModel", "pullRemoteData: reconciled local watched items with ${remoteWatchedItems.size} remote items")
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            return Result.failure(e)
        }
    }

    override fun onCleared() {
        cancelQrLoginPolling()
        super.onCleared()
    }
}
