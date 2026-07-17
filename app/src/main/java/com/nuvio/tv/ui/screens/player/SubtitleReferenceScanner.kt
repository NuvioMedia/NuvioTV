package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import com.nuvio.tv.core.player.dvmkv.TrackAwareSeekMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicReference

internal sealed interface SubtitleReferenceScanResult {
    data class Indexed(val cueCount: Int) : SubtitleReferenceScanResult
    data object Unsupported : SubtitleReferenceScanResult
    data object IndexUnavailable : SubtitleReferenceScanResult
    data object TimedOut : SubtitleReferenceScanResult
}

@UnstableApi
internal class SubtitleReferenceScanner(
    private val context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val store: SubtitleReferenceCueStore
) : Closeable {
    private val activeDataSource = AtomicReference<DataSource?>()
    @Volatile private var closed = false
    private var bytesRead = 0L
    private var streamLength = C.LENGTH_UNSET.toLong()
    private var reopenCount = 0

    suspend fun scan(): SubtitleReferenceScanResult = try {
        withTimeout(MAX_SCAN_TIME_MS) {
            runInterruptible(Dispatchers.IO) { scanBlocking() }
        }
    } catch (_: TimeoutCancellationException) {
        close()
        SubtitleReferenceScanResult.TimedOut
    }

    override fun close() {
        closed = true
        runCatching { activeDataSource.getAndSet(null)?.close() }
    }

    private fun scanBlocking(): SubtitleReferenceScanResult {
        if (!supportsRangeRequests()) return SubtitleReferenceScanResult.Unsupported

        val extractor = MatroskaExtractor(DefaultSubtitleParserFactory())
        val output = IndexedSubtitleExtractorOutput(store)
        var sourceAndInput = openInput(0L)
        try {
            val sniffed = try {
                extractor.sniff(sourceAndInput.input)
            } catch (_: EOFException) {
                false
            } finally {
                sourceAndInput.input.resetPeekPosition()
            }
            if (!sniffed) return SubtitleReferenceScanResult.Unsupported

            extractor.init(output)
            val seekPosition = PositionHolder()
            while (!closed && bytesRead < MAX_SCAN_BYTES) {
                val result = extractor.read(sourceAndInput.input, seekPosition)
                val indexed = output.indexedCues()
                if (indexed > 0) return SubtitleReferenceScanResult.Indexed(indexed)
                when (result) {
                    Extractor.RESULT_END_OF_INPUT -> return SubtitleReferenceScanResult.IndexUnavailable
                    Extractor.RESULT_SEEK -> {
                        if (seekPosition.position < 0L || ++reopenCount > MAX_REOPENS) {
                            return SubtitleReferenceScanResult.IndexUnavailable
                        }
                        sourceAndInput.close()
                        sourceAndInput = openInput(seekPosition.position)
                    }
                }
            }
            return SubtitleReferenceScanResult.IndexUnavailable
        } finally {
            sourceAndInput.close()
            extractor.release()
        }
    }

    private fun supportsRangeRequests(): Boolean {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = PlayerMediaSourceFactory.sanitizeHeaders(headers),
            method = "GET",
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            range = "bytes=0-0"
        )
        return try {
            connection.connect()
            connection.responseCode == 206
        } finally {
            connection.disconnect()
        }
    }

    private fun openInput(position: Long): SourceAndInput {
        check(!closed)
        val source = PlayerPlaybackNetworking.createDataSourceFactory(
            context,
            PlayerMediaSourceFactory.sanitizeHeaders(headers)
        ).createDataSource()
        activeDataSource.set(source)
        val openedLength = source.open(
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(position)
                .setLength((MAX_SCAN_BYTES - bytesRead).coerceAtLeast(1L))
                .build()
        )
        if (position > 0L) {
            val contentRange = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
            val returnedStart = contentRange
                ?.let { CONTENT_RANGE_PATTERN.find(it) }
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
            if (returnedStart != position) {
                source.close()
                activeDataSource.compareAndSet(source, null)
                error("Server ignored subtitle index range request")
            }
        }
        if (position == 0L && openedLength != C.LENGTH_UNSET.toLong()) streamLength = openedLength
        val countingReader = androidx.media3.common.DataReader { buffer, offset, length ->
            if (closed || bytesRead >= MAX_SCAN_BYTES) return@DataReader C.RESULT_END_OF_INPUT
            val allowed = minOf(length.toLong(), MAX_SCAN_BYTES - bytesRead).toInt()
            val read = source.read(buffer, offset, allowed)
            if (read > 0) bytesRead += read
            read
        }
        return SourceAndInput(
            source = source,
            input = DefaultExtractorInput(countingReader, position, streamLength),
            onClose = { activeDataSource.compareAndSet(source, null) }
        )
    }

    private class SourceAndInput(
        private val source: DataSource,
        val input: DefaultExtractorInput,
        private val onClose: () -> Unit
    ) : Closeable {
        override fun close() {
            onClose()
            runCatching { source.close() }
        }
    }

    private companion object {
        const val MAX_SCAN_TIME_MS = 8_000L
        const val MAX_SCAN_BYTES = 16L * 1024L * 1024L
        const val MAX_REOPENS = 4
        val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-\\d+/\\d+", RegexOption.IGNORE_CASE)
    }
}

@UnstableApi
private class IndexedSubtitleExtractorOutput(
    private val store: SubtitleReferenceCueStore
) : ExtractorOutput {
    private val eligibleTracks = mutableMapOf<Int, String>()
    private var seekMap: SeekMap? = null

    override fun track(id: Int, type: Int): TrackOutput {
        val discard = DiscardingTrackOutput()
        if (type != C.TRACK_TYPE_TEXT) return discard
        return object : ForwardingTrackOutput(discard) {
            override fun format(format: Format) {
                if (format.isEligibleEnglishEmbeddedSrt()) {
                    store.register(format)?.let { eligibleTracks[id] = it }
                }
                super.format(format)
            }
        }
    }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    fun indexedCues(): Int {
        val trackMap = seekMap as? TrackAwareSeekMap ?: return 0
        var total = 0
        eligibleTracks.forEach { (trackId, trackKey) ->
            val timesUs = trackMap.getCueTimesUs(trackId)
            timesUs.forEach { timeUs ->
                val startMs = timeUs / 1000L
                store.addCue(trackKey, SrtCue(startMs, startMs + 1_000L, " "))
            }
            total = maxOf(total, timesUs.size)
        }
        return total
    }
}
