package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SubtitleFontValidationTest {

    @Test
    fun `accepts TrueType and OpenType files with a valid table directory`() {
        assertTrue(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType)))
        assertTrue(isSupportedSubtitleFont("Font.OTF", fontBytes(openType)))
    }

    @Test
    fun `rejects files that are not ttf or otf`() {
        assertFalse(isSupportedSubtitleFont("Font.woff", fontBytes(trueType)))
        assertFalse(isSupportedSubtitleFont("Font", fontBytes(trueType)))
    }

    @Test
    fun `rejects unknown signatures`() {
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes("wOFF".encodeToByteArray())))
    }

    @Test
    fun `rejects a truncated table directory`() {
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType, tables = 4).copyOf(40)))
    }

    @Test
    fun `rejects tables that point outside the file`() {
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType, tableLength = 4_096)))
    }

    @Test
    fun `rejects files over the size limit`() {
        assertFalse(isSupportedSubtitleFont("Font.ttf", fontBytes(trueType).copyOf(SubtitleFonts.MAX_BYTES + 1)))
    }

    private val trueType = byteArrayOf(0, 1, 0, 0)
    private val openType = "OTTO".encodeToByteArray()

    private fun fontBytes(signature: ByteArray, tables: Int = 1, tableLength: Int = 16): ByteArray {
        val dataOffset = 12 + tables * 16
        val buffer = ByteBuffer.allocate(dataOffset + tables * 16)
        buffer.put(signature)
        buffer.putShort(tables.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        repeat(tables) { index ->
            buffer.put("tb$index ".take(4).encodeToByteArray())
            buffer.putInt(0)
            buffer.putInt(dataOffset + index * 16)
            buffer.putInt(tableLength)
        }
        return buffer.array()
    }
}
