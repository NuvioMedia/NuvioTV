package com.nuvio.tv.ui.screens.account

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager
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
                _uiState.update { it.copy(isLoading = false, error = "Sign in with an account first.") }
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
                _uiState.update { it.copy(isLoading = false, error = "Sign in with an account first.") }
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
                        authManager.signOut()
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                },
                onFailure = { e ->
                    authManager.signOut()
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
                    qrLoginStatus = "Preparing QR login...",
                    qrLoginExpiresAtMillis = null
                )
            }
            authManager.ensureQrSessionAuthenticated().onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = userFriendlyError(e),
                        qrLoginStatus = "Failed to authenticate device"
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
                            qrLoginStatus = "Scan QR and sign in on your phone",
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
                            qrLoginStatus = "Failed to start QR login"
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
            _uiState.update { it.copy(isLoading = true, error = null, qrLoginStatus = "Signing you in...") }
            authManager.exchangeTvLoginSession(code = code, deviceNonce = nonce).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "exchangeQrLogin: pullRemoteData failed, continuing", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false, qrLoginStatus = "Signed in successfully") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = "Could not complete QR sign in"
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

        return when {
            // PIN errors (from PG RAISE EXCEPTION or any wrapper)
            message.contains("incorrect pin") || message.contains("invalid pin") || message.contains("wrong pin") -> "Incorrect PIN."

            // Sync code errors
            message.contains("expired") -> "Sync code has expired."
            message.contains("invalid") && message.contains("code") -> "Invalid sync code."
            message.contains("not found") || message.contains("no sync code") -> "Sync code not found."
            message.contains("already linked") -> "Device is already linked."
            message.contains("empty response") -> "Something went wrong. Please try again."

            // Auth errors
            message.contains("invalid login credentials") -> "Incorrect email or password."
            message.contains("email not confirmed") -> "Please confirm your email first."
            message.contains("user already registered") -> "An account with this email already exists."
            message.contains("invalid email") -> "Please enter a valid email address."
            message.contains("password") && message.contains("short") -> "Password is too short."
            message.contains("password") && message.contains("weak") -> "Password is too weak."
            message.contains("signup is disabled") -> "Sign up is currently disabled."
            message.contains("rate limit") || message.contains("too many requests") -> "Too many attempts. Please try again later."
            message.contains("tv login") && message.contains("expired") -> "QR login expired. Please try again."
            message.contains("tv login") && message.contains("invalid") -> "Invalid QR login code."
            message.contains("tv login") && message.contains("nonce") -> "This QR login was requested from another device."
            message.contains("start_tv_login_session") && message.contains("could not find the function") ->
                "QR login service is outdated. Reapply TV login SQL setup."
            message.contains("gen_random_bytes") && message.contains("does not exist") ->
                "QR login backend is missing setup. Update TV login SQL setup."
            message.contains("invalid tv login redirect base url") ->
                "QR login URL is misconfigured."
            message.contains("invalid device nonce") ->
                "QR login request was invalid. Please retry."

            // Network errors
            message.contains("unable to resolve host") || message.contains("no address associated") -> "No internet connection."
            message.contains("timeout") || message.contains("timed out") -> "Connection timed out. Please try again."
            message.contains("connection refused") || message.contains("connect failed") -> "Could not connect to server."

            // Auth state
            message.contains("not authenticated") -> "Please sign in first."

            // Supabase HTTP errors (e.g. 404 for missing RPC, 400 for bad params)
            message.contains("404") || message.contains("could not find") -> "Service unavailable. Please try again later."
            message.contains("400") || message.contains("bad request") -> "Invalid request. Please check your input."

            // Fallback
            else -> "An unexpected error occurred."
        }
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
                            "approved" -> "Login approved. Finishing sign in..."
                            "pending" -> "Waiting for approval on your phone..."
                            "expired" -> "QR login expired. Generate a new code."
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
        pluginSyncService.pushToRemote()
        addonSyncService.pushToRemote()
        watchProgressSyncService.pushToRemote()
        librarySyncService.pushToRemote()
        watchedItemsSyncService.pushToRemote()
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            pluginManager.isSyncingFromRemote = true
            val remotePluginUrls = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
            pluginManager.reconcileWithRemoteRepoUrls(
                remoteUrls = remotePluginUrls,
                removeMissingLocal = true
            )
            pluginManager.isSyncingFromRemote = false

            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = true
            )
            addonRepository.isSyncingFromRemote = false

            val isPrimaryProfile = profileManager.activeProfileId.value == 1
            val isTraktConnected = isPrimaryProfile && traktAuthDataStore.isAuthenticated.first()
            Log.d("AccountViewModel", "pullRemoteData: isTraktConnected=$isTraktConnected isPrimaryProfile=$isPrimaryProfile")
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
