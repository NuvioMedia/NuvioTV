package com.nuvio.tv.core.player

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Av1Hdr10PlusStripperTest {

    private fun obu(type: Int, payload: ByteArray, hasSizeField: Boolean = true, extension: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        var header = (type shl 3) or if (hasSizeField) 0x02 else 0
        if (extension) header = header or 0x04
        out.write(header)
        if (extension) out.write(0)
        if (hasSizeField) {
            var size = payload.size
            while (true) {
                var b = size and 0x7F
                size = size ushr 7
                if (size != 0) b = b or 0x80
                out.write(b)
                if (size == 0) break
            }
        }
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    private fun t35MetadataObu(countryProviderPoc: ByteArray, tail: ByteArray = ba(0x04, 0x01, 0x40)): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0x04) // METADATA_TYPE_ITUT_T35
        payload.write(countryProviderPoc, 0, countryProviderPoc.size)
        payload.write(tail, 0, tail.size)
        return obu(5, payload.toByteArray())
    }

    private val hdr10Plus = t35MetadataObu(ba(0xB5, 0x00, 0x3C, 0x00, 0x01))
    private val dvRpu = t35MetadataObu(ba(0xB5, 0x00, 0x3B, 0x00, 0x00), ba(0x11, 0x22, 0x33))
    private val cllMetadata = obu(5, ba(0x01, 0x03, 0xDE, 0x02, 0x5C, 0x80))
    private val mdcvMetadata = obu(5, ba(0x02, 0xB5, 0x3F, 0x4A, 0xC1))
    private val sequenceHeader = obu(1, ba(0x02, 0x00, 0x00, 0x62, 0x97))
    private val frame = obu(6, ByteArray(64) { (it * 7).toByte() })
    private val temporalDelimiter = obu(2, ba())

    private fun strip(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(data.size)
        val written = Av1Hdr10PlusStripper.stripHdr10PlusObus(data, data.size, out)
        return if (written < 0) null else out.toByteArray().copyOf(written)
    }

    private fun ba(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p, 0, p.size)
        return out.toByteArray()
    }

    @Test
    fun `strips a single HDR10+ metadata OBU and keeps everything else`() {
        val sample = concat(temporalDelimiter, sequenceHeader, hdr10Plus, frame)
        val result = strip(sample)!!
        assertArrayEquals(concat(temporalDelimiter, sequenceHeader, frame), result)
    }

    @Test
    fun `keeps DV RPU T35 metadata untouched`() {
        val sample = concat(sequenceHeader, dvRpu, frame)
        assertNull(strip(sample))
    }

    @Test
    fun `keeps non-T35 metadata types untouched`() {
        val sample = concat(sequenceHeader, cllMetadata, mdcvMetadata, frame)
        assertNull(strip(sample))
    }

    @Test
    fun `strips multiple HDR10+ OBUs across the sample`() {
        val sample = concat(sequenceHeader, hdr10Plus, frame, hdr10Plus, temporalDelimiter)
        val result = strip(sample)!!
        assertArrayEquals(concat(sequenceHeader, frame, temporalDelimiter), result)
    }

    @Test
    fun `returns -1 when no HDR10+ is present and leaves output untouched`() {
        val sample = concat(sequenceHeader, dvRpu, cllMetadata, frame)
        val out = ByteArrayOutputStream(sample.size)
        assertEquals(-1, Av1Hdr10PlusStripper.stripHdr10PlusObus(sample, sample.size, out))
        assertEquals(0, out.size())
    }

    @Test
    fun `handles OBU without size field as final OBU`() {
        val trailing = obu(15, ba(0xAA, 0xBB), hasSizeField = false)
        val sample = concat(sequenceHeader, hdr10Plus, trailing)
        val result = strip(sample)!!
        assertArrayEquals(concat(sequenceHeader, trailing), result)
    }

    @Test
    fun `handles OBU extension flag`() {
        val extObu = obu(5, ByteArrayOutputStream().apply {
            write(0x04); write(ba(0xB5, 0x00, 0x3C, 0x00, 0x01)); write(ba(0x04, 0x01, 0x40))
        }.toByteArray(), extension = true)
        val sample = concat(sequenceHeader, extObu, frame)
        val result = strip(sample)!!
        assertArrayEquals(concat(sequenceHeader, frame), result)
    }

    @Test
    fun `returns -1 on truncated OBU`() {
        val truncated = concat(sequenceHeader, hdr10Plus).copyOf(concat(sequenceHeader, hdr10Plus).size - 3)
        assertNull(strip(truncated))
    }

    @Test
    fun `returns -1 on forbidden bit`() {
        val bad = ba(0x8A) + sequenceHeader
        assertNull(strip(bad))
    }

    @Test
    fun `returns -1 on unterminated leb128 size`() {
        val bad = ba(0x2A, 0x80, 0x80)
        assertNull(strip(bad))
    }

    @Test
    fun `strips HDR10+ even when it is the first OBU`() {
        val sample = concat(hdr10Plus, sequenceHeader, frame)
        val result = strip(sample)!!
        assertArrayEquals(concat(sequenceHeader, frame), result)
    }

    @Test
    fun `strips HDR10+ even when it is the last OBU`() {
        val sample = concat(sequenceHeader, frame, hdr10Plus)
        val result = strip(sample)!!
        assertArrayEquals(concat(sequenceHeader, frame), result)
    }

    @Test
    fun `rejects T35 with wrong provider code`() {
        val wrongProvider = t35MetadataObu(ba(0xB5, 0x00, 0x31, 0x00, 0x01))
        assertNull(strip(concat(sequenceHeader, wrongProvider, frame)))
    }

    @Test
    fun `rejects T35 with wrong country code`() {
        val wrongCountry = t35MetadataObu(ba(0xB4, 0x00, 0x3C, 0x00, 0x01))
        assertNull(strip(concat(sequenceHeader, wrongCountry, frame)))
    }

    @Test
    fun `rejects T35 signature truncated below 5 bytes`() {
        val short = obu(5, ba(0x04, 0xB5, 0x00, 0x3C))
        assertNull(strip(concat(sequenceHeader, short, frame)))
    }

    @Test
    fun `output buffer reuse returns consistent results across calls`() {
        val out = ByteArrayOutputStream(1024)
        val s1 = concat(sequenceHeader, hdr10Plus, frame)
        val s2 = concat(sequenceHeader, frame)
        val w1 = Av1Hdr10PlusStripper.stripHdr10PlusObus(s1, s1.size, out)
        assertTrue(w1 > 0)
        val first = out.toByteArray().copyOf(w1)
        out.reset()
        val w2 = Av1Hdr10PlusStripper.stripHdr10PlusObus(s2, s2.size, out)
        assertEquals(-1, w2)
        // second call must not corrupt first result semantics: buffer still holds first output until reset
        assertArrayEquals(concat(sequenceHeader, frame), first)
    }
}
