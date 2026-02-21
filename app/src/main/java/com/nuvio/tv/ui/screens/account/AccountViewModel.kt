@file:OptIn(ExperimentalTvMaterial3Api::class)

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
                if (state is AuthState.FullAccount || state is AuthState.Anonymous) {
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
                authManager.signInAnonymously().onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                    return@launch
                }
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
                authManager.signInAnonymously().onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                    return@launch
                }
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
                    qrLoginStatus = "Preparando inicio de sesión por QR...",
                    qrLoginExpiresAtMillis = null
                )
            }
            if (!authManager.isAuthenticated) {
                authManager.signInAnonymously().onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = "Fallo al autenticar el dispositivo"
                        )
                    }
                    return@launch
                }
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
                            qrLoginStatus = "Escanea el QR e inicia sesión en tu teléfono",
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
                            qrLoginStatus = "Fallo al iniciar sesión por QR"
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
            _uiState.update { it.copy(isLoading = true, error = null, qrLoginStatus = "Iniciando sesión...") }
            authManager.exchangeTvLoginSession(code = code, deviceNonce = nonce).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "exchangeQrLogin: pullRemoteData failed, continuing", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false, qrLoginStatus = "Sesión iniciada correctamente") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = "No se pudo completar el inicio de sesión por QR"
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
            is AuthState.Anonymous -> state.userId
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
                        profileName = local?.name ?: remote?.name ?: "Perfil $pid",
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
            // PIN errors
            message.contains("incorrect pin") || message.contains("invalid pin") || message.contains("wrong pin") -> "PIN incorrecto."

            // Sync code errors
            message.contains("expired") -> "El código de sincronización ha expirado."
            message.contains("invalid") && message.contains("code") -> "Código de sincronización inválido."
            message.contains("not found") || message.contains("no sync code") -> "Código de sincronización no encontrado."
            message.contains("already linked") -> "El dispositivo ya está vinculado."
            message.contains("empty response") -> "Algo salió mal. Por favor, inténtalo de nuevo."

            // Auth errors
            message.contains("invalid login credentials") -> "Correo o contraseña incorrectos."
            message.contains("email not confirmed") -> "Por favor, confirma tu correo primero."
            message.contains("user already registered") -> "Ya existe una cuenta con este correo."
            message.contains("invalid email") -> "Por favor, ingresa un correo electrónico válido."
            message.contains("password") && message.contains("short") -> "La contraseña es muy corta."
            message.contains("password") && message.contains("weak") -> "La contraseña es muy débil."
            message.contains("signup is disabled") -> "El registro está deshabilitado actualmente."
            message.contains("rate limit") || message.contains("too many requests") -> "Demasiados intentos. Por favor, inténtalo más tarde."
            message.contains("tv login") && message.contains("expired") -> "El inicio de sesión por QR expiró. Por favor, inténtalo de nuevo."
            message.contains("tv login") && message.contains("invalid") -> "Código de inicio de sesión QR inválido."
            message.contains("tv login") && message.contains("nonce") -> "Este inicio de sesión por QR se solicitó desde otro dispositivo."
            message.contains("start_tv_login_session") && message.contains("could not find the function") ->
                "El servicio de inicio de sesión QR está desactualizado. Vuelve a aplicar la configuración SQL."
            message.contains("gen_random_bytes") && message.contains("does not exist") ->
                "Falta configuración en el servidor para el QR. Actualiza la configuración SQL."
            message.contains("invalid tv login redirect base url") ->
                "La URL de inicio de sesión QR está mal configurada."
            message.contains("invalid device nonce") ->
                "La solicitud de QR fue inválida. Por favor, reintenta."

            // Network errors
            message.contains("unable to resolve host") || message.contains("no address associated") -> "Sin conexión a internet."
            message.contains("timeout") || message.contains("timed out") -> "Tiempo de espera agotado. Por favor, inténtalo de nuevo."
            message.contains("connection refused") || message.contains("connect failed") -> "No se pudo conectar al servidor."

            // Auth state
            message.contains("not authenticated") -> "Por favor, inicia sesión primero."

            // Supabase HTTP errors
            message.contains("404") || message.contains("could not find") -> "Servicio no disponible. Por favor, inténtalo más tarde."
            message.contains("400") || message.contains("bad request") -> "Solicitud inválida. Por favor, revisa los datos ingresados."

            // Fallback
            else -> "Ocurrió un error inesperado."
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
                            "approved" -> "Inicio de sesión aprobado. Finalizando..."
                            "pending" -> "Esperando aprobación en tu teléfono..."
                            "expired" -> "El inicio de sesión por QR expiró. Genera un nuevo código."
                            else -> "Estado: ${result.status}"
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
                removeMissingLocal = false
            )
            pluginManager.isSyncingFromRemote = false

            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = false
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