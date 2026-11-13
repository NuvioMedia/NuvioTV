package com.nuvio.tv.data.wetrakr

import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WeTrakrHttpClient(
    private val engine: WeTrakrHttpEngine,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val blockedUntil = mutableMapOf<String, Long>()

    suspend fun associateAccountLimit(previousKey: String, accountKey: String) = mutex.withLock {
        blockedUntil.remove(previousKey)?.let { until ->
            blockedUntil[accountKey] = maxOf(until, blockedUntil[accountKey] ?: 0L)
        }
    }

    suspend fun execute(
        request: WeTrakrHttpRequest,
        checkScope: () -> Unit = {}
    ): WeTrakrHttpResponse = mutex.withLock {
        val attempts = if (request.retrySafe) 3 else 1
        repeat(attempts) { attempt ->
            checkScope()
            val now = nowEpochMs()
            blockedUntil.entries.removeAll { it.value <= now }
            blockedUntil[request.limitKey]?.let { reset ->
                throw WeTrakrApiException(429, "rate_limited", reset)
            }
            val response = try {
                engine.execute(request)
            } catch (_: IOException) {
                checkScope()
                if (attempt == attempts - 1) throw WeTrakrApiException(code = "transport_failure")
                sleep(1_000L shl attempt)
                return@repeat
            }
            checkScope()
            val retryAt = response.header("Retry-After")?.let { weTrakrRetryAfterEpochMs(it, nowEpochMs()) }
            val windowRemaining = response.header("RateLimit-Remaining")?.trim()?.toLongOrNull()
            if (request.returnsRateLimit && response.status == 429) return@withLock response
            if (response.status == 429 || windowRemaining == 0L) {
                blockedUntil[request.limitKey] = maxOf(
                    nowEpochMs() + 1_000L,
                    retryAt ?: weTrakrLimitResetEpochMs(response, nowEpochMs())
                )
            }
            if (response.status == 429) {
                throw WeTrakrApiException(429, response.errorCode() ?: "rate_limited", blockedUntil[request.limitKey])
            }
            if (request.retrySafe && response.status in setOf(500, 502, 503, 504) && attempt < attempts - 1) {
                val wait = maxOf(1_000L shl attempt, (retryAt ?: 0L) - nowEpochMs())
                if (wait > 5_000L) {
                    throw WeTrakrApiException(response.status, retryAtEpochMs = retryAt ?: (nowEpochMs() + 30_000L))
                }
                sleep(wait)
            } else {
                return@withLock response
            }
        }
        throw WeTrakrApiException(code = "request_failed")
    }
}

internal fun weTrakrLimitResetEpochMs(response: WeTrakrHttpResponse, now: Long): Long {
    if (response.errorCode() == "QUOTA_EXCEEDED") return weTrakrQuotaResetEpochMs(response, now)
    return response.header("RateLimit-Reset")?.trim()?.toLongOrNull()
        ?.takeIf { it in 1..3_600L }
        ?.let { now + it * 1_000L }
        ?: (now + 60_000L)
}

internal fun weTrakrQuotaResetEpochMs(response: WeTrakrHttpResponse, now: Long): Long =
    response.header("X-Quota-Reset")?.trim()?.toLongOrNull()
        ?.takeIf { it in 1..172_800L }
        ?.let { now + it * 1_000L }
        ?: LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun weTrakrRetryAfterEpochMs(value: String, now: Long): Long? {
    val seconds = value.trim().toLongOrNull()
    if (seconds != null) return seconds.takeIf { it >= 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L }
    return runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()
}
