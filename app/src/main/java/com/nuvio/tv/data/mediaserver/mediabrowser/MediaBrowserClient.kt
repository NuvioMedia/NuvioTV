package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

class ServerClientIdentity(
    val device: String,
    val version: String,
    val deviceId: () -> String
)

internal class MediaBrowserClient(
    private val authorizationHeader: String,
    http: OkHttpClient,
    private val identity: ServerClientIdentity
) {
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val deviceId: String
        get() = identity.deviceId()

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        token: String? = null,
        query: Map<String, String?> = emptyMap()
    ): T = json.decodeFromString(deserializer, execute("GET", baseUrl, path, token, query).body)

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        allowRedirect: Boolean = false
    ): ApiResponse {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .header(authorizationHeader, authorizationValue(token))
                .method(
                    method,
                    body?.toRequestBody(JSON_MEDIA_TYPE) ?: if (method == "POST") ByteArray(0).toRequestBody() else null
                )
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val response = try {
            send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        if (allowRedirect && response.status in 300..399) return response
        throwForStatus(response.status)
        return response
    }

    fun authorizationValue(token: String?): String = buildString {
        append("MediaBrowser Client=\"Nuvio\", Device=\"")
        append(identity.device.headerValue())
        append("\", DeviceId=\"")
        append(deviceId.headerValue())
        append("\", Version=\"")
        append(identity.version.headerValue())
        append('"')
        if (token != null) {
            append(", Token=\"")
            append(token.headerValue())
            append('"')
        }
    }

    private suspend fun send(request: Request): ApiResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body?.let { readBoundedBody(it.source(), it.contentLength()) }.orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(ApiResponse(response.code, body, response.header("Location")))
                        }
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun throwForStatus(status: Int) {
        val failure = when {
            status in 200..299 -> return
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP $status")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal class ApiResponse(
    val status: Int,
    val body: String,
    val location: String?
) {
    override fun toString(): String = "ApiResponse(status=$status)"
}

internal fun buildUrl(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): String {
    require(path.startsWith('/'))
    val base = requireNotNull(baseUrl.toHttpUrlOrNull())
    return base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/') + path)
        .query(null)
        .fragment(null)
        .apply { query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) } }
        .build()
        .toString()
}

internal fun pathSegment(value: String): String =
    HttpUrl.Builder().scheme("http").host("h").addPathSegment(value).build().encodedPath.removePrefix("/")

internal fun normalizeServerAddress(input: String, apiPath: String = ""): String? {
    var value = input.trim().trimEnd('/')
    if (value.isEmpty()) return null
    if (!value.contains("://")) value = "http://$value"
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.host.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath.trimEnd('/')
        .removeSuffix("/web/index.html")
        .removeSuffix("/web")
        .trimEnd('/')
        .let { if (apiPath.isNotEmpty() && it.endsWith(apiPath, ignoreCase = true)) it.dropLast(apiPath.length) else it }
    return url.newBuilder()
        .encodedPath(path.ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

private fun readBoundedBody(source: BufferedSource, contentLength: Long, maxBytes: Long = 16L * 1024L * 1024L): String {
    if (contentLength > maxBytes) throw IOException("Response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("Response exceeds size limit")
    }
}

private fun String.headerValue(): String = filter { it != '"' && it != '\r' && it != '\n' }
