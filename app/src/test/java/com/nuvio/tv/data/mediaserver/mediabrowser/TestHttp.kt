package com.nuvio.tv.data.mediaserver.mediabrowser

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal class TestHttp(private val responder: (Request) -> String = { error("Unexpected request ${it.url}") }) {
    private val recorded = mutableListOf<Request>()

    val requests: List<Request>
        get() = synchronized(recorded) { recorded.toList() }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            synchronized(recorded) { recorded += request }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responder(request).toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}

internal val testIdentity = ServerClientIdentity(device = "Test TV", version = "1.0.0", deviceId = { "device-1" })

internal val Request.text: String
    get() = Buffer().also { buffer -> body?.writeTo(buffer) }.readUtf8()
