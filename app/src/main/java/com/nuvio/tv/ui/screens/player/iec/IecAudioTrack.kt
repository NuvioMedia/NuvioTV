package com.nuvio.tv.ui.screens.player.iec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.nuvio.tv.ui.screens.player.DirectOpenProbeLock

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
    fun write(data: ByteArray, offset: Int, size: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun playbackHeadFrames(): Long
    fun setVolume(volume: Float)
    fun underrunCount(): Int
}

internal fun interface IecAudioTrackFactory {
    fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack?

    fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val track = open(sampleRate, channelCount, bufferSizeBytes = 0, sessionId = 0)
        track?.release()
        return track != null
    }

    fun iec61937Ready(): Boolean = false

    /** Invoked (on the probe thread) when the background IEC61937 probe proves the encoding usable. */
    fun setReadyListener(listener: (() -> Unit)?) = Unit

    /** A live IEC track failed after opening; stop attempting IEC for this process. */
    fun markIecUnusable() = Unit

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

    init {
        startIec61937Probe()
    }

    override fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val mask = channelMaskFor(channelCount)
        val mat = dolbyMatEncoding()
        if (mat != null && AudioTrack.getMinBufferSize(sampleRate, mask, mat) > 0) {
            return true
        }
        return iec61937Usable
    }

    override fun iec61937Ready(): Boolean = iec61937Usable

    override fun setReadyListener(listener: (() -> Unit)?) {
        iec61937ReadyListener = listener
    }

    override fun markIecUnusable() {
        iec61937Usable = false
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
                    return PlatformIecAudioTrack(track, sampleRate, channelCount * 2, HbrPayload.MAT)
                }
                Log.w(TAG, "DOLBY_MAT refused")
            }
        }
        if (iec61937Usable) {
            val track = createTrack(
                sampleRate,
                mask,
                AudioFormat.ENCODING_IEC61937,
                bufferSizeBytes,
                sessionId
            )
            if (track != null) {
                Log.i(TAG, "opened IEC61937 $sampleRate/$channelCount")
                return PlatformIecAudioTrack(track, sampleRate, channelCount * 2, HbrPayload.IEC_BURST)
            }
            iec61937Usable = false
            Log.w(TAG, "IEC61937 open failed after probe")
        }
        return null
    }

    private fun createTrack(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSizeBytes: Int,
        sessionId: Int
    ): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (min <= 0) return null
        val size = maxOf(min, bufferSizeBytes)
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

        @Volatile
        private var iec61937Usable: Boolean = false

        @Volatile
        private var iec61937ProbeStarted: Boolean = false

        @Volatile
        private var iec61937ReadyListener: (() -> Unit)? = null

        fun startIec61937Probe() {
            if (iec61937ProbeStarted) return
            iec61937ProbeStarted = true
            Thread({
                val mask = channelMaskFor(8)
                val min = AudioTrack.getMinBufferSize(
                    192_000,
                    mask,
                    AudioFormat.ENCODING_IEC61937
                )
                if (min <= 0) {
                    Log.i(TAG, "IEC61937 probe: minBufferSize=$min")
                    return@Thread
                }
                // Serialised with the passthrough re-verification probe: one open direct
                // stream can make every other direct open fail on the HAL.
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
                    Log.i(TAG, "IEC61937 probe: usable")
                    iec61937ReadyListener?.invoke()
                } else {
                    Log.i(TAG, "IEC61937 probe: not usable")
                }
            }, "iec61937-probe").apply { isDaemon = true }.start()
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
    override val payload: HbrPayload
) : IecAudioTrack {
    private var headWrap: Long = 0L
    private var lastHead: Int = 0

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        return track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun play() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    override fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
        }
    }

    override fun flush() {
        track.pause()
        track.flush()
        headWrap = 0L
        lastHead = 0
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

    override fun playbackHeadFrames(): Long {
        val head = track.playbackHeadPosition
        if (head < lastHead) {
            headWrap += 1L shl 32
        }
        lastHead = head
        return headWrap + (head.toLong() and 0xFFFFFFFFL)
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume.coerceIn(0f, 1f))
    }
}
