package com.nuvio.tv.ui.screens.player

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.media3.common.C
import org.junit.Test

class BlurayClipInfoTest {
    private fun stream(pid: Int, vararg info: Int) = byteArrayOf((pid shr 8).toByte(), pid.toByte(), info.size.toByte()) +
        ByteArray(info.size) { info[it].toByte() }

    private fun language(code: String) = code.map { it.code }.toIntArray()

    // HDMV header, then ProgramInfo at 40 with one program sequence, as a disc writes it.
    private fun clipInfo(vararg streams: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HDMV0300".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 40))
        out.write(ByteArray(24))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1)) // Length, reserved, one program sequence.
        out.write(byteArrayOf(0, 0, 0, 0, 0x01, 0x00, streams.size.toByte(), 0))
        streams.forEach { out.write(it) }
        return out.toByteArray()
    }

    private val disc = clipInfo(
        stream(0x1011, 0x24, 0x61, 0, 0, 0),
        stream(0x1100, 0x83, 0x61, *language("eng"), 0),
        stream(0x1101, 0x81, 0x61, *language("SPA"), 0),
        stream(0x1102, 0x81, 0x61, *language("und"), 0),
        stream(0x12A0, 0x90, *language("fra"), 0),
        stream(0x1A00, 0x92, 0x01, *language("deu"))
    )

    @Test fun `stream languages by PID`() {
        assertEquals(
            mapOf(0x1100 to "eng", 0x1101 to "spa", 0x12A0 to "fra", 0x1A00 to "deu"),
            BlurayClipInfo.parseLanguages(disc)
        )
    }

    @Test fun `a cut or foreign file yields what it holds and never throws`() {
        for (size in 0 until disc.size) BlurayClipInfo.parseLanguages(disc.copyOf(size))
        // Cut into the spa stream: the streams before it still count.
        assertEquals(mapOf(0x1100 to "eng"), BlurayClipInfo.parseLanguages(disc.copyOf(disc.size - 26)))
        assertTrue(BlurayClipInfo.parseLanguages(ByteArray(4096) { 0x47 }).isEmpty())
    }

    @Test fun `clip info sits beside the clip in its case`() {
        assertEquals("/stream/abc/BDMV/CLIPINF/00065.clpi", BlurayClipInfo.clipInfoPath("/stream/abc/BDMV/STREAM/00065.m2ts"))
        assertEquals("/disc/bdmv/clipinf/00001.clpi", BlurayClipInfo.clipInfoPath("/disc/bdmv/stream/00001.m2ts"))
        assertEquals("/Disc/BDMV/CLIPINF/00800.CLPI", BlurayClipInfo.clipInfoPath("/Disc/BDMV/STREAM/00800.M2TS"))
        assertNull(BlurayClipInfo.clipInfoPath("/movie.m2ts"))
        assertNull(BlurayClipInfo.clipInfoPath("/Disc/BDMV/STREAM/SSIF/00065.ssif"))
    }

    @Test fun `only tracks the PMT left unnamed take the clip info language`() {
        val languages = mapOf(4352 to "eng")
        assertEquals("en", BlurayClipInfo.withLanguage(Format.Builder().setId("1/4352").build(), languages).language)
        assertEquals("de", BlurayClipInfo.withLanguage(Format.Builder().setId("1/4352").setLanguage("de").build(), languages).language)
        assertNull(BlurayClipInfo.withLanguage(Format.Builder().setId("1/4353").build(), languages).language)
        assertNull(BlurayClipInfo.withLanguage(Format.Builder().build(), languages).language)
    }

    @Test fun `the wrapped extractor names its tracks`() {
        val formats = mutableListOf<Format>()
        val sink = object : TrackOutput {
            override fun format(format: Format) { formats += format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        val clip = object : Extractor {
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) {
                output.track(0, 1).format(Format.Builder().setId("1/4352").build())
                output.track(1, 1).format(Format.Builder().setId("1/4353").build())
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
        }
        BlurayClipInfo.ClipInfoExtractor(clip, BlurayClipInfo.ClipInfo(mapOf(4352 to "eng"), null)).init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = sink
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        assertEquals(listOf("en", null), formats.map { it.language })
    }

    /** A CLPI whose EP map holds [points], each a 90 kHz PTS (a multiple of 512) and a source packet number. */
    private fun epMapClip(points: List<Pair<Long, Long>>): ByteArray {
        // A coarse entry starts wherever the bits fine entries cannot hold change.
        val coarse = mutableListOf<Int>()
        points.forEachIndexed { i, (pts, spn) ->
            if (i == 0 || pts shr 20 != points[coarse.last()].first shr 20 || spn shr 17 != points[coarse.last()].second shr 17) coarse += i
        }
        val out = ByteArrayOutputStream()
        fun u32(v: Long) = out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        out.write("HDMV0300".toByteArray())
        u32(0); u32(0); u32(40) // SequenceInfo, ProgramInfo, CPI.
        out.write(ByteArray(20))
        val streamMap = 4 + coarse.size * 8 + points.size * 4
        u32(2L + 2 + 12 + streamMap) // CPI length.
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // EP map, one stream.
        val fields = (1L shl 34) or (coarse.size.toLong() shl 18) or points.size.toLong()
        out.write(byteArrayOf(0x10, 0x11) + ByteArray(6) { (fields shr (40 - 8 * it)).toByte() })
        u32(14) // The stream's map, after this entry.
        u32(4L + coarse.size * 8) // Its fine table, after the coarse entries.
        coarse.forEach { i -> u32((i.toLong() shl 14) or ((points[i].first shr 19) and 0x3FFF)); u32(points[i].second) }
        points.forEach { (pts, spn) -> u32((((pts shr 9) and 0x7FF) shl 17) or (spn and 0x1FFFF)) }
        return out.toByteArray()
    }

    private val tick = 90_112L // 176 x 512: just over a second at 90 kHz.
    private val clipPoints = listOf(512L * 3000 to 100L, 512L * 3000 + tick to 5_000L, 512L * 3000 + 2 * tick to 140_000L, 512L * 3000 + 3 * tick to 150_000L)

    @Test fun `the EP map gives each key frame's time and position`() {
        val points = BlurayClipInfo.parseEntryPoints(epMapClip(clipPoints))!!
        assertEquals(listOf(0L, 1_001_244L, 2_002_488L, 3_003_733L), points.timesUs.toList())
        // Packets of 188 bytes, across a coarse entry at 2^17 packets.
        assertEquals(listOf(18_800L, 940_000L, 26_320_000L, 28_200_000L), points.positions.toList())
    }

    @Test fun `an EP map that runs backwards, a cut file or none leaves seeking as it was`() {
        assertNull(BlurayClipInfo.parseEntryPoints(epMapClip(clipPoints.reversed())))
        val clip = epMapClip(clipPoints)
        for (size in 0 until clip.size) BlurayClipInfo.parseEntryPoints(clip.copyOf(size))
        assertNull(BlurayClipInfo.parseEntryPoints(clip.copyOf(clip.size - 4)))
        assertNull(BlurayClipInfo.parseEntryPoints(disc))
    }

    @Test fun `a seek goes to the key frame at or before its time`() {
        val points = BlurayClipInfo.parseEntryPoints(epMapClip(clipPoints))!!
        val map = BlurayClipInfo.EntryPointSeekMap(points, 4_000_000L) { 40_000L }
        val seek = map.getSeekPoints(1_500_000L)
        assertEquals(1_041_244L to 940_000L, seek.first.timeUs to seek.first.position)
        assertEquals(2_042_488L to 26_320_000L, seek.second.timeUs to seek.second.position)
        assertEquals(18_800L, map.getSeekPoints(0L).first.position)
        assertEquals(28_200_000L, map.getSeekPoints(9_000_000L).first.position)
    }

    @Test fun `with an EP map the extractor reads on from the key frame once its clock is set`() {
        val seeks = mutableListOf<Long>()
        val maps = mutableListOf<SeekMap>()
        val clip = object : Extractor {
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) { outputHolder = output }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) { seeks += timeUs }
            override fun release() {}
        }
        val sink = object : TrackOutput {
            override fun format(format: Format) {}
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        val info = BlurayClipInfo.ClipInfo(emptyMap(), BlurayClipInfo.parseEntryPoints(epMapClip(clipPoints)))
        fun wrapped(): BlurayClipInfo.ClipInfoExtractor {
            val extractor = BlurayClipInfo.ClipInfoExtractor(clip, info)
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int) = sink
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) { maps += seekMap }
            })
            outputHolder!!.seekMap(seekable)
            return extractor
        }

        val extractor = wrapped()
        assertTrue(maps.last() is BlurayClipInfo.EntryPointSeekMap)
        extractor.seek(0, 0)
        extractor.seek(940_000, 1_041_244) // No sample yet: TsExtractor still searches.
        outputHolder!!.track(0, C.TRACK_TYPE_VIDEO).sampleMetadata(40_000, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        extractor.seek(940_000, 1_041_244)
        assertEquals(listOf(0L, 1_041_244L, 1_041_244L), seeks)

        seeks.clear()
        val fresh = wrapped()
        fresh.seek(0, 0)
        outputHolder!!.track(0, C.TRACK_TYPE_VIDEO).sampleMetadata(40_000, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        fresh.seek(940_000, 1_041_244)
        assertEquals(listOf(0L, 0L), seeks)
        assertEquals(1_041_244L, maps.last().getSeekPoints(1_500_000L).first.timeUs)

        // An unseekable clip keeps its own map.
        BlurayClipInfo.ClipInfoExtractor(clip, info).init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = sink
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { maps += seekMap }
        })
        outputHolder!!.seekMap(SeekMap.Unseekable(4_000_000L))
        assertTrue(maps.last() is SeekMap.Unseekable)
    }

    private var outputHolder: ExtractorOutput? = null

    /** TsExtractor's own map for a seekable clip, which searches its PCRs. */
    private val seekable = object : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = 4_000_000L
        override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(androidx.media3.extractor.SeekPoint(timeUs, 0))
    }
}
