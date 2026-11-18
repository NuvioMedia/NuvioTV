package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** Requests to plex.tv and to Plex Media Server, with the X-Plex-* headers both expect. */
internal class PlexClient(
    http: OkHttpClient,
    private val identity: ServerClientIdentity
) {
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Plex replies differ between versions: a tag id is a number in some and a string in others. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    val clientId: String
        get() = identity.deviceId()

    val headers: Map<String, String>
        get() = mapOf(
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to identity.version.take(MAX_HEADER),
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to identity.device.take(MAX_HEADER),
            "X-Plex-Device-Name" to "$PRODUCT on ${identity.device}".take(MAX_HEADER),
            "X-Plex-Provides" to "player"
        )

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        range: LongRange? = null,
        accepted: Set<Int> = emptySet()
    ): PlexResponse {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .apply {
                    (headers + extraHeaders).forEach { (name, value) -> header(name, value.headerValue()) }
                    token?.let { header(TOKEN_HEADER, it.headerValue()) }
                    range?.let { header("Range", "bytes=${it.first}-${it.last}") }
                }
                .method(method, if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val response = try {
            send(request, readBody = range == null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        val failure = when {
            response.status in 200..299 || response.status in accepted -> return response
            response.status == 401 -> ServerFailure.AUTH_REQUIRED
            response.status == 403 -> ServerFailure.FORBIDDEN
            response.status == 404 -> ServerFailure.NOT_FOUND
            response.status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP ${response.status}")
    }

    private suspend fun send(request: Request, readBody: Boolean): PlexResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = if (readBody) response.body?.let { readBounded(it.source(), it.contentLength()) }.orEmpty() else ""
                        if (continuation.isActive) continuation.resume(PlexResponse(response.code, body))
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun readBounded(source: okio.BufferedSource, contentLength: Long): String {
        if (contentLength > MAX_BODY_BYTES) throw IOException("Response exceeds size limit")
        val buffer = Buffer()
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_BODY_BYTES - buffer.size + 1L))
            if (count == -1L) return buffer.readUtf8()
            if (buffer.size > MAX_BODY_BYTES) throw IOException("Response exceeds size limit")
        }
    }

    companion object {
        const val PRODUCT = "Nuvio"
        const val TOKEN_HEADER = "X-Plex-Token"
        private const val MAX_HEADER = 64
        private const val MAX_BODY_BYTES = 16L * 1024L * 1024L
    }
}

internal class PlexResponse(val status: Int, val body: String) {
    override fun toString(): String = "PlexResponse(status=$status)"
}

private fun String.headerValue(): String = filter { it in ' '..'~' }
