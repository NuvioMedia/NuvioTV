package com.nuvio.tv.data.mdblist

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

class OkHttpMdbListEngine(
    client: OkHttpClient,
    private val configuration: MdbListConfiguration
) : MdbListHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: MdbListHttpRequest): MdbListHttpResponse =
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
                            val body = response.body.let { body ->
                                readMdbListResponseBody(body.source(), body.contentLength())
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    MdbListHttpResponse(
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

    internal fun buildRequest(request: MdbListHttpRequest): Request {
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        require(request.path.none { it == '?' || it == '#' })
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1"))
        val url = base.newBuilder().encodedPath(request.path).query(null).fragment(null).apply {
            request.query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/${configuration.appVersion}")
            .apply {
                request.accessToken?.let { header("Authorization", "Bearer $it") }
                when (request.method) {
                    MdbListHttpMethod.GET -> get()
                    MdbListHttpMethod.POST -> post(
                        request.form?.let { fields ->
                            FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
                        } ?: request.body.toRequestBody("application/json".toMediaType())
                    )
                }
            }.build()
    }
}

internal fun readMdbListResponseBody(
    source: BufferedSource,
    contentLength: Long,
    maxBytes: Long = 16L * 1024L * 1024L
): String {
    if (contentLength > maxBytes) throw IOException("MDBList response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("MDBList response exceeds size limit")
    }
}
