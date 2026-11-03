package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionP5NalTest {

    // HEVC NAL headers: type in bits 1..6 of the first byte.
    private val sliceHeader = byteArrayOf(0x02, 0x01) // TRAIL_R (type 1)
    private val rpuHeader = byteArrayOf(0x7C, 0x01)   // UNSPEC62 (type 62)
    private val rpuPayload = byteArrayOf(0x19, 0x08, 0x09, 0x80.toByte())

    private fun bytes(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    private fun unpack(packed: Long): Pair<Int, Int> =
        (packed ushr 32).toInt() to (packed and 0xFFFFFFFFL).toInt()

    @Test
    fun findsRpuAfterSliceInAnnexB() {
        val sample = bytes(
            byteArrayOf(0, 0, 0, 1), sliceHeader, byteArrayOf(0x11, 0x22, 0x33),
            byteArrayOf(0, 0, 0, 1), rpuHeader, rpuPayload,
        )
        val (offset, length) = unpack(DolbyVisionP5Nal.findRpu(sample, sample.size))
        assertEquals(13, offset)
        assertEquals(rpuHeader.size + rpuPayload.size, length)
    }

    @Test
    fun trimsTheZeroOfAFollowingFourByteStartCode() {
        val sample = bytes(
            byteArrayOf(0, 0, 1), rpuHeader, rpuPayload,
            byteArrayOf(0, 0, 0, 1), sliceHeader, byteArrayOf(0x44),
        )
        val (offset, length) = unpack(DolbyVisionP5Nal.findRpu(sample, sample.size))
        assertEquals(3, offset)
        assertEquals(rpuHeader.size + rpuPayload.size, length)
    }

    @Test
    fun findsRpuInLengthPrefixedSample() {
        val slice = bytes(sliceHeader, byteArrayOf(0x11, 0x22))
        val rpu = bytes(rpuHeader, rpuPayload)
        val sample = bytes(
            byteArrayOf(0, 0, 0, slice.size.toByte()), slice,
            byteArrayOf(0, 0, 0, rpu.size.toByte()), rpu,
        )
        val (offset, length) = unpack(DolbyVisionP5Nal.findRpu(sample, sample.size))
        assertEquals(4 + slice.size + 4, offset)
        assertEquals(rpu.size, length)
    }

    @Test
    fun noRpuReturnsNone() {
        val sample = bytes(byteArrayOf(0, 0, 0, 1), sliceHeader, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        assertEquals(DolbyVisionP5Nal.NONE, DolbyVisionP5Nal.findRpu(sample, sample.size))
    }

    @Test
    fun onlyTheGivenLengthIsScanned() {
        val sample = bytes(
            byteArrayOf(0, 0, 0, 1), sliceHeader, byteArrayOf(0x11, 0x22, 0x33),
            byteArrayOf(0, 0, 0, 1), rpuHeader, rpuPayload,
        )
        assertEquals(DolbyVisionP5Nal.NONE, DolbyVisionP5Nal.findRpu(sample, 9))
    }
}
