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

        val response = traktApi.requestDeviceCode(
            TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID)
        )
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return Result.failure(
                IllegalStateException("Failed to start Trakt auth (${response.code()})")
            )
        }

        traktAuthDataStore.saveDeviceFlow(body)
        return Result.success(body)
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

        val response = traktApi.requestDeviceToken(
            TraktDeviceTokenRequestDto(
                code = deviceCode,
                clientId = BuildConfig.TRAKT_CLIENT_ID,
                clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
            )
        )

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
        var token = getValidAccessToken() ?: return null
        var retriedAuth = false
        var retriedRateLimit = false
        var retriedTransient = false
        var retriedNetwork = false

        while (true) {
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

            if (response.code() == 401 && !retriedAuth && refreshTokenIfNeeded(force = true)) {
                trace("authorized request: 401 for ${responseTarget(response)}, retrying after token refresh")
                token = getCurrentAuthState().accessToken ?: return response
                retriedAuth = true
                continue
            }

            if (response.code() == 429 && !retriedRateLimit) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 2L, maxSeconds = 60L)
                trace("authorized request: 429 for ${responseTarget(response)}, retrying in ${waitSeconds}s")
                retriedRateLimit = true
                continue
            }

            if (response.code() in transientRetryStatusCodes && !retriedTransient) {
                val waitSeconds = delayForRetryAfter(response = response, fallbackSeconds = 30L, maxSeconds = 30L)
                trace("authorized request: transient ${response.code()} for ${responseTarget(response)}, retrying in ${waitSeconds}s")
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
