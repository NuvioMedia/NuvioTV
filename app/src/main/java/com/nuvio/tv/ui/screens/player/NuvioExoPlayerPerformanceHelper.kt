package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Centralizes all Nuvio ExoPlayer performance enhancements behind a single toggle.
 *
 * When [enabled] is `true`, the helper applies:
 * - Large allocator segments (256 KB) with a 400 MB target buffer
 * - Extended buffer durations (200–280 s) with a 12 s back-buffer
 * - 50 Mbps initial bandwidth estimate
 * - Scrubbing mode for faster seeks (disables audio/metadata, boosts codec rate)
 * - In-buffer seek detection to suppress transient buffering UI
 * - HTTP/2 with an 8-connection pool for networking
 *
 * When [enabled] is `false`, stock ExoPlayer defaults are used everywhere.
 */
@androidx.media3.common.util.UnstableApi
object NuvioExoPlayerPerformanceHelper {

    /** Whether Nuvio performance enhancements are active. Set from [PlayerSettingsDataStore]. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            val newValue = value && supported
            field = newValue
            applyEngineConfig(newValue)
        }

    @Volatile
    var sharedConnectionPool: okhttp3.ConnectionPool = okhttp3.ConnectionPool(
        DEFAULT_NUVIO_CONNECTION_POOL_SIZE,
        3,
        java.util.concurrent.TimeUnit.MINUTES
    )
        private set

    // ─── Constants ────────────────────────────────────────────────────────────
    const val DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE = 64 * 1024        // 64 KB
    const val DEFAULT_NUVIO_TARGET_BUFFER_BYTES = 250 * 1024 * 1024    // 250 MB
    const val DEFAULT_NUVIO_MIN_BUFFER_MS = 40_000
    const val DEFAULT_NUVIO_MAX_BUFFER_MS = 120_000
    const val DEFAULT_NUVIO_BACK_BUFFER_MS = 1_000
    const val DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE = 50_000_000L     // 50 Mbps
    const val DEFAULT_NUVIO_CONNECTION_POOL_SIZE = 8

    // ─── Customization Variables ──────────────────────────────────────────────
    @Volatile
    var minBufferMs: Int = DEFAULT_NUVIO_MIN_BUFFER_MS

    @Volatile
    var maxBufferMs: Int = DEFAULT_NUVIO_MAX_BUFFER_MS

    @Volatile
    var bufferForPlaybackMs: Int = 3_000

    @Volatile
    var bufferForPlaybackAfterRebufferMs: Int = 3_000

    @Volatile
    var backBufferMs: Int = DEFAULT_NUVIO_BACK_BUFFER_MS

    @Volatile
    var targetBufferSizeMb: Int = 250

    @Volatile
    var connectionPoolSize: Int = DEFAULT_NUVIO_CONNECTION_POOL_SIZE

    @Volatile
    var enableHttp2: Boolean = false

    /**
     * Updates the performance helper with customized settings from PlayerSettings.
     */
    fun updateSettings(settings: PlayerSettings, context: Context) {
        val customBuffers = settings.bufferEngineEnabled
        val bufferSettings = settings.bufferSettings
        enableHttp2 = settings.enableHttp2
        
        minBufferMs = if (customBuffers) bufferSettings.minBufferMs else DEFAULT_NUVIO_MIN_BUFFER_MS
        maxBufferMs = if (customBuffers) bufferSettings.maxBufferMs else DEFAULT_NUVIO_MAX_BUFFER_MS
        bufferForPlaybackMs = if (customBuffers) bufferSettings.bufferForPlaybackMs else 3_000
        bufferForPlaybackAfterRebufferMs = if (customBuffers) bufferSettings.bufferForPlaybackAfterRebufferMs else 3_000
        backBufferMs = if (customBuffers) bufferSettings.backBufferDurationMs else DEFAULT_NUVIO_BACK_BUFFER_MS

        val safeLimitMb = getSafeNativeMemoryLimitMb(context)
        targetBufferSizeMb = if (customBuffers && !settings.bufferBudgetManaged) {
            val storedSize = bufferSettings.targetBufferSizeMb
            if (!settings.allowLargeTargetBuffer && storedSize > safeLimitMb) {
                safeLimitMb
            } else {
                storedSize
            }
        } else {
            safeLimitMb
        }

        val oldPoolSize = connectionPoolSize
        val customNetwork = settings.parallelNetworkEnabled
        connectionPoolSize = if (customNetwork && settings.useParallelConnections) {
            settings.parallelConnectionCount * 2
        } else {
            DEFAULT_NUVIO_CONNECTION_POOL_SIZE
        }
        if (connectionPoolSize != oldPoolSize) {
            sharedConnectionPool = okhttp3.ConnectionPool(
                connectionPoolSize,
                3,
                java.util.concurrent.TimeUnit.MINUTES
            )
        }
    }

    private const val SEEK_BACK_BUFFER_THRESHOLD_MS = 10_000L
    private const val SEEK_BACKWARD_TOLERANCE_MS = 2_000L
    const val SEEK_SUPPRESS_TIMEOUT_MS = 800L

    // ─── LoadControl ──────────────────────────────────────────────────────────

    /**
     * Helper to read system memory directly from /proc/meminfo as a reliable fallback.
     */
    private fun getRamFromMemInfo(): Long {
        return try {
            val file = java.io.File("/proc/meminfo")
            if (file.exists()) {
                file.useLines { lines ->
                    val firstLine = lines.firstOrNull() ?: ""
                    val match = java.util.regex.Pattern.compile("\\d+").matcher(firstLine)
                    if (match.find()) {
                        match.group().toLong() * 1024L
                    } else {
                        0L
                    }
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    @Volatile
    private var cachedDevicePhysicalRamBytes: Long = 0L

    /**
     * Clears the cached RAM size. Useful for testing.
     */
    fun clearCache() {
        cachedDevicePhysicalRamBytes = 0L
    }

    /**
     * Gets the total physical memory of the device in bytes.
     */
    fun getDevicePhysicalRamBytes(context: Context): Long {
        if (cachedDevicePhysicalRamBytes > 0L) {
            return cachedDevicePhysicalRamBytes
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) {
                cachedDevicePhysicalRamBytes = memoryInfo.totalMem
                return memoryInfo.totalMem
            }
        }
        val ram = getRamFromMemInfo()
        if (ram > 0L) {
            cachedDevicePhysicalRamBytes = ram
        }
        return ram
    }

    /**
     * Gets the total physical memory of the device in GB.
     */
    fun getDevicePhysicalRamGb(context: Context): Double {
        val totalBytes = getDevicePhysicalRamBytes(context)
        return totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Gets a friendly, marketed description of the device physical memory.
     * Uses mid-point boundaries adjusted for up to 20% hardware reservations.
     */
    fun getFriendlyRamLabel(context: Context): String {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> "Unknown"
            totalMem < 1.15 * gb -> "1 GB"
            totalMem < 1.45 * gb -> "1.5 GB"
            totalMem < 2.3 * gb -> "2 GB"
            totalMem < 3.2 * gb -> "3 GB"
            totalMem < 4.8 * gb -> "4 GB"
            totalMem < 6.8 * gb -> "6 GB"
            totalMem < 9.6 * gb -> "8 GB"
            totalMem < 13.8 * gb -> "12 GB"
            else -> "16 GB"
        }
    }

    /**
     * Calculates the safe ExoPlayer native target buffer size limit in MB based on RAM tier thresholds.
     */
    fun getSafeNativeMemoryLimitMb(context: Context): Int {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 250 // Safe default
            totalMem < 1.15 * gb -> 150
            totalMem < 1.45 * gb -> 200
            totalMem < 2.3 * gb -> 250
            totalMem < 3.2 * gb -> 500
            totalMem < 4.8 * gb -> 1000
            totalMem < 6.8 * gb -> 1600
            else -> 2000
        }
    }

    /**
     * Calculates the warning native target buffer size limit in MB based on RAM tier thresholds.
     */
    fun getWarningNativeMemoryLimitMb(context: Context): Int {
        val totalMem = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 325
            totalMem < 1.15 * gb -> 180
            totalMem < 1.45 * gb -> 250
            totalMem < 2.3 * gb -> 325
            totalMem < 3.2 * gb -> 650
            totalMem < 4.8 * gb -> 1200
            totalMem < 6.8 * gb -> 2000
            else -> 2500
        }
    }

    /**
     * Builds a [DefaultLoadControl] tuned for Nuvio performance when enabled,
     * or a standard ExoPlayer [DefaultLoadControl] when disabled.
     */
    fun buildLoadControl(context: Context? = null): DefaultLoadControl {
        return if (enabled) {
            val targetBufferBytes = (targetBufferSizeMb.toLong() * 1024L * 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE, 64, enabled))
                .setTargetBufferBytes(targetBufferBytes)
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs
                )
                .setBackBuffer(backBufferMs, true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()
        }
    }

    // ─── BandwidthMeter ───────────────────────────────────────────────────────

    /**
     * Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when
     * enabled, or the platform default when disabled.
     */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter {
        return if (enabled) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE)
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }
    }

    // ─── Seek / Scrubbing ─────────────────────────────────────────────────────

    /**
     * Returns [ScrubbingModeParameters] that disable audio/metadata decoding and
     * boost codec operating rate for the fastest possible seek, or `null` when
     * performance mode is off.
     */
    fun buildScrubbingParams(): ScrubbingModeParameters? {
        if (!enabled) return null
        return ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window (forward into [Player.getBufferedPosition] or
     * backward into the retained back-buffer).
     *
     * Only meaningful when performance mode is enabled; returns `false` otherwise.
     */
    fun isSeekInBuffer(player: ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - SEEK_BACK_BUFFER_THRESHOLD_MS - SEEK_BACKWARD_TOLERANCE_MS)
            .coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    // ─── Buffering UI ─────────────────────────────────────────────────────────

    /**
     * Determines whether transient buffering UI should be suppressed during a
     * seek operation. Returns `false` when performance mode is disabled so that
     * the stock buffering indicator always shows.
     *
     * @param suppressBufferingUiForSeek  Flag set when an in-buffer seek is active.
     * @param seekBufferingUiDeferred     Flag set during the 1 s grace window.
     * @param isBuffering                 Current [Player.STATE_BUFFERING] state.
     */
    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) ||
            (seekBufferingUiDeferred && isBuffering)
    }

    // ─── Networking ───────────────────────────────────────────────────────────

    /**
     * Applies HTTP/2 and a connection pool to the given [builder] when
     * performance mode is enabled. No-op otherwise.
     */
    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val withPool = builder.connectionPool(sharedConnectionPool)
        return if (enableHttp2) {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        } else {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        }
    }

    // ─── Audio Renderer ───────────────────────────────────────────────────────

    /**
     * Returns `true` when the audio renderer should bypass the codec for a
     * non-PCM format that the sink supports directly. Only active when
     * performance mode is enabled.
     */
    fun shouldBypassForNonPcmFormat(): Boolean {
        return enabled
    }

    // ─── Memory Logging ───────────────────────────────────────────────────────

    /**
     * Returns `true` when off-heap allocator memory logging should be active.
     */
    fun shouldLogMemoryFootprint(): Boolean {
        return enabled
    }

    // ─── Track Rebuild Guard ──────────────────────────────────────────────────

    /**
     * Returns `true` when track selection rebuild should be skipped after seeks
     * (only allow on first ready). When disabled, always rebuilds (stock behaviour).
     */
    fun shouldGuardTrackRebuild(): Boolean {
        return enabled
    }

    // ─── Engine Config ───────────────────────────────────────────────────────

    /**
     * Applies [NuvioEngineConfig] based on the toggle state.
     * When enabled: native off-heap allocation + zero-copy ByteBuffer pipeline + 64 KB scratch.
     * When disabled: stock heap allocation + standard byte[] pipeline + 4 KB scratch.
     *
     * Must be called **before** building an ExoPlayer instance.
     */
    private fun applyEngineConfig(performanceModeEnabled: Boolean) {
        if (performanceModeEnabled) {
            NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
        } else {
            NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        }
    }
}
