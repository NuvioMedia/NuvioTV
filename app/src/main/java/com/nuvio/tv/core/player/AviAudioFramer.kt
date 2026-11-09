package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DtsUtil
import kotlin.math.abs

/**
 * Splits the audio chunks of an AVI stream into single AC-3 or DTS syncframes.
 *
 * AVI muxers pack several syncframes into one chunk, often cutting a frame across two chunks, and the stock reader
 * hands each chunk on as one sample. A passthrough sink counts every sample as one syncframe, so its clock falls
 * behind the sample times and it resyncs over and over. Each frame is timed from the audio before it, anchored to
 * the chunk times after a start or seek and again whenever the two drift apart by more than [RESYNC_US].
 * A stream without recognisable syncframes is passed on chunk by chunk, as before.
 */
@UnstableApi
internal class AviAudioFramer(private val codec: Codec, private val streamSampleRate: Int) {

    enum class Codec { AC3, DTS }

    fun interface FrameSink {
        fun frame(data: ByteArray, offset: Int, size: Int, timeUs: Long)
    }

    var engaged = false
        private set
    var passingThrough = false
        private set
    var framesOut = 0L
        private set
    var firstChunkFrames = 0
        private set
    var firstFrameBytes = 0
        private set
    var firstFrameBitrate = 0
        private set
    var reanchors = 0
        private set
    var lastReanchorDriftUs = 0L
        private set
    var bytesDropped = 0L
        private set

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var chunkBytes = 0
    private var pendingStartTimeUs = UNSET
    private var locked = false
    private var lockConfirmed = false
    private var anchorUs = UNSET
    private var anchorSamples = 0L
    private var anchorRate = 0
    private var parsedSamples = 0
    private var parsedRate = 0
    private val header = ByteArray(HEADER_BYTES)

    /** Takes up to [length] bytes of the current chunk from [read], which works like DataReader.read. */
    fun append(length: Int, read: (ByteArray, Int, Int) -> Int): Int {
        val wanted = minOf(length, MAX_READ_BYTES)
        if (buffer.size - size < wanted) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + wanted))
        val count = read(buffer, size, wanted)
        if (count > 0) {
            size += count
            chunkBytes += count
        }
        return count
    }

    /** The chunk whose bytes were appended since the last call ends here, stamped [chunkTimeUs] by the reader. */
    fun endChunk(chunkTimeUs: Long, sink: FrameSink) {
        val chunkStart = size - chunkBytes
        chunkBytes = 0
        if (pendingStartTimeUs == UNSET) pendingStartTimeUs = chunkTimeUs
        if (passingThrough) {
            flushWhole(sink)
            return
        }
        val framesBefore = framesOut
        var pos = 0
        while (true) {
            if (!locked) {
                val at = findLock(pos)
                if (at < 0) {
                    if (engaged) pos = drop(pos, maxOf(pos, size - (HEADER_BYTES - 1)))
                    break
                }
                if (!lockConfirmed) {
                    if (engaged) pos = drop(pos, at)
                    break
                }
                pos = drop(pos, at)
                engaged = true
                locked = true
            }
            if (pos + HEADER_BYTES > size) break
            val frameSize = frameSizeAt(pos)
            if (frameSize <= 0) {
                locked = false
                continue
            }
            if (pos + frameSize > size) break
            emit(pos, frameSize, chunkStart, chunkTimeUs, sink)
            pos += frameSize
        }
        if (firstChunkFrames == 0) firstChunkFrames = (framesOut - framesBefore).toInt()
        compact(pos)
        if (!engaged && size > MAX_SEARCH_BYTES) {
            passingThrough = true
            flushWhole(sink)
        } else if (size == 0 || framesOut > framesBefore) {
            pendingStartTimeUs = UNSET
        }
    }

    /** The reader moved (seek or restart): bytes held from before belong to another place in the file. */
    fun reset() {
        size = 0
        chunkBytes = 0
        pendingStartTimeUs = UNSET
        locked = false
        anchorUs = UNSET
        anchorSamples = 0L
    }

    private fun emit(pos: Int, frameSize: Int, chunkStart: Int, chunkTimeUs: Long, sink: FrameSink) {
        val samples = parsedSamples
        val rate = parsedRate
        val frameDurationUs = samples * MICROS_PER_SECOND / rate
        val chunkDerivedUs = chunkTimeUs + (pos - chunkStart) * frameDurationUs / frameSize
        if (anchorUs == UNSET) {
            anchorUs = chunkDerivedUs
            anchorSamples = 0L
            anchorRate = rate
        } else {
            val predictedUs = anchorUs + anchorSamples * MICROS_PER_SECOND / anchorRate
            if (abs(predictedUs - chunkDerivedUs) > RESYNC_US) {
                reanchors++
                lastReanchorDriftUs = chunkDerivedUs - predictedUs
                anchorUs = chunkDerivedUs
                anchorSamples = 0L
                anchorRate = rate
            } else if (rate != anchorRate) {
                anchorUs = predictedUs
                anchorSamples = 0L
                anchorRate = rate
            }
        }
        val timeUs = anchorUs + anchorSamples * MICROS_PER_SECOND / anchorRate
        anchorSamples += samples
        if (framesOut == 0L) {
            firstFrameBytes = frameSize
            firstFrameBitrate = (frameSize * 8L * rate / samples).toInt()
        }
        framesOut++
        sink.frame(buffer, pos, frameSize, timeUs)
    }

    private fun flushWhole(sink: FrameSink) {
        if (size > 0) sink.frame(buffer, 0, size, pendingStartTimeUs)
        size = 0
        pendingStartTimeUs = UNSET
    }

    // The first frame start at or after [from] whose header is followed by another frame header. [lockConfirmed]
    // is false when the candidate looks right but the bytes that would confirm it have not arrived yet.
    private fun findLock(from: Int): Int {
        var at = from
        while (at + HEADER_BYTES <= size) {
            val frameSize = frameSizeAt(at)
            if (frameSize > 0) {
                val next = at + frameSize
                if (next + HEADER_BYTES > size) {
                    lockConfirmed = false
                    return at
                }
                if (frameSizeAt(next) > 0) {
                    lockConfirmed = true
                    return at
                }
            }
            at++
        }
        return -1
    }

    private fun drop(from: Int, to: Int): Int {
        bytesDropped += to - from
        return to
    }

    private fun compact(pos: Int) {
        if (pos <= 0) return
        System.arraycopy(buffer, pos, buffer, 0, size - pos)
        size -= pos
    }

    /** Size of the syncframe starting at [at], or -1; sets [parsedSamples] and [parsedRate] for it. */
    private fun frameSizeAt(at: Int): Int {
        if (at + HEADER_BYTES > size) return -1
        return when (codec) {
            Codec.AC3 -> ac3FrameSizeAt(at)
            Codec.DTS -> dtsFrameSizeAt(at)
        }
    }

    private fun ac3FrameSizeAt(at: Int): Int {
        if (buffer[at] != AC3_SYNC_0 || buffer[at + 1] != AC3_SYNC_1) return -1
        val bsid = (buffer[at + 5].toInt() and 0xFF) shr 3
        if (bsid > AC3_MAX_BSID) return -1
        val fscod = (buffer[at + 4].toInt() and 0xFF) shr 6
        if (fscod >= AC3_SAMPLE_RATES.size) return -1
        System.arraycopy(buffer, at, header, 0, HEADER_BYTES)
        val frameSize = Ac3Util.parseAc3SyncframeSize(header)
        if (frameSize < AC3_MIN_FRAME_BYTES) return -1
        parsedSamples = AC3_SAMPLES_PER_FRAME
        parsedRate = AC3_SAMPLE_RATES[fscod]
        return frameSize
    }

    private fun dtsFrameSizeAt(at: Int): Int {
        if (streamSampleRate <= 0) return -1
        val word = ((buffer[at].toInt() and 0xFF) shl 24) or ((buffer[at + 1].toInt() and 0xFF) shl 16) or
            ((buffer[at + 2].toInt() and 0xFF) shl 8) or (buffer[at + 3].toInt() and 0xFF)
        if (DtsUtil.getFrameType(word) != DtsUtil.FRAME_TYPE_CORE) return -1
        System.arraycopy(buffer, at, header, 0, HEADER_BYTES)
        val frameSize = DtsUtil.getDtsFrameSize(header)
        val samples = DtsUtil.parseDtsAudioSampleCount(header)
        if (frameSize !in DTS_MIN_FRAME_BYTES..DTS_MAX_FRAME_BYTES || samples <= 0) return -1
        parsedSamples = samples
        parsedRate = streamSampleRate
        return frameSize
    }

    companion object {
        const val RESYNC_US = 1_000_000L
        const val MAX_SEARCH_BYTES = 64 * 1024
        private const val MAX_READ_BYTES = 64 * 1024
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val HEADER_BYTES = 16
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val UNSET = Long.MIN_VALUE
        private const val AC3_SYNC_0 = 0x0B.toByte()
        private const val AC3_SYNC_1 = 0x77.toByte()
        private const val AC3_MAX_BSID = 10
        private const val AC3_MIN_FRAME_BYTES = 128
        private const val AC3_SAMPLES_PER_FRAME = 1536
        private val AC3_SAMPLE_RATES = intArrayOf(48_000, 44_100, 32_000)
        private const val DTS_MIN_FRAME_BYTES = 96
        private const val DTS_MAX_FRAME_BYTES = 32 * 1024

        /** The codec to split for an AVI audio track, or null to leave the track as the reader delivers it. */
        fun codecFor(sampleMimeType: String?, sampleRate: Int): Codec? = when (sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> Codec.AC3
            MimeTypes.AUDIO_DTS -> if (sampleRate > 0) Codec.DTS else null
            else -> null
        }
    }
}
