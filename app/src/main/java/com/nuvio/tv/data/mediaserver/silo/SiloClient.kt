package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

/** Requests to Silo's own API (`/api/v2`). Failures keep Silo's problem code, which the sign-in and playback act on. */
internal class SiloClient(
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
        coerceInputValues = true
    }

    val deviceId: String
        get() = identity.deviceId()

    /** The name the server shows for this device in its list of signed-in devices. */
    val deviceName: String
        get() = "Nuvio on ${identity.device}"

    val deviceHeaders: Map<String, String>
        get() = mapOf(
            "X-Device-ID" to deviceId,
            "X-Silo-Device-Id" to deviceId,
            "X-Client-Name" to "Nuvio",
            "X-Client-Version" to identity.version.take(MAX_HEADER)
        )

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        allowRedirect: Boolean = false
    ): SiloResponse {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .header("User-Agent", deviceName.headerValue())
                .apply { headers.forEach { (name, value) -> header(name, value.headerValue()) } }
                .method(
                    method,
                    body?.toRequestBody(JSON_MEDIA_TYPE)
                        ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null
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
        if (response.status in 200..299 || allowRedirect && response.status in 300..399) return response
        throw SiloProblem.from(response, json).toServerException()
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(deserializer, body)
    } catch (_: SerializationException) {
        throw ServerException(ServerFailure.FAILED)
    } catch (_: IllegalArgumentException) {
        throw ServerException(ServerFailure.FAILED)
    }

    private suspend fun send(request: Request): SiloResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body?.let { readBounded(it.source(), it.contentLength()) }.orEmpty()
                        if (continuation.isActive) continuation.resume(SiloResponse(response.code, body, response.header("Location")))
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun readBounded(source: BufferedSource, contentLength: Long): String {
        if (contentLength > MAX_BODY_BYTES) throw IOException("Response exceeds size limit")
        val buffer = Buffer()
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_BODY_BYTES - buffer.size + 1L))
            if (count == -1L) return buffer.readUtf8()
            if (buffer.size > MAX_BODY_BYTES) throw IOException("Response exceeds size limit")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAX_HEADER = 64
        const val MAX_BODY_BYTES = 16L * 1024L * 1024L
    }
}

internal class SiloResponse(val status: Int, val body: String, val location: String?) {
    override fun toString(): String = "SiloResponse(status=$status)"
}

/** A refused request: the HTTP status and Silo's problem code, when it sent one. */
internal class SiloProblem(val status: Int, val code: String?) {
    fun toServerException(): ServerException {
        val failure = when {
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        return ServerException(failure, "$PREFIX$status ${code.orEmpty()}".trim())
    }

    companion object {
        private const val PREFIX = "Silo "

        fun from(response: SiloResponse, json: Json): SiloProblem {
            val code = try {
                json.decodeFromString(ProblemWire.serializer(), response.body).type
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            return SiloProblem(response.status, code)
        }

        /** The status and code a [ServerException] thrown for a Silo reply carries; null for any other failure. */
        fun of(error: ServerException): SiloProblem? {
            val text = error.message?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val status = text.substringBefore(' ').toIntOrNull() ?: return null
            return SiloProblem(status, text.substringAfter(' ', "").takeIf { it.isNotBlank() })
        }
    }
}

/** An RFC 9457 problem reply; Silo names the error in the last part of its type. */
@Serializable
private data class ProblemWire(val type: String? = null)

internal val ServerException.siloStatus: Int?
    get() = SiloProblem.of(this)?.status

internal val ServerException.siloCode: String?
    get() = SiloProblem.of(this)?.code

private fun String.headerValue(): String = filter { it in ' '..'~' }
