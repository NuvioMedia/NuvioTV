package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WeTrakrAuthRepository(
    private val http: WeTrakrHttpClient,
    private val configuration: WeTrakrConfiguration,
    private val store: WeTrakrAuthStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startDeviceAuthorization(scope: WeTrakrAuthScope = store.scope()): WeTrakrDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        if (!hasRequiredCredentials()) throw WeTrakrAuthException(WeTrakrAuthError.MISSING_CLIENT_ID)
        check(!state.value.isAuthenticated)
        val response = post("/oauth/device/code", mapOf("client_id" to configuration.clientId), scope, returnsRateLimit = true)
        when (response.status) {
            in 200..299 -> Unit
            401, 403, 423 -> throw WeTrakrAuthException(WeTrakrAuthError.INVALID_CLIENT)
            429 -> throw WeTrakrAuthException(WeTrakrAuthError.TOO_MANY_CODES)
            else -> throw WeTrakrApiException(response.status, response.errorCode())
        }
        val payload = decode<WeTrakrDeviceResponse>(response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val url = verificationUrl(payload.verificationUrl)
        val complete = url.newBuilder().setQueryParameter("code", userCode).build()
        val interval = payload.interval.takeIf { it in 1..3_600 } ?: invalidResponse()
        val now = nowEpochMs()
        val session = WeTrakrDeviceSession(
            userCode = userCode,
            verificationUrl = url.toString(),
            verificationUrlComplete = complete.toString(),
            expiresAtEpochMs = expiresAt(payload.expiresIn, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: WeTrakrAuthScope = store.scope()): WeTrakrDevicePollResult = deviceMutex.withLock {
        store.checkScope(scope)
        val session = state.value.session ?: scopeChanged()
        val now = nowEpochMs()
        if (now >= session.expiresAtEpochMs) {
            store.cancelSession(WeTrakrAuthError.CODE_EXPIRED, scope)
            return@withLock WeTrakrDevicePollResult.Expired
        }
        if (now < session.nextPollAtEpochMs) return@withLock WeTrakrDevicePollResult.Pending
        val deviceCode = store.deviceCode(scope) ?: scopeChanged()
        if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) {
            scopeChanged()
        }
        val response = post(
            "/oauth/device/token",
            mapOf("code" to deviceCode, "client_id" to configuration.clientId),
            scope,
            returnsRateLimit = true
        )
        when (response.status) {
            in 200..299 -> {
                if (!store.authorize(tokensFrom(response), scope)) scopeChanged()
                WeTrakrDevicePollResult.Authorized
            }
            400 -> WeTrakrDevicePollResult.Pending
            429 -> {
                val interval = (session.intervalSeconds + 5).coerceAtMost(3_600)
                store.updateSession(
                    session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L),
                    scope
                )
                WeTrakrDevicePollResult.Pending
            }
            418 -> {
                store.cancelSession(WeTrakrAuthError.ACCESS_DENIED, scope)
                WeTrakrDevicePollResult.Denied
            }
            404, 409, 410 -> {
                store.cancelSession(WeTrakrAuthError.CODE_EXPIRED, scope)
                WeTrakrDevicePollResult.Expired
            }
            401, 403, 423 -> {
                store.cancelSession(WeTrakrAuthError.INVALID_CLIENT, scope)
                WeTrakrDevicePollResult.Denied
            }
            else -> throw WeTrakrApiException(response.status, response.errorCode())
        }
    }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    suspend fun authorization(
        scope: WeTrakrAuthScope,
        rejectedAccessToken: String? = null
    ): WeTrakrAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
        if (rejectedAccessToken != null && current.tokens.accessToken != rejectedAccessToken) return@withLock current
        if (rejectedAccessToken == null && current.tokens.expiresAtEpochMs - nowEpochMs() > REFRESH_MARGIN_MS) {
            return@withLock current
        }
        val response = post("/oauth/token/refresh", mapOf("refresh_token" to current.tokens.refreshToken), scope)
        if (response.status !in 200..299) {
            if (response.status == 400 || response.status == 401) {
                store.clearAuth(scope, WeTrakrAuthError.AUTHORIZATION_REVOKED, current.tokens.accessToken)
                throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            }
            throw WeTrakrApiException(response.status, response.errorCode())
        }
        val tokens = tokensFrom(response, current.tokens.refreshToken)
        if (!store.refreshTokens(tokens, current)) scopeChanged()
        WeTrakrAuthorization(scope, tokens)
    }

    suspend fun disconnect(scope: WeTrakrAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (current != null && current.scope != scope) scopeChanged()
        if (!store.clearAuth(scope)) scopeChanged()
        if (current == null) return true
        return try {
            val response = http.execute(
                WeTrakrHttpRequest(
                    method = WeTrakrHttpMethod.POST,
                    path = "/oauth/logout",
                    body = body(mapOf("refresh_token" to current.tokens.refreshToken)),
                    accessToken = current.tokens.accessToken
                )
            )
            response.status in 200..299
        } catch (error: CancellationException) {
            throw error
        } catch (_: WeTrakrApiException) {
            false
        }
    }

    private suspend fun post(
        path: String,
        fields: Map<String, String>,
        scope: WeTrakrAuthScope,
        returnsRateLimit: Boolean = false
    ) =
        http.execute(
            WeTrakrHttpRequest(
                method = WeTrakrHttpMethod.POST,
                path = path,
                body = body(fields),
                limitKey = "oauth:${scope.profileId}",
                returnsRateLimit = returnsRateLimit
            ),
            checkScope = { store.checkScope(scope) }
        )

    private fun body(fields: Map<String, String>): String =
        buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()

    private fun tokensFrom(response: WeTrakrHttpResponse, previousRefreshToken: String? = null): WeTrakrTokens {
        val payload = decode<WeTrakrTokenResponse>(response.body)
        val accessToken = payload.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        val refreshToken = payload.newRefreshToken?.takeIf(String::isNotBlank)
            ?: payload.refreshToken?.takeIf(String::isNotBlank)
            ?: previousRefreshToken
            ?: invalidResponse()
        if (payload.tokenType != null && !payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        val now = nowEpochMs()
        val expiresAt = payload.expiresIn?.let { expiresAt(it, now) } ?: (now + DEFAULT_TOKEN_LIFETIME_MS)
        return WeTrakrTokens(accessToken, refreshToken, expiresAt)
    }

    private fun verificationUrl(value: String?) = value?.toHttpUrlOrNull()
        ?.takeIf {
            it.isHttps && (it.host == "wetrakr.com" || it.host.endsWith(".wetrakr.com")) &&
                it.username.isEmpty() && it.password.isEmpty()
        }
        ?: invalidResponse()

    private fun expiresAt(seconds: Long?, now: Long): Long = seconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw WeTrakrAuthException(WeTrakrAuthError.INVALID_RESPONSE)
    private fun scopeChanged(): Nothing = throw CancellationException("WeTrakr account changed")

    private companion object {
        const val REFRESH_MARGIN_MS = 60L * 60 * 1_000
        const val DEFAULT_TOKEN_LIFETIME_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
