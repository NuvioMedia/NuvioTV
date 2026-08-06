package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class MatroskaZlibSampleDecompressorTest {

    @Test
    fun `decompress restores zlib-compressed subtitle payload`() {
        val original = "Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello".toByteArray()
        val compressed = deflate(original)

        val decompressor = MatroskaZlibSampleDecompressor()
        val decompressed = decompressor.decompress(compressed, 0, compressed.size)

        assertEquals(original.size, decompressed.limit())
        assertArrayEquals(original, decompressed.data.copyOfRange(0, decompressed.limit()))
    }

    @Test
    fun `decompress reuses inflater and grows output buffer across calls`() {
        val first = "First".toByteArray()
        val second = "Second subtitle line with more bytes".toByteArray()

        val decompressor = MatroskaZlibSampleDecompressor()
        val firstOut =
            decompressor.decompress(deflate(first), 0, deflate(first).size).let { sample ->
                sample.data.copyOfRange(0, sample.limit())
            }
        val secondOut =
            decompressor.decompress(deflate(second), 0, deflate(second).size).let { sample ->
                sample.data.copyOfRange(0, sample.limit())
            }

        assertArrayEquals(first, firstOut)
        assertArrayEquals(second, secondOut)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(input.size)
        val scratch = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(scratch)
            if (count > 0) {
                output.write(scratch, 0, count)
            }
        }
        deflater.end()
        return output.toByteArray()
    }
}
