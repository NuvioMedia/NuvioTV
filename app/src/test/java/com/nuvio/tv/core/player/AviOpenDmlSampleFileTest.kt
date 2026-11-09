package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.avi.FileSource
import com.nuvio.tv.core.player.avi.RecordingOutput
import com.nuvio.tv.core.player.avi.SourceInput
import com.nuvio.tv.core.player.avi.runExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs real AVI files through the player's AVI path when they are on this machine (AVI_SAMPLES, ';' separated). */
class AviOpenDmlSampleFileTest {

    private val samples = (System.getenv("AVI_SAMPLES") ?: "").split(';').map(::File).filter { it.isFile }

    private fun extractor(sample: File): Extractor {
        val stock = ExtractorsFactory {
            arrayOf(AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED))
        }
        val extractor = AviTrackFormatExtractorsFactory(stock).createExtractors().single()
        FileSource(sample).use { source -> assertTrue(extractor.sniff(SourceInput(source))) }
        return extractor
    }

    @Test
    fun playsToTheEndAndSeeksIntoTheLastSegment() {
        assumeTrue(samples.isNotEmpty())
        samples.forEach(::check)
    }

    private fun check(sample: File) {
        println(sample.name)
        val output = RecordingOutput()
        val extractor = extractor(sample).apply { init(output) }
        FileSource(sample).use { source -> runExtractor(extractor, source) }

        val seekMap = output.seekMap!!
        val video = output.track(C.TRACK_TYPE_VIDEO).samples
        val audio = output.track(C.TRACK_TYPE_AUDIO).samples
        println("duration=${seekMap.durationUs} video=${video.size} last=${video.lastOrNull()?.timeUs} audio=${audio.size} last=${audio.lastOrNull()?.timeUs}")
        assertTrue(seekMap.isSeekable)
        assertTrue(video.last().timeUs > seekMap.durationUs - 1_000_000)
        assertTrue(audio.last().timeUs > seekMap.durationUs - 1_000_000)
        assertTrue((1 until audio.size).all { audio[it].timeUs >= audio[it - 1].timeUs })

        for (fraction in listOf(0.1, 0.5, 0.9)) {
            val targetUs = (seekMap.durationUs * fraction).toLong()
            val point = seekMap.getSeekPoints(targetUs).first
            output.tracks.values.forEach { it.samples.clear() }
            extractor.seek(point.position, point.timeUs)
            var read = 0
            FileSource(sample).use { source ->
                runExtractor(extractor, source, start = point.position) { ++read > 20_000 }
            }
            val firstVideo = output.track(C.TRACK_TYPE_VIDEO).samples.first()
            val firstAudio = output.track(C.TRACK_TYPE_AUDIO).samples.first()
            println("seek $fraction target=$targetUs point=${point.timeUs} video=${firstVideo.timeUs} audio=${firstAudio.timeUs}")
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, firstVideo.flags and C.BUFFER_FLAG_KEY_FRAME)
            // The seek point and the reader number frames with differently rounded frame durations.
            assertTrue(kotlin.math.abs(firstVideo.timeUs - point.timeUs) < 50_000)
            assertTrue(targetUs - point.timeUs in 0..20_000_000)
            assertTrue(kotlin.math.abs(firstAudio.timeUs - firstVideo.timeUs) < 1_000_000)
        }
    }
}
