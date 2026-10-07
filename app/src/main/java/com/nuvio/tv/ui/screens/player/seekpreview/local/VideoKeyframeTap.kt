@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekpreview.local

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.ui.screens.player.RewrappableExtractor
import java.io.EOFException

/** Receives the video keyframes playback downloads, to turn them into free thumbnails. */
internal interface KeyframeSink {
    /** Cheap check made for every chunk of video data; false leaves the track untouched. */
    fun wantsKeyframes(): Boolean

    /** Only for keyframes the sink may want ([wantsKeyframe]); [data] is valid during the call only. */
    fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int)

    /** Whether a keyframe at [timeUs] would fill a missing thumbnail. */
    fun wantsKeyframe(timeUs: Long): Boolean

    /**
     * Whether the next keyframe after a sample at [timeUs] could still be wanted. False lets the
     * tap stop copying video until one could be; it must never be false where [wantsKeyframe] is true.
     */
    fun mayWantKeyframesNear(timeUs: Long): Boolean = true

    /** The stream's seek map, once its index has been read. */
    fun onSeekMap(seekMap: SeekMap) = Unit
}

/**
 * Wraps every extractor so the video keyframes ExoPlayer downloads are copied to [sink] while
 * being forwarded unchanged to the player. Nothing is downloaded twice; the copy happens at
 * load time, so keyframes are collected as far ahead as playback buffers.
 */
internal class VideoKeyframeExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val sink: KeyframeSink,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index -> VideoTapExtractor(extractors[index], sink) }
}

private class VideoTapExtractor(
    private val delegate: Extractor,
    private val sink: KeyframeSink,
) : RewrappableExtractor {
    override val wrappedExtractor: Extractor get() = delegate
    override fun rewrap(inner: Extractor): Extractor = VideoTapExtractor(inner, sink)

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    private var output: VideoTapExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val tapped = VideoTapExtractorOutput(output, sink)
        this.output = tapped
        delegate.init(tapped)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        output?.onSeek()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

private class VideoTapExtractorOutput(
    private val delegate: ExtractorOutput,
    private val sink: KeyframeSink,
) : ExtractorOutput {
    private val videoOutputs = HashMap<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return output
        return videoOutputs.getOrPut(id) { VideoTapTrackOutput(output, sink) }
    }

    override fun endTracks() = delegate.endTracks()

    /** Sample times start over somewhere else: watch for keyframes again until the first sample. */
    fun onSeek() {
        videoOutputs.values.forEach { (it as? VideoTapTrackOutput)?.onSeek() }
    }

    override fun seekMap(seekMap: SeekMap) {
        runCatching { sink.onSeekMap(seekMap) }
        delegate.seekMap(seekMap)
    }
}

/**
 * Keeps the bytes of the samples being written until [sampleMetadata] says whether they form a
 * keyframe (extractors may write a sample in parts and report it later, with [offset] bytes of
 * following samples already written). Same bookkeeping as the AutoSync audio tap.
 *
 * Bytes read from the stream go straight into [pending] and are handed to playback from there,
 * so each one is copied once. Between keyframes no slot could use (see
 * [KeyframeSink.mayWantKeyframesNear]) nothing is copied at all.
 */
private class VideoTapTrackOutput(
    private val delegate: TrackOutput,
    private val sink: KeyframeSink,
) : TrackOutput {
    private var format: Format? = null
    private var capturing = false
    private var pending = ByteArray(0)
    private var start = 0
    private var end = 0
    private val pendingReader = ParsableByteArray()

    /** Latest sample time since the stream started or seeked, or [C.TIME_UNSET]. */
    private var latestTimeUs = C.TIME_UNSET

    /** Whether the next keyframe could be wanted, judged from [latestTimeUs]. */
    private var keyframeInReach = true

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!updateCapturing() || !reserve(length)) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val read = input.read(pending, end, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pendingReader.reset(pending, end + read)
        pendingReader.setPosition(end)
        end += read
        delegate.sampleData(pendingReader, read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (updateCapturing()) append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (capturing) {
            val sampleStart = end - offset - size
            val currentFormat = format
            val usable = currentFormat != null && size > 0 && sampleStart >= start &&
                timeUs != C.TIME_UNSET && cryptoData == null &&
                flags and C.BUFFER_FLAG_KEY_FRAME != 0 && flags and C.BUFFER_FLAG_ENCRYPTED == 0
            if (usable) {
                try {
                    if (sink.wantsKeyframe(timeUs)) sink.onKeyframe(currentFormat!!, timeUs, pending, sampleStart, size)
                } catch (_: Throwable) {
                    // Never let thumbnails disturb playback.
                }
            }
            start = maxOf(start, end - offset)
            if (start == end) {
                start = 0
                end = 0
            }
        }
        if (timeUs != C.TIME_UNSET && (latestTimeUs == C.TIME_UNSET || timeUs > latestTimeUs)) {
            latestTimeUs = timeUs
            keyframeInReach = runCatching { sink.mayWantKeyframesNear(timeUs) }.getOrDefault(true)
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    fun onSeek() {
        latestTimeUs = C.TIME_UNSET
        keyframeInReach = true
    }

    private fun updateCapturing(): Boolean {
        val wanted = format != null && keyframeInReach && runCatching { sink.wantsKeyframes() }.getOrDefault(false)
        if (wanted != capturing) {
            capturing = wanted
            start = 0
            end = 0
        }
        return wanted
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || !reserve(length)) return
        data.copyInto(pending, end, offset, offset + length)
        end += length
    }

    /**
     * Makes room for [length] more bytes at [end]. False (and nothing kept) when that would pass
     * [MAX_PENDING_BYTES]; the bytes then reach playback without being kept.
     */
    private fun reserve(length: Int): Boolean {
        if (pending.size - end >= length) return true
        val retained = end - start
        if (start > 0) {
            pending.copyInto(pending, 0, start, end)
            start = 0
            end = retained
        }
        if (pending.size - end >= length) return true
        val required = end.toLong() + length
        if (required > MAX_PENDING_BYTES) {
            start = 0
            end = 0
            return false
        }
        pending = pending.copyOf(maxOf(required.toInt(), pending.size * 2, 65_536))
        return true
    }

    companion object {
        private const val MAX_PENDING_BYTES = 24 * 1024 * 1024
    }
}
