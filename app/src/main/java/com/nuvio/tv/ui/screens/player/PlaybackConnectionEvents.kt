package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

internal class PlaybackConnectionEventListener(
    private val id: Long
) : EventListener() {

    private val emitted = AtomicBoolean(false)

    private var callT0 = 0L
    private var dnsT0 = 0L
    private var connT0 = 0L
    private var tlsT0 = 0L

    private var dnsEndT = 0L
    private var connEndT = 0L
    private var headersT = 0L
    private var acqT = 0L

    private var dnsMs = -1L
    private var connMs = -1L
    private var tlsMs = -1L
    private var headersMs = -1L

    private var opens = 0
    private var range: String? = null
    private var host: String? = null
    private var proto: String? = null
    private var inflightAtStart = -1
    private var hostInflightAtStart = -1
    private var code = -1

    private fun now() = SystemClock.elapsedRealtime()

    override fun callStart(call: Call) {
        callT0 = now()
        val request = call.request()
        range = request.header("Range")
        val reqHost = request.url.host
        host = reqHost
        val before = PlaybackConnectionEvents.enter(reqHost)
        inflightAtStart = before[0]
        hostInflightAtStart = before[1]
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsT0 = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsEndT = now()
        dnsMs = if (dnsT0 > 0L) (dnsEndT - dnsT0).coerceAtLeast(0L) else -1L
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        opens += 1
        connT0 = now()
    }

    override fun secureConnectStart(call: Call) {
        tlsT0 = now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = if (tlsT0 > 0L) (now() - tlsT0).coerceAtLeast(0L) else -1L
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        connEndT = now()
        connMs = if (connT0 > 0L) (connEndT - connT0).coerceAtLeast(0L) else -1L
        if (protocol != null) proto = protocol.toString()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        connMs = if (connT0 > 0L) (now() - connT0).coerceAtLeast(0L) else -1L
        val currentHost = host ?: "unknown"
        val msg = "NET_CONN id=$id connectFailed after ${connMs}ms host=$currentHost err=${ioe.message}"
        PlaybackConnectionEvents.recordEvent(msg)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        acqT = now()
        if (proto == null) proto = connection.protocol().toString()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        headersT = now()
        headersMs = if (callT0 > 0L) (headersT - callT0).coerceAtLeast(0L) else -1L
        code = response.code
        if (!response.isRedirect) {
            PlaybackConnectionEvents.setResolvedHost(response.request.url.host)
        }
    }

    override fun callEnd(call: Call) {
        emit("ok")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit("failed")
    }

    override fun canceled(call: Call) {
        emit("canceled")
    }

    private fun emit(outcome: String) {
        if (!emitted.compareAndSet(false, true)) return

        val currentHost = host ?: "unknown"
        if (callT0 > 0L) {
            PlaybackConnectionEvents.exit(currentHost)
        }

        val totalMs = if (callT0 > 0L) (now() - callT0).coerceAtLeast(0L) else -1L
        val rangeLabel = range ?: "none"
        val protoLabel = proto ?: "unknown"
        val preDnsMs = if (dnsT0 > 0L && callT0 > 0L) (dnsT0 - callT0).coerceAtLeast(0L) else -1L
        val routeMs = if (dnsEndT > 0L && connT0 > 0L) (connT0 - dnsEndT).coerceAtLeast(0L) else -1L
        val ttfbMs = if (connEndT > 0L && headersT > 0L) (headersT - connEndT).coerceAtLeast(0L) else -1L
        val acqToHdrMs = if (acqT > 0L && headersT > 0L) (headersT - acqT).coerceAtLeast(0L) else -1L
        val logLine = "NET_CONN id=$id outcome=$outcome pooled=${opens == 0} opens=$opens " +
            "dns=${dnsMs}ms connect=${connMs}ms tls=${tlsMs}ms " +
            "headers=${headersMs}ms total=${totalMs}ms " +
            "preDns=${preDnsMs}ms route=${routeMs}ms ttfb=${ttfbMs}ms acqToHdr=${acqToHdrMs}ms " +
            "inflight=$inflightAtStart hostInflight=$hostInflightAtStart " +
            "code=$code proto=$protoLabel range=$rangeLabel host=$currentHost"
        PlaybackConnectionEvents.recordEvent(logLine)
    }
}

internal object PlaybackConnectionEvents : EventListener.Factory {
    private val seq = AtomicLong(0L)

    private val inflightTotal = AtomicInteger(0)
    private val inflightPerHost = ConcurrentHashMap<String, AtomicInteger>()
    private val recentLogs = ConcurrentLinkedDeque<String>()
    private const val MAX_RECENT_LOGS = 50

    @Volatile private var resolvedServingHost: String? = null
    fun setResolvedHost(host: String?) { resolvedServingHost = host?.takeIf { it.isNotBlank() } }
    fun resolvedHost(): String? = resolvedServingHost
    fun clearResolvedHost() { resolvedServingHost = null }

    fun recordEvent(msg: String) {
        recentLogs.addLast(msg)
        while (recentLogs.size > MAX_RECENT_LOGS) {
            recentLogs.pollFirst()
        }
    }

    fun recentEvents(): List<String> = recentLogs.toList()
    fun clear() {
        recentLogs.clear()
        resolvedServingHost = null
        inflightTotal.set(0)
        inflightPerHost.clear()
    }

    fun enter(host: String): IntArray {
        val hostCounter = inflightPerHost.getOrPut(host) { AtomicInteger(0) }
        val totalBefore = inflightTotal.getAndIncrement().coerceAtLeast(0)
        val hostBefore = hostCounter.getAndIncrement().coerceAtLeast(0)
        return intArrayOf(totalBefore, hostBefore)
    }

    fun exit(host: String) {
        inflightTotal.updateAndGet { if (it > 0) it - 1 else 0 }
        inflightPerHost[host]?.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun create(call: Call): EventListener =
        PlaybackConnectionEventListener(seq.incrementAndGet())
}
