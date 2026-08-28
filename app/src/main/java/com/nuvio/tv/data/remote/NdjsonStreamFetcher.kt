package com.nuvio.tv.data.remote

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "NdjsonStreamFetcher"

const val NDJSON_CONTENT_TYPE = "application/x-ndjson"

/**
 * Raised when a stream response fails, carrying the HTTP status so callers can
 * classify failures the same way [com.nuvio.tv.core.network.safeApiCall] does.
 */
class NdjsonHttpException(
    val code: Int,
    override val message: String
) : IOException(message)

fun isNdjsonContentType(contentType: String?): Boolean =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.equals(NDJSON_CONTENT_TYPE, ignoreCase = true) == true

/**
 * Parses one NDJSON stream response object; malformed payloads yield an empty list.
 */
fun parseNdjsonStreamDtos(
    adapter: JsonAdapter<StreamResponseDto>,
    payload: String
): List<StreamDto> {
    val trimmed = payload.trim()
    if (trimmed.isEmpty()) return emptyList()
    return try {
        adapter.fromJson(trimmed)?.streams ?: emptyList()
    } catch (e: Exception) {
        Log.d(TAG, "Failed to parse NDJSON line: ${e.message} payload=${trimmed.take(200)}")
        emptyList()
    }
}


/**
 * Streams an HTTP response body line by line, reporting the Content-Type first.
 */
@Singleton
class NdjsonStreamFetcher @Inject constructor(
    okHttpClient: OkHttpClient
) {
    /**
     * Shares the addon client's connection pool, interceptors and cache, but drops
     * the read timeout: an incremental response is idle between batches by design,
     * and the inherited 60s cap would abort long-running searches.
     */
    private val streamingClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLines(
        url: String,
        onContentType: (contentType: String?) -> Unit,
        onLine: suspend (line: String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, $NDJSON_CONTENT_TYPE")
            .build()

        streamingClient.newCall(request).execute().use { response ->
            onContentType(response.header("Content-Type"))
            if (!response.isSuccessful) {
                throw NdjsonHttpException(
                    code = response.code,
                    message = response.message.ifBlank { "HTTP ${response.code}" }
                )
            }
            val source = response.body?.source() ?: throw IOException("Empty response body")
            val context = coroutineContext
            while (true) {
                context.ensureActive()
                val line = source.readUtf8Line() ?: break
                onLine(line)
            }
        }
    }
}
