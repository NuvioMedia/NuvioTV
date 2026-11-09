package com.nuvio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DolbyVisionMatroskaTransformerTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write(b(n.size ushr 24, n.size ushr 16, n.size ushr 8, n.size and 0xFF))
            out.write(n)
        }
        return out.toByteArray()
    }

    // HEVC NAL header: forbidden_zero(1) | type(6) | layer_id bit | tid. type = byte0 ushr 1.
    private fun nal(type: Int, vararg payload: Int) =
        b((type shl 1) and 0xFF, 0x01, *payload)

    // Prefix SEI (type 39) carrying only an HDR10+ T.35 message (+ rbsp stop bit)
    private val hdr10PlusSei = b(0x4E, 0x01, 0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x80)
    // In-band DV RPU (type 62) and an enhancement-layer style NAL (type 63)
    private val rpuNal = nal(62, 0x44, 0x56, 0x52, 0x50, 0x55) // "DVRPU"
    private val elNal = nal(63, 0x45, 0x4C)
    private val sliceNal = nal(19, 0xAA, 0xBB, 0xCC)

    private val dvSample = lengthDelimited(hdr10PlusSei, rpuNal, elNal, sliceNal)
    private val hevcCodecs = "hvc1.1.6.L93.B0"
    private val dvCodecs = "dvhe.07.06.L93.B0"

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun nonDvTrackOnlyGetsHdr10PlusStrippingAndKeepsDvNals() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            stripRpuOnly = false,
            stripHdr10PlusSei = true
        )
        val out = transformer.transformHevcSample(
            dvSample, dvSample.size, 4, null, null, hevcCodecs
        )
        assertNotNull(out)
        val valid = out!!.copyOf(transformer.lastTransformedSampleLength())
        // HDR10+ signature is gone, but the DV RPU/EL NALs are untouched.
        assertTrue(!containsSubsequence(valid, byteArrayOf(0xB5.toByte(), 0x00, 0x3C.toByte())))
        assertTrue(containsSubsequence(valid, rpuNal))
        assertTrue(containsSubsequence(valid, elNal))
        assertTrue(containsSubsequence(valid, sliceNal))
    }

    @Test
    fun dvTrackWithStripRpuOnlyDropsRpuAndElNals() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            stripRpuOnly = true,
            stripHdr10PlusSei = false
        )
        val out = transformer.transformHevcSample(
            dvSample, dvSample.size, 4, null, null, dvCodecs
        )
        assertNotNull(out)
        val valid = out!!.copyOf(transformer.lastTransformedSampleLength())
        assertTrue(!containsSubsequence(valid, rpuNal))
        assertTrue(!containsSubsequence(valid, elNal))
        assertTrue(containsSubsequence(valid, sliceNal))
    }

    @Test
    fun codecsArePassedPerCallAndDoNotLeakAcrossTracks() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            stripRpuOnly = true,
            stripHdr10PlusSei = false
        )
        // First call looks like a DV track (e.g. dvhe.07): RPU must be stripped.
        transformer.transformHevcSample(dvSample, dvSample.size, 4, null, null, dvCodecs)
        // Second call has no DV signaling at all (plain HEVC / another track):
        // the previous call's codecs must not leak into this decision.
        val out = transformer.transformHevcSample(
            dvSample, dvSample.size, 4, null, null, null
        )
        assertNotNull(out)
        // Non-DV + stripRpuOnly + no HDR10+ flag: original sample returned untouched.
        assertArrayEquals(dvSample, out)
    }

    @Test
    fun blockAdditionalResolvesProfileFromPerCallCodecs() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = true),
            stripRpuOnly = false,
            stripHdr10PlusSei = false
        )
        // dvhe.07 -> profile 7 -> shouldConvert(7) is true for an active config, so the
        // RPU goes through convertRpuNal. DoviBridge is unavailable on the JVM, so the
        // conversion yields nothing and the original payload is kept (null return).
        val out = transformer.onDolbyVisionBlockAdditionalData(
            rpuNal, 0, null, dvCodecs
        )
        assertNull(out)
        // A non-DV codec string resolves no profile and also keeps the payload.
        assertNull(
            transformer.onDolbyVisionBlockAdditionalData(rpuNal, 0, null, hevcCodecs)
        )
    }
}
