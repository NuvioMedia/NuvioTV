package com.nuvio.tv.ui.screens.player.iec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import com.nuvio.tv.ui.screens.player.DirectOpenProbeLock
import java.util.concurrent.atomic.AtomicBoolean

internal enum class HbrPayload {
    /** IEC 61937 burst (Pa/Pb + payload). HDMI InfoFrame = bitstream. */
    IEC_BURST,
    /** Raw Dolby MAT frame. HDMI InfoFrame = Dolby MAT / TrueHD. */
    MAT
}

internal interface IecAudioTrack {
    val sampleRate: Int
    val frameSizeBytes: Int
    val payload: HbrPayload
    val bufferSizeBytes: Int
    fun write(data: ByteArray, offset: Int, size: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun playbackHeadFrames(): Long
    fun setVolume(volume: Float)
    fun underrunCount(): Int
    fun isPlayHeld(): Boolean = false
    fun outputLatencyUs(): Long = 0L
    fun timestamp(): IecAudioTimestampSample? = null
}

internal const val IEC_MAX_OUTPUT_LATENCY_US = 5L * C.MICROS_PER_SECOND

internal fun iecOutputLatencyUs(reportedLatencyMs: Int, bufferSizeUs: Long): Long {
    if (reportedLatencyMs <= 0) return 0L
    val latencyUs = reportedLatencyMs * 1_000L - bufferSizeUs.coerceAtLeast(0L)
    if (latencyUs <= 0L || latencyUs > IEC_MAX_OUTPUT_LATENCY_US) return 0L
    return latencyUs
}

internal fun interface IecAudioTrackFactory {
    fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack?

    fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val track = open(sampleRate, channelCount, bufferSizeBytes = 0, sessionId = 0)
        track?.release()
        return track != null
    }

    fun iec61937Ready(): Boolean = false

    /** True when a background probe has opened IEC 61937 at [sampleRate]. */
    fun iec61937ReadyAt(sampleRate: Int): Boolean =
        sampleRate == 192_000 && iec61937Ready()

    /** Invoked (on the probe thread) when the background IEC61937 probe proves the encoding usable. */
    fun setReadyListener(listener: (() -> Unit)?) = Unit

    /** A live IEC track failed after opening; stop attempting IEC for this process. */
    fun markIecUnusable() = Unit

    fun startProbe() = Unit

    fun openHbr(
        sampleRate: Int,
        channelCount: Int,
        bufferSizeBytes: Int,
        sessionId: Int,
        trueHd: Boolean
    ): IecAudioTrack? = open(sampleRate, channelCount, bufferSizeBytes, sessionId)
}

/**
 * Compressed HBR track, never PCM.
 *
 * [AudioFormat.ENCODING_IEC61937] create can block the caller for seconds on HALs
 * that advertise the encoding then reject the track. Never open it on the
 * playback thread. A background probe records whether it actually initializes;
 * only then is IEC used. TrueHD also tries DOLBY_MAT (cheap, badge-preserving).
 */
internal class PlatformIecAudioTrackFactory : IecAudioTrackFactory {

    override fun startProbe() {
        startIec61937Probe()
    }

    override fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val mask = channelMaskFor(channelCount)
        val mat = dolbyMatEncoding()
        if (mat != null && AudioTrack.getMinBufferSize(sampleRate, mask, mat) > 0) {
            return true
        }
        return if (sampleRate == 176_400) iec176400Usable else iec61937Usable
    }

    override fun iec61937Ready(): Boolean = iec61937Usable

    override fun iec61937ReadyAt(sampleRate: Int): Boolean = when (sampleRate) {
        176_400 -> iec176400Usable
        192_000 -> iec61937Usable
        else -> false
    }

    override fun setReadyListener(listener: (() -> Unit)?) {
        iec61937ReadyListener = listener
    }

    override fun markIecUnusable() {
        iec61937Usable = false
        iec176400Usable = false
        iec61937ProbeExhausted = true
    }

    override fun open(
        sampleRate: Int,
        channelCount: Int,
        bufferSizeBytes: Int,
        sessionId: Int
    ): IecAudioTrack? = openHbr(sampleRate, channelCount, bufferSizeBytes, sessionId, trueHd = true)

    override fun openHbr(
        sampleRate: Int,
        channelCount: Int,
        bufferSizeBytes: Int,
        sessionId: Int,
        trueHd: Boolean
    ): IecAudioTrack? {
        val mask = channelMaskFor(channelCount)
        if (trueHd) {
            val mat = dolbyMatEncoding()
            if (mat != null) {
                val track = createTrack(sampleRate, mask, mat, bufferSizeBytes, sessionId)
                if (track != null) {
                    Log.i(TAG, "opened DOLBY_MAT $sampleRate/$channelCount")
                    return PlatformIecAudioTrack(
                        track.first,
                        sampleRate,
                        channelCount * 2,
                        HbrPayload.MAT,
                        track.second
                    )
                }
                Log.w(TAG, "DOLBY_MAT refused")
            }
        }
        val iecProbed = if (sampleRate == 176_400) iec176400Usable else iec61937Usable
        if (iecProbed) {
            val track = createTrack(
                sampleRate,
                mask,
                AudioFormat.ENCODING_IEC61937,
                bufferSizeBytes,
                sessionId
            )
            if (track != null) {
                Log.i(TAG, "opened IEC61937 $sampleRate/$channelCount")
                return PlatformIecAudioTrack(
                    track.first,
                    sampleRate,
                    channelCount * 2,
                    HbrPayload.IEC_BURST,
                    track.second
                )
            }
            if (sampleRate == 176_400) {
                iec176400Usable = false
                Log.w(TAG, "IEC61937 176.4 kHz open failed after probe")
            } else {
                iec61937Usable = false
                Log.w(TAG, "IEC61937 open failed after probe")
            }
        }
        return null
    }

    private fun createTrack(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSizeBytes: Int,
        sessionId: Int
    ): Pair<AudioTrack, Int>? {
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (min <= 0) return null
        val requested = maxOf(min, bufferSizeBytes)
        if (requested > min) {
            createTrackAtSize(sampleRate, channelMask, encoding, requested, sessionId)
                ?.let { return it to requested }
        }
        return createTrackAtSize(sampleRate, channelMask, encoding, min, sessionId)?.let { it to min }
    }

    private fun createTrackAtSize(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        size: Int,
        sessionId: Int
    ): AudioTrack? {
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (sessionId != AudioTrack.ERROR && sessionId != 0) {
                builder.setSessionId(sessionId)
            }
            val track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.pause()
            track.flush()
            track
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "IecPassthrough"

        private const val PROBE_ATTEMPTS = 3

        private val PROBE_DELAYS_MS = longArrayOf(0L, 2_000L, 1_000L)

        private const val PROBE_DEFER_RETRY_MS = 2_000L

        @Volatile
        private var iec61937Usable: Boolean = false

        @Volatile
        private var iec61937ProbeExhausted: Boolean = false

        @Volatile
        private var iec176400Usable: Boolean = false

        private val iec61937ProbeRunning = AtomicBoolean(false)

        @Volatile
        private var iec61937ReadyListener: (() -> Unit)? = null

        @Volatile
        private var iec61937ProbeResultListener: ((Boolean) -> Unit)? = null

        @Volatile
        private var iec61937ProbeRealFailures: Int = 0

        fun resetIec61937Probe(onResult: ((Boolean) -> Unit)? = null) {
            iec61937Usable = false
            iec176400Usable = false
            iec61937ProbeExhausted = false
            iec61937ProbeRealFailures = 0
            iec61937ProbeResultListener = onResult
            startIec61937Probe(ignoreLivePassthrough = true)
        }

        private fun reportProbeResult(usable: Boolean) {
            val listener = iec61937ProbeResultListener ?: return
            iec61937ProbeResultListener = null
            listener(usable)
        }

        fun invalidateIec61937ProbeMemo() {
            iec61937ProbeExhausted = false
            iec61937ProbeRealFailures = 0
        }

        fun startIec61937Probe(ignoreLivePassthrough: Boolean = false) {
            ensurePassthroughLiveClearedHook()
            if (iec61937Usable || iec61937ProbeExhausted) return
            if (!iec61937ProbeRunning.compareAndSet(false, true)) return
            Thread({
                try {
                    val mask = channelMaskFor(8)
                    val min = AudioTrack.getMinBufferSize(
                        192_000,
                        mask,
                        AudioFormat.ENCODING_IEC61937
                    )
                    if (min <= 0) {
                        Log.i(TAG, "IEC61937 probe: minBufferSize=$min")
                        iec61937ProbeExhausted = true
                        reportProbeResult(false)
                        return@Thread
                    }
                    var firstInThisRun = true
                    var attempt = iec61937ProbeRealFailures
                    while (attempt < PROBE_ATTEMPTS) {
                        when (
                            iecProbeAttemptAction(
                                usable = iec61937Usable,
                                passthroughLive = LiveDirectAudioPlayback.isPassthroughLive(),
                                ignoreLivePassthrough = ignoreLivePassthrough
                            )
                        ) {
                            IecProbeAttemptAction.STOP_USABLE -> return@Thread
                            IecProbeAttemptAction.DEFER -> {
                                Log.i(
                                    TAG,
                                    "IEC61937 probe: deferred, passthrough track live " +
                                        "(attempt ${attempt + 1} of $PROBE_ATTEMPTS); " +
                                        "retry in ${PROBE_DEFER_RETRY_MS}ms"
                                )
                                try {
                                    Thread.sleep(PROBE_DEFER_RETRY_MS)
                                } catch (_: InterruptedException) {
                                    return@Thread
                                }
                                continue
                            }
                            IecProbeAttemptAction.OPEN -> Unit
                        }
                        val delayMs = if (firstInThisRun) 0L else PROBE_DELAYS_MS[attempt]
                        firstInThisRun = false
                        if (delayMs > 0L) {
                            try {
                                Thread.sleep(delayMs)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                        val opened = synchronized(DirectOpenProbeLock) {
                            val track = try {
                                createTrackStatic(192_000, mask, AudioFormat.ENCODING_IEC61937, min)
                            } catch (_: Exception) {
                                null
                            }
                            track?.release()
                            track != null
                        }
                        if (opened) {
                            iec61937Usable = true
                            iec61937ProbeRealFailures = 0
                            Log.i(TAG, "IEC61937 probe: usable on attempt ${attempt + 1}")
                            iec61937ReadyListener?.invoke()
                            probe176400(mask)
                            reportProbeResult(true)
                            return@Thread
                        }
                        attempt++
                        iec61937ProbeRealFailures = attempt
                        Log.i(
                            TAG,
                            "IEC61937 probe: not usable, real attempt $attempt of $PROBE_ATTEMPTS"
                        )
                    }
                    iec61937ProbeExhausted = true
                    reportProbeResult(false)
                } finally {
                    iec61937ProbeRunning.set(false)
                }
            }, "iec61937-probe").apply { isDaemon = true }.start()
        }

        private fun ensurePassthroughLiveClearedHook() {
            LiveDirectAudioPlayback.setOnPassthroughLiveCleared {
                startIec61937Probe()
            }
        }

        private fun probe176400(mask: Int) {
            val min = AudioTrack.getMinBufferSize(
                176_400,
                mask,
                AudioFormat.ENCODING_IEC61937
            )
            if (min <= 0) {
                Log.i(TAG, "IEC61937 176.4 probe: minBufferSize=$min")
                return
            }
            val opened = synchronized(DirectOpenProbeLock) {
                val track = try {
                    createTrackStatic(176_400, mask, AudioFormat.ENCODING_IEC61937, min)
                } catch (_: Exception) {
                    null
                }
                track?.release()
                track != null
            }
            iec176400Usable = opened
            Log.i(TAG, "IEC61937 176.4 probe: ${if (opened) "usable" else "not usable"}")
        }

        private fun channelMaskFor(channelCount: Int): Int {
            return if (channelCount > 2) {
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

        private fun createTrackStatic(
            sampleRate: Int,
            channelMask: Int,
            encoding: Int,
            bufferSize: Int
        ): AudioTrack? {
            return try {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    null
                } else {
                    track
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun dolbyMatEncoding(): Int? {
            if (Build.VERSION.SDK_INT < 31) return null
            return try {
                AudioFormat::class.java.getField("ENCODING_DOLBY_MAT").getInt(null)
            } catch (_: Exception) {
                null
            }
        }
    }
}

private class PlatformIecAudioTrack(
    private val track: AudioTrack,
    override val sampleRate: Int,
    override val frameSizeBytes: Int,
    override val payload: HbrPayload,
    override val bufferSizeBytes: Int
) : IecAudioTrack {
    private val headTracker = IecPlaybackHeadTracker()
    private val settleGate = IecFlushSettleGate()
    private val audioTimestamp = AudioTimestamp()
    private var sampledLatencyUs: Long = 0L
    private var lastLatencySampleMs: Long = Long.MIN_VALUE
    private val latencyMethod: java.lang.reflect.Method? = try {
        AudioTrack::class.java.getMethod("getLatency")
    } catch (_: Exception) {
        null
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        return track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun play() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            if (!settleGate.mayPlay()) return
            headTracker.onPlay(track.playbackHeadPosition)
            track.play()
        }
    }

    override fun isPlayHeld(): Boolean = settleGate.isHolding()

    override fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
        }
    }

    override fun flush() {
        track.pause()
        track.flush()
        headTracker.onFlush()
        settleGate.onFlush()
    }

    override fun underrunCount(): Int = track.underrunCount

    override fun release() {
        try {
            track.pause()
            track.flush()
        } catch (_: Exception) {
        }
        track.release()
    }

    override fun playbackHeadFrames(): Long = headTracker.frames(track.playbackHeadPosition)

    override fun timestamp(): IecAudioTimestampSample? {
        return if (track.getTimestamp(audioTimestamp)) {
            IecAudioTimestampSample(audioTimestamp.framePosition, audioTimestamp.nanoTime)
        } else {
            null
        }
    }

    override fun outputLatencyUs(): Long {
        val method = latencyMethod ?: return 0L
        val nowMs = SystemClock.elapsedRealtime()
        if (lastLatencySampleMs != Long.MIN_VALUE &&
            nowMs - lastLatencySampleMs < MIN_LATENCY_SAMPLE_MS
        ) {
            return sampledLatencyUs
        }
        lastLatencySampleMs = nowMs
        val reported = try {
            method.invoke(track) as Int
        } catch (_: Exception) {
            return sampledLatencyUs
        }
        val bufferUs = if (frameSizeBytes > 0 && sampleRate > 0) {
            bufferSizeBytes.toLong() / frameSizeBytes * C.MICROS_PER_SECOND / sampleRate
        } else {
            0L
        }
        sampledLatencyUs = iecOutputLatencyUs(reported, bufferUs)
        return sampledLatencyUs
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume.coerceIn(0f, 1f))
    }
}

private const val MIN_LATENCY_SAMPLE_MS = 500L

internal enum class IecProbeAttemptAction { OPEN, DEFER, STOP_USABLE }

internal fun iecProbeAttemptAction(
    usable: Boolean,
    passthroughLive: Boolean,
    ignoreLivePassthrough: Boolean
): IecProbeAttemptAction {
    if (usable) return IecProbeAttemptAction.STOP_USABLE
    if (passthroughLive && !ignoreLivePassthrough) return IecProbeAttemptAction.DEFER
    return IecProbeAttemptAction.OPEN
}
