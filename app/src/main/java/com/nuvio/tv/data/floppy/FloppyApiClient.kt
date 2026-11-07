package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class FloppyConnectionResult {
    CONNECTED,

    /** Floppy answered but refused the token (revoked, expired, or missing a permission). */
    REJECTED,

    /** Nothing answered at that address, or the answer was not usable. */
    UNREACHABLE,

    /** Something answered, but it does not look like a Floppy server. */
    NOT_FLOPPY
}

class FloppyApiException(val status: Int) : IOException("Floppy responded with HTTP $status")

/**
 * The two Floppy API calls NuvioTV needs: check that a token works, and send playback events.
 * See docs/integrations/nuvio-client-guide.md in the Floppy repository for the contract.
 */
class FloppyApiClient(
    client: OkHttpClient,
    private val appVersion: String
) {
    // A redirect would replay the request elsewhere (and a POST would silently become a GET),
    // so the user is told to enter the final address instead.
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** `GET /api/v1/sync/connections/` is in the default token preset and proves the token is valid. */
    suspend fun checkConnection(credentials: FloppyCredentials): FloppyConnectionResult {
        val response = try {
            execute(request(credentials, "/api/v1/sync/connections/").get().build())
        } catch (_: IOException) {
            return FloppyConnectionResult.UNREACHABLE
        }
        return when {
            response.status == 401 || response.status == 403 -> FloppyConnectionResult.REJECTED
            response.status !in 200..299 -> FloppyConnectionResult.NOT_FLOPPY
            looksLikeJsonCollection(response.body) -> FloppyConnectionResult.CONNECTED
            else -> FloppyConnectionResult.NOT_FLOPPY
        }
    }

    /** `POST /api/v1/scrobble/`. Throws [FloppyApiException] when Floppy refuses the event. */
    suspend fun scrobble(credentials: FloppyCredentials, body: JsonObject) {
        val response = execute(
            request(credentials, "/api/v1/scrobble/")
                .post(body.toString().toRequestBody(JSON))
                .build()
        )
        if (response.status !in 200..299) throw FloppyApiException(response.status)
    }

    private fun request(credentials: FloppyCredentials, path: String): Request.Builder =
        Request.Builder()
            .url(credentials.baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/$appVersion")
            .header("Authorization", "Bearer ${credentials.token}")

    private suspend fun execute(request: Request): FloppyResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = response.body?.source()?.let { source ->
                                source.request(MAX_BODY_BYTES + 1)
                                if (source.buffer.size > MAX_BODY_BYTES) throw IOException("Floppy response too large")
                                source.buffer.readUtf8()
                            }.orEmpty()
                            if (continuation.isActive) continuation.resume(FloppyResponse(response.code, text))
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun looksLikeJsonCollection(body: String): Boolean = runCatching {
        val element = Json.parseToJsonElement(body)
        element is JsonObject || element is JsonArray
    }.getOrDefault(false)

    private class FloppyResponse(val status: Int, val body: String)

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BODY_BYTES = 1L * 1024L * 1024L
    }
}
