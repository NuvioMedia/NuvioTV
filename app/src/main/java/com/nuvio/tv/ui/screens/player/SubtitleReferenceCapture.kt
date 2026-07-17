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

internal data class SubtitleReferenceTrack(
    val key: String,
    val name: String,
    val language: String?,
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
    private data class TrackState(
        val key: String,
        val name: String,
        val language: String?,
        val cues: MutableMap<Long, SrtCue> = sortedMapOf()
    )

    private val tracks = ConcurrentHashMap<String, TrackState>()
    @Volatile private var lastStatus = SubtitleReferenceCaptureStatus(0, 0)

    fun clear() {
        tracks.clear()
        notifyAvailability()
    }

    fun register(format: Format): String? {
        if (!format.isEligibleEnglishEmbeddedSrt()) return null
        val key = listOfNotNull(format.id, format.language, format.label).joinToString("|")
            .ifBlank { "english-srt" }
        val isNew = tracks.putIfAbsent(
            key,
            TrackState(
                key = key,
                name = format.label ?: format.language ?: "English SRT",
                language = format.language
            )
        ) == null
        if (isNew) notifyAvailability()
        return key
    }

    fun addCue(trackKey: String, cue: SrtCue) {
        val track = tracks[trackKey] ?: return
        synchronized(track) {
            track.cues[cue.startMs] = cue
        }
        notifyAvailability()
    }

    fun snapshot(): List<SubtitleReferenceTrack> = tracks.values
        .map { track ->
            synchronized(track) {
                SubtitleReferenceTrack(track.key, track.name, track.language, track.cues.values.toList())
            }
        }
        .sortedByDescending { it.cues.size }

    private fun notifyAvailability() {
        val status = SubtitleReferenceCaptureStatus(
            eligibleTrackCount = tracks.size,
            capturedCueCount = tracks.values.maxOfOrNull { track ->
                synchronized(track) { track.cues.size }
            } ?: 0
        )
        if (status != lastStatus) {
            lastStatus = status
            onStatusChanged?.invoke(status)
        }
    }

}

internal fun Format.isEligibleEnglishEmbeddedSrt(): Boolean {
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
    val sourceMime = if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else sampleMimeType
    return sourceMime == MimeTypes.APPLICATION_SUBRIP
}

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
    private val pendingData = ByteArrayOutputStream()
    private var trackKey: String? = null
    private var sampleMimeType: String? = null

    override fun format(format: Format) {
        pendingData.reset()
        trackKey = store.register(format)
        sampleMimeType = format.sampleMimeType
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
        val tee = DataReader { buffer, offset, requested ->
            val read = input.read(buffer, offset, requested)
            if (read > 0 && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && trackKey != null) {
                pendingData.write(buffer, offset, read)
            }
            read
        }
        return super.sampleData(tee, length, allowEndOfInput, sampleDataPart)
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
        if (key != null && timeUs != C.TIME_UNSET && size > 0) {
            val bytes = pendingData.toByteArray()
            val sampleStart = bytes.size - offset - size
            if (sampleStart >= 0 && sampleStart + size <= bytes.size) {
                if (sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                    captureMedia3CueSample(key, timeUs, bytes, sampleStart, size)
                } else {
                    captureRawSrtSample(key, timeUs, bytes, sampleStart, size)
                }
            }
            val carry = offset.coerceIn(0, bytes.size)
            pendingData.reset()
            if (carry > 0) pendingData.write(bytes, bytes.size - carry, carry)
        }
        super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun captureMedia3CueSample(
        key: String,
        timeUs: Long,
        bytes: ByteArray,
        offset: Int,
        size: Int
    ) {
        runCatching { cueDecoder.decode(timeUs, bytes, offset, size) }
            .getOrNull()
            ?.takeIf { it.durationUs != C.TIME_UNSET && it.durationUs > 0L }
            ?.let { decoded ->
                val text = decoded.cues.mapNotNull { it.text?.toString() }
                    .joinToString("\n")
                    .ifBlank { " " }
                store.addCue(
                    key,
                    SrtCue(
                        startMs = decoded.startTimeUs / 1000L,
                        endMs = (decoded.startTimeUs + decoded.durationUs) / 1000L,
                        text = text
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
        val sampleText = bytes.decodeToString(offset, offset + size).trimEnd('\u0000')
        val relativeCues = SrtDocument.parse(sampleText).cues
        val sampleStartMs = timeUs / 1000L
        relativeCues.forEach { cue ->
            store.addCue(
                key,
                cue.copy(
                    startMs = sampleStartMs + cue.startMs,
                    endMs = sampleStartMs + cue.endMs
                )
            )
        }
    }
}
