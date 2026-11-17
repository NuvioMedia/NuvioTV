package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okhttp3.ResponseBody.Companion.toResponseBody

/** Answers every request with the status and body [route] gives for it, and keeps the requests. */
internal class SiloTestHttp(private val route: (Request) -> Pair<Int, String>) {
    private val recorded = mutableListOf<Request>()

    val requests: List<Request>
        get() = synchronized(recorded) { recorded.toList() }

    fun requests(path: String): List<Request> = requests.filter { it.url.encodedPath == path }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            synchronized(recorded) { recorded += request }
            val (status, body) = route(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}

internal val Request.text: String
    get() = Buffer().also { buffer -> body?.writeTo(buffer) }.readUtf8()

internal val siloTestIdentity = ServerClientIdentity(device = "Test TV", version = "1.0.0", deviceId = { "device-1" })
