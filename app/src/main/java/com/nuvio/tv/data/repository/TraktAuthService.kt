package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktTokenPollResult {
    data object Pending : TraktTokenPollResult
    data object AlreadyUsed : TraktTokenPollResult
    data object Expired : TraktTokenPollResult
    data object Denied : TraktTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : TraktTokenPollResult
    data class Approved(val username: String?) : TraktTokenPollResult
    data class Failed(val reason: String) : TraktTokenPollResult
}

@Singleton
class TraktAuthService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore
) {
    private val refreshLeewaySeconds = 60L
    private val writeRequestMutex = Mutex()
    private val tokenRefreshMutex = Mutex()
    private var lastWriteRequestAtMs = 0L
    private val minWriteIntervalMs = 1_000L
    private val transientRetryStatusCodes = setOf(502, 503, 504, 520, 521, 522)
    private val nonRetryableStatusCodes = setOf(400, 403, 404, 405, 409, 412, 420, 422, 423, 426)

    @Volatile private var circuitOpenUntilMs = 0L
    private val circuitFailures = AtomicInteger(0)
    private val circuitBaseCooldownMs = 5 * 60_000L
    private val circuitMaxCooldownMs = 60 * 60_000L

    private val rateLimitWindowMs = 5 * 60_000L
    private val rateLimitMaxCalls = 900
    private val getRequestTimestamps = ArrayDeque<Long>()
    private val rateLimitMutex = Mutex()

    private fun isCircuitOpen(): Boolean {
        return System.currentTimeMillis() < circuitOpenUntilMs
    }

    fun isCircuitClosed(): Boolean = !isCircuitOpen()

    private fun tripCircuit(reason: String) {
        val failures = circuitFailures.incrementAndGet()
        val cooldownMs = (circuitBaseCooldownMs shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(circuitMaxCooldownMs)
        circuitOpenUntilMs = System.currentTimeMillis() + cooldownMs
        Log.w("TraktAuthService",
            "Circuit breaker OPEN: $reason (failures=$failures, cooldown=${cooldownMs / 1000}s)")
    }

    private fun resetCircuit() {
        if (circuitFailures.get() > 0) {
            trace("Circuit breaker reset after successful request")
        }
        circuitFailures.set(0)
        circuitOpenUntilMs = 0L
    }

    private suspend fun acquireGetRateSlot() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val windowStart = now - rateLimitWindowMs
            while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < windowStart) {
                getRequestTimestamps.removeFirst()
            }
            if (getRequestTimestamps.size >= rateLimitMaxCalls) {
                val oldestInWindow = getRequestTimestamps.first()
                val waitMs = oldestInWindow + rateLimitWindowMs - now + 100L
                if (waitMs > 0) {
                    Log.w("TraktAuthService",
                        "GET rate limit approaching (${getRequestTimestamps.size}/$rateLimitMaxCalls in window), delaying ${waitMs}ms")
                    delay(waitMs)
                }
                val afterDelay = System.currentTimeMillis()
                val newWindowStart = afterDelay - rateLimitWindowMs
                while (getRequestTimestamps.isNotEmpty() && getRequestTimestamps.first() < newWindowStart) {
                    getRequestTimestamps.removeFirst()
                }
            }
            getRequestTimestamps.addLast(System.currentTimeMillis())
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("TraktAuthService", message)
        }
    }

    private fun traktRedirectUri(): String {
        return BuildConfig.TRAKT_REDIRECT_URI.ifBlank { "urn:ietf:wg:oauth:2.0:oob" }
    }

    fun hasRequiredCredentials(): Boolean {
        return BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
    }

    suspend fun getCurrentAuthState(): TraktAuthState = traktAuthDataStore.state.first()

    suspend fun startDeviceAuth(): Result<TraktDeviceCodeResponseDto> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Missing TRAKT credentials"))
        }

        val response = try {
            traktApi.requestDeviceCode(
                TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID)
            )
        } catch (e: IOException) {
            return Result.failure(IllegalStateException("Network error, please try again"))
        }

        val body = response.body()
        if (response.isSuccessful && body != null) {
            traktAuthDataStore.saveDeviceFlow(body)
            return Result.success(body)
        }

        if (response.code() == 429) {
            val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
            val minutes = ((retryAfter ?: 300L) + 59L) / 60L
            return Result.failure(
                IllegalStateException("Trakt is rate limiting requests. Try again in ~${minutes} min")
            )
        }

        return Result.failure(
            IllegalStateException("Failed to start Trakt auth (${response.code()})")
        )
    }

    suspend fun pollDeviceToken(): TraktTokenPollResult {
        if (!hasRequiredCredentials()) {
            return TraktTokenPollResult.Failed("Missing TRAKT credentials")
        }

        val state = getCurrentAuthState()
        val deviceCode = state.deviceCode
        if (deviceCode.isNullOrBlank()) {
            return TraktTokenPollResult.Failed("No active Trakt device code")
        }

        val response = try {
            traktApi.requestDeviceToken(
                TraktDeviceTokenRequestDto(
                    code = deviceCode,
                    clientId = BuildConfig.TRAKT_CLIENT_ID,
                    clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                )
            )
        } catch (e: IOException) {
            return TraktTokenPollResult.Failed("Network error, will retry")
        }

        val tokenBody = response.body()
        if (response.isSuccessful && tokenBody != null) {
            traktAuthDataStore.saveToken(tokenBody)
            traktAuthDataStore.clearDeviceFlow()
            val user = fetchUserSettings()
            return TraktTokenPollResult.Approved(user)
        }

        return when (response.code()) {
            400 -> TraktTokenPollResult.Pending
            409 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.AlreadyUsed
            }
            404 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Failed("Invalid device code")
            }
            410 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Expired
            }
            418 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Denied
            }
            429 -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                traktAuthDataStore.updatePollInterval(nextInterval)
                TraktTokenPollResult.SlowDown(nextInterval)
            }
            else -> TraktTokenPollResult.Failed("Token polling failed (${response.code()})")
        }
    }

    suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean {
        if (!hasRequiredCredentials()) return false

        return tokenRefreshMutex.withLock {
            val state = getCurrentAuthState()
            val refreshToken = state.refreshToken ?: return@withLock false
            if (!force && !isTokenExpiredOrExpiring(state)) {
                trace("refreshTokenIfNeeded: token still valid, skip refresh")
                return@withLock true
            }

            trace("refreshTokenIfNeeded: refreshing token (force=$force)")
            val response = try {
                traktApi.refreshToken(
                    TraktRefreshTokenRequestDto(
                        refreshToken = refreshToken,
                        clientId = BuildConfig.TRAKT_CLIENT_ID,
                        clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                        redirectUri = traktRedirectUri()
                    )
                )
            } catch (e: IOException) {
                Log.w("TraktAuthService", "Network error while refreshing token", e)
                return@withLock false
            }

            val tokenBody = response.body()
            if (!response.isSuccessful || tokenBody == null) {
                trace("refreshTokenIfNeeded: failed code=${response.code()}")
                if (response.code() == 401 || response.code() == 403) {
                    traktAuthDataStore.clearAuth()
                    tripCircuit("Token refresh returned ${response.code()}")
                }
                return@withLock false
            }

            traktAuthDataStore.saveToken(tokenBody)
            trace("refreshTokenIfNeeded: success")
            true
        }
    }

    suspend fun revokeAndLogout() {
        val state = getCurrentAuthState()
        if (hasRequiredCredentials()) {
            state.accessToken?.let { accessToken ->
                runCatching {
                    traktApi.revokeToken(
                        TraktRevokeRequestDto(
                            token = accessToken,
                            clientId = BuildConfig.TRAKT_CLIENT_ID,
                            clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                        )
                    )
                }
            }
        }
        traktAuthDataStore.clearAuth()
    }

    suspend fun fetchUserSettings(): String? {
        val response = executeAuthorizedRequest { authHeader ->
            traktApi.getUserSettings(authorization = authHeader)
        } ?: return null

        if (!response.isSuccessful) return null

        val username = response.body()?.user?.username
        val slug = response.body()?.user?.ids?.slug
        traktAuthDataStore.saveUser(username = username, userSlug = slug)
        return username
    }

    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        if (isCircuitOpen()) {
            trace("authorized request: circuit breaker is OPEN, skipping request")
            return null
        }

        var token = getValidAccessToken() ?: return null
        var retriedAuth = false
        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
            acquireGetRateSlot()

            val response = try {
                call("Bearer $token")
            } catch (e: IOException) {
                if (!retriedNetwork) {
                    trace("authorized request: network error, retrying once")
                    delay(1_000L)
                    retriedNetwork = true
                    continue
                }
                Log.w("TraktAuthService", "Network error during authorized request", e)
                return null
            }

            val code = response.code()

            if (response.isSuccessful) {
                resetCircuit()
                return response
            }

            if (code == 403) {
                tripCircuit("403 Forbidden (invalid API key or unapproved app) for ${responseTarget(response)}")
                return response
            }

            if (code == 423) {
                tripCircuit("423 Locked User Account for ${responseTarget(response)}")
                return response
            }

            if (code in nonRetryableStatusCodes) {
                trace("authorized request: non-retryable $code for ${responseTarget(response)}")
                return response
            }

            if (code == 401 && !retriedAuth) {
                val refreshed = refreshTokenIfNeeded(force = true)
                if (refreshed) {
                    trace("authorized request: 401 for ${responseTarget(response)}, retrying after token refresh")
                    token = getCurrentAuthState().accessToken ?: return response
                    retriedAuth = true
                    continue
                }
                tripCircuit("401 Unauthorized and token refresh failed for ${responseTarget(response)}")
                return response
            }

            if (code == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("authorized request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (code in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("authorized request: transient $code for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedTransient = true
                continue
            }

            return response
        }
    }

    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        writeRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (lastWriteRequestAtMs + minWriteIntervalMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            lastWriteRequestAtMs = System.currentTimeMillis()
        }
        return executeAuthorizedRequest(call)
    }

    private suspend fun getValidAccessToken(): String? {
        val state = getCurrentAuthState()
        if (state.accessToken.isNullOrBlank()) return null
        if (refreshTokenIfNeeded(force = false)) {
            return getCurrentAuthState().accessToken
        }
        return null
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAt = createdAt + expiresIn
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= (expiresAt - refreshLeewaySeconds)
    }

    private suspend fun delayForRetryAfter(
        response: Response<*>,
        fallbackSeconds: Long,
        maxSeconds: Long
    ): Long {
        val retryAfterSeconds = response.headers()["Retry-After"]
            ?.toLongOrNull()
            ?.coerceIn(1L, maxSeconds)
            ?: fallbackSeconds
        delay(retryAfterSeconds * 1000L)
        return retryAfterSeconds
    }

    private fun responseTarget(response: Response<*>): String {
        val requestUrl = response.raw().request.url
        return buildString {
            append(requestUrl.encodedPath)
            requestUrl.encodedQuery?.let {
                append('?')
                append(it)
            }
        }
    }
}
