package com.nuvio.tv.core.player

import androidx.media3.common.Format
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AviHeaderTest {

    private fun ByteArrayOutputStream.tag(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArrayOutputStream.u32(v: Long) {
        write((v and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt())
        write(((v shr 24) and 0xFF).toInt())
    }

    private fun streamHeader(type: String, scale: Long, rate: Long, handler: String = "XVID"): ByteArray =
        ByteArrayOutputStream().apply {
            tag("strh"); u32(56)
            tag(type); tag(handler); u32(0); u32(0); u32(0)
            u32(scale); u32(rate)
            repeat(7) { u32(0) }
        }.toByteArray()

    private fun videoFormat(compression: String): ByteArray =
        ByteArrayOutputStream().apply {
            tag("strf"); u32(40)
            u32(40); u32(640); u32(480); u32(0x00180001)
            tag(compression)
            repeat(5) { u32(0) }
        }.toByteArray()

    private fun streamList(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ByteArrayOutputStream().apply {
            tag("LIST"); u32(4L + body.size); tag("strl"); write(body)
        }.toByteArray()
    }

    private fun avi(microsPerFrame: Long, vararg streams: ByteArray): ByteArray {
        val mainHeader = ByteArrayOutputStream().apply {
            tag("avih"); u32(56); u32(microsPerFrame)
            repeat(13) { u32(0) }
        }.toByteArray()
        val headerBody = streams.fold(mainHeader) { acc, s -> acc + s }
        return ByteArrayOutputStream().apply {
            tag("RIFF"); u32(0); tag("AVI ")
            tag("LIST"); u32(4L + headerBody.size); tag("hdrl"); write(headerBody)
            tag("LIST"); u32(4); tag("movi")
        }.toByteArray()
    }

    private fun frameRate(bytes: ByteArray) =
        AviHeader.read(bytes, bytes.size)?.frameRate ?: Format.NO_VALUE.toFloat()

    @Test
    fun readsRateAndScaleOfTheVideoStream() {
        assertEquals(25f, frameRate(avi(40_000, streamList(streamHeader("vids", 1, 25)))), 0.001f)
        assertEquals(23.976f, frameRate(avi(41_708, streamList(streamHeader("vids", 1001, 24000)))), 0.001f)
    }

    @Test
    fun skipsAudioStreamsAndOtherChunks() {
        val junk = ByteArrayOutputStream().apply { tag("JUNK"); u32(5); write(ByteArray(6)) }.toByteArray()
        val bytes = avi(
            33_367,
            streamList(streamHeader("auds", 1152, 48000)),
            junk,
            streamList(streamHeader("vids", 1001, 30000))
        )
        assertEquals(29.97f, frameRate(bytes), 0.001f)
    }

    @Test
    fun fallsBackToTheMainHeaderWhenTheStreamHeaderHasNoRate() {
        assertEquals(25f, frameRate(avi(40_000, streamList(streamHeader("vids", 0, 0)))), 0.001f)
    }

    @Test
    fun rejectsOtherFilesTruncatedHeadersAndAbsurdRates() {
        val unset = Format.NO_VALUE.toFloat()
        assertEquals(unset, frameRate(ByteArray(64)), 0f)
        assertEquals(unset, frameRate("RIFF....WAVEfmt ".toByteArray(Charsets.ISO_8859_1)), 0f)
        assertEquals(unset, frameRate(avi(0, streamList(streamHeader("vids", 1, 1000)))), 0f)
        val whole = avi(40_000, streamList(streamHeader("vids", 1, 25)))
        assertNull(AviHeader.read(whole, 20))
        val audioOnly = avi(40_000, streamList(streamHeader("auds", 1152, 48000)))
        assertNull(AviHeader.read(audioOnly, audioOnly.size))
    }

    @Test
    fun reportsTheVideoFourCc_preferringTheStreamFormat() {
        val handlerOnly = avi(40_000, streamList(streamHeader("vids", 1, 25, handler = "divx")))
        assertEquals("divx", AviHeader.read(handlerOnly, handlerOnly.size)?.fourCc)

        val both = avi(40_000, streamList(streamHeader("vids", 1, 25, handler = "divx"), videoFormat("DIV3")))
        assertEquals("DIV3", AviHeader.read(both, both.size)?.fourCc)

        val blank = String(CharArray(4))
        val blankHandler = avi(40_000, streamList(streamHeader("vids", 1, 25, handler = blank)))
        assertNull(AviHeader.read(blankHandler, blankHandler.size)?.fourCc)
    }

    private fun fullStreamHeader(type: String, scale: Long, rate: Long, length: Long, sampleSize: Long): ByteArray =
        ByteArrayOutputStream().apply {
            tag("strh"); u32(56)
            tag(type); u32(0); u32(0); u32(0); u32(0)
            u32(scale); u32(rate); u32(0); u32(length); u32(0); u32(0xFFFFFFFFL); u32(sampleSize)
            u32(0); u32(0)
        }.toByteArray()

    private fun audioFormat(formatTag: Int, sampleRate: Long, avgBytesPerSecond: Long, blockAlign: Int): ByteArray =
        ByteArrayOutputStream().apply {
            tag("strf"); u32(18)
            write(formatTag and 0xFF); write(formatTag shr 8); write(2); write(0)
            u32(sampleRate); u32(avgBytesPerSecond)
            write(blockAlign and 0xFF); write(blockAlign shr 8); write(0); write(0); write(0); write(0)
        }.toByteArray()

    @Test
    fun listsEveryStreamWithItsTimingFields() {
        val bytes = avi(
            40_000,
            streamList(fullStreamHeader("vids", 1, 25, 178_944, 0), videoFormat("XVID")),
            streamList(fullStreamHeader("auds", 384, 16_000, 298_226, 384), audioFormat(0x55, 48_000, 16_000, 384)),
        )
        val streams = AviHeader.streams(bytes, bytes.size)
        assertEquals(2, streams.size)
        assertEquals(AviHeader.Stream(0, "vids", 1, 25, 178_944, 0), streams[0])
        assertEquals(AviHeader.Stream(1, "auds", 384, 16_000, 298_226, 384, 16_000), streams[1])
        assertEquals(25f, frameRate(bytes), 0.001f)
    }

    @Test
    fun aTruncatedStreamHeaderKeepsTheNumbering() {
        val bytes = avi(40_000, streamList(fullStreamHeader("vids", 1, 25, 100, 0)))
        val streams = AviHeader.streams(bytes, bytes.size - 30)
        assertEquals(listOf(AviHeader.Stream(0, "vids", 0, 0, 0, 0)), streams)
        assertEquals(emptyList<AviHeader.Stream>(), AviHeader.streams(ByteArray(64), 64))
    }
}
