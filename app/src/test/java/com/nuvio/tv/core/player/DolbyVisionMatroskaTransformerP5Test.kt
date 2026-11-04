package com.nuvio.tv.core.player

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Matroska transformer keeps Profile 5 RPUs for the HDR10 mapping, even in strip modes. */
class DolbyVisionMatroskaTransformerP5Test {

    private val sessions = mutableListOf<DolbyVisionP5Session>()

    @After
    fun releaseSessions() = sessions.forEach(DolbyVisionP5Session::release)

    private fun stripTransformer(active: Boolean): DolbyVisionMatroskaTransformer {
        val session = DolbyVisionP5Session(active = active).also { sessions += it }
        return DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            stripRpuOnly = true,
            p5Session = session,
        )
    }

    // Length-delimited (4-byte) sample: a slice NAL, then an RPU NAL.
    private val slice = byteArrayOf(0x02, 0x01, 0x11, 0x22)
    private val rpu = byteArrayOf(0x7C, 0x01, 0x19, 0x08, 0x09, 0x80.toByte())
    private val sample = byteArrayOf(0, 0, 0, slice.size.toByte()) + slice + byteArrayOf(0, 0, 0, rpu.size.toByte()) + rpu

    private fun output(transformer: DolbyVisionMatroskaTransformer, result: ByteArray?): ByteArray =
        (result ?: sample).copyOf(if (result == null) sample.size else transformer.lastTransformedSampleLength())

    @Test
    fun profile5ByCodecsOnly_keepsItsRpuInStripMode() {
        val transformer = stripTransformer(active = true)
        // No Dolby Vision config: only the codecs say Profile 5.
        transformer.onDolbyVisionCodecString("dvhe.05.06", null)
        assertTrue(transformer.shouldTransform("dvhe.05.06", null))
        val out = output(transformer, transformer.transformHevcSample(sample, sample.size, 4, null, null))
        assertArrayEquals(sample, out)
    }

    @Test
    fun blockAdditionalRpu_isCarriedInTheSample() {
        val transformer = stripTransformer(active = true)
        transformer.onDolbyVisionCodecString("dvhe.05.06", null)
        val bareRpu = byteArrayOf(0x19, 0x08, 0x09, 0x80.toByte())
        assertEquals(null, transformer.onDolbyVisionBlockAdditionalData(bareRpu, 0, null))
        val sliceOnly = byteArrayOf(0, 0, 0, slice.size.toByte()) + slice
        val out = output(transformer, transformer.transformHevcSample(sliceOnly, sliceOnly.size, 4, bareRpu, null))
        val expected = sliceOnly + byteArrayOf(0, 0, 0, (bareRpu.size + 2).toByte(), 0x7C, 0x01) + bareRpu
        assertArrayEquals(expected, out)
    }

    @Test
    fun withoutAnActiveSession_stripModeStillStrips() {
        val transformer = stripTransformer(active = false)
        transformer.onDolbyVisionCodecString("dvhe.05.06", null)
        val out = output(transformer, transformer.transformHevcSample(sample, sample.size, 4, null, null))
        assertTrue("the RPU is stripped as before", out.size < sample.size)
    }
}
