package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal data class SubtitleReferenceTrack(
    val key: String,
    val name: String,
    val language: String?,
    val sourceMimeType: String,
    val cues: List<SrtCue>
)

internal data class SubtitleReferenceCaptureStatus(
    val eligibleTrackCount: Int,
    val capturedCueCount: Int
) {
    val isAvailable: Boolean get() = capturedCueCount >= MINIMUM_SYNC_CUES

    companion object {
        const val MINIMUM_SYNC_CUES = 12
    }
}

internal class SubtitleReferenceCueStore(
    private val onStatusChanged: ((SubtitleReferenceCaptureStatus) -> Unit)? = null
) {
    private class TrackState(
        val key: String,
        val name: String,
        val language: String?,
        val sourceMimeType: String,
        val cues: MutableMap<Long, SrtCue> = sortedMapOf()
    )

    private val tracks = ConcurrentHashMap<String, TrackState>()
    private val publishedStatus = AtomicReference(SubtitleReferenceCaptureStatus(0, 0))
    @Volatile private var largestTrackCueCount = 0

    fun clear() {
        tracks.clear()
        largestTrackCueCount = 0
        publish()
    }

    fun register(format: Format): String? {
        if (!format.isEligibleEnglishSubtitleReference()) return null
        val sourceMimeType = format.subtitleSourceMimeType() ?: return null
        val key = listOfNotNull(format.id, format.language, format.label).joinToString("|")
            .ifBlank { "english-subtitle" }
        val isNew = tracks.putIfAbsent(
            key,
            TrackState(
                key = key,
                name = format.label ?: format.language ?: "English subtitle",
                language = format.language,
                sourceMimeType = sourceMimeType
            )
        ) == null
        if (isNew) publish()
        return key
    }

    fun addCue(trackKey: String, cue: SrtCue) {
        val track = tracks[trackKey] ?: return
        synchronized(track) {
            if (track.cues.size >= MAX_CUES_PER_TRACK) return
            track.cues[cue.startMs] = cue
            if (track.cues.size > largestTrackCueCount) largestTrackCueCount = track.cues.size
        }
        publish()
    }

    /**
     * Bulk variant. [SubtitleReferenceScanner] recovers a whole subtitle index at once, and
     * publishing a status per cue meant thousands of [PlayerUiState] copies in a tight loop while
     * video was decoding.
     */
    fun addCues(trackKey: String, cues: Collection<SrtCue>) {
        if (cues.isEmpty()) return
        val track = tracks[trackKey] ?: return
        synchronized(track) {
            for (cue in cues) {
                if (track.cues.size >= MAX_CUES_PER_TRACK) break
                track.cues[cue.startMs] = cue
            }
            if (track.cues.size > largestTrackCueCount) largestTrackCueCount = track.cues.size
        }
        publish()
    }

    fun snapshot(): List<SubtitleReferenceTrack> = tracks.values
        .map { track ->
            synchronized(track) {
                SubtitleReferenceTrack(
                    track.key,
                    track.name,
                    track.language,
                    track.sourceMimeType,
                    track.cues.values.toList()
                )
            }
        }
        .sortedByDescending { it.cues.size }

    /**
     * The captured count is reported capped at [SubtitleReferenceCaptureStatus.MINIMUM_SYNC_CUES]
     * because that is all the UI ever renders ("captured N of 12"). Capping it means the status
     * stops changing once enough cues exist, so the callback -- which copies a 200 field UI state
     * and is invoked from the ExoPlayer loader thread -- fires a bounded number of times per
     * playback instead of once per subtitle cue.
     */
    private fun publish() {
        val status = SubtitleReferenceCaptureStatus(
            eligibleTrackCount = tracks.size,
            capturedCueCount = largestTrackCueCount
                .coerceAtMost(SubtitleReferenceCaptureStatus.MINIMUM_SYNC_CUES)
        )
        while (true) {
            val previous = publishedStatus.get()
            if (previous == status) return
            if (publishedStatus.compareAndSet(previous, status)) {
                onStatusChanged?.invoke(status)
                return
            }
        }
    }

    private companion object {
        /** Safety net so a pathological container cannot grow the store without bound. */
        const val MAX_CUES_PER_TRACK = 20_000
    }
}

internal fun Format.isEligibleEnglishSubtitleReference(): Boolean {
    if (id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) return false
    if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return false
    val trackTexts = listOfNotNull(label, id)
    if (trackTexts.any { value -> value.contains("forced", ignoreCase = true) }) return false
    if (trackTexts.any { value ->
            value.contains("songs", ignoreCase = true) && value.contains("sign", ignoreCase = true)
        }
    ) return false
    val isEnglish = PlayerSubtitleUtils.matchesLanguageCode(language, "en") ||
        trackTexts.any { value ->
            value.contains("english", ignoreCase = true) ||
                value.split(Regex("[^A-Za-z]+"))
                    .any { token -> token.equals("eng", ignoreCase = true) || token.equals("en", ignoreCase = true) }
        }
    if (!isEnglish) return false
    return subtitleSourceMimeType() in SUPPORTED_SUBTITLE_REFERENCE_MIME_TYPES
}

internal fun Format.subtitleSourceMimeType(): String? =
    if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType

private val SUPPORTED_SUBTITLE_REFERENCE_MIME_TYPES = setOf(
    MimeTypes.APPLICATION_SUBRIP,
    MimeTypes.APPLICATION_PGS,
    MimeTypes.APPLICATION_TX3G,
    MimeTypes.APPLICATION_MP4VTT,
    MimeTypes.TEXT_VTT,
    MimeTypes.APPLICATION_TTML
)

@UnstableApi
internal class SubtitleReferenceCaptureExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val store: SubtitleReferenceCueStore
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor = object : ForwardingExtractor(extractor) {
        override fun init(output: ExtractorOutput) {
            super.init(CapturingExtractorOutput(output, store))
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            super.read(input, seekPosition)
    }
}

@UnstableApi
private class CapturingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val store: SubtitleReferenceCueStore
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val output = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_TEXT) CapturingSubtitleTrackOutput(output, store) else output
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class CapturingSubtitleTrackOutput(
    delegate: TrackOutput,
    private val store: SubtitleReferenceCueStore
) : ForwardingTrackOutput(delegate) {
    private val cueDecoder = CueDecoder()
    private val pendingData = ExposedByteArrayOutputStream()
    private var trackKey: String? = null
    private var sampleMimeType: String? = null
    private var sourceMimeType: String? = null
    private var teeSource: DataReader? = null
    private var teeEnabled = false

    /**
     * Reused across every sample instead of allocating a lambda per call. [teeSource] is swapped
     * before each delegation; the extractor consumes it synchronously on one thread.
     */
    private val tee = DataReader { buffer, offset, requested ->
        val read = teeSource!!.read(buffer, offset, requested)
        if (read > 0 && teeEnabled) pendingData.write(buffer, offset, read)
        read
    }

    override fun format(format: Format) {
        pendingData.reset()
        trackKey = store.register(format)
        sampleMimeType = format.sampleMimeType
        sourceMimeType = format.subtitleSourceMimeType()
        super.format(format)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean
    ): Int = sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (trackKey == null) return super.sampleData(input, length, allowEndOfInput, sampleDataPart)
        teeSource = input
        teeEnabled = sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN
        return try {
            super.sampleData(tee, length, allowEndOfInput, sampleDataPart)
        } finally {
            teeSource = null
            teeEnabled = false
        }
    }

    override fun sampleData(data: ParsableByteArray, length: Int) {
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && trackKey != null && length > 0) {
            pendingData.write(data.data, data.position, length)
        }
        super.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        val key = trackKey
        if (key != null) {
            // Read the accumulated bytes in place. Copying the buffer out per sample cost tens of
            // megabytes of garbage over a film on bitmap subtitle tracks.
            val bytes = pendingData.buffer()
            val available = pendingData.size()
            if (timeUs != C.TIME_UNSET && size > 0) {
                val sampleStart = available - offset - size
                if (sampleStart >= 0 && sampleStart + size <= available) {
                    captureSample(key, timeUs, bytes, sampleStart, size)
                }
            }
            // Always rewind, including for samples that were skipped above. Previously the buffer
            // was only reset inside the capture branch, so a track emitting untimed or empty
            // samples grew it without bound for the whole session.
            val carry = offset.coerceIn(0, available)
            pendingData.retainLast(carry)
        }
        super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun captureSample(key: String, timeUs: Long, bytes: ByteArray, offset: Int, size: Int) {
        if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
            captureMedia3CueSample(key, timeUs, bytes, offset, size)
        } else if (sourceMimeType == MimeTypes.APPLICATION_SUBRIP) {
            captureRawSrtSample(key, timeUs, bytes, offset, size)
        } else if (isSubtitleDisplaySample(sourceMimeType, bytes, offset, size)) {
            val startMs = timeUs / 1000L
            store.addCue(key, SrtCue(startMs, startMs + 1_000L, PLACEHOLDER_TEXT))
        }
    }

    private fun captureMedia3CueSample(
        key: String,
        timeUs: Long,
        bytes: ByteArray,
        offset: Int,
        size: Int
    ) {
        // The decode is kept even though the text is discarded: it is what distinguishes a real
        // display cue from a clear-screen or empty packet, and feeding those to the aligner as
        // timing landmarks measurably degrades matching.
        runCatching { cueDecoder.decode(timeUs, bytes, offset, size) }
            .getOrNull()
            ?.takeIf { isDecodedSubtitleDisplay(it.cues.size, it.durationUs) }
            ?.let { decoded ->
                store.addCue(
                    key,
                    SrtCue(
                        startMs = decoded.startTimeUs / 1000L,
                        endMs = (decoded.startTimeUs + decoded.durationUs) / 1000L,
                        text = PLACEHOLDER_TEXT
                    )
                )
            }
    }

    private fun captureRawSrtSample(
        key: String,
        timeUs: Long,
        bytes: ByteArray,
        offset: Int,
        size: Int
    ) {
        val sampleText = String(bytes, offset, size, Charsets.UTF_8).trimEnd('\u0000')
        val relativeCues = SrtDocument.parse(sampleText).cues
        if (relativeCues.isEmpty()) return
        val sampleStartMs = timeUs / 1000L
        store.addCues(
            key,
            relativeCues.map { cue ->
                SrtCue(
                    startMs = sampleStartMs + cue.startMs,
                    endMs = sampleStartMs + cue.endMs,
                    text = PLACEHOLDER_TEXT
                )
            }
        )
    }

    private companion object {
        /**
         * Reference cue text is never read: [SubtitleTimingAligner] aligns on start timestamps
         * alone. Retaining a shared constant keeps a full subtitle track's worth of strings out of
         * memory for the whole playback session.
         */
        const val PLACEHOLDER_TEXT = " "
    }
}

/** [ByteArrayOutputStream] that exposes its backing array so samples can be read without a copy. */
private class ExposedByteArrayOutputStream : ByteArrayOutputStream(INITIAL_CAPACITY) {
    fun buffer(): ByteArray = buf

    /** Keeps only the final [count] bytes, discarding everything before them. */
    fun retainLast(count: Int) {
        if (count <= 0) {
            reset()
            return
        }
        System.arraycopy(buf, size() - count, buf, 0, count)
        this.count = count
    }

    private companion object {
        const val INITIAL_CAPACITY = 8 * 1024
    }
}

internal fun isDecodedSubtitleDisplay(cueCount: Int, durationUs: Long): Boolean =
    cueCount > 0 && durationUs != C.TIME_UNSET && durationUs > 0L

internal fun isSubtitleDisplaySample(
    sourceMimeType: String?,
    bytes: ByteArray,
    offset: Int,
    size: Int
): Boolean = when (sourceMimeType) {
    MimeTypes.APPLICATION_PGS -> {
        var position = offset
        val limit = offset + size
        var hasObjects = false
        while (position + 3 <= limit) {
            val segmentType = bytes[position].toInt() and 0xFF
            val segmentSize = ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                (bytes[position + 2].toInt() and 0xFF)
            if (position + 3 + segmentSize > limit) break
            if (segmentType == 0x16 && segmentSize >= 11 &&
                (bytes[position + 13].toInt() and 0xFF) > 0
            ) {
                hasObjects = true
                break
            }
            position += 3 + segmentSize
        }
        hasObjects
    }
    MimeTypes.APPLICATION_TX3G -> size >= 2 &&
        ((((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)) > 0)
    MimeTypes.APPLICATION_MP4VTT,
    MimeTypes.TEXT_VTT,
    MimeTypes.APPLICATION_TTML -> size > 0
    else -> false
}
