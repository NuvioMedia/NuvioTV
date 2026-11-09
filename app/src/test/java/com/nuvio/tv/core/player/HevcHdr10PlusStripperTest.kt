package com.nuvio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HevcHdr10PlusStripperTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) {
            out.write(b(n.size ushr 24, n.size ushr 16, n.size ushr 8, n.size and 0xFF))
            out.write(n)
        }
        return out.toByteArray()
    }

    // Prefix SEI (type 39) carrying only an HDR10+ T.35 message (+ rbsp stop bit)
    private val hdr10PlusSei = b(0x4E, 0x01, 0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x80)
    private val slice = b(0x26, 0x01, 0xAA, 0xBB, 0xCC)

    @Test
    fun dropsHdr10PlusOnlySeiNal() {
        val sample = lengthDelimited(hdr10PlusSei, slice)
        val out = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4)
        assertNotNull(out)
        assertArrayEquals(lengthDelimited(slice), out)
    }

    @Test
    fun keepsOtherSeiMessagesAndIgnoresTrailingPadding() {
        // user_data_unregistered-ish message (type 5, size 1) + HDR10+ + 0x80 + multiple zero paddings
        val mixed = b(
            0x4E, 0x01,
            0x05, 0x01, 0x11,
            0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04,
            0x80, 0x00, 0x00, 0x00
        )
        val sample = lengthDelimited(mixed, slice)
        val out = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4)!!
        val expectedSei = b(0x4E, 0x01, 0x05, 0x01, 0x11, 0x80)
        assertArrayEquals(lengthDelimited(expectedSei, slice), out)
    }

    @Test
    fun untouchedWhenNoHdr10Plus() {
        val sample = lengthDelimited(slice)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }

    @Test
    fun malformedLengthDelimitedDataIsNeverRewrittenAsAnnexB() {
        // 4-byte length 0x000001xx contains 00 00 01; the old fallback treated it as a start code.
        val sample = b(0x00, 0x00, 0x01, 0x4E, 0x01, 0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x80)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }

    @Test
    fun unterminatedPayloadTypeReturnsNull() {
        // NAL header (2 bytes) + payloadType ending in 0xFF without terminating byte (< 0xFF)
        val unterminatedType = b(0x4E, 0x01, 0xFF, 0xFF)
        val sample = lengthDelimited(unterminatedType, slice)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }

    @Test
    fun unterminatedPayloadSizeReturnsNull() {
        // NAL header + payloadType 4 + payloadSize ending in 0xFF without terminator
        val unterminatedSize = b(0x4E, 0x01, 0x04, 0xFF, 0xFF)
        val sample = lengthDelimited(unterminatedSize, slice)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }

    @Test
    fun payloadSizeExceedingRbspReturnsNull() {
        // payloadSize = 50, but only 3 bytes remain in RBSP
        val overflowSize = b(0x4E, 0x01, 0x04, 0x32, 0xB5, 0x00, 0x3C)
        val sample = lengthDelimited(overflowSize, slice)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }
}
