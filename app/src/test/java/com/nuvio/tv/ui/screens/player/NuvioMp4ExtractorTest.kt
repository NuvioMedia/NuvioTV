package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

class NuvioMp4ExtractorTest {

    @Test
    fun `byteArrayExtractorInput reads and peeks accurately with baseOffset`() {
        val rawData = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val baseOffset = 1000L
        val streamLength = 2000L
        val input = ByteArrayExtractorInput(rawData, baseOffset, streamLength)

        assertEquals(baseOffset, input.position)
        assertEquals(baseOffset, input.peekPosition)
        assertEquals(streamLength, input.length)

        // Test peek
        val peekBuf = ByteArray(3)
        assertEquals(3, input.peek(peekBuf, 0, 3))
        assertArrayEquals(byteArrayOf(10, 20, 30), peekBuf)
        assertEquals(baseOffset, input.position) // position unchanged
        assertEquals(baseOffset + 3, input.peekPosition)

        // Reset peek
        input.resetPeekPosition()
        assertEquals(baseOffset, input.peekPosition)

        // Test read
        val readBuf = ByteArray(4)
        input.readFully(readBuf, 0, 4)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), readBuf)
        assertEquals(baseOffset + 4, input.position)
        assertEquals(baseOffset + 4, input.peekPosition)

        // Test skip
        input.skipFully(2)
        assertEquals(baseOffset + 6, input.position)

        // Read remaining
        val remainBuf = ByteArray(2)
        input.readFully(remainBuf, 0, 2)
        assertArrayEquals(byteArrayOf(70, 80), remainBuf)
        assertEquals(baseOffset + 8, input.position)

        // EOF on further read
        val eofBuf = ByteArray(1)
        assertEquals(C.RESULT_END_OF_INPUT, input.read(eofBuf, 0, 1))
        assertFalse(input.readFully(eofBuf, 0, 1, allowEndOfInput = true))
    }

    @Test
    fun `byteArrayExtractorInput throws EOFException when reading past buffer with allowEndOfInput false`() {
        val rawData = byteArrayOf(1, 2)
        val input = ByteArrayExtractorInput(rawData, 0L, 2L)
        val buf = ByteArray(5)
        try {
            input.readFully(buf, 0, 5, allowEndOfInput = false)
            org.junit.Assert.fail("Expected EOFException")
        } catch (_: EOFException) {
            // Expected
        }
    }

    @Test
    fun `nuvioExtractorsFactory wraps Mp4Extractor but leaves others untouched`() {
        val dummyExtractor = object : Extractor {
            override fun init(output: ExtractorOutput) {}
            override fun sniff(input: ExtractorInput): Boolean = false
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = 0
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }

        val delegateFactory = ExtractorsFactory {
            arrayOf(
                Mp4Extractor(),
                dummyExtractor
            )
        }

        val wrappedFactory = delegateFactory.withNuvioMp4Extractor()
        val extractors = wrappedFactory.createExtractors()

        assertEquals(2, extractors.size)
        assertTrue("Expected first extractor to be NuvioMp4Extractor", extractors[0] is NuvioMp4Extractor)
        assertEquals("Expected second extractor to remain untouched", dummyExtractor, extractors[1])
    }

    @Test
    fun `nuvioMp4Extractor detects moov atom at tail, caches it, and triggers onMoovParsed callback`() {
        val moovParsed = AtomicBoolean(false)
        val delegateReadCalledWithMemInput = AtomicBoolean(false)

        val delegate = object : Extractor {
            private var out: ExtractorOutput? = null
            override fun init(output: ExtractorOutput) { out = output }
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                if (input is ByteArrayExtractorInput) {
                    delegateReadCalledWithMemInput.set(true)
                    // Simulate Mp4Extractor emitting seekMap / endTracks and returning RESULT_SEEK to mdat (pos 32)
                    out?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
                    out?.endTracks()
                    seekPosition.position = 32L
                    return Extractor.RESULT_SEEK
                }
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }

        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = {
            moovParsed.set(true)
        }

        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: androidx.media3.common.Format) {}
                override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = length
                override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {}
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        extractor.init(output)

        // Build synthetic input at tail: offset 100_000L, length 101_000L
        // Atom header: size = 20, type = 'moov' (0x6d6f6f76)
        val tailOffset = 100_000L
        val moovPayloadSize = 20
        val moovBytes = ByteArray(moovPayloadSize)
        // size: 20 -> 0x00, 0x00, 0x00, 0x14
        moovBytes[0] = 0
        moovBytes[1] = 0
        moovBytes[2] = 0
        moovBytes[3] = 20
        // type: 'm' 'o' 'o' 'v' -> 0x6d, 0x6f, 0x6f, 0x76
        moovBytes[4] = 0x6d
        moovBytes[5] = 0x6f
        moovBytes[6] = 0x6f
        moovBytes[7] = 0x76

        val mockInput = ByteArrayExtractorInput(moovBytes, tailOffset, 100_020L)
        val seekPos = PositionHolder()

        val result = extractor.read(mockInput, seekPos)

        assertEquals(Extractor.RESULT_SEEK, result)
        assertEquals(32L, seekPos.position)
        assertTrue("Delegate should have been called with in-memory ExtractorInput", delegateReadCalledWithMemInput.get())
        assertTrue("onMoovParsedCallback should have been called immediately", moovParsed.get())
    }

    @Test
    fun `nuvioMp4Extractor intercepts subsequent seek to moovOffset and replays from RAM`() {
        var callCount = 0
        val delegate = object : Extractor {
            override fun init(output: ExtractorOutput) {}
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                callCount++
                if (callCount == 1 && input is ByteArrayExtractorInput) {
                    // First read from memory: simulate finishing moov and seeking to 32L
                    seekPosition.position = 32L
                    return Extractor.RESULT_SEEK
                }
                if (callCount == 2) {
                    // Simulated second read: ExoPlayer seeks or resets, delegate wants to re-read moov at 100_000L
                    seekPosition.position = 100_000L
                    return Extractor.RESULT_SEEK
                }
                if (callCount == 3 && input is ByteArrayExtractorInput) {
                    // Replayed from RAM!
                    seekPosition.position = 32L
                    return Extractor.RESULT_SEEK
                }
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }

        val extractor = NuvioMp4Extractor(delegate)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: androidx.media3.common.Format) {}
                override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = length
                override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {}
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })

        // Step 1: Initial parse of moov at 100_000L
        val moovBytes = ByteArray(16)
        moovBytes[3] = 16 // size 16
        moovBytes[4] = 0x6d
        moovBytes[5] = 0x6f
        moovBytes[6] = 0x6f
        moovBytes[7] = 0x76 // 'moov'
        val input = ByteArrayExtractorInput(moovBytes, 100_000L, 100_016L)
        val seekPos = PositionHolder()
        extractor.read(input, seekPos)
        assertEquals(32L, seekPos.position)

        // Step 2: Delegate asks to seek back to 100_000L (moovOffset)
        // NuvioMp4Extractor will intercept and replay immediately from memory
        val dummyInput = ByteArrayExtractorInput(ByteArray(10), 32L, 100_016L)
        val res = extractor.read(dummyInput, seekPos)

        // Should return RESULT_SEEK to 32L directly, satisfied entirely from RAM!
        assertEquals(Extractor.RESULT_SEEK, res)
        assertEquals(32L, seekPos.position)
        assertEquals(3, callCount)
    }

    @Test
    fun `byteArrayExtractorInput skip returns end of input at eof`() {
        val input = ByteArrayExtractorInput(byteArrayOf(1, 2, 3), 0L, 3L)
        assertEquals(3, input.skip(10))
        assertEquals(C.RESULT_END_OF_INPUT, input.skip(1))
    }

    @Test
    fun `seekMap without a trailing moov does not trigger release callback`() {
        val released = AtomicBoolean(false)
        val delegate = object : Extractor {
            private var out: ExtractorOutput? = null
            override fun init(output: ExtractorOutput) { out = output }
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                out?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
                out?.endTracks()
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }
        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = { released.set(true) }
        extractor.init(silentOutput())

        val input = ByteArrayExtractorInput(ByteArray(32) { 0 }, 0L, 2_000_000_000L)
        extractor.read(input, PositionHolder())
        assertFalse("faststart seekMap must not release tail chunks", released.get())
    }

    @Test
    fun `moov-like bytes far from eof are not consumed from live input`() {
        val liveReads = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val delegate = object : Extractor {
            override fun init(output: ExtractorOutput) {}
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                if (input !is ByteArrayExtractorInput || input.length > 1_000_000L) {
                    liveReads.set(true)
                }
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }
        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = { released.set(true) }
        extractor.init(silentOutput())

        val fakeMoov = moovHeader(size = 32)
        val input = ByteArrayExtractorInput(fakeMoov, 0L, 2_000_000_000L)
        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertEquals(0L, input.position)
        assertTrue(liveReads.get())
        assertFalse(released.get())
    }

    @Test
    fun `oversized moov header is ignored rather than cached`() {
        val liveReads = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val delegate = object : Extractor {
            override fun init(output: ExtractorOutput) {}
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                liveReads.set(true)
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }
        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = { released.set(true) }
        extractor.init(silentOutput())

        val oversized = NuvioMp4Extractor.MAX_MOOV_CACHE_SIZE + 1024L
        val header = moovHeader(size = oversized)
        val tailOffset = 100_000_000L
        val fileLength = tailOffset + oversized
        val input = ByteArrayExtractorInput(header, tailOffset, fileLength)
        extractor.read(input, PositionHolder())
        assertEquals(tailOffset, input.position)
        assertTrue(liveReads.get())
        assertFalse(released.get())
    }

    @Test
    fun `oversized moov still tracks moovOffset and releases chunks upon endTracks`() {
        val released = AtomicBoolean(false)
        var delegateOut: ExtractorOutput? = null
        val delegate = object : Extractor {
            override fun init(output: ExtractorOutput) { delegateOut = output }
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                // Read from live input (not cached)
                input.skip(8)
                delegateOut?.endTracks()
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }
        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = { released.set(true) }
        extractor.init(silentOutput())

        val oversized = NuvioMp4Extractor.MAX_MOOV_CACHE_SIZE + 1024L
        val header = moovHeader(size = oversized)
        val tailOffset = 100_000_000L
        val fileLength = tailOffset + oversized
        val input = ByteArrayExtractorInput(header, tailOffset, fileLength)
        extractor.read(input, PositionHolder())
        assertTrue("Oversized moov must still trigger release when tracks end", released.get())
    }

    @Test
    fun `mp4Extractor seek past mdat to tail enables tail detection and moov caching`() {
        val released = AtomicBoolean(false)
        var step = 0
        var delegateOut: ExtractorOutput? = null
        val tailOffset = 200_000_000L
        val fileLength = 200_100_000L

        val delegate = object : Extractor {
            override fun init(output: ExtractorOutput) { delegateOut = output }
            override fun sniff(input: ExtractorInput): Boolean = true
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                if (step == 0) {
                    // Simulate Mp4Extractor seeing mdat at head and requesting seek to tail
                    step = 1
                    seekPosition.position = tailOffset
                    return Extractor.RESULT_SEEK
                } else if (step == 1) {
                    // At tail, delegate reading moov from memory
                    assertTrue("Input should be cached in memory", input is ByteArrayExtractorInput)
                    delegateOut?.endTracks()
                    return Extractor.RESULT_CONTINUE
                }
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {}
            override fun release() {}
            override fun getUnderlyingImplementation(): Extractor = this
        }
        val extractor = NuvioMp4Extractor(delegate)
        extractor.onMoovParsedCallback = { released.set(true) }
        extractor.init(silentOutput())

        val seekPos = PositionHolder()
        // Step 0: Read at offset 32 (head of file, 200MB total)
        val headInput = ByteArrayExtractorInput(ByteArray(64), 32L, fileLength)
        val res0 = extractor.read(headInput, seekPos)
        assertEquals(Extractor.RESULT_SEEK, res0)
        assertEquals(tailOffset, seekPos.position)

        // Step 1: Read at tailOffset
        val moovBytes = moovHeader(size = 8)
        val tailInput = ByteArrayExtractorInput(moovBytes, tailOffset, fileLength)
        extractor.read(tailInput, seekPos)
        assertTrue(released.get())
    }

    private fun silentOutput(): ExtractorOutput = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: androidx.media3.common.Format) {}
            override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = length
            override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int) {}
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private fun moovHeader(size: Long): ByteArray {
        val bytes = ByteArray(8)
        if (size <= 0xFFFFFFFFL) {
            val s = size.toInt()
            bytes[0] = ((s ushr 24) and 0xFF).toByte()
            bytes[1] = ((s ushr 16) and 0xFF).toByte()
            bytes[2] = ((s ushr 8) and 0xFF).toByte()
            bytes[3] = (s and 0xFF).toByte()
        }
        bytes[4] = 0x6d
        bytes[5] = 0x6f
        bytes[6] = 0x6f
        bytes[7] = 0x76
        return bytes
    }
}
