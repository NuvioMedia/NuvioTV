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
    val scrobbleStopConflictIsSuccess: Boolean = false
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
            val requestAuthorization = if (request.requiresAuthentication) {
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
            val token = requestAuthorization?.accessToken
            val maxAttempts = if (request.retryPolicy == SimklRetryPolicy.NEVER) 1 else MAX_ATTEMPTS
            var syncWriteLockRetried = false
            repeat(maxAttempts) { attempt ->
                val response = try {
                    executeRateLimited(request.method) {
                        engine.execute(
                            method = request.method.name,
                            url = buildSimklApiUrl(configuration, request.path, request.query),
                            headers = simklRequestHeaders(
                                configuration = configuration,
                                accessToken = token,
                                contentTypeJson = request.method != SimklHttpMethod.GET
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
                    return@repeat
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
                    response.errorCode(json) == "rate_limit" &&
                    attempt < maxAttempts - 1
                ) {
                    syncWriteLockRetried = true
                    sleep(SYNC_WRITE_LOCK_RETRY_DELAY_MS)
                    return@repeat
                }

                when (classifySimklResponse(response.status, request.scrobbleStopConflictIsSuccess)) {
                    SimklResponseAction.SUCCESS -> return@withLock response.toApiResponse()
                    SimklResponseAction.SOFT_SUCCESS -> return@withLock response.toApiResponse(true)
                    SimklResponseAction.REAUTHENTICATE -> {
                        requestAuthorization?.let(onUnauthorized)
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
                    }
                }
            }
            error("Simkl request loop completed without a response")
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
    }
}

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
