package com.nuvio.tv.core.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.nuvio.tv.BuildConfig
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Independent HTTPS listener: existing configuration HTTP servers never receive remote credentials. */
class TvRemoteServer private constructor(private val context: Context) : NanoHTTPD(0) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("deviceId", it).commit())
    }
    val deviceName: String = Build.MODEL.take(100)
    private val monitor = Object()
    private val main = Handler(Looper.getMainLooper())
    private val polls = Semaphore(4)
    private var revision = 0L
    private var snapshot = RemoteSnapshot(deviceId = deviceId, deviceName = deviceName)
    private var owner: Any? = null
    private var executeCommand: ((RemoteCommand) -> Boolean)? = null
    private val pairing = RemotePairingAuthority(
        prefs.getString("credentialHash", null), SystemClock::elapsedRealtime, ::randomToken,
    ) { hash -> check(prefs.edit().putString("credentialHash", hash).commit()) }
    private val commands = RemoteCommandLedger()
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val certificate: X509Certificate

    init {
        val identity = RemoteTlsIdentity()
        certificate = identity.certificate
        makeSecure(identity.socketFactory, null)
    }
    fun beginPairing(): String = synchronized(monitor) {
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: error("Connect the TV to your home network first")
        val secret = pairing.open()
        android.net.Uri.Builder().scheme("nuvio-tv").authority("pair")
            .appendQueryParameter("v", "1").appendQueryParameter("id", deviceId)
            .appendQueryParameter("name", deviceName).appendQueryParameter("host", host)
            .appendQueryParameter("port", listeningPort.toString())
            .appendQueryParameter("pin", hash(certificate.encoded))
            .appendQueryParameter("secret", secret)
            .appendQueryParameter("expires", (System.currentTimeMillis() + 120_000).toString())
            .build().toString()
    }

    fun closePairing() = synchronized(monitor) { pairing.close() }
    fun hasPairedPhone(): Boolean = synchronized(monitor) { pairing.hasCredential }
    fun revoke() = synchronized(monitor) {
        pairing.revoke()
        commands.clear()
        monitor.notifyAll()
    }

    /** All callers and commands run on the main/player thread. Snapshots are immutable DTOs. */
    fun publish(source: Any, next: RemoteSnapshot, command: (RemoteCommand) -> Boolean) = synchronized(monitor) {
        if (owner !== source || snapshot.sessionId != next.sessionId) commands.clear()
        owner = source
        executeCommand = command
        snapshot = next.copy(deviceId = deviceId, deviceName = deviceName, revision = ++revision)
        monitor.notifyAll()
    }

    fun clear(source: Any) = synchronized(monitor) {
        if (owner === source) {
            owner = null
            executeCommand = null
            commands.clear()
            snapshot = RemoteSnapshot(deviceId = deviceId, deviceName = deviceName, revision = ++revision)
            monitor.notifyAll()
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        if (session.method == Method.POST && session.uri == "/v1/pair") {
            pair(json.decodeFromString<PairRequest>(readBody(session)))
        } else {
            val token = session.headers["authorization"]?.removePrefix("Bearer ").orEmpty()
            if (!authorized(token)) reply(Response.Status.UNAUTHORIZED) else when {
                session.method == Method.GET && session.uri == "/v1/playback" -> poll(session, token)
                session.method == Method.POST && session.uri == "/v1/command" -> command(token, json.decodeFromString(readBody(session)))
                session.method == Method.DELETE && session.uri == "/v1/pair" -> synchronized(monitor) {
                    if (!authorized(token)) reply(Response.Status.UNAUTHORIZED) else { revoke(); reply(Response.Status.OK) }
                }
                else -> reply(Response.Status.NOT_FOUND)
            }
        }
    } catch (_: Exception) { reply(Response.Status.BAD_REQUEST) }

    private fun readBody(session: IHTTPSession): String {
        val size = session.headers["content-length"]?.toIntOrNull() ?: error("Length required")
        require(size in 1..8192 && session.headers["transfer-encoding"] == null)
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = session.inputStream.read(bytes, offset, size - offset)
            require(read > 0)
            offset += read
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun pair(request: PairRequest): Response = synchronized(monitor) {
        if (request.deviceId != deviceId) return reply(Response.Status.FORBIDDEN)
        val token = pairing.pair(request.secret) ?: return reply(Response.Status.FORBIDDEN)
        commands.clear()
        monitor.notifyAll()
        reply(Response.Status.OK, json.encodeToString(PairResponse(deviceId, deviceName, token)))
    }

    private fun poll(session: IHTTPSession, token: String): Response {
        if (!polls.tryAcquire()) return reply(Response.Status.SERVICE_UNAVAILABLE)
        try {
            return synchronized(monitor) {
                val after = session.parameters["after"]?.firstOrNull()?.toLongOrNull()
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (after == snapshot.revision && authorized(token)) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    monitor.wait(remaining)
                }
                if (!authorized(token)) reply(Response.Status.UNAUTHORIZED)
                else reply(Response.Status.OK, json.encodeToString(snapshot))
            }
        } finally { polls.release() }
    }

    private fun command(token: String, request: RemoteCommand): Response {
        require(request.requestId.length in 16..80 && request.sessionId.length in 16..80)
        require(request.action in setOf("play", "pause", "seek"))
        require(if (request.action == "seek") request.positionMs != null && request.positionMs >= 0 else request.positionMs == null)
        val done = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var status = Response.Status.CONFLICT
        main.post {
            try {
                synchronized(monitor) {
                    if (cancelled.get()) return@synchronized
                    if (!authorized(token)) { status = Response.Status.UNAUTHORIZED; return@synchronized }
                    if (snapshot.sessionId != request.sessionId || snapshot.state == "idle") return@synchronized
                    val accepted = commands.execute(snapshot, request) { executeCommand?.invoke(it) == true }
                    if (accepted) status = Response.Status.OK
                }
            } finally { done.countDown() }
        }
        if (!done.await(3, TimeUnit.SECONDS)) { cancelled.set(true); return reply(Response.Status.SERVICE_UNAVAILABLE) }
        return reply(status)
    }

    private fun authorized(token: String): Boolean = synchronized(monitor) {
        pairing.authorized(token)
    }

    private fun reply(status: Response.Status, body: String = "{}") = newFixedLengthResponse(status, "application/json", body).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun advertise() {
        val nsd = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsdListener = listener
        runCatching { nsd.registerService(NsdServiceInfo().apply {
            serviceName = "Nuvio-${deviceId.take(8)}"
            serviceType = "_nuvio-remote._tcp."
            port = listeningPort
            setAttribute("id", deviceId)
            setAttribute("v", "1")
        }, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    companion object {
        @Volatile var instance: TvRemoteServer? = null
            private set
        fun start(context: Context) {
            if (BuildConfig.FLAVOR != "full" || instance != null) return
            // Keystore and listener startup must not block the TV UI.
            Thread({ runCatching {
                synchronized(this) {
                    if (instance == null) {
                        val server = TvRemoteServer(context.applicationContext)
                        server.start(30_000, true)
                        instance = server
                        server.advertise()
                    }
                }
            } }, "NuvioTvRemote").start()
        }
        private fun randomToken(): String = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
