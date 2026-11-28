package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Cues read whole and walked in memory must give the extractor exactly what reading them element
 * by element gives it. Each case runs the whole extractor twice over the same file, once each way,
 * and compares the seek map, the formats and the samples.
 */
class MatroskaWholeCuesTest {

    @Test
    fun `generated cues give the same seek map either way`() {
        for (seed in 1..60) {
            val random = Random(seed)
            val cues = generatedCues(random)
            for (cuesFirst in listOf(false, true)) {
                val file = mkv(cues, cuesBeforeCluster = cuesFirst)
                val whole = extract(file, whole = true)
                assertEquals("seed $seed cuesFirst $cuesFirst", extract(file, whole = false).summary, whole.summary)
                assertTrue(
                    "seed $seed cuesFirst $cuesFirst: cue points expected\n" + whole.summary.take(1500),
                    whole.summary.contains("seekable=true"),
                )
            }
        }
    }

    @Test
    fun `whole cues are read in a handful of reads and need no extra seek`() {
        val file = mkv(generatedCues(Random(7), cuePoints = 3_000))
        val whole = extract(file, whole = true)
        val streamed = extract(file, whole = false)
        assertEquals(streamed.summary, whole.summary)
        assertEquals(streamed.seeks, whole.seeks)
        // Up to the seek map; the Cues met again at the end of the file are read as before.
        assertTrue(
            "whole ${whole.readsToSeekMap} reads vs ${streamed.readsToSeekMap}",
            whole.readsToSeekMap * 20 < streamed.readsToSeekMap,
        )
    }

    @Test
    fun `cues the walk does not take are read element by element after a seek back`() {
        // A cluster timecode inside a cue point: the reader hands it to integerElement, which the
        // walk does not do, so the extractor must go back and let the reader handle it.
        val odd = element(ID_CUES, cuePoint(1_000, listOf(Triple(1, 4_096L, null))) +
            element(ID_CUE_POINT, uint(ID_CUE_TIME, 2_000) + uint(0xE7, 5) +
                element(ID_CUE_TRACK_POSITIONS, uint(ID_CUE_TRACK, 1) + uint(ID_CUE_CLUSTER_POSITION, 8_192))))
        val file = mkv(odd)
        val whole = extract(file, whole = true)
        val streamed = extract(file, whole = false)
        assertEquals(streamed.summary, whole.summary)
        assertEquals(streamed.seeks + 1, whole.seeks)
    }

    @Test
    fun `deeply nested cue points are read element by element without exhausting the stack`() {
        // Muxers put CuePoints in the Cues and CueTrackPositions in a CuePoint, nothing deeper, but
        // a file can nest them as deep as it likes. The walk must hand those Cues to the reader,
        // which keeps its nesting on the heap, instead of recursing once per level.
        val inner = cuePoint(1_000, listOf(Triple(1, 4_096L, null)))
        val headers = ArrayList<ByteArray>()
        var size = inner.size.toLong()
        repeat(100_000) {
            val header = idBytes(ID_CUE_POINT) + sizeBytes(size, 0)
            headers += header
            size += header.size
        }
        val nested = ByteArrayOutputStream()
        for (i in headers.indices.reversed()) nested.write(headers[i])
        nested.write(inner)
        val file = mkv(element(ID_CUES, nested.toByteArray()))
        val whole = extract(file, whole = true)
        val streamed = extract(file, whole = false)
        assertEquals(streamed.summary, whole.summary)
        assertEquals(streamed.seeks + 1, whole.seeks)
    }

    @Test
    fun `an I-O error while the cues are read is resumed where the input stopped`() {
        val cues = generatedCues(Random(11), cuePoints = 2_000)
        val file = mkv(cues)
        val cuesAt = indexOf(file, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B))
        for (failAt in listOf(cuesAt + 2, cuesAt + 9, cuesAt + cues.size / 2, cuesAt + cues.size - 1)) {
            val whole = extract(file, whole = true, failOnceAt = failAt)
            assertEquals("fail at $failAt", extract(file, whole = false).summary, whole.summary)
        }
    }

    @Test
    fun `damaged cues end the same way either way`() {
        val base = generatedCues(Random(3), cuePoints = 60)
        val cuesAt = indexOf(mkv(base), byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B))
        val random = Random(99)
        repeat(1_500) { round ->
            val cues = base.copyOf()
            repeat(1 + random.nextInt(3)) {
                val at = 5 + random.nextInt(cues.size - 5)
                cues[at] = when (random.nextInt(4)) {
                    0 -> 0
                    1 -> 0xFF.toByte()
                    2 -> (cues[at].toInt() xor (1 shl random.nextInt(8))).toByte()
                    else -> random.nextInt(256).toByte()
                }
            }
            val file = mkv(cues)
            check(indexOf(file, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)) == cuesAt)
            assertEquals("round $round", extract(file, whole = false).summary, extract(file, whole = true).summary)
        }
    }

    @Test
    fun `real remux cues give the same seek map either way`() {
        // MKV_CUES_DIR holds whole Cues elements (*-cues.bin) cut from real remuxes.
        val dir = System.getenv("MKV_CUES_DIR")?.let(::File)
        assumeTrue("MKV_CUES_DIR not set", dir?.isDirectory == true)
        val files = dir!!.listFiles { f -> f.name.endsWith("-cues.bin") }.orEmpty().sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        for (cues in files) {
            val file = mkv(cues.readBytes())
            val streamedAt = System.nanoTime()
            val streamed = extract(file, whole = false, samplePoints = 400)
            val wholeAt = System.nanoTime()
            val whole = extract(file, whole = true, samplePoints = 400)
            val doneAt = System.nanoTime()
            println("${cues.name}: ${cues.length()} bytes, reads to the seek map " +
                "${streamed.readsToSeekMap} -> ${whole.readsToSeekMap}, " +
                "extract ${(wholeAt - streamedAt) / 1_000_000} ms -> ${(doneAt - wholeAt) / 1_000_000} ms")
            assertEquals(cues.name, streamed.summary, whole.summary)
            assertTrue(cues.name, whole.summary.contains("seekable=true"))
        }
    }

    // --- Extraction ------------------------------------------------------------------------------

    private class Outcome(val summary: String, val readsToSeekMap: Int, val seeks: Int)

    private fun extract(
        file: ByteArray,
        whole: Boolean,
        failOnceAt: Int = -1,
        samplePoints: Int = 120,
    ): Outcome {
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0, null)
        extractor.readCuesWhole = whole
        val output = RecordingOutput()
        extractor.init(output)
        var failAt = failOnceAt
        var input = SeekableInput(file, 0, failAt)
        val holder = PositionHolder()
        var reads = 0
        var readsToSeekMap = -1
        var seeks = 0
        var error: String? = null
        var rounds = 0
        try {
            loop@ while (true) {
                if (++rounds > 100_000 || seeks > 50) {
                    error = "stopped after $rounds rounds and $seeks seeks"
                    break@loop
                }
                val result = try {
                    extractor.read(input, holder)
                } catch (e: InjectedIOException) {
                    // As the player does: a new input from where this one stopped.
                    failAt = -1
                    reads += input.reads
                    input = SeekableInput(file, input.getPosition().toInt(), failAt)
                    continue@loop
                }
                if (readsToSeekMap < 0 && output.hasSeekMap) readsToSeekMap = reads + input.reads
                when (result) {
                    Extractor.RESULT_SEEK -> {
                        seeks++
                        reads += input.reads
                        input = SeekableInput(file, holder.position.toInt(), failAt)
                    }
                    Extractor.RESULT_END_OF_INPUT -> break@loop
                }
            }
        } catch (e: Exception) {
            error = if (e is EOFException) "EOF" else "${e.javaClass.simpleName}: ${e.message}"
        }
        return Outcome(output.summary(samplePoints) + "\nerror=$error", readsToSeekMap, seeks)
    }

    private class InjectedIOException : IOException("injected")

    private class SeekableInput(
        private val data: ByteArray,
        start: Int,
        private val failAt: Int,
    ) : ExtractorInput {
        private var cursor = start.toLong()
        private var peekPosition = start.toLong()
        var reads = 0

        private fun failIfCrossing(from: Long, length: Int) {
            if (failAt in from until from + length) throw InjectedIOException()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (cursor >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - cursor).toInt()
            failIfCrossing(cursor, n)
            System.arraycopy(data, cursor.toInt(), buffer, offset, n)
            cursor += n
            peekPosition = cursor
            return n
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            reads++
            if (length == 0) return true
            if (cursor >= data.size && allowEndOfInput) return false
            if (cursor + length > data.size) throw EOFException()
            failIfCrossing(cursor, length)
            System.arraycopy(data, cursor.toInt(), target, offset, length)
            cursor += length
            peekPosition = cursor
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            reads++
            if (cursor >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - cursor).toInt()
            failIfCrossing(cursor, n)
            cursor += n
            peekPosition = cursor
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            reads++
            if (length == 0) return true
            if (cursor >= data.size && allowEndOfInput) return false
            if (cursor + length > data.size) throw EOFException()
            // Bytes already peeked were fetched; only the rest can fail.
            val fetched = maxOf(0L, minOf(peekPosition, cursor + length) - cursor).toInt()
            failIfCrossing(cursor + fetched, length - fetched)
            cursor += length
            peekPosition = cursor
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (peekPosition >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), data.size - peekPosition).toInt()
            failIfCrossing(peekPosition, n)
            System.arraycopy(data, peekPosition.toInt(), target, offset, n)
            peekPosition += n
            return n
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            reads++
            if (length == 0) return true
            if (peekPosition >= data.size && allowEndOfInput) return false
            if (peekPosition + length > data.size) throw EOFException()
            failIfCrossing(peekPosition, length)
            System.arraycopy(data, peekPosition.toInt(), target, offset, length)
            peekPosition += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            reads++
            if (length == 0) return true
            if (peekPosition >= data.size && allowEndOfInput) return false
            if (peekPosition + length > data.size) throw EOFException()
            failIfCrossing(peekPosition, length)
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPosition = cursor
        }

        override fun getPeekPosition(): Long = peekPosition

        override fun getPosition(): Long = cursor

        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            throw e
        }
    }

    private class RecordingOutput : ExtractorOutput {
        private val tracks = sortedMapOf<Int, RecordingTrack>()
        private var seekMap: SeekMap? = null
        private var tracksEnded = false
        val hasSeekMap get() = seekMap != null

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack(type) }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun summary(samplePoints: Int): String = buildString {
            append("tracksEnded=$tracksEnded\n")
            tracks.forEach { (id, track) ->
                append("track $id type=${track.type} format=${track.format?.sampleMimeType} samples=${track.samples}\n")
            }
            val map = seekMap
            if (map == null) {
                append("no seek map\n")
                return@buildString
            }
            append("${map.javaClass.simpleName} seekable=${map.isSeekable} duration=${map.durationUs}\n")
            if (!map.isSeekable || map.durationUs == C.TIME_UNSET) return@buildString
            val step = maxOf(1L, map.durationUs / samplePoints)
            val times = (0..samplePoints + 1).map { it * step }
            for (t in times) append(points(map.getSeekPoints(t))).append(' ')
            append('\n')
            if (map is TrackAwareSeekMap) {
                for (trackId in 0..40) {
                    append("t$trackId seekable=${map.isSeekable(trackId)}:")
                    for (t in times) append(points(map.getSeekPoints(t, trackId))).append(' ')
                    append('\n')
                }
            }
        }

        private fun points(p: SeekMap.SeekPoints) =
            "${p.first.timeUs}@${p.first.position}/${p.second.timeUs}@${p.second.position}"
    }

    private class RecordingTrack(val type: Int) : TrackOutput {
        var format: Format? = null
        var samples = 0
        private val scratch = ByteArray(4096)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            return if (n == C.RESULT_END_OF_INPUT && allowEndOfInput) C.RESULT_END_OF_INPUT else n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples++
        }
    }

    // --- Files -----------------------------------------------------------------------------------

    private fun mkv(cues: ByteArray, cuesBeforeCluster: Boolean = false, durationMs: Double = 4.0 * 3_600_000): ByteArray {
        val header = element(0x1A45DFA3, uint(0x42F7, 1) + string(0x4282, "matroska") + uint(0x4285, 2))
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000) + float(0x4489, durationMs))
        val tracks = element(0x1654AE6B,
            element(0xAE, uint(0xD7, 1) + uint(0x73C5, 1) + uint(0x83, 1) + string(0x86, "V_VP9") +
                element(0xE0, uint(0xB0, 64) + uint(0xBA, 48))) +
                element(0xAE, uint(0xD7, 2) + uint(0x73C5, 2) + uint(0x83, 2) + string(0x86, "A_AC3") +
                    element(0xE1, float(0xB5, 48_000.0) + uint(0x9F, 2))) +
                element(0xAE, uint(0xD7, 3) + uint(0x73C5, 3) + uint(0x83, 0x11) + string(0x86, "S_TEXT/UTF8")))
        val cluster = element(0x1F43B675, uint(0xE7, 0) +
            element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte(), 1, 2, 3, 4)) +
            element(0xA3, byteArrayOf(0x82.toByte(), 0, 0, 0x80.toByte(), 5, 6, 7)))
        // The SeekHead size is fixed (8-byte position), so the Cues offset is known before writing it.
        fun seekHead(cuesOffset: Long) = element(0x114D9B74,
            element(0x4DBB, element(0x53AB, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)) + uint(0x53AC, cuesOffset, width = 8)))
        val headSize = seekHead(0).size + info.size + tracks.size
        val body = if (cuesBeforeCluster) {
            seekHead(headSize.toLong()) + info + tracks + cues + cluster
        } else {
            seekHead((headSize + cluster.size).toLong()) + info + tracks + cluster + cues
        }
        return header + element(0x18538067, body)
    }

    /** Cues with the variety real muxers produce, plus elements this extractor skips. */
    private fun generatedCues(random: Random, cuePoints: Int = 20 + random.nextInt(400)): ByteArray {
        val out = ByteArrayOutputStream()
        if (random.nextInt(4) == 0) out.write(element(0xBF, ByteArray(4) { random.nextInt(256).toByte() }))
        var time = 0L
        repeat(cuePoints) {
            time += random.nextLong(1, 5_000)
            val positions = (1..1 + random.nextInt(3)).map {
                Triple(1 + random.nextInt(5), random.nextLong(0, 1L shl 40), if (random.nextBoolean()) random.nextLong(0, 1 shl 20) else null)
            }
            out.write(cuePoint(time, positions, random))
            if (random.nextInt(50) == 0) out.write(element(0xEC, ByteArray(random.nextInt(8))))
        }
        if (random.nextInt(10) == 0) out.write(element(ID_CUE_POINT, ByteArray(0)))
        return element(ID_CUES, out.toByteArray(), sizeWidth = if (random.nextBoolean()) 8 else 0)
    }

    private fun cuePoint(time: Long, positions: List<Triple<Int, Long, Long?>>, random: Random? = null): ByteArray {
        val w = { if (random != null && random.nextInt(8) == 0) 8 else 0 }
        val body = ByteArrayOutputStream()
        val timeElement = uint(ID_CUE_TIME, time, width = w())
        val timeAfter = random != null && random.nextInt(20) == 0
        if (!timeAfter && (random == null || random.nextInt(40) != 0)) body.write(timeElement)
        for ((track, cluster, relative) in positions) {
            val p = ByteArrayOutputStream()
            p.write(uint(ID_CUE_TRACK, track.toLong(), width = w()))
            p.write(uint(ID_CUE_CLUSTER_POSITION, cluster, width = w()))
            if (random != null && random.nextInt(10) == 0) p.write(uint(ID_CUE_CLUSTER_POSITION, cluster + 1))
            if (relative != null) p.write(uint(ID_CUE_RELATIVE_POSITION, relative, width = w()))
            if (random != null && random.nextInt(3) == 0) p.write(uint(0xB2, random.nextLong(0, 100_000)))
            if (random != null && random.nextInt(6) == 0) p.write(uint(0x5378, random.nextLong(1, 300)))
            body.write(element(ID_CUE_TRACK_POSITIONS, p.toByteArray(), sizeWidth = w()))
        }
        if (timeAfter) body.write(timeElement)
        return element(ID_CUE_POINT, body.toByteArray(), sizeWidth = w())
    }

    private fun element(id: Int, content: ByteArray, sizeWidth: Int = 0): ByteArray =
        idBytes(id) + sizeBytes(content.size.toLong(), sizeWidth) + content

    private fun uint(id: Int, value: Long, width: Int = 0): ByteArray {
        val length = if (width > 0) width else maxOf(1, (64 - java.lang.Long.numberOfLeadingZeros(value) + 7) / 8)
        return element(id, ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() })
    }

    private fun float(id: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        return element(id, ByteArray(8) { i -> (bits ushr (8 * (7 - i))).toByte() })
    }

    private fun string(id: Int, value: String) = element(id, value.toByteArray())

    private fun idBytes(id: Int): ByteArray {
        val length = when {
            id and 0xFF000000.toInt() != 0 -> 4
            id and 0xFF0000 != 0 -> 3
            id and 0xFF00 != 0 -> 2
            else -> 1
        }
        return ByteArray(length) { i -> (id ushr (8 * (length - 1 - i))).toByte() }
    }

    private fun sizeBytes(size: Long, width: Int): ByteArray {
        var length = if (width > 0) width else 1
        while (width == 0 && size >= (1L shl (7 * length)) - 1) length++
        val bytes = ByteArray(length) { i -> (size ushr (8 * (length - 1 - i))).toByte() }
        bytes[0] = (bytes[0].toInt() or (0x80 ushr (length - 1))).toByte()
        return bytes
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val ID_CUES = 0x1C53BB6B
        const val ID_CUE_POINT = 0xBB
        const val ID_CUE_TIME = 0xB3
        const val ID_CUE_TRACK_POSITIONS = 0xB7
        const val ID_CUE_TRACK = 0xF7
        const val ID_CUE_CLUSTER_POSITION = 0xF1
        const val ID_CUE_RELATIVE_POSITION = 0xF0
    }
}
