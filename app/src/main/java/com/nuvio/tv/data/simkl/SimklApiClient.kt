package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random

enum class SimklHttpMethod {
    GET,
    POST,
    DELETE
}

enum class SimklRetryPolicy {
    TRANSIENT_FAILURES,
    SYNC_WRITE,
    NEVER
}

data class SimklApiRequest(
    val method: SimklHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val requiresAuthentication: Boolean = true,
    val retryPolicy: SimklRetryPolicy = SimklRetryPolicy.TRANSIENT_FAILURES,
    val scrobbleStopConflictIsSuccess: Boolean = false,
    val contentType: SimklContentType = SimklContentType.JSON
)

data class SimklRawHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

data class SimklApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
    val isSoftSuccess: Boolean = false
)

class SimklApiException(
    val status: Int?,
    val errorCode: String?,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

fun interface SimklHttpEngine {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): SimklRawHttpResponse
}

class SimklApiClient(
    private val engine: SimklHttpEngine,
    private val configuration: SimklApiConfiguration,
    private val authorization: () -> SimklAuthorization?,
    private val onUnauthorized: (SimklAuthorization) -> Unit,
    /**
     * Persists tokens minted by an AUTH V2 refresh and returns the authorization to continue
     * with, or null when the grant changed meanwhile (profile switch or disconnect).
     */
    private val onTokensRefreshed: (SimklAuthorization, SimklTokenGrant) -> SimklAuthorization? = { _, _ -> null },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retryJitterMs: () -> Long = { Random.nextLong(RETRY_JITTER_BOUND_MS + 1L) }
) {
    private val requestMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextGetAtEpochMs = 0L
    private var nextPostAtEpochMs = 0L

    suspend fun execute(
        request: SimklApiRequest,
        expectedAuthScope: SimklAuthScope? = null
    ): SimklApiResponse {
        val queuedAtEpochMs = nowEpochMs()
        if (request.path.startsWith("/scrobble/")) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl client queued path=${request.path}"
            )
        }
        return requestMutex.withLock {
            if (request.path.startsWith("/scrobble/")) {
                Log.d(
                    TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                    "simkl client acquired path=${request.path} waitMs=" +
                        (nowEpochMs() - queuedAtEpochMs).coerceAtLeast(0L)
                )
            }
            var requestAuthorization = if (request.requiresAuthentication) {
                authorization()?.takeIf { it.accessToken.isNotBlank() }
                    ?.also { current ->
                        if (expectedAuthScope != null && current.scope != expectedAuthScope) {
                            throw SimklApiException(
                                409,
                                "auth_scope_changed",
                                "Simkl authentication profile changed"
                            )
                        }
                    }
                    ?: throw SimklApiException(401, "authentication_required", "Simkl authentication is required")
            } else {
                null
            }
            requestAuthorization?.takeIf(::needsProactiveRefresh)?.let { current ->
                requestAuthorization = refreshBeforeRequest(current)
            }
            val maxAttempts = if (request.retryPolicy == SimklRetryPolicy.NEVER) 1 else MAX_ATTEMPTS
            var syncWriteLockRetried = false
            var refreshedAfterUnauthorized = false
            var attempt = 0
            while (attempt < maxAttempts) {
                val token = requestAuthorization?.accessToken
                val response = try {
                    executeRateLimited(request.method) {
                        engine.execute(
                            method = request.method.name,
                            url = buildSimklApiUrl(configuration, request.path, request.query),
                            headers = simklRequestHeaders(
                                configuration = configuration,
                                accessToken = token,
                                contentType = request.contentType.takeIf { request.method != SimklHttpMethod.GET }
                            ),
                            body = request.body
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (attempt == maxAttempts - 1) {
                        throw SimklApiException(null, "transport_failure", "Simkl request failed", error)
                    }
                    sleep(retryDelayMs(attempt, null, retryJitterMs()))
                    attempt += 1
                    continue
                }

                logSyncResponse(request, response, attempt + 1)
                if (request.path.startsWith("/scrobble/")) {
                    Log.d(
                        TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                        "simkl client response path=${request.path} status=${response.status} attempt=${attempt + 1}"
                    )
                }
                if (
                    request.retryPolicy == SimklRetryPolicy.SYNC_WRITE &&
                    response.status == 400 &&
                    !syncWriteLockRetried &&
                    response.errorCode(json).equals("rate_limit", ignoreCase = true) &&
                    attempt < maxAttempts - 1
                ) {
                    syncWriteLockRetried = true
                    sleep(SYNC_WRITE_LOCK_RETRY_DELAY_MS)
                    attempt += 1
                    continue
                }

                when (classifySimklResponse(response.status, request.scrobbleStopConflictIsSuccess)) {
                    SimklResponseAction.SUCCESS -> return@withLock response.toApiResponse()
                    SimklResponseAction.SOFT_SUCCESS -> return@withLock response.toApiResponse(true)
                    SimklResponseAction.REAUTHENTICATE -> {
                        val current = requestAuthorization
                        if (current != null && !refreshedAfterUnauthorized && current.refreshToken != null) {
                            // An AUTH V2 access token lives 7 days: refresh once, then replay
                            // the same request without spending one of its retry attempts.
                            refreshedAfterUnauthorized = true
                            requestAuthorization = refreshAfterUnauthorized(current, response)
                            continue
                        }
                        current?.let(onUnauthorized)
                        throw response.toApiException(json)
                    }
                    SimklResponseAction.FAIL -> throw response.toApiException(json)
                    SimklResponseAction.RETRY -> {
                        if (attempt == maxAttempts - 1) throw response.toApiException(json)
                        sleep(
                            retryDelayMs(
                                attempt,
                                response.headers.headerValue("retry-after"),
                                retryJitterMs()
                            )
                        )
                        attempt += 1
                    }
                }
            }
            error("Simkl request loop completed without a response")
        }
    }

    private fun needsProactiveRefresh(current: SimklAuthorization): Boolean {
        val expiresAt = current.accessTokenExpiresAtEpochMs ?: return false
        return current.refreshToken != null && expiresAt - nowEpochMs() <= PROACTIVE_REFRESH_WINDOW_MS
    }

    /** Refreshes a token that is close to expiry. A token that is still valid survives a failed refresh. */
    private suspend fun refreshBeforeRequest(current: SimklAuthorization): SimklAuthorization =
        when (val result = refreshLocked(current)) {
            is SimklRefreshResult.Refreshed -> result.authorization
            SimklRefreshResult.Rejected -> {
                onUnauthorized(current)
                throw SimklApiException(401, "invalid_grant", "Simkl authorization expired. Connect again.")
            }
            is SimklRefreshResult.Failed -> {
                val expiresAt = current.accessTokenExpiresAtEpochMs ?: Long.MAX_VALUE
                if (expiresAt <= nowEpochMs()) throw result.error
                current
            }
        }

    private suspend fun refreshAfterUnauthorized(
        current: SimklAuthorization,
        unauthorizedResponse: SimklRawHttpResponse
    ): SimklAuthorization = when (val result = refreshLocked(current)) {
        is SimklRefreshResult.Refreshed -> result.authorization
        SimklRefreshResult.Rejected -> {
            onUnauthorized(current)
            throw unauthorizedResponse.toApiException(json)
        }
        is SimklRefreshResult.Failed -> throw result.error
    }

    /** Runs the AUTH V2 refresh grant. Must be called while holding [requestMutex]. */
    private suspend fun refreshLocked(current: SimklAuthorization): SimklRefreshResult {
        val refreshToken = current.refreshToken ?: return SimklRefreshResult.Rejected
        val response = try {
            executeRateLimited(SimklHttpMethod.POST) {
                engine.execute(
                    method = SimklHttpMethod.POST.name,
                    url = buildSimklApiUrl(configuration, SIMKL_TOKEN_PATH),
                    headers = simklRequestHeaders(
                        configuration = configuration,
                        contentType = SimklContentType.FORM
                    ),
                    body = simklFormBody(
                        mapOf(
                            "grant_type" to "refresh_token",
                            "client_id" to configuration.clientId,
                            "refresh_token" to refreshToken
                        )
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return SimklRefreshResult.Failed(
                SimklApiException(null, "transport_failure", "Simkl token refresh failed", error)
            )
        }
        if (response.status in 200..299) {
            val grant = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
                .getOrNull()
                ?.toGrant(nowEpochMs(), fallbackRefreshToken = refreshToken)
                ?: return SimklRefreshResult.Failed(
                    SimklApiException(response.status, "invalid_token_response", "Simkl token refresh failed")
                )
            val refreshed = onTokensRefreshed(current, grant)
                ?: return SimklRefreshResult.Failed(
                    SimklApiException(409, "auth_scope_changed", "Simkl authentication profile changed")
                )
            return SimklRefreshResult.Refreshed(refreshed)
        }
        val errorCode = response.errorCode(json)
        return if (
            response.status == 401 ||
            errorCode == "invalid_grant" ||
            errorCode == "invalid_client" ||
            errorCode == "unauthorized_client"
        ) {
            SimklRefreshResult.Rejected
        } else {
            SimklRefreshResult.Failed(response.toApiException(json))
        }
    }

    private suspend fun <T> executeRateLimited(
        method: SimklHttpMethod,
        block: suspend () -> T
    ): T {
        awaitRateLimit(method)
        return try {
            block()
        } finally {
            recordRateLimitCompletion(method)
        }
    }

    private suspend fun awaitRateLimit(method: SimklHttpMethod) {
        val now = nowEpochMs()
        val scheduledAt = when (method) {
            SimklHttpMethod.GET -> nextGetAtEpochMs
            SimklHttpMethod.POST, SimklHttpMethod.DELETE -> nextPostAtEpochMs
        }
        if (scheduledAt > now) sleep(scheduledAt - now)
    }

    private fun recordRateLimitCompletion(method: SimklHttpMethod) {
        val completedAt = nowEpochMs()
        when (method) {
            SimklHttpMethod.GET -> {
                nextGetAtEpochMs = max(nextGetAtEpochMs, completedAt + GET_INTERVAL_MS)
            }
            SimklHttpMethod.POST, SimklHttpMethod.DELETE -> {
                nextPostAtEpochMs = max(nextPostAtEpochMs, completedAt + POST_INTERVAL_MS)
            }
        }
    }

    private fun logSyncResponse(request: SimklApiRequest, response: SimklRawHttpResponse, attempt: Int) {
        if (request.path.startsWith("/sync/")) {
            Log.d("SimklSync", simklSyncResponseLogMessage(request, response, attempt))
        }
    }

    private companion object {
        const val GET_INTERVAL_MS = 100L
        const val POST_INTERVAL_MS = 1_000L
        const val MAX_ATTEMPTS = 5
        const val SYNC_WRITE_LOCK_RETRY_DELAY_MS = 3_000L
        const val RETRY_JITTER_BOUND_MS = 1_000L
        /** Refresh "a day or so from expiry", as the AUTH V2 token docs recommend. */
        const val PROACTIVE_REFRESH_WINDOW_MS = 24L * 60L * 60L * 1_000L
    }
}

private sealed interface SimklRefreshResult {
    data class Refreshed(val authorization: SimklAuthorization) : SimklRefreshResult
    data object Rejected : SimklRefreshResult
    data class Failed(val error: SimklApiException) : SimklRefreshResult
}

internal const val SIMKL_DEVICE_PATH = "/oauth2/device"
internal const val SIMKL_TOKEN_PATH = "/oauth2/token"
internal const val SIMKL_REVOKE_PATH = "/oauth2/revoke"

enum class SimklResponseAction {
    SUCCESS,
    SOFT_SUCCESS,
    REAUTHENTICATE,
    RETRY,
    FAIL
}

fun classifySimklResponse(
    status: Int,
    scrobbleStopConflictIsSuccess: Boolean = false
): SimklResponseAction = when {
    status in 200..299 -> SimklResponseAction.SUCCESS
    status == 409 && scrobbleStopConflictIsSuccess -> SimklResponseAction.SOFT_SUCCESS
    status == 401 -> SimklResponseAction.REAUTHENTICATE
    status == 429 || status == 500 || status == 502 || status == 503 -> SimklResponseAction.RETRY
    else -> SimklResponseAction.FAIL
}

fun retryDelayMs(attempt: Int, retryAfterHeader: String?, jitterMs: Long): Long {
    require(attempt in 0..4)
    val exponentialDelayMs = 1_000L shl attempt
    val retryAfterMs = retryAfterHeader
        ?.substringBefore(',')
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?.times(1_000L)
        ?: 0L
    return (max(exponentialDelayMs, retryAfterMs) + jitterMs.coerceIn(0L, 1_000L)).coerceAtMost(60_000L)
}

fun simklSyncResponseLogMessage(
    request: SimklApiRequest,
    response: SimklRawHttpResponse,
    attempt: Int
): String {
    val queryKeys = request.query.keys.sorted().joinToString(prefix = "[", postfix = "]")
    val contentType = response.headers.headerValue("content-type") ?: "<missing>"
    return "Simkl HTTP response: method=${request.method} path=${request.path} " +
        "queryKeys=$queryKeys status=${response.status} attempt=$attempt " +
        "contentType=$contentType bodyChars=${response.body.length}"
}

private fun SimklRawHttpResponse.toApiResponse(isSoftSuccess: Boolean = false): SimklApiResponse =
    SimklApiResponse(status, body, headers, isSoftSuccess)

private fun SimklRawHttpResponse.toApiException(json: Json): SimklApiException {
    val envelope = errorEnvelope(json)
    return SimklApiException(
        status = status,
        errorCode = envelope?.error,
        message = envelope?.message?.takeIf(String::isNotBlank)
            ?: envelope?.errorDescription?.takeIf(String::isNotBlank)
            ?: envelope?.error?.takeIf(String::isNotBlank)
            ?: "Simkl request failed with HTTP $status"
    )
}

private fun SimklRawHttpResponse.errorCode(json: Json): String? = errorEnvelope(json)?.error

private fun SimklRawHttpResponse.errorEnvelope(json: Json): SimklErrorEnvelope? = body
    .takeIf(String::isNotBlank)
    ?.let { runCatching { json.decodeFromString<SimklErrorEnvelope>(it) }.getOrNull() }

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

@Serializable
private data class SimklErrorEnvelope(
    val error: String? = null,
    val code: Int? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)
