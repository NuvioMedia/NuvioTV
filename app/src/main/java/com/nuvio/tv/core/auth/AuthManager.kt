package com.nuvio.tv.core.auth

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.diagnostics.AuthDiagnosticEventListenerFactory
import com.nuvio.tv.core.auth.diagnostics.AuthDiagnosticsSession
import com.nuvio.tv.core.auth.diagnostics.authDiagnosticFilteredBody
import com.nuvio.tv.core.logging.bodySnippetForLog
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.logging.rawForLog
import com.nuvio.tv.core.logging.urlForLog
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.remote.supabase.TvLoginExchangeResult
import com.nuvio.tv.data.remote.supabase.TvLoginPollResult
import com.nuvio.tv.data.remote.supabase.TvLoginStartResult
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val TAG = "AuthManager"
private const val AUTH_ENDPOINT_SIGNUP = "/auth/v1/signup"
private const val AUTH_ENDPOINT_PASSWORD = "/auth/v1/token?grant_type=password"
private const val AUTH_ENDPOINT_REFRESH = "/auth/v1/token?grant_type=refresh_token"
private const val AUTH_ENDPOINT_START_TV_LOGIN = "/rest/v1/rpc/start_tv_login_session"
private const val AUTH_ENDPOINT_POLL_TV_LOGIN = "/rest/v1/rpc/poll_tv_login_session"
private const val AUTH_ENDPOINT_EXCHANGE_TV_LOGIN = "/functions/v1/tv-logins-exchange"
private val tvLoginRequestCounter = AtomicLong(0L)
private val authJsonMediaType = "application/json".toMediaType()

private enum class SessionRefreshResult {
    REFRESHED,
    INVALID_SESSION,
    TRANSIENT_FAILURE
}

private data class SessionRefreshOutcome(
    val result: SessionRefreshResult,
    val error: Throwable? = null
)

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val httpClient: OkHttpClient,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    private val accountLocalDataResetService: AccountLocalDataResetService,
    private val authDiagnosticReportRepository: AuthDiagnosticReportRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val startupAuthLock = Any()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null
    private var startupAuthDiagnostics: AuthDiagnosticsSession? = null
    private var startupAuthCompleted = false

    init {
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val diagnostics = getStartupAuthDiagnostics()
                        val user = auth.currentUserOrNull()
                        val tokenPresent = auth.currentAccessTokenOrNull()?.isNotBlank() == true
                        diagnostics?.recordState(
                            "session_status_authenticated",
                            mapOf(
                                "userId" to user?.id,
                                "emailPresent" to (!user?.email.isNullOrBlank()).toString(),
                                "tokenPresent" to tokenPresent.toString(),
                                "authState" to _authState.value.nameForLog()
                            )
                        )
                        Log.d(TAG, "SessionStatus.Authenticated user=${user?.id.rawForLog()} emailPresent=${!user?.email.isNullOrBlank()} tokenPresent=$tokenPresent")
                        if (user != null) {
                            if (cachedEffectiveUserSourceUserId != user.id) {
                                cachedEffectiveUserId = null
                                cachedEffectiveUserSourceUserId = null
                            }
                            if (user.email.isNullOrBlank()) {
                                handleUnexpectedSignedOut()
                                finishStartupAuthDiagnostics("signed_out", "authenticated_session_missing_email")
                            } else {
                                _authState.value = AuthState.FullAccount(userId = user.id, email = user.email!!)
                                authSessionNoticeDataStore.markNuvioAuthenticated()
                                finishStartupAuthDiagnostics("success", "authenticated_session_restored")
                            }
                        } else {
                            finishStartupAuthDiagnostics("failed", "authenticated_status_without_user")
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val diagnostics = getStartupAuthDiagnostics()
                        val session = auth.currentSessionOrNull()
                        val refreshToken = session?.refreshToken?.takeIf { it.isNotBlank() }
                        diagnostics?.recordState(
                            "session_status_not_authenticated",
                            mapOf(
                                "refreshTokenPresent" to (refreshToken != null).toString(),
                                "authState" to _authState.value.nameForLog()
                            )
                        )
                        Log.d(TAG, "SessionStatus.NotAuthenticated refreshTokenPresent=${refreshToken != null} authState=${_authState.value.nameForLog()}")
                        if (refreshToken != null) {
                            scope.launch {
                                val outcome = refreshCurrentSessionSerialized(
                                    observedRefreshToken = refreshToken,
                                    reason = "Session became unauthenticated",
                                    diagnostics = diagnostics
                                )
                                when (outcome.result) {
                                    SessionRefreshResult.REFRESHED -> {
                                        finishStartupAuthDiagnostics("success", "startup_refresh_token_completed")
                                    }
                                    SessionRefreshResult.INVALID_SESSION -> {
                                        handleUnexpectedSignedOut()
                                        finishStartupAuthDiagnostics("signed_out", "startup_refresh_token_invalid", AUTH_ENDPOINT_REFRESH, outcome.error?.authHttpStatus(), outcome.error)
                                    }
                                    SessionRefreshResult.TRANSIENT_FAILURE -> {
                                        Log.w(TAG, "Session refresh failed transiently; keeping current auth state")
                                        finishStartupAuthDiagnostics("failed", "startup_refresh_token_transient_failure", AUTH_ENDPOINT_REFRESH, outcome.error?.authHttpStatus(), outcome.error)
                                    }
                                }
                            }
                        } else {
                            handleUnexpectedSignedOut()
                            finishStartupAuthDiagnostics("signed_out", "startup_no_refresh_token")
                        }
                    }
                    is SessionStatus.Initializing -> {
                        getStartupAuthDiagnostics()?.recordState(
                            "session_status_initializing",
                            mapOf("authState" to _authState.value.nameForLog())
                        )
                        Log.d(TAG, "SessionStatus.Initializing")
                        _authState.value = AuthState.Loading
                    }
                    else -> {
                        val diagnostics = getStartupAuthDiagnostics()
                        diagnostics?.recordState(
                            "session_status_other",
                            mapOf(
                                "statusClass" to status.javaClass.name,
                                "status" to status.toString(),
                                "authState" to _authState.value.nameForLog()
                            )
                        )
                        finishStartupAuthDiagnostics("failed", "startup_session_status_${status.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun getStartupAuthDiagnostics(): AuthDiagnosticsSession? {
        var created = false
        val diagnostics = synchronized(startupAuthLock) {
            if (startupAuthCompleted) {
                null
            } else {
                startupAuthDiagnostics ?: AuthDiagnosticsSession(authDiagnosticReportRepository, "startup_auth").also {
                    startupAuthDiagnostics = it
                    created = true
                }
            }
        }
        if (created) {
            diagnostics?.recordState(
                "startup_auth_begin",
                mapOf(
                    "supabaseUrl" to BuildConfig.SUPABASE_URL,
                    "authFallbackSupabaseUrl" to BuildConfig.SUPABASE_FALLBACK_URL,
                    "tvLoginWebBaseUrl" to BuildConfig.TV_LOGIN_WEB_BASE_URL,
                    "reportsBaseUrlConfigured" to BuildConfig.PLAYBACK_REPORTS_BASE_URL.isNotBlank().toString(),
                    "authState" to _authState.value.nameForLog()
                )
            )
        }
        return diagnostics
    }

    private fun finishStartupAuthDiagnostics(status: String, reason: String, failingEndpoint: String? = null, httpStatus: Int? = null, error: Throwable? = null) {
        val diagnostics = synchronized(startupAuthLock) {
            if (startupAuthCompleted) {
                null
            } else {
                startupAuthCompleted = true
                startupAuthDiagnostics.also {
                    startupAuthDiagnostics = null
                }
            }
        }
        diagnostics?.let {
            scope.launch {
                it.finishTerminal(status = status, reason = reason, failingEndpoint = failingEndpoint, httpStatus = httpStatus, error = error)
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
        val diagnostics = AuthDiagnosticsSession(authDiagnosticReportRepository, "signup")
        return try {
            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString()
            val body = executeSupabaseJsonRequest(
                diagnostics = diagnostics,
                endpoint = AUTH_ENDPOINT_SIGNUP,
                url = supabaseUrl(AUTH_ENDPOINT_SIGNUP),
                headers = supabaseHeaders(),
                body = payload
            ).body
            runCatching {
                val result = json.decodeFromString<TvLoginExchangeResult>(body)
                auth.importTokenResponse(result)
            }
            diagnostics.finishSuccess("signup_completed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            diagnostics.finishFailure("signup_failed", AUTH_ENDPOINT_SIGNUP, e.authHttpStatus(), e)
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        val diagnostics = AuthDiagnosticsSession(authDiagnosticReportRepository, "password_login")
        return try {
            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString()
            val body = executeSupabaseJsonRequest(
                diagnostics = diagnostics,
                endpoint = AUTH_ENDPOINT_PASSWORD,
                url = supabaseUrl(AUTH_ENDPOINT_PASSWORD),
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "Sign in token response tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            diagnostics.finishSuccess("password_login_completed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            diagnostics.finishFailure("password_login_failed", AUTH_ENDPOINT_PASSWORD, e.authHttpStatus(), e)
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
        ).result == SessionRefreshResult.REFRESHED
    }

    private suspend fun refreshCurrentSessionSerialized(
        observedRefreshToken: String?,
        reason: String,
        diagnostics: AuthDiagnosticsSession? = null
    ): SessionRefreshOutcome = refreshMutex.withLock {
        val currentRefreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
        if (currentRefreshToken == null) {
            Log.w(TAG, "$reason but no refresh token available; cannot refresh session")
            return@withLock SessionRefreshOutcome(SessionRefreshResult.INVALID_SESSION)
        }
        if (observedRefreshToken != null && currentRefreshToken != observedRefreshToken) {
            Log.d(TAG, "$reason; session was already refreshed by another request")
            return@withLock SessionRefreshOutcome(SessionRefreshResult.REFRESHED)
        }
        val refreshDiagnostics = diagnostics ?: AuthDiagnosticsSession(authDiagnosticReportRepository, "refresh_token")
        val ownsDiagnostics = diagnostics == null
        return@withLock try {
            Log.w(TAG, "$reason; refreshing Supabase session")
            val payload = buildJsonObject {
                put("refresh_token", currentRefreshToken)
            }.toString()
            val body = executeSupabaseJsonRequest(
                diagnostics = refreshDiagnostics,
                endpoint = AUTH_ENDPOINT_REFRESH,
                url = supabaseUrl(AUTH_ENDPOINT_REFRESH),
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "Supabase session refresh token response tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            if (ownsDiagnostics) {
                refreshDiagnostics.finishSuccess("refresh_token_completed")
            } else {
                refreshDiagnostics.recordState("refresh_token_completed")
            }
            SessionRefreshOutcome(SessionRefreshResult.REFRESHED)
        } catch (refreshError: Exception) {
            val result = refreshError.toSessionRefreshResult()
            if (ownsDiagnostics) {
                refreshDiagnostics.finishFailure("refresh_token_failed", AUTH_ENDPOINT_REFRESH, refreshError.authHttpStatus(), refreshError)
            } else {
                refreshDiagnostics.recordState(
                    "refresh_token_failed",
                    mapOf(
                        "httpStatus" to refreshError.authHttpStatus()?.toString(),
                        "result" to result.name
                    )
                )
            }
            if (result == SessionRefreshResult.INVALID_SESSION) {
                Log.e(TAG, "Supabase session refresh failed with invalid session", refreshError)
            } else {
                Log.w(TAG, "Supabase session refresh failed transiently", refreshError)
            }
            SessionRefreshOutcome(result, refreshError)
        }
    }

    suspend fun startTvLoginSession(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String,
        traceId: Long? = null,
        diagnostics: AuthDiagnosticsSession? = null
    ): Result<TvLoginStartResult> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        Log.d(TAG, "$trace startTvLoginSession begin nonce=${deviceNonce.rawForLog()} deviceNamePresent=${!deviceName.isNullOrBlank()} redirect=${redirectBaseUrl.urlForLog()}")
        return try {
            val result = startTvLoginSessionRpc(
                deviceNonce = deviceNonce,
                deviceName = deviceName,
                redirectBaseUrl = redirectBaseUrl,
                traceId = traceId,
                diagnostics = diagnostics
            )
            Log.d(TAG, "$trace startTvLoginSession ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} code=${result.code.rawForLog()} url=${result.webUrl.urlForLog()} urlLength=${result.webUrl.length} expiresAt=${result.expiresAt} pollInterval=${result.pollIntervalSeconds}")
            Result.success(result)
        } catch (e: Exception) {
            val message = e.message.orEmpty().lowercase()
            val shouldRetryLegacySignature = !deviceName.isNullOrBlank() &&
                message.contains("could not find the function") &&
                message.contains("start_tv_login_session") &&
                message.contains("p_device_name")

            if (shouldRetryLegacySignature) {
                return try {
                    Log.w(TAG, "$trace start_tv_login_session legacy signature detected; retrying without p_device_name elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}")
                    val result = startTvLoginSessionRpc(
                        deviceNonce = deviceNonce,
                        deviceName = null,
                        redirectBaseUrl = redirectBaseUrl,
                        traceId = traceId,
                        diagnostics = diagnostics
                    )
                    Log.d(TAG, "$trace startTvLoginSession legacy retry ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} code=${result.code.rawForLog()} url=${result.webUrl.urlForLog()} urlLength=${result.webUrl.length} expiresAt=${result.expiresAt} pollInterval=${result.pollIntervalSeconds}")
                    Result.success(result)
                } catch (retryError: Exception) {
                    Log.e(TAG, "$trace startTvLoginSession legacy retry failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${retryError.diagnosticSummary()}", retryError)
                    Result.failure(retryError)
                }
            }

            Log.e(TAG, "$trace startTvLoginSession failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    private suspend fun startTvLoginSessionRpc(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String,
        traceId: Long?,
        diagnostics: AuthDiagnosticsSession?
    ): TvLoginStartResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        val params = buildJsonObject {
            put("p_device_nonce", deviceNonce)
            put("p_redirect_base_url", redirectBaseUrl)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        Log.d(TAG, "$trace rpc start_tv_login_session request nonce=${deviceNonce.rawForLog()} redirect=${redirectBaseUrl.urlForLog()} deviceNamePresent=${!deviceName.isNullOrBlank()}")
        val body = executeSupabaseJsonRequest(
            diagnostics = diagnostics,
            endpoint = AUTH_ENDPOINT_START_TV_LOGIN,
            url = supabaseUrl(AUTH_ENDPOINT_START_TV_LOGIN),
            headers = supabaseHeaders(),
            body = params.toString()
        ).body
        val results = json.decodeFromString<List<TvLoginStartResult>>(body)
        Log.d(TAG, "$trace rpc start_tv_login_session response rows=${results.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} firstCode=${results.firstOrNull()?.code.rawForLog()} firstUrl=${results.firstOrNull()?.webUrl.urlForLog()}")
        return results.firstOrNull()
            ?: throw Exception("Empty response from start_tv_login_session")
    }

    suspend fun pollTvLoginSession(
        code: String,
        deviceNonce: String,
        traceId: Long? = null,
        attempt: Int? = null,
        diagnostics: AuthDiagnosticsSession? = null
    ): Result<TvLoginPollResult> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        return try {
            Log.d(TAG, "$trace pollTvLoginSession begin attempt=${attempt ?: "-"} code=${code.rawForLog()} nonce=${deviceNonce.rawForLog()}")
            val params = buildJsonObject {
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val body = executeSupabaseJsonRequest(
                diagnostics = diagnostics,
                endpoint = AUTH_ENDPOINT_POLL_TV_LOGIN,
                url = supabaseUrl(AUTH_ENDPOINT_POLL_TV_LOGIN),
                headers = supabaseHeaders(),
                body = params.toString()
            ).body
            val results = json.decodeFromString<List<TvLoginPollResult>>(body)
            val result = results.firstOrNull()
                ?: throw Exception("Empty response from poll_tv_login_session")
            Log.d(TAG, "$trace pollTvLoginSession ok attempt=${attempt ?: "-"} rows=${results.size} status=${result.status} expiresAt=${result.expiresAt ?: "-"} pollInterval=${result.pollIntervalSeconds ?: "-"} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "$trace pollTvLoginSession failed attempt=${attempt ?: "-"} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    suspend fun exchangeTvLoginSession(
        code: String,
        deviceNonce: String,
        traceId: Long? = null,
        diagnostics: AuthDiagnosticsSession? = null
    ): Result<Unit> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        return try {
            val payload = buildJsonObject {
                put("code", code)
                put("device_nonce", deviceNonce)
            }.toString()
            val url = supabaseUrl(AUTH_ENDPOINT_EXCHANGE_TV_LOGIN)
            Log.d(TAG, "$trace exchangeTvLoginSession request url=${url.urlForLog()} code=${code.rawForLog()} nonce=${deviceNonce.rawForLog()} payloadBytes=${payload.length}")
            val body = executeSupabaseJsonRequest(
                diagnostics = diagnostics,
                endpoint = AUTH_ENDPOINT_EXCHANGE_TV_LOGIN,
                url = url,
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "$trace exchangeTvLoginSession decoded tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            Log.d(TAG, "$trace exchangeTvLoginSession imported auth token elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "$trace exchangeTvLoginSession failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    private suspend fun executeSupabaseJsonRequest(
        diagnostics: AuthDiagnosticsSession?,
        endpoint: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        method: String = "POST"
    ): AuthHttpResponse {
        val requestId = tvLoginRequestCounter.incrementAndGet()
        return try {
            executeSupabaseJsonRequestAttempt(
                requestId = requestId,
                attempt = "primary",
                diagnostics = diagnostics,
                endpoint = endpoint,
                url = url,
                headers = headers,
                body = body,
                method = method
            )
        } catch (primaryError: Exception) {
            val fallbackUrl = supabaseFallbackUrl(endpoint)
            if (fallbackUrl == null || !primaryError.shouldRetryWithAuthFallback()) {
                throw primaryError
            }
            diagnostics?.recordState(
                "auth_origin_fallback_retry",
                mapOf(
                    "endpoint" to endpoint,
                    "primaryUrl" to url,
                    "fallbackUrl" to fallbackUrl,
                    "reason" to primaryError.diagnosticSummary()
                )
            )
            Log.w(TAG, "auth request #$requestId endpoint=$endpoint retrying origin fallback url=${fallbackUrl.urlForLog()} reason=${primaryError.diagnosticSummary()}")
            try {
                executeSupabaseJsonRequestAttempt(
                    requestId = requestId,
                    attempt = "origin_fallback",
                    diagnostics = diagnostics,
                    endpoint = endpoint,
                    url = fallbackUrl,
                    headers = headers,
                    body = body,
                    method = method
                )
            } catch (fallbackError: Exception) {
                fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private suspend fun executeSupabaseJsonRequestAttempt(
        requestId: Long,
        attempt: String,
        diagnostics: AuthDiagnosticsSession?,
        endpoint: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        method: String
    ): AuthHttpResponse {
        val startedAtMs = SystemClock.elapsedRealtime()
        diagnostics?.recordRequest(endpoint = endpoint, method = method, url = url, headers = headers, body = body)
        Log.d(TAG, "auth request #$requestId attempt=$attempt method=$method endpoint=$endpoint url=${url.urlForLog()} payloadBytes=${body.length}")
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        when (method.uppercase()) {
            "POST" -> requestBuilder.post(body.toRequestBody(authJsonMediaType))
            else -> error("Unsupported auth diagnostics method: $method")
        }
        val request = requestBuilder.build()
        val client = diagnostics?.let {
            httpClient.newBuilder()
                .eventListenerFactory(AuthDiagnosticEventListenerFactory(it, endpoint))
                .build()
        } ?: httpClient
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    val responseContentType = body?.contentType()?.toString()
                    val responseBody = body?.string().orEmpty()
                    diagnostics?.recordResponse(
                        endpoint = endpoint,
                        method = method,
                        url = url,
                        statusCode = response.code,
                        isSuccessful = response.isSuccessful,
                        headers = response.headers.toDiagnosticMap(),
                        body = responseBody,
                        contentType = responseContentType
                    )
                    Log.d(TAG, "auth response #$requestId attempt=$attempt endpoint=$endpoint http=${response.code} success=${response.isSuccessful} bodyBytes=${responseBody.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} body=${authDiagnosticFilteredBody(responseBody).orEmpty().bodySnippetForLog()}")
                    if (!response.isSuccessful) {
                        throw AuthHttpException(endpoint, response.code, responseBody)
                    }
                    AuthHttpResponse(response.code, responseBody)
                }
            }
        } catch (e: Exception) {
            diagnostics?.recordException(endpoint, e)
            Log.e(TAG, "auth request #$requestId attempt=$attempt endpoint=$endpoint failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            throw e
        }
    }

    private fun supabaseUrl(endpoint: String): String =
        "${BuildConfig.SUPABASE_URL.trimEnd('/')}$endpoint"

    private fun supabaseFallbackUrl(endpoint: String): String? {
        val primary = BuildConfig.SUPABASE_URL.trimEnd('/')
        val fallback = BuildConfig.SUPABASE_FALLBACK_URL.trim().trimEnd('/')
        if (fallback.isBlank()) return null
        if (primary.equals(fallback, ignoreCase = true)) return null
        return "$fallback$endpoint"
    }

    private fun supabaseHeaders(accessToken: String? = null): Map<String, String> =
        buildMap {
            put("apikey", BuildConfig.SUPABASE_ANON_KEY)
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) put("Authorization", "Bearer $accessToken")
        }
}

private data class AuthHttpResponse(
    val statusCode: Int,
    val body: String
)

private class AuthHttpException(
    val endpoint: String,
    val statusCode: Int,
    val responseBody: String
) : Exception("Auth request failed endpoint=$endpoint status=$statusCode body=$responseBody")

private fun Headers.toDiagnosticMap(): Map<String, String> =
    names().associateWith { name -> values(name).joinToString(", ") }

private fun qrTrace(traceId: Long?): String =
    if (traceId == null) "QR_LOGIN" else "QR_LOGIN[$traceId]"

private fun AuthState.nameForLog(): String =
    when (this) {
        is AuthState.FullAccount -> "FullAccount(${userId.rawForLog()})"
        AuthState.Loading -> "Loading"
        AuthState.SignedOut -> "SignedOut"
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
    findCause<AuthHttpException>()?.let { error ->
        return if (isInvalidAuthRefreshResponse(error.statusCode, error.responseBody)) {
            SessionRefreshResult.INVALID_SESSION
        } else {
            SessionRefreshResult.TRANSIENT_FAILURE
        }
    }

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
        return if (isInvalidAuthRefreshResponse(status, error.message.orEmpty())) {
            SessionRefreshResult.INVALID_SESSION
        } else {
            SessionRefreshResult.TRANSIENT_FAILURE
        }
    }

    val message = causeMessages().lowercase()
    val invalidMarkers = listOf(
        "invalid refresh token",
        "refresh token is not valid",
        "refresh token not found",
        "refresh_token_not_found",
        "invalid_grant",
        "session not found",
        "session_not_found",
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

private fun Throwable.authHttpStatus(): Int? =
    findCause<AuthHttpException>()?.statusCode

private fun Throwable.shouldRetryWithAuthFallback(): Boolean {
    findCause<AuthHttpException>()?.let { error ->
        return error.statusCode in setOf(408, 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530) ||
            error.responseBody.contains("cloudflare", ignoreCase = true) ||
            error.responseBody.contains("cf-error-code", ignoreCase = true)
    }

    return hasCause<UnknownHostException>() ||
        hasCause<ConnectException>() ||
        hasCause<NoRouteToHostException>() ||
        hasCause<SSLException>()
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
