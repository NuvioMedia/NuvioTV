package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * What a Blu-ray clip's CLPI, BDMV/CLIPINF/<clip>.clpi beside BDMV/STREAM/<clip>.m2ts, tells a player:
 * its streams' languages, which the clip's PMT lacks, so without it a disc's tracks show unnamed;
 * and its EP map, where each video key frame starts, so a seek reads from there at once.
 */
internal object BlurayClipInfo {
    private const val TAG = "BlurayClipInfo"
    const val MAX_BYTES = 1 shl 20

    private val CLIP = Regex("/(BDMV)/(STREAM)/([^/]+)\\.(m2ts)$", RegexOption.IGNORE_CASE)

    // Per clip, as a player rebuilds its sources on retries and track changes.
    private val cache = object : LinkedHashMap<String, ClipInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClipInfo>?) = size > 16
    }

    class ClipInfo(val languages: Map<Int, String>, val entryPoints: EntryPoints?) {
        val isEmpty: Boolean get() = languages.isEmpty() && entryPoints == null

        companion object {
            val EMPTY = ClipInfo(emptyMap(), null)
        }
    }

    /**
     * A clip's video key frames, in order: each one's time from the clip's first, and its offset in
     * the clip as TsExtractor reads it, 188-byte packets once the BDAV headers are stripped.
     */
    class EntryPoints(val timesUs: LongArray, val positions: LongArray)

    /** The CLPI path beside a clip path, in the clip path's case, or null for any other path. */
    fun clipInfoPath(path: String): String? {
        val match = CLIP.find(path) ?: return null
        val (bdmv, stream, clip, extension) = match.destructured
        val dir = if (stream == stream.lowercase()) "clipinf" else "CLIPINF"
        val ext = if (extension == extension.lowercase()) "clpi" else "CLPI"
        return path.substring(0, match.range.first) + "/$bdmv/$dir/$clip.$ext"
    }

    /**
     * The CLPI of the clip at [uri]. Empty when the clip has none or it cannot be read, and the
     * clip then plays as it would without this.
     */
    fun info(uri: Uri, dataSourceFactory: DataSource.Factory): ClipInfo {
        val path = uri.path ?: return ClipInfo.EMPTY
        val infoPath = clipInfoPath(path) ?: return ClipInfo.EMPTY
        val key = uri.toString()
        synchronized(cache) { cache[key]?.let { return it } }
        // A failed read, such as one a seek cancelled, is not kept: the next load asks again.
        val bytes = read(uri.buildUpon().path(infoPath).build(), dataSourceFactory) ?: return ClipInfo.EMPTY
        val info = ClipInfo(parseLanguages(bytes), parseEntryPoints(bytes))
        Log.i(
            TAG,
            "clip info: ${info.languages.size} stream languages, " +
                "${info.entryPoints?.timesUs?.size ?: 0} entry points for ${uri.lastPathSegment}"
        )
        synchronized(cache) { cache[key] = info }
        return info
    }

    private fun read(uri: Uri, dataSourceFactory: DataSource.Factory): ByteArray? {
        val source = dataSourceFactory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).setLength(MAX_BYTES.toLong()).build())
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 shl 10)
            while (out.size() < MAX_BYTES) {
                val read = source.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } catch (e: Exception) {
            // Missing (404), unreachable or refused: the clip plays without names.
            Log.d(TAG, "no clip info: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Reads ProgramInfo: per stream its PID and StreamCodingInfo, where audio (0x80-0x86, 0xA1,
     * 0xA2), graphics (0x90, 0x91) and text subtitles (0x92) carry a language code.
     */
    fun parseLanguages(b: ByteArray): Map<Int, String> {
        if (b.size < 40 || String(b, 0, 4, Charsets.US_ASCII) != "HDMV") return emptyMap()
        val out = LinkedHashMap<Int, String>()
        var p = u32(b, 12) + 5 // Length and a reserved byte.
        if (p < 40 || p >= b.size) return out
        val sequences = b[p].u8()
        p++
        repeat(sequences) {
            if (p + 8 > b.size) return out
            val streams = b[p + 6].u8()
            p += 8
            repeat(streams) {
                if (p + 3 > b.size) return out
                val pid = (b[p].u8() shl 8) or b[p + 1].u8()
                val length = b[p + 2].u8()
                val info = p + 3
                p = info + length
                if (length < 1 || p > b.size) return out
                val at = when (b[info].u8()) {
                    in 0x80..0x86, 0xA1, 0xA2, 0x92 -> 2
                    0x90, 0x91 -> 1
                    else -> return@repeat
                }
                if (at + 3 > length) return@repeat
                val language = String(b, info + at, 3, Charsets.US_ASCII).lowercase()
                if (language != "und" && language.all { it in 'a'..'z' }) out.putIfAbsent(pid, language)
            }
        }
        return out
    }

    /**
     * Reads the CPI's EP map for the video stream (as libbluray's clpi_parse): coarse entries carry
     * a PTS's top bits and a source packet number's top bits, the fine entries they point to the
     * rest. Null when the clip has none, or its points do not run forward, as across a second STC
     * sequence; the clip then seeks as any TS does.
     */
    fun parseEntryPoints(b: ByteArray): EntryPoints? {
        if (b.size < 40 || String(b, 0, 4, Charsets.US_ASCII) != "HDMV") return null
        val cpi = u32(b, 16)
        if (cpi < 40 || cpi + 8 > b.size || u32(b, cpi) < 2 || (b[cpi + 5].u8() and 0x0F) != CPI_TYPE_EP_MAP) return null
        val map = cpi + 6
        val streams = b[map + 1].u8()
        if (streams == 0 || map + 2 + streams * 12 > b.size) return null
        // The video stream's map, or the first one.
        val entry = (0 until streams).map { map + 2 + it * 12 }
            .firstOrNull { ((u48(b, it + 2) ushr 34) and 0xF) == EP_STREAM_TYPE_VIDEO } ?: (map + 2)
        val fields = u48(b, entry + 2)
        val coarseCount = ((fields ushr 18) and 0xFFFF).toInt()
        val fineCount = (fields and 0x3FFFF).toInt()
        val stream = map + u32(b, entry + 8)
        if (coarseCount == 0 || fineCount == 0 || stream < map || stream + 4 > b.size) return null
        val fine = stream + u32(b, stream)
        if (stream + 4 + coarseCount * 8L > b.size || fine < stream || fine + fineCount * 4L > b.size) return null
        val times = LongArray(fineCount)
        val positions = LongArray(fineCount)
        var count = 0
        for (c in 0 until coarseCount) {
            val at = stream + 4 + c * 8
            val head = u32(b, at).toLong() and 0xFFFFFFFFL
            val firstFine = (head ushr 14).toInt()
            val coarsePts = head and 0x3FFE // Its low bit overlaps the fine PTS.
            val coarseSpn = (u32(b, at + 4).toLong() and 0xFFFFFFFFL) and 0x1FFFFL.inv()
            val lastFine = if (c + 1 < coarseCount) ((u32(b, at + 8).toLong() and 0xFFFFFFFFL) ushr 14).toInt() else fineCount
            if (firstFine != count || lastFine < firstFine || lastFine > fineCount) return null
            for (f in firstFine until lastFine) {
                val value = u32(b, fine + f * 4).toLong() and 0xFFFFFFFFL
                val pts = (coarsePts shl 19) + (((value ushr 17) and 0x7FF) shl 9)
                val position = (coarseSpn + (value and 0x1FFFF)) * TS_PACKET_SIZE
                if (count > 0 && (pts < times[count - 1] || position <= positions[count - 1])) return null
                times[count] = pts
                positions[count] = position
                count++
            }
        }
        if (count < 2) return null
        val first = times[0]
        for (i in 0 until count) times[i] = (times[i] - first) * 100 / 9 // 90 kHz ticks to microseconds.
        return EntryPoints(times.copyOf(count), positions.copyOf(count))
    }

    private const val CPI_TYPE_EP_MAP = 1
    private const val EP_STREAM_TYPE_VIDEO = 1L
    private const val TS_PACKET_SIZE = 188

    private fun Byte.u8() = toInt() and 0xFF

    private fun u48(b: ByteArray, at: Int): Long =
        (0 until 6).fold(0L) { value, i -> (value shl 8) or b[at + i].u8().toLong() }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].u8() shl 24) or (b[at + 1].u8() shl 16) or (b[at + 2].u8() shl 8) or b[at + 3].u8()

    /** A TS track's format id is "program/PID"; a format the PMT left unnamed takes the CLPI's language. */
    fun withLanguage(format: Format, languages: Map<Int, String>): Format {
        if (format.language != null && format.language != C.LANGUAGE_UNDETERMINED) return format
        val pid = format.id?.substringAfterLast('/')?.toIntOrNull() ?: return format
        val language = languages[pid] ?: return format
        return format.buildUpon().setLanguage(language).build()
    }

    /**
     * Wraps a clip's TsExtractor with its CLPI: each track's format carries the CLPI's language,
     * and with an EP map a seek reads from the key frame at or before its time, one request, where
     * TsExtractor would search the clip's PCRs over several.
     */
    class ClipInfoExtractor(
        private val delegate: Extractor,
        private val info: ClipInfo
    ) : Extractor {
        // The EP map's times run from the clip's first key frame, the clip's timeline from its first
        // timestamp, which can be a little earlier: the first sample, read from the start, says by how much.
        private var keyFrameOffsetUs = 0L
        private var clockSet = false
        private var seekedBeforeClock = false
        private var entryPointSeeks = false

        override fun sniff(input: ExtractorInput) = delegate.sniff(input)

        override fun init(output: ExtractorOutput) = delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                ClipInfoTrackOutput(output.track(id, type), info.languages) { timeUs -> onSample(type, timeUs) }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                val points = info.entryPoints
                entryPointSeeks = points != null && seekMap.isSeekable
                output.seekMap(
                    if (points != null && entryPointSeeks) EntryPointSeekMap(points, seekMap.durationUs) { keyFrameOffsetUs } else seekMap
                )
            }
        })

        private fun onSample(type: Int, timeUs: Long) {
            if (clockSet || seekedBeforeClock) return
            clockSet = true
            if (type == C.TRACK_TYPE_VIDEO) keyFrameOffsetUs = timeUs
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) {
            if (!clockSet && (position != 0L || timeUs != 0L)) seekedBeforeClock = true
            // At time 0 TsExtractor reads on from the key frame's position instead of searching. Its clock
            // then stays as a sample read from the start set it; before that, it searches as it would.
            delegate.seek(position, if (entryPointSeeks && clockSet) 0L else timeUs)
        }

        override fun release() = delegate.release()

        override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
    }

    /** The key frames of [points], at the clip's times. */
    class EntryPointSeekMap(
        private val points: EntryPoints,
        private val durationUs: Long,
        private val offsetUs: () -> Long
    ) : SeekMap {
        override fun isSeekable() = true

        override fun getDurationUs() = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val offset = offsetUs()
            val index = Util.binarySearchFloor(points.timesUs, timeUs - offset, /* inclusive= */ true, /* stayInBounds= */ true)
            val point = SeekPoint(points.timesUs[index] + offset, points.positions[index])
            if (point.timeUs >= timeUs || index == points.timesUs.size - 1) return SeekMap.SeekPoints(point)
            return SeekMap.SeekPoints(point, SeekPoint(points.timesUs[index + 1] + offset, points.positions[index + 1]))
        }
    }

    private class ClipInfoTrackOutput(
        private val delegate: TrackOutput,
        private val languages: Map<Int, String>,
        private val onSample: (Long) -> Unit
    ) : TrackOutput {
        override fun format(format: Format) = delegate.format(withLanguage(format, languages))

        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) =
            delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            onSample(timeUs)
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }
}
