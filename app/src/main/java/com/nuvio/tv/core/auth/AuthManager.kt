package com.nuvio.tv.core.auth

import android.util.Log
import com.nuvio.tv.core.network.SyncBackendRepository
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.remote.supabase.TvLoginExchangeResult
import com.nuvio.tv.data.remote.supabase.TvLoginPollResult
import com.nuvio.tv.data.remote.supabase.TvLoginStartResult
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val TAG = "AuthManager"

private enum class SessionRefreshResult {
    REFRESHED,
    INVALID_SESSION,
    TRANSIENT_FAILURE
}

@Singleton
class AuthManager @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val syncBackendRepository: SyncBackendRepository,
    private val httpClient: OkHttpClient,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    private val accountLocalDataResetService: AccountLocalDataResetService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null

    private val auth
        get() = supabaseProvider.auth

    private val postgrest
        get() = supabaseProvider.postgrest

    init {
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            syncBackendRepository.ensureLoaded()
            syncBackendRepository.state.collectLatest { backendState ->
                if (!backendState.isLoaded) return@collectLatest
                auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val user = auth.currentUserOrNull()
                            if (user != null) {
                                if (cachedEffectiveUserSourceUserId != user.id) {
                                    cachedEffectiveUserId = null
                                    cachedEffectiveUserSourceUserId = null
                                }
                                if (user.email.isNullOrBlank()) {
                                    handleUnexpectedSignedOut()
                                } else {
                                    _authState.value = AuthState.FullAccount(userId = user.id, email = user.email!!)
                                    authSessionNoticeDataStore.markNuvioAuthenticated()
                                }
                            }
                        }
                        is SessionStatus.NotAuthenticated -> {
                            val session = auth.currentSessionOrNull()
                            val refreshToken = session?.refreshToken?.takeIf { it.isNotBlank() }
                            if (refreshToken != null) {
                                scope.launch {
                                    when (refreshCurrentSessionSerialized(
                                            observedRefreshToken = refreshToken,
                                            reason = "Session became unauthenticated"
                                        )
                                    ) {
                                        SessionRefreshResult.REFRESHED -> Unit
                                        SessionRefreshResult.INVALID_SESSION -> handleUnexpectedSignedOut()
                                        SessionRefreshResult.TRANSIENT_FAILURE -> {
                                            Log.w(TAG, "Session refresh failed transiently; keeping current auth state")
                                        }
                                    }
                                }
                            } else {
                                handleUnexpectedSignedOut()
                            }
                        }
                        is SessionStatus.Initializing -> {
                            _authState.value = AuthState.Loading
                        }
                        else -> { /* NetworkError etc. — keep current state */ }
                    }
                }
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val currentUserId: String?
        get() = when (val state = _authState.value) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }

    /**
     * Returns the effective user ID for data operations.
     * For sync-linked devices, this returns the sync owner's user ID.
     * For direct users, returns their own user ID.
     */
    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? {
        val userId = currentUserId ?: return null
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        cachedEffectiveUserId?.let { return it }

        suspend fun resolveAndCache(): String {
            val result = postgrest.rpc("get_sync_owner")
            val effectiveId = result.decodeAs<String>()
            cachedEffectiveUserId = effectiveId
            cachedEffectiveUserSourceUserId = userId
            return effectiveId
        }

        return try {
            resolveAndCache()
        } catch (e: Exception) {
            if (refreshSessionIfJwtExpired(e)) {
                return try {
                    resolveAndCache()
                } catch (retryError: Exception) {
                    if (fallbackToOwnIdOnFailure) {
                        Log.e(TAG, "Failed to get effective user ID after refresh; falling back to own ID", retryError)
                        userId
                    } else {
                        Log.e(TAG, "Failed to get effective user ID after refresh", retryError)
                        null
                    }
                }
            }

            if (fallbackToOwnIdOnFailure) {
                Log.e(TAG, "Failed to get effective user ID, falling back to own ID", e)
                userId
            } else {
                Log.e(TAG, "Failed to get effective user ID", e)
                null
            }
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * QR login RPCs currently require an authenticated Supabase session.
     * This creates/reuses an anonymous session only for the QR flow while
     * keeping app-level auth state exposed as SignedOut until a full account exists.
     */
    suspend fun ensureQrSessionAuthenticated(): Result<Unit> {
        val user = auth.currentUserOrNull()
        val hasToken = auth.currentAccessTokenOrNull() != null

        if (user != null && hasToken) {
            return Result.success(Unit)
        }

        return try {
            auth.signInAnonymously()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "QR anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(explicit: Boolean = true) {
        if (explicit) {
            authSessionNoticeDataStore.markNuvioExplicitLogout()
        } else {
            authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()
        }
        try {
            auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        }
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        accountLocalDataResetService.clearAfterSignOut()
    }

    suspend fun resetForSyncBackendChange(): Result<Unit> {
        return runCatching {
            authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()
            try {
                auth.signOut()
            } catch (e: Exception) {
                Log.w(TAG, "Sign out failed while resetting for sync backend change; continuing local reset", e)
            }
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
            _authState.value = AuthState.SignedOut
            accountLocalDataResetService.clearAfterSignOut()
        }
    }

    fun clearEffectiveUserIdCache() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    private suspend fun handleUnexpectedSignedOut() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        if (authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()) {
            accountLocalDataResetService.clearAfterSignOut()
        }
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        if (!error.isJwtExpiredError()) return false
        val refreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "JWT expired but no refresh token available; cannot refresh session")
                return false
            }
        return refreshCurrentSessionSerialized(
            observedRefreshToken = refreshToken,
            reason = "JWT expired"
        ) == SessionRefreshResult.REFRESHED
    }

    private suspend fun refreshCurrentSessionSerialized(
        observedRefreshToken: String?,
        reason: String
    ): SessionRefreshResult = refreshMutex.withLock {
        val currentRefreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
        if (currentRefreshToken == null) {
            Log.w(TAG, "$reason but no refresh token available; cannot refresh session")
            return@withLock SessionRefreshResult.INVALID_SESSION
        }
        if (observedRefreshToken != null && currentRefreshToken != observedRefreshToken) {
            Log.d(TAG, "$reason; session was already refreshed by another request")
            return@withLock SessionRefreshResult.REFRESHED
        }
        return@withLock try {
            Log.w(TAG, "$reason; refreshing Supabase session")
            auth.refreshCurrentSession()
            SessionRefreshResult.REFRESHED
        } catch (refreshError: Exception) {
            val result = refreshError.toSessionRefreshResult()
            if (result == SessionRefreshResult.INVALID_SESSION) {
                Log.e(TAG, "Supabase session refresh failed with invalid session", refreshError)
            } else {
                Log.w(TAG, "Supabase session refresh failed transiently", refreshError)
            }
            result
        }
    }

    suspend fun startTvLoginSession(deviceNonce: String, deviceName: String?, redirectBaseUrl: String): Result<TvLoginStartResult> {
        return try {
            Result.success(
                startTvLoginSessionRpc(
                    deviceNonce = deviceNonce,
                    deviceName = deviceName,
                    redirectBaseUrl = redirectBaseUrl
                )
            )
        } catch (e: Exception) {
            val message = e.message.orEmpty().lowercase()
            val shouldRetryLegacySignature = !deviceName.isNullOrBlank() &&
                message.contains("could not find the function") &&
                message.contains("start_tv_login_session") &&
                message.contains("p_device_name")

            if (shouldRetryLegacySignature) {
                return try {
                    Log.w(TAG, "start_tv_login_session legacy signature detected; retrying without p_device_name")
                    Result.success(
                        startTvLoginSessionRpc(
                            deviceNonce = deviceNonce,
                            deviceName = null,
                            redirectBaseUrl = redirectBaseUrl
                        )
                    )
                } catch (retryError: Exception) {
                    Log.e(TAG, "Failed to start TV login session after legacy retry", retryError)
                    Result.failure(retryError)
                }
            }

            Log.e(TAG, "Failed to start TV login session", e)
            Result.failure(e)
        }
    }

    private suspend fun startTvLoginSessionRpc(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): TvLoginStartResult {
        val params = buildJsonObject {
            put("p_device_nonce", deviceNonce)
            put("p_redirect_base_url", redirectBaseUrl)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        val response = postgrest.rpc("start_tv_login_session", params)
        return response.decodeList<TvLoginStartResult>().firstOrNull()
            ?: throw Exception("Empty response from start_tv_login_session")
    }

    suspend fun pollTvLoginSession(code: String, deviceNonce: String): Result<TvLoginPollResult> {
        return try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val response = postgrest.rpc("poll_tv_login_session", params)
            val result = response.decodeList<TvLoginPollResult>().firstOrNull()
                ?: return Result.failure(Exception("Empty response from poll_tv_login_session"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll TV login session", e)
            Result.failure(e)
        }
    }

    suspend fun exchangeTvLoginSession(code: String, deviceNonce: String): Result<Unit> {
        return try {
            val token = auth.currentAccessTokenOrNull()
                ?: return Result.failure(Exception("Not authenticated"))
            val payload = buildJsonObject {
                put("code", code)
                put("device_nonce", deviceNonce)
            }.toString()
            val backend = supabaseProvider.selectedBackend
            val request = Request.Builder()
                .url("${backend.normalizedSupabaseUrl}/functions/v1/tv-logins-exchange")
                .header("apikey", backend.anonKey)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val body = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("TV login exchange failed (${response.code}): $responseBody")
                    }
                    responseBody
                }
            }
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            auth.importAuthToken(result.accessToken, result.refreshToken)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exchange TV login session", e)
            Result.failure(e)
        }
    }
}

private fun Throwable.isJwtExpiredError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("jwt expired", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}

private fun Throwable.toSessionRefreshResult(): SessionRefreshResult {
    if (hasCause<HttpRequestTimeoutException>() ||
        hasCause<ServerResponseException>() ||
        hasCause<UnknownHostException>() ||
        hasCause<SocketTimeoutException>() ||
        hasCause<ConnectException>() ||
        hasCause<NoRouteToHostException>() ||
        hasCause<SSLException>() ||
        hasCause<IOException>()
    ) {
        return SessionRefreshResult.TRANSIENT_FAILURE
    }

    findCause<ClientRequestException>()?.let { error ->
        val status = error.response.status.value
        return when (status) {
            400, 401, 403 -> SessionRefreshResult.INVALID_SESSION
            408, 429 -> SessionRefreshResult.TRANSIENT_FAILURE
            else -> SessionRefreshResult.TRANSIENT_FAILURE
        }
    }

    val message = causeMessages().lowercase()
    val invalidMarkers = listOf(
        "invalid refresh token",
        "refresh token not found",
        "refresh_token_not_found",
        "invalid_grant",
        "session not found",
        "invalid session",
        "invalid token"
    )
    if (invalidMarkers.any { marker -> message.contains(marker) }) {
        return SessionRefreshResult.INVALID_SESSION
    }

    val transientMarkers = listOf(
        "timeout",
        "timed out",
        "unable to resolve host",
        "failed to connect",
        "connection reset",
        "connection refused",
        "network",
        "server error",
        "service unavailable",
        "502",
        "503",
        "504"
    )
    if (transientMarkers.any { marker -> message.contains(marker) }) {
        return SessionRefreshResult.TRANSIENT_FAILURE
    }

    return SessionRefreshResult.TRANSIENT_FAILURE
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    findCause<T>() != null

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun Throwable.causeMessages(): String {
    val messages = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null) {
        current.message?.let(messages::add)
        current = current.cause
    }
    return messages.joinToString(" ")
}
