package com.nuvio.tv.data.wetrakr

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

class OkHttpWeTrakrEngine(
    client: OkHttpClient,
    private val configuration: WeTrakrConfiguration
) : WeTrakrHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(buildRequest(request))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = response.body?.let { body ->
                                readWeTrakrResponseBody(body.source(), body.contentLength())
                            }.orEmpty()
                            if (continuation.isActive) {
                                continuation.resume(
                                    WeTrakrHttpResponse(
                                        status = response.code,
                                        body = body,
                                        headers = response.headers.toMultimap()
                                            .mapValues { it.value.joinToString(",") }
                                    )
                                )
                            }
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    internal fun buildRequest(request: WeTrakrHttpRequest): Request {
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        require(request.path.none { it == '?' || it == '#' })
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1"))
        val url = base.newBuilder().encodedPath(request.path).query(null).fragment(null).apply {
            request.query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = request.body?.toRequestBody(JSON)
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/${configuration.appVersion}")
            .header("wetrakr-api-key", configuration.clientId)
            .header("wetrakr-api-version", "1")
            .apply {
                request.accessToken?.let { header("Authorization", "Bearer $it") }
                request.headers.forEach { (name, value) -> header(name, value) }
                when (request.method) {
                    WeTrakrHttpMethod.GET -> get()
                    WeTrakrHttpMethod.POST -> post(body ?: "{}".toRequestBody(JSON))
                    WeTrakrHttpMethod.DELETE -> if (body == null) delete() else delete(body)
                }
            }.build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal fun readWeTrakrResponseBody(
    source: BufferedSource,
    contentLength: Long,
    maxBytes: Long = 16L * 1024L * 1024L
): String {
    if (contentLength > maxBytes) throw IOException("WeTrakr response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("WeTrakr response exceeds size limit")
    }
}
