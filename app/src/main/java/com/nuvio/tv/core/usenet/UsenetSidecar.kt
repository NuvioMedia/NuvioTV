package com.nuvio.tv.core.usenet

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Subtitle
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes the APK-installed PIE binary using the same Android-supported
 * nativeLibraryDir mechanism as TorrServer. All media remains in the child.
 * Provider secrets only cross an authenticated loopback control request.
 */
class UsenetSidecar private constructor(private val context: Context) {
    init { UsenetStartupDiagnostics.initialize(context) }
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private var process: Process? = null
    private var processProfile: String? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var sessionId: String? = null
    @Volatile private var playbackUrl: String? = null
    private var idleStop: Job? = null
    private val preparationGuard = Any()
    private var preparationJob: Job? = null
    private var preparationOwner: Any? = null
    private var preparationKey: PreparationKey? = null
    private var preparationGeneration: Any? = null
    // The existing session owns all parsed NZB metadata and cached article bytes.
    // These fields are protected by mutex; no second video cache is allocated.
    private var preparedStream: Stream? = null
    private var preparedKey: PreparationKey? = null
    private var preparedGeneration: Any? = null
    private var preparedExpiry: Job? = null

    private data class PreparationKey(
        val nzb: String?, val servers: List<String>?, val headers: Map<String, String>,
        val fileIdx: Int?, val selector: String?, val season: Int?, val episode: Int?,
        val configuration: UsenetConfiguration, val profileId: Int?
    )

    private fun preparationKey(stream: Stream, season: Int?, episode: Int?, profileId: Int?) =
        PreparationKey(stream.nzbUrl, stream.servers?.toList(),
            stream.behaviorHints?.proxyHeaders?.request.orEmpty().toMap(), stream.fileIdx,
            stream.fileMustInclude, season, episode, UsenetSettings.read(context), profileId)

    /** Start immediately. Only one candidate may own a prepared session. */
    fun prefetch(owner: Any, stream: Stream, season: Int?, episode: Int?, profileId: Int?): Job {
        val key = preparationKey(stream, season, episode, profileId)
        if (!key.configuration.prefetchResults) return Job().apply { complete() }
        return synchronized(preparationGuard) {
            if (preparationOwner === owner && preparationKey == key) return@synchronized preparationJob!!
            preparationJob?.cancel()
            preparationOwner = owner
            preparationKey = key
            val generation = Any()
            preparationGeneration = generation
            cleanup.launch {
                mutex.withLock {
                    if (!appForeground || !UsenetSettings.read(context).prefetchResults ||
                        (sessionId != null && preparedStream == null)) return@withLock
                    if (preparedStream != null) releaseSessionLocked()
                    try {
                        val resolved = openLocked(stream, season, episode, profileId)
                        preparedStream = resolved
                        preparedKey = key
                        preparedGeneration = generation
                        preparedExpiry = cleanup.launch {
                            delay(120_000)
                            mutex.withLock {
                                if (preparedGeneration === generation) {
                                    preparedExpiry = null
                                    releaseSessionLocked()
                                }
                            }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        // Optional preparation is silent; selecting it retries normally.
                    }
                }
            }.also { preparationJob = it }
        }
    }

    fun cancelPrefetch(owner: Any? = null): Job {
        val generation = synchronized(preparationGuard) {
            if (owner != null && preparationOwner !== owner) return Job().apply { complete() }
            preparationJob?.cancel()
            preparationJob = null; preparationOwner = null; preparationKey = null
            preparationGeneration.also { preparationGeneration = null }
        }
        return cleanup.launch { mutex.withLock {
            if (generation != null && preparedGeneration === generation) releaseSessionLocked()
        } }
    }
    private val startupReader = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "usenet-sidecar-io").apply { isDaemon = true }
    }

    /** Shares the resolve mutex so launch/Play cannot start duplicate children.
     * No NZB, provider credentials, or NNTP connections are needed to warm Go. */
    fun prewarm(): Job = cleanup.launch {
        mutex.withLock {
            if (sessionId != null) return@withLock
            if (!appForeground || !UsenetSettings.read(context).prewarmOnLaunch) {
                stopLocked()
                return@withLock
            }
            try {
                start()
                // Visibility can change while the blocking bootstrap runs.
                if (!appForeground) stopLocked()
            } catch (_: Exception) {
                // Optional startup must not crash browsing; Play can retry and
                // report its normal actionable error if the engine is missing.
                stopLocked()
            }
        }
    }

    suspend fun resolve(stream: Stream, season: Int?, episode: Int?, profileId: Int? = null): Stream = withContext(Dispatchers.IO) {
        val key = preparationKey(stream, season, episode, profileId)
        val selectionTrace = UsenetStartupDiagnostics.Trace(key.configuration.fastMkvStartup, key.configuration.fastNzbFetch)
        val pending = synchronized(preparationGuard) {
            if (preparationKey != key) preparationJob?.cancel()
            preparationJob
        }
        // Joining outside the session mutex lets a click share an in-flight open.
        pending?.join()
        mutex.withLock {
            synchronized(preparationGuard) {
                if (preparationJob === pending) {
                    preparationJob = null; preparationOwner = null; preparationKey = null; preparationGeneration = null
                }
            }
            val cached = preparedStream
            if (cached != null && preparedKey == key && process?.let(::isRunning) == true) {
                clearPreparedLocked()
                selectionTrace.mark("prepared_session_reused")
                UsenetStartupDiagnostics.bind(requireNotNull(cached.url), selectionTrace, null)
                return@withLock cached
            }
            if (preparedStream != null) releaseSessionLocked()
            openLocked(stream, season, episode, profileId, selectionTrace)
        }
    }

    private suspend fun openLocked(stream: Stream, season: Int?, episode: Int?, profileId: Int?,
        selectionTrace: UsenetStartupDiagnostics.Trace? = null): Stream {
        val configuration = UsenetSettings.read(context)
        val trace = selectionTrace ?: UsenetStartupDiagnostics.Trace(configuration.fastMkvStartup, configuration.fastNzbFetch)
        return try {
            trace.mark("resolve_lock_acquired")
            start()
            trace.mark("engine_ready")
            val request = JSONObject().apply {
                put("cacheScope", profileId?.toString() ?: "unscoped")
                put("nzbUrl", stream.nzbUrl)
                put("servers", JSONArray(stream.servers.orEmpty()))
                stream.fileIdx?.let { put("fileIdx", it) }
                stream.fileMustInclude?.let { put("fileMustInclude", it) }
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
                put("headers", JSONObject(stream.behaviorHints?.proxyHeaders?.request.orEmpty()))
                put("config", JSONObject().apply {
                    put("profile", configuration.profile)
                    put("readAhead", configuration.readAhead)
                    put("maxConnections", configuration.maxConnections)
                    put("fastMkvStartup", configuration.fastMkvStartup)
                    put("fastNzbFetch", configuration.fastNzbFetch)
                    put("cacheNzb", configuration.cacheNzb)
                })
            }
            trace.mark("session_request")
            val result = JSONObject(control("/sessions", request))
            trace.mark("session_response")
            val newId = result.getString("id")
            val newPath = result.getString("path")
            require(newId.matches(Regex("[0-9a-f]{48}")) && newPath.startsWith("/stream/$newId/"))
            val oldId = sessionId
            sessionId = newId
            val url = "$endpoint/stream/$newId/${Uri.encode(result.getString("filename").substringAfterLast('/'))}"
            playbackUrl = url
            UsenetStartupDiagnostics.bind(url, trace, result.optJSONObject("startup"))
            if (oldId != null) control("/sessions/$oldId", null, delete = true)
            val nativeSubtitles = result.optJSONArray("subtitles")?.let { items ->
                (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    val subtitleId = item.getString("id")
                    require(subtitleId.toIntOrNull() != null)
                    Subtitle(id = "usenet-$newId-$subtitleId",
                        url = "$endpoint/subtitle/$newId/$subtitleId/${Uri.encode(item.getString("filename"))}",
                        lang = item.optString("lang", "und"), addonName = stream.addonName,
                        addonLogo = stream.addonLogo, isStreamProvided = true)
                }
            }.orEmpty()
            trace.mark("resolved")
            stream.copy(
                url = url, nzbUrl = null, servers = null, externalUrl = null, infoHash = null,
                subtitles = stream.subtitles + nativeSubtitles,
                behaviorHints = (stream.behaviorHints ?: com.nuvio.tv.domain.model.StreamBehaviorHints(null, null, null, null)).copy(
                    proxyHeaders = null, filename = result.getString("filename"), videoSize = result.getLong("size")
                )
            )
        } catch (e: Exception) {
            stopLocked()
            throw e
        }
    }

    fun captureStartup(url: String) {
        cleanup.launch { mutex.withLock {
            if (url != playbackUrl) return@withLock
            val id = sessionId ?: return@withLock
            runCatching {
                val request = Request.Builder().url("$endpoint/sessions/$id/diagnostics")
                    .header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) UsenetStartupDiagnostics.complete(url, JSONObject(response.body!!.string()))
                }
            }
        } }
    }

    /** Only release the session owned by the exiting player. An older screen must
     * not tear down a newer playback session during navigation. */
    fun release(url: String?) {
        cleanup.launch { mutex.withLock { if (url != null && url == playbackUrl) releaseSessionLocked() } }
    }

    fun releaseIfDifferent(nextUrl: String?) {
        if (nextUrl == null || !isSessionUrl(nextUrl)) cancelPrefetch()
        val previous = playbackUrl
        cleanup.launch { mutex.withLock { if (previous != null && playbackUrl == previous && previous != nextUrl) releaseSessionLocked() } }
    }

    private suspend fun releaseSessionLocked() {
        clearPreparedLocked()
        val id = sessionId
        sessionId = null; playbackUrl = null
        try { if (id != null) control("/sessions/$id", null, delete = true) } catch (_: Exception) { stopLocked(); return }
        if (!appForeground) { stopLocked(); return }
        idleStop?.cancel()
        if (UsenetSettings.read(context).prewarmOnLaunch) { idleStop = null; return }
        // Keep the initialized Go runtime and trust roots across episode changes,
        // while dropping all provider sockets, credentials and article buffers.
        idleStop = cleanup.launch { delay(30_000); mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    fun onAppBackgrounded() {
        cancelPrefetch()
        cleanup.launch { mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    private fun clearPreparedLocked() {
        preparedExpiry?.cancel(); preparedExpiry = null
        preparedStream = null; preparedKey = null
        preparedGeneration = null
    }

    fun settingsChanged(): Job {
        // Invalidate unused sessions so a previously enabled warmer cannot bypass
        // a changed toggle. Playback that has already adopted its session continues.
        val invalidation = cancelPrefetch()
        return cleanup.launch {
            invalidation.join()
            mutex.withLock {
                if (!UsenetSettings.read(context).cacheNzb) {
                    File(context.cacheDir, "usenet-nzb").listFiles()?.forEach { it.delete() }
                }
            }
            prewarm().join()
        }
    }

    private fun start() {
        idleStop?.cancel(); idleStop = null
        val cfg = UsenetSettings.read(context)
        if (process?.let(::isRunning) == true && endpoint != null && processProfile == cfg.profile) return
        stopLocked()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libnuvio_usenet.so")
        if (!binary.canExecute()) throw IOException("Usenet engine is missing for this device's CPU")
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val child = ProcessBuilder(binary.absolutePath).start()
        process = child
        processProfile = cfg.profile
        startupReader.execute { child.errorStream.use { input -> val bytes = ByteArray(4096); while (input.read(bytes) >= 0) { /* no credentials in Android logs */ } } }
        val bootstrap = JSONObject().apply {
            put("token", secret)
            put("nzbCacheDir", File(context.cacheDir, "usenet-nzb").absolutePath)
            put("memoryMiB", when (cfg.profile) { "low-memory" -> 96; "throughput" -> 224; else -> 144 })
            // Go cannot consistently locate Android's APEX trust store across
            // OS versions. Export the system trust manager's public CA certs.
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val certs = (tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager).acceptedIssuers
            put("certificates", JSONArray(certs.map { cert ->
                "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(cert.encoded, Base64.NO_WRAP) + "\n-----END CERTIFICATE-----\n"
            }))
        }
        // Keep stdin open until stopLocked. Parent death closes the pipe and
        // causes the Go process to terminate even if onCleared never ran.
        child.outputStream.write((bootstrap.toString() + "\n").toByteArray())
        child.outputStream.flush()
        val ready = startupReader.submit<String> { child.inputStream.bufferedReader().readLine() ?: throw IOException("Usenet engine exited before startup") }
        try {
            val record = JSONObject(ready.get(15, TimeUnit.SECONDS))
            require(record.getInt("protocol") == 1)
            val port = record.getInt("port"); require(port in 1..65535)
            endpoint = "http://127.0.0.1:$port"; token = secret
        } catch (e: Exception) { ready.cancel(true); stopLocked(); throw IOException("Usenet engine could not start", e) }
    }

    private suspend fun control(path: String, payload: JSONObject?, delete: Boolean = false): String {
        val request = Request.Builder().url("$endpoint$path").header("Authorization", "Bearer $token")
        if (delete) request.delete() else request.post(payload.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Usenet engine connection failed")) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        val text = it.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (it.isSuccessful) continuation.resume(text)
                        else continuation.resumeWithException(IOException(text.take(240).ifBlank { "Usenet engine returned HTTP ${it.code}" }))
                    } } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Usenet engine response was interrupted"))
                    }
                }
            })
        }
    }

    private fun stopLocked() {
        clearPreparedLocked()
        idleStop?.cancel(); idleStop = null
        val child = process
        process = null; processProfile = null; endpoint = null; token = null; sessionId = null; playbackUrl = null
        if (child != null) {
            runCatching { child.outputStream.close() }
            // Process.isAlive / timed waitFor / destroyForcibly require API 26;
            // Nuvio also supports API 24 and 25 TVs. Android destroy sends SIGKILL.
            runCatching {
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (isRunning(child) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                if (isRunning(child)) child.destroy()
            }
            runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
        }
    }

    companion object {
        private fun isRunning(child: Process): Boolean = try { child.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        @Volatile private var instance: UsenetSidecar? = null
        @Volatile private var appForeground = true
        fun get(context: Context): UsenetSidecar = instance ?: synchronized(this) {
            instance ?: UsenetSidecar(context.applicationContext).also { instance = it }
        }
        fun onAppForegrounded() { appForeground = true }
        fun stopIdleOnBackground() { appForeground = false; instance?.onAppBackgrounded() }
        fun isSessionUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:") && "/stream/" in url
        fun isSubtitleUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:") && "/subtitle/" in url
    }
}
