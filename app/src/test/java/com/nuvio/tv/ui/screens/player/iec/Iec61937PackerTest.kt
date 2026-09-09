package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Iec61937PackerTest {

    // The word wide swap has to be byte identical to a pairwise swap, including the tail a burst
    // size leaves when it is not a multiple of eight.
    @Test
    fun packTrueHd_payloadMatchesAPairwiseSwap() {
        val random = java.util.Random(20260905L)
        val mat = ByteArray(Iec61937Packer.TRUEHD_IEC_SIZE).also { random.nextBytes(it) }
        val expected = mat.copyOf().also { pairwiseSwap(it, Iec61937Packer.DATA_OFFSET, it.size) }
        val packed = Iec61937Packer.packTrueHd(mat)
        assertArrayEquals(
            expected.copyOfRange(Iec61937Packer.DATA_OFFSET, expected.size),
            packed.copyOfRange(Iec61937Packer.DATA_OFFSET, packed.size)
        )
    }

    // auSize 1 leaves a payload that is not a multiple of eight, so this covers the scalar tail
    // the word wide loop cannot reach.
    @Test
    fun packDtsHd_payloadUnswapsToTheStartCode() {
        val random = java.util.Random(20260906L)
        for (period in intArrayOf(512, 2048, 8192)) {
            for (auSize in intArrayOf(1, 7, 100, 513)) {
                val au = ByteArray(auSize).also { random.nextBytes(it) }
                val packed = Iec61937Packer.packDtsHd(au, period)
                val restored = packed.copyOf()
                pairwiseSwap(restored, Iec61937Packer.DATA_OFFSET, restored.size)
                val where = "period=$period auSize=$auSize"
                assertEquals(where, 0x01.toByte(), restored[Iec61937Packer.DATA_OFFSET])
                assertEquals(where, 0xFE.toByte(), restored[Iec61937Packer.DATA_OFFSET + 8])
                assertEquals(where, 0xFE.toByte(), restored[Iec61937Packer.DATA_OFFSET + 9])
                assertEquals(where, au[0], restored[Iec61937Packer.DATA_OFFSET + 12])
            }
        }
    }

    private fun pairwiseSwap(data: ByteArray, offset: Int, end: Int) {
        var i = offset
        while (i + 1 < end) {
            val tmp = data[i]
            data[i] = data[i + 1]
            data[i + 1] = tmp
            i += 2
        }
    }

    @Test
    fun packTrueHd_writesPreambleAndSwapsPayload() {
        val mat = ByteArray(Iec61937Packer.TRUEHD_IEC_SIZE)
        mat[8] = 0x12
        mat[9] = 0x34
        val packed = Iec61937Packer.packTrueHd(mat)
        val header = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.short)
        assertEquals(Iec61937Packer.PREAMBLE2, header.short)
        assertEquals(Iec61937Packer.TYPE_TRUEHD.toShort(), header.short)
        assertEquals(Iec61937Packer.TRUEHD_LENGTH_FIELD.toShort(), header.short)
        assertEquals(0x34.toByte(), packed[8])
        assertEquals(0x12.toByte(), packed[9])
    }

    @Test
    fun packDtsHd_eightChannelBurstIs32768() {
        val au = ByteArray(100) { 0xAB.toByte() }
        val period = Iec61937Packer.dtsHdIecPeriod(channelCount = 8, coreSampleCount = 512)
        assertEquals(8192, period)
        val packed = Iec61937Packer.packDtsHd(au, period)
        assertEquals(32768, packed.size)
        val header = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.short)
        assertEquals(Iec61937Packer.PREAMBLE2, header.short)
        val type = header.short.toInt() and 0xFFFF
        assertEquals(Iec61937Packer.TYPE_DTSHD, type and 0xFF)
        assertEquals(4, type shr 8)
    }

    @Test
    fun dtsHdPeriod_stereoUses2048() {
        assertEquals(2048, Iec61937Packer.dtsHdIecPeriod(channelCount = 2, coreSampleCount = 512))
    }

    @Test
    fun packTrueHdInPlace_matchesPackTrueHdAndReturnsTheSameArray() {
        val mat = ByteArray(Iec61937Packer.TRUEHD_IEC_SIZE) { i -> (i * 31 + 7).toByte() }
        for (i in 0 until Iec61937Packer.DATA_OFFSET) mat[i] = 0
        val expected = Iec61937Packer.packTrueHd(mat)
        val packed = Iec61937Packer.packTrueHdInPlace(mat)
        assertSame(mat, packed)
        assertArrayEquals(expected, packed)
    }

    @Test
    fun packDtsHdInto_matchesTheAllocatingPacker() {
        for (period in intArrayOf(512, 1024, 2048, 4096, 8192, 16384, 3000)) {
            val burstSize = period shl 2
            val sizes = listOf(
                0, 1, 5, 7, 8, 9, 100, 101,
                burstSize - 21, burstSize - 20, burstSize - 19,
                burstSize - 9, burstSize - 8, burstSize - 7, burstSize, burstSize + 33,
            )
            for (size in sizes) {
                val au = ByteArray(size) { i -> (i * 13 + period).toByte() }
                val expected = referencePackDtsHd(au, period)
                val out = ByteArray(burstSize) { 0x7E.toByte() }
                val buffer = ByteBuffer.wrap(au)
                Iec61937Packer.packDtsHdInto(buffer, period, out)
                assertArrayEquals("period=$period size=$size", expected, out)
                assertEquals("period=$period size=$size consumed", 0, buffer.remaining())
                assertArrayEquals("period=$period size=$size wrapper", expected, Iec61937Packer.packDtsHd(au, period))
            }
        }
    }

    // The DTS-HD packer as it was before packDtsHdInto existed, kept verbatim as the oracle.
    private fun referencePackDtsHd(accessUnit: ByteArray, iecPeriod: Int): ByteArray {
        val startCode = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFE.toByte(), 0xFE.toByte())
        val wrappedSize = Iec61937Packer.DTSHD_START_CODE_SIZE + accessUnit.size
        val wrapped = ByteArray(wrappedSize)
        System.arraycopy(startCode, 0, wrapped, 0, startCode.size)
        wrapped[10] = ((accessUnit.size shr 8) and 0xFF).toByte()
        wrapped[11] = (accessUnit.size and 0xFF).toByte()
        System.arraycopy(accessUnit, 0, wrapped, Iec61937Packer.DTSHD_START_CODE_SIZE, accessUnit.size)
        val subtype = when (iecPeriod) {
            512 -> 0
            1024 -> 1
            2048 -> 2
            4096 -> 3
            8192 -> 4
            16384 -> 5
            else -> 4
        }
        val burstSize = iecPeriod shl 2
        val out = ByteArray(burstSize)
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, Iec61937Packer.PREAMBLE1)
        header.putShort(2, Iec61937Packer.PREAMBLE2)
        header.putShort(4, (Iec61937Packer.TYPE_DTSHD or (subtype shl 8)).toShort())
        val length = ((wrappedSize + 0x17) and 0xFFFFFFF0.toInt()) - 0x08
        header.putShort(6, length.toShort())
        val payloadBytes = wrappedSize + (wrappedSize and 1)
        val copy = payloadBytes.coerceAtMost(burstSize - Iec61937Packer.DATA_OFFSET)
        System.arraycopy(wrapped, 0, out, Iec61937Packer.DATA_OFFSET, copy.coerceAtMost(wrapped.size))
        val end = (Iec61937Packer.DATA_OFFSET + (copy and 0x7FFFFFFE)).coerceAtMost(out.size)
        var i = Iec61937Packer.DATA_OFFSET
        while (i + 1 < end) {
            val tmp = out[i]
            out[i] = out[i + 1]
            out[i + 1] = tmp
            i += 2
        }
        return out
    }
}
