package com.nuvio.tv.core.connection

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Coarse network class. Throughput is learned separately for each one. */
internal enum class NetworkKind {
    WIFI,
    CELLULAR,
    OTHER,
}

@Serializable
internal data class ConnectionSpeedSample(
    val network: NetworkKind,
    val mbps: Double,
    val recordedAtMs: Long,
)

/**
 * Learns sustained download throughput from real playback, per [NetworkKind], and owns the
 * "match streams to connection" preference.
 *
 * Samples come passively from [PlaybackThroughput]; nothing is measured when a stream list
 * loads, so reading the estimate is a memory lookup. Kept outside PlayerSettingsDataStore so
 * upstream settings need no new fields or migrations.
 */
internal object ConnectionSpeedEstimator {
    private const val PREFS_NAME = "nuvio_tv_connection_speed"
    private const val KEY_SAMPLES = "throughput_samples_v2"
    private const val KEY_ENABLED = "prefer_connection_fit"
    private const val MAX_SAMPLES_PER_NETWORK = 3
    private const val MIN_SAMPLES = 2
    private const val MAX_SAMPLE_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var cachedSamples: List<ConnectionSpeedSample>? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Bumped on every recorded sample, so a settings row can refresh its status line. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (preferences != null) return
        synchronized(lock) {
            if (preferences != null) return
            val appContext = context.applicationContext
            appContext.getSystemService(ConnectivityManager::class.java)?.let(DefaultNetworkObserver::start)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
            preferences = prefs
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _enabled.value = enabled
        preferences?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    /** The estimate for the current network, or null while still learning or offline. */
    fun estimateMbps(context: Context): Double? {
        ensureLoaded(context)
        val network = DefaultNetworkObserver.kind ?: return null
        return estimateMbps(loadedSamples(), network, System.currentTimeMillis())
    }

    /** Records a sample for [network], the network it was measured on (not necessarily the current one). */
    fun record(context: Context, network: NetworkKind, mbps: Double) {
        if (!mbps.isValidThroughput()) return
        ensureLoaded(context)
        synchronized(lock) {
            val updated = appendSample(
                loadedSamples(),
                ConnectionSpeedSample(network, mbps, System.currentTimeMillis())
            )
            cachedSamples = updated
            preferences?.edit()?.putString(KEY_SAMPLES, json.encodeToString(updated))?.apply()
        }
        _revision.update { it + 1 }
    }

    /**
     * The best of the last few samples. Each sample is capped by whichever server delivered it,
     * so the fastest one is the tightest lower bound on the connection itself; one slow host
     * must not make every other source look unplayable. Only the most recent samples count, so
     * a connection that really got slower takes over within a few playbacks. At least two
     * samples are required so a single session never drives ranking on its own.
     */
    internal fun estimateMbps(
        samples: List<ConnectionSpeedSample>,
        network: NetworkKind,
        nowMs: Long,
    ): Double? {
        var count = 0
        var best = 0.0
        for (index in samples.indices.reversed()) {
            val sample = samples[index]
            if (sample.network != network) continue
            if (nowMs - sample.recordedAtMs !in 0..MAX_SAMPLE_AGE_MS || !sample.mbps.isValidThroughput()) continue
            if (sample.mbps > best) best = sample.mbps
            if (++count == MAX_SAMPLES_PER_NETWORK) break
        }
        return best.takeIf { count >= MIN_SAMPLES }
    }

    internal fun appendSample(
        samples: List<ConnectionSpeedSample>,
        sample: ConnectionSpeedSample,
    ): List<ConnectionSpeedSample> {
        val (sameNetwork, otherNetworks) = samples.partition { it.network == sample.network }
        return otherNetworks + sameNetwork.takeLast(MAX_SAMPLES_PER_NETWORK - 1) + sample
    }

    private fun loadedSamples(): List<ConnectionSpeedSample> =
        cachedSamples ?: synchronized(lock) {
            cachedSamples ?: runCatching {
                json.decodeFromString<List<ConnectionSpeedSample>>(
                    preferences?.getString(KEY_SAMPLES, null).orEmpty()
                )
            }.getOrDefault(emptyList()).also { cachedSamples = it }
        }
}

/**
 * Follows the default network through one system callback, so reading its kind on the stream
 * load and playback paths is a field read rather than a binder call into the system server.
 * [generation] changes whenever the default network changes; a measurement that spans a
 * change describes neither network and is dropped.
 */
internal object DefaultNetworkObserver : ConnectivityManager.NetworkCallback() {
    @Volatile var kind: NetworkKind? = null
        private set

    @Volatile var generation = 0
        private set

    private var network: Network? = null
    private var isStarted = false

    @Synchronized
    fun start(manager: ConnectivityManager) {
        if (isStarted) return
        isStarted = true
        // Seed synchronously; the callback's first update arrives asynchronously.
        runCatching { manager.activeNetwork?.let { active -> update(active, manager.getNetworkCapabilities(active)) } }
        runCatching { manager.registerDefaultNetworkCallback(this) }
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        update(network, capabilities)
    }

    /** A switch can report the new default before the old one is lost, so only the current one clears. */
    @Synchronized
    override fun onLost(network: Network) {
        if (network == this.network) update(null, null)
    }

    /** Called on every signal-strength change too, so it only bumps [generation] on real changes. */
    @Synchronized
    private fun update(network: Network?, capabilities: NetworkCapabilities?) {
        val newKind = capabilities?.toNetworkKind()
        if (network == this.network && newKind == kind) return
        this.network = network
        kind = newKind
        generation++
    }

    private fun NetworkCapabilities.toNetworkKind(): NetworkKind = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        else -> NetworkKind.OTHER
    }
}

private const val MIN_VALID_MBPS = 0.2
private const val MAX_VALID_MBPS = 1_000.0

internal fun Double.isValidThroughput(): Boolean = isFinite() && this in MIN_VALID_MBPS..MAX_VALID_MBPS

/**
 * Measures sustained download throughput over one playback session and reports it once.
 *
 * Only ticks where the player is fetching and data actually arrives count. Players stop
 * downloading once their buffer is full, and they also wait on connection setup, redirects and
 * seeks (resume position, file index) without receiving anything; counting either would make a
 * fast connection look slow. A genuinely slow link still delivers data on every tick.
 *
 * The first second of transfer is skipped: it is mostly TCP/TLS slow start and reads low on
 * fast lines. The sample is tied to the network it was measured on and dropped if the
 * default network changed during the measurement. Adds no requests and no polling of its own:
 * it runs on the player's existing progress tick.
 */
internal class PlaybackThroughputSampler(
    sourceUrl: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val networkKind: () -> NetworkKind? = { DefaultNetworkObserver.kind },
    private val networkGeneration: () -> Int = { DefaultNetworkObserver.generation },
    private val onSample: (NetworkKind, Double) -> Unit,
) {
    private val isEligible = sourceUrl.isInternetPlaybackSource()
    private var lastTick: TimeMark? = null
    private var warmupMs = 0L
    private var activeBytes = 0L
    private var activeMs = 0L
    private var measuredNetwork: NetworkKind? = null
    private var measuredGeneration = 0
    private var isFinished = false

    /** [bytes] is the number of bytes received since the previous tick. */
    fun onBytesTick(bytes: Long, isFetching: Boolean) {
        tick(isFetching) { bytes }
    }

    /** For players that report a transfer rate rather than a byte count. */
    fun onRateTick(bytesPerSecond: Long, isFetching: Boolean) {
        tick(isFetching) { elapsedMs -> bytesPerSecond * elapsedMs / 1000 }
    }

    fun finish() {
        if (isFinished) return
        isFinished = true
        val network = measuredNetwork ?: return
        if (activeMs < MIN_WINDOW_MS) return
        if (activeBytes < MIN_WINDOW_BYTES && activeMs < SLOW_WINDOW_MS) return
        if (networkGeneration() != measuredGeneration) return
        onSample(network, activeBytes * 8.0 / activeMs / 1000.0)
    }

    private inline fun tick(isFetching: Boolean, bytesFor: (elapsedMs: Long) -> Long) {
        if (!isEligible || isFinished) return
        val previous = lastTick
        lastTick = timeSource.markNow()
        val elapsedMs = previous?.elapsedNow()?.inWholeMilliseconds ?: return
        // A long gap means the app was suspended; the interval says nothing about the network.
        if (!isFetching || elapsedMs <= 0 || elapsedMs > MAX_TICK_GAP_MS) return
        val bytes = bytesFor(elapsedMs)
        if (bytes <= 0L) return
        if (warmupMs < WARMUP_MS) {
            if (warmupMs == 0L) {
                measuredNetwork = networkKind() ?: run { isFinished = true; return }
                measuredGeneration = networkGeneration()
            }
            warmupMs += elapsedMs
            return
        }
        activeBytes += bytes
        activeMs += elapsedMs
        if (activeMs >= MAX_WINDOW_MS) finish()
    }

    private companion object {
        const val WARMUP_MS = 1_000L
        const val MIN_WINDOW_MS = 3_000L
        const val MIN_WINDOW_BYTES = 8L * 1024 * 1024
        const val SLOW_WINDOW_MS = 10_000L
        const val MAX_WINDOW_MS = 10_000L
        const val MAX_TICK_GAP_MS = 2_000L
    }
}

/**
 * True for http(s) sources reached over the internet. Local files, the on-device torrent
 * proxy and LAN servers measure something other than the internet connection.
 */
internal fun String.isInternetPlaybackSource(): Boolean {
    val value = trim()
    val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val authority = value.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.removePrefix("[").substringBefore(']')
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return false
    }
    if (':' in host) {
        return host != "::1" && !host.startsWith("fe80:") && !host.startsWith("fc") && !host.startsWith("fd")
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return true
    val first = octets[0]!!
    val second = octets[1]!!
    return !(
        first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
        )
}
