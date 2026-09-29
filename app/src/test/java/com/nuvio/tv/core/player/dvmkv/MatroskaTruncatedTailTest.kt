package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.extractor.ExtractorInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class MatroskaTruncatedTailTest {

    @Test
    fun `zero byte with allowEndOfInput returns max length exceeded instead of crashing`() {
        val reader = VarintReader()
        val result = reader.readUnsignedVarint(
            FakeExtractorInput(byteArrayOf(0x00)),
            /* allowEndOfInput = */ true,
            /* removeLengthMask = */ false,
            /* maximumAllowedLength = */ 4
        )
        assertEquals(C.RESULT_MAX_LENGTH_EXCEEDED.toLong(), result)
    }

    @Test
    fun `zero byte without allowEndOfInput is a malformed container`() {
        val reader = VarintReader()
        try {
            reader.readUnsignedVarint(
                FakeExtractorInput(byteArrayOf(0x00)),
                /* allowEndOfInput = */ false,
                /* removeLengthMask = */ true,
                /* maximumAllowedLength = */ 8
            )
            throw AssertionError("expected ParserException")
        } catch (e: ParserException) {
            assertTrue(e.message.orEmpty().contains("No valid varint length mask found"))
        }
    }

    @Test
    fun `trailing zeros after a complete element end the stream`() {
        val data = byteArrayOf(0xEC.toByte(), 0x80.toByte()) + ByteArray(32)
        val reader = DefaultEbmlReader()
        val processor = RecordingProcessor()
        reader.init(processor)

        val input = FakeExtractorInput(data)
        assertFalse(reader.read(input))
        assertEquals(listOf(0xEC), processor.seenIds)
    }

    @Test
    fun `resync skips zero padding to the next level-1 cluster`() {
        val clusterId = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        val data = byteArrayOf(0xEC.toByte(), 0x80.toByte()) +
            ByteArray(8) +
            clusterId +
            byteArrayOf(0x80.toByte())
        val reader = DefaultEbmlReader()
        val processor = RecordingProcessor(level1Ids = setOf(0x1F43B675))
        reader.init(processor)

        val input = FakeExtractorInput(data)
        assertFalse(reader.read(input))
        assertEquals(listOf(0xEC, 0x1F43B675), processor.seenIds)
    }

    private class FakeExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var position = 0
        private var peekPosition = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, toRead)
            position += toRead
            peekPosition = position
            return toRead
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (length == 0) return true
            if (position >= data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            if (position + length > data.size) {
                throw EOFException()
            }
            System.arraycopy(data, position, target, offset, length)
            position += length
            peekPosition = position
            return true
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val skipped = minOf(length, data.size - position)
            position += skipped
            peekPosition = position
            return skipped
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (position + length > data.size) {
                if (allowEndOfInput && position >= data.size) return false
                throw EOFException()
            }
            position += length
            peekPosition = position
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            if (peekPosition >= data.size) return C.RESULT_END_OF_INPUT
            val toPeek = minOf(length, data.size - peekPosition)
            System.arraycopy(data, peekPosition, target, offset, toPeek)
            peekPosition += toPeek
            return toPeek
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPosition + length > data.size) {
                if (allowEndOfInput && peekPosition >= data.size) return false
                throw EOFException()
            }
            System.arraycopy(data, peekPosition, target, offset, length)
            peekPosition += length
            return true
        }

        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPosition + length > data.size) {
                if (allowEndOfInput && peekPosition >= data.size) return false
                throw EOFException()
            }
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPosition = position
        }

        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun getPosition(): Long = position.toLong()

        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            this.position = position.toInt()
            peekPosition = this.position
            throw e
        }
    }

    private class RecordingProcessor(
        private val level1Ids: Set<Int> = emptySet()
    ) : EbmlProcessor {
        val seenIds = mutableListOf<Int>()

        override fun getElementType(id: Int): Int {
            seenIds += id
            return EbmlProcessor.ELEMENT_TYPE_UNKNOWN
        }

        override fun isLevel1Element(id: Int) = id in level1Ids

        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) = Unit

        override fun endMasterElement(id: Int) = Unit

        override fun integerElement(id: Int, value: Long) = Unit

        override fun floatElement(id: Int, value: Double) = Unit

        override fun stringElement(id: Int, value: String) = Unit

        @Throws(IOException::class)
        override fun binaryElement(id: Int, contentsSize: Int, input: ExtractorInput) {
            input.skipFully(contentsSize)
        }
    }
}
