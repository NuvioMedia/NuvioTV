package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * HDMI HBR passthrough that packs TrueHD / DTS-HD / DTS:X into IEC 61937 and
 * writes a CBR [AudioFormat.ENCODING_IEC61937] track.
 *
 * Android's RAW packer (`ENCODING_DOLBY_TRUEHD` / `ENCODING_DTS_HD`) is
 * byte-paced, so silence sprints and the media clock drifts. IEC bursts are
 * constant-rate at 192 kHz, so written frames equal content time.
 *
 * Formats this sink does not pack (AC-3, E-AC-3, DTS core, PCM) go through
 * the wrapped [AudioSink] unchanged. If IEC HBR cannot be opened, the same
 * wrapped sink is used — codecs are never rejected here.
 */
internal class IecPassthroughAudioSink(
    sink: AudioSink,
    private val trackFactory: IecAudioTrackFactory = PlatformIecAudioTrackFactory(),
    private val hbrIecEnabled: Boolean = true,
    private val onIecBecameReady: (() -> Unit)? = null
) : ForwardingAudioSink(sink) {

    private val matPacker = TrueHdMatPacker()
    private var iecTrack: IecAudioTrack? = null
    private var mode: Mode = Mode.FORWARD
    private val pendingFrames = ArrayDeque<ByteArray>()
    private var pendingOffset: Int = 0
    private var leftover: ByteArray = ByteArray(0)
    private var startPtsUs: Long = C.TIME_UNSET
    private var writtenFrames: Long = 0L
    private var playing: Boolean = false
    private var handledEndOfStream: Boolean = false
    private var audioSessionId: Int = 0
    private var volume: Float = 1f
    private var dtsChannelCount: Int = 8
    private var configuredFormat: Format? = null
    private var configuredBufferSize: Int = 0
    private var configuredOutputChannels: IntArray? = null
    private var iecFailedThisSession: Boolean = false
    private var consecutiveWriteStalls: Int = 0

    init {
        trackFactory.setReadyListener { onIecBecameReady?.invoke() }
    }

    val isIecActive: Boolean
        get() = mode != Mode.FORWARD && iecTrack != null

    override fun getFormatSupport(format: Format): Int {
        if (isHbrPassthrough(format) && iecAvailable(format)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (isHbrPassthrough(format) && iecAvailable(format)) return true
        return super.supportsFormat(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        releaseIec()
        // IEC61937 AudioTrack.Builder can block for seconds on HALs that advertise
        // the encoding then reject the track. Never wait for that on this thread:
        // TrueHD may use DOLBY_MAT immediately; IEC only if a background probe
        // already proved it initializes. DTS-HD uses RAW until then.
        val tryCustomHbr = hbrIecEnabled && !iecFailedThisSession && (isTrueHd(inputFormat) ||
            (isHbrPassthrough(inputFormat) && trackFactory.iec61937Ready()))
        if (tryCustomHbr) {
            val opened = openIec(inputFormat)
            if (opened) {
                mode = if (isTrueHd(inputFormat)) Mode.TRUEHD else Mode.DTS_HD
                dtsChannelCount = inputFormat.channelCount.takeIf { it > 0 } ?: 8
                android.util.Log.i(
                    "IecPassthrough",
                    "HBR active payload=${iecTrack?.payload} mime=${inputFormat.sampleMimeType}"
                )
                return
            }
        }
        mode = Mode.FORWARD
        if (isHbrPassthrough(inputFormat)) {
            android.util.Log.i(
                "IecPassthrough",
                "HBR RAW mime=${inputFormat.sampleMimeType} (compressed, not PCM)"
            )
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (mode == Mode.FORWARD) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (startPtsUs == C.TIME_UNSET && presentationTimeUs != C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }
        if (!drainPending()) return false
        val accepted = when (mode) {
            Mode.TRUEHD -> handleTrueHd(buffer)
            Mode.DTS_HD -> handleDtsHd(buffer)
            Mode.FORWARD -> super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        drainPending()
        return accepted
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isIecActive) return super.getCurrentPositionUs(sourceEnded)
        val track = iecTrack ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (writtenFrames == 0L || startPtsUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val head = minOf(track.playbackHeadFrames(), writtenFrames)
        return startPtsUs + head * C.MICROS_PER_SECOND / track.sampleRate
    }

    override fun play() {
        playing = true
        if (isIecActive) {
            iecTrack?.play()
        } else {
            super.play()
        }
    }

    override fun pause() {
        playing = false
        if (isIecActive) {
            iecTrack?.pause()
        } else {
            super.pause()
        }
    }

    override fun flush() {
        if (isIecActive) {
            resetIecState(keepTrack = true)
            iecTrack?.flush()
        } else {
            super.flush()
        }
    }

    override fun handleDiscontinuity() {
        if (isIecActive) {
            startPtsUs = C.TIME_UNSET
        } else {
            super.handleDiscontinuity()
        }
    }

    override fun reset() {
        releaseIec()
        mode = Mode.FORWARD
        super.reset()
    }

    override fun release() {
        releaseIec()
        mode = Mode.FORWARD
        super.release()
    }

    override fun playToEndOfStream() {
        if (isIecActive) {
            drainPending()
            handledEndOfStream = true
        } else {
            super.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        return if (isIecActive) {
            handledEndOfStream && !hasPendingData()
        } else {
            super.isEnded()
        }
    }

    override fun hasPendingData(): Boolean {
        if (!isIecActive) return super.hasPendingData()
        val track = iecTrack ?: return false
        return pendingFrames.isNotEmpty() || leftover.isNotEmpty() || writtenFrames > track.playbackHeadFrames()
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (!isIecActive) super.setAudioSessionId(audioSessionId)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (isIecActive) {
            iecTrack?.setVolume(volume)
        } else {
            super.setVolume(volume)
        }
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!isIecActive) return super.getAudioTrackBufferSizeUs()
        val track = iecTrack ?: return C.TIME_UNSET
        // Two MAT frames (40 ms) or four DTS-HD bursts (~43 ms).
        val bytes = if (mode == Mode.TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE * 2
        } else {
            (8192 shl 2) * 4
        }
        val frames = bytes / track.frameSizeBytes
        return frames * C.MICROS_PER_SECOND / track.sampleRate
    }

    private fun iecAvailable(format: Format): Boolean {
        if (!hbrIecEnabled) return false
        return trackFactory.canOpen(IEC_SAMPLE_RATE, hbrIecChannelCount(format))
    }

    private fun openIec(format: Format): Boolean {
        val channelCount = hbrIecChannelCount(format)
        val frameBytes = if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            TrueHdMatPacker.MAT_BUFFER_SIZE
        } else {
            Iec61937Packer.dtsHdIecPeriod(channelCount, 512) shl 2
        }
        val bufferBytes = frameBytes * if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) 2 else 4
        val track = trackFactory.openHbr(
            sampleRate = IEC_SAMPLE_RATE,
            channelCount = channelCount,
            bufferSizeBytes = bufferBytes,
            sessionId = audioSessionId,
            trueHd = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        ) ?: return false
        track.setVolume(volume)
        iecTrack = track
        return true
    }

    private fun hbrIecChannelCount(format: Format): Int {
        if (format.sampleMimeType == MimeTypes.AUDIO_TRUEHD) return 8
        val count = format.channelCount
        return if (count > 0) Iec61937Packer.dtsHdChannelMask(count) else 8
    }

    private fun handleTrueHd(buffer: ByteBuffer): Boolean {
        val data = concat(leftover, buffer)
        var offset = 0
        while (offset + 10 <= data.size) {
            val auSize = TrueHdMatPacker.trueHdAccessUnitSize(data, offset)
            if (auSize < 10 || offset + auSize > data.size) break
            val au = data.copyOfRange(offset, offset + auSize)
            offset += auSize
            if (matPacker.packAccessUnit(au)) {
                while (matPacker.hasFrame()) {
                    val mat = matPacker.pollFrame()!!
                    pendingFrames.add(
                        if (iecTrack?.payload == HbrPayload.MAT) mat
                        else Iec61937Packer.packTrueHd(mat)
                    )
                }
            }
        }
        leftover = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset, data.size)
        buffer.position(buffer.limit())
        return true
    }

    private fun handleDtsHd(buffer: ByteBuffer): Boolean {
        val au = ByteArray(buffer.remaining())
        buffer.get(au)
        val sampleCount = try {
            DtsUtil.parseDtsAudioSampleCount(au)
        } catch (_: Exception) {
            512
        }
        val period = Iec61937Packer.dtsHdIecPeriod(dtsChannelCount, sampleCount)
        pendingFrames.add(Iec61937Packer.packDtsHd(au, period))
        return true
    }

    private fun drainPending(): Boolean {
        val track = iecTrack ?: return true
        if (playing) track.play()
        while (pendingFrames.isNotEmpty()) {
            val frame = pendingFrames.first()
            while (pendingOffset < frame.size) {
                val written = track.write(frame, pendingOffset, frame.size - pendingOffset)
                if (written < 0) {
                    return fallbackToWrappedSink()
                }
                if (written == 0) {
                    if (++consecutiveWriteStalls >= MAX_WRITE_STALLS) {
                        return fallbackToWrappedSink()
                    }
                    return false
                }
                consecutiveWriteStalls = 0
                pendingOffset += written
                writtenFrames += written / track.frameSizeBytes
            }
            pendingFrames.removeFirst()
            pendingOffset = 0
        }
        return true
    }

    /**
     * The IEC track died mid-stream (HDMI unplug, HAL error). Drop it and keep
     * playing through the wrapped RAW sink instead of stalling forever.
     */
    private fun fallbackToWrappedSink(): Boolean {
        val format = configuredFormat
        android.util.Log.w("IecPassthrough", "IEC write failed; falling back to RAW")
        trackFactory.markIecUnusable()
        iecFailedThisSession = true
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
        if (format != null) {
            super.reset()
            super.configure(format, configuredBufferSize, configuredOutputChannels)
            if (playing) super.play()
        }
        return true
    }

    private fun resetIecState(keepTrack: Boolean) {
        matPacker.reset()
        pendingFrames.clear()
        pendingOffset = 0
        leftover = ByteArray(0)
        startPtsUs = C.TIME_UNSET
        writtenFrames = 0L
        handledEndOfStream = false
        consecutiveWriteStalls = 0
        if (!keepTrack) {
            iecTrack?.release()
            iecTrack = null
        }
    }

    private fun releaseIec() {
        resetIecState(keepTrack = false)
        mode = Mode.FORWARD
    }

    private enum class Mode { FORWARD, TRUEHD, DTS_HD }

    companion object {
        const val IEC_SAMPLE_RATE = 192_000
        internal const val MAX_WRITE_STALLS = 1_000

        fun isTrueHd(format: Format): Boolean {
            return format.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        }

        fun isHbrPassthrough(format: Format): Boolean {
            val mime = format.sampleMimeType ?: return false
            return isTrueHd(format) ||
                mime == MimeTypes.AUDIO_DTS_HD ||
                mime == MimeTypes.AUDIO_DTS_X ||
                mime.startsWith("audio/vnd.dts.hd") ||
                mime.startsWith("audio/vnd.dts.uhd")
        }

        private fun concat(prefix: ByteArray, buffer: ByteBuffer): ByteArray {
            if (prefix.isEmpty() && buffer.hasArray() && buffer.arrayOffset() == 0 &&
                buffer.position() == 0 && buffer.remaining() == buffer.array().size
            ) {
                val copy = ByteArray(buffer.remaining())
                val pos = buffer.position()
                buffer.get(copy)
                buffer.position(pos)
                return copy
            }
            val combined = ByteArray(prefix.size + buffer.remaining())
            System.arraycopy(prefix, 0, combined, 0, prefix.size)
            val pos = buffer.position()
            buffer.get(combined, prefix.size, buffer.remaining())
            buffer.position(pos)
            return combined
        }
    }
}
