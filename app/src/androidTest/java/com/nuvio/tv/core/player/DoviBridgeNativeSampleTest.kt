package com.nuvio.tv.core.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Instrumented coverage for the native sample rewriter in dovi_bridge.cpp.
 * Runs against the real .so packaged in the app APK.
 */
@RunWith(AndroidJUnit4::class)
class DoviBridgeNativeSampleTest {

    @Before
    fun requireNativeBridge() {
        assertTrue("dovi_bridge native library unavailable", DoviBridge.isAvailable())
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write(b(n.size ushr 24, n.size ushr 16, n.size ushr 8, n.size and 0xFF))
            out.write(n)
        }
        return out.toByteArray()
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write(b(0, 0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun process(
        sample: ByteArray,
        nalFormat: Int,
        nalLengthFieldLength: Int = 4,
        convertDovi: Boolean = false,
        stripDoviRpu: Boolean = false,
        stripHdr10Plus: Boolean = false
    ): ByteArray {
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample = sample,
            sampleLen = sample.size,
            nalFormat = nalFormat,
            nalLengthFieldLength = nalLengthFieldLength,
            convertDovi = convertDovi,
            doviMode = 1,
            doviProfile = -1,
            stripDoviRpu = stripDoviRpu,
            stripHdr10Plus = stripHdr10Plus
        )
        assertTrue("expected rewritten output, got written=$written", written > 0)
        return DoviBridge.rpuOutBuffer.copyOf(written)
    }

    // Prefix SEI (type 39) carrying only an HDR10+ T.35 message (+ rbsp stop bit)
    private val hdr10PlusSei = b(0x4E, 0x01, 0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x80)
    private val slice = b(0x26, 0x01, 0xAA, 0xBB, 0xCC)

    // DV RPU (type 62), enhancement layer (type 63), base slice (type 19)
    private val rpuNal = b(0x7C, 0x01, 0x44, 0x56, 0x52, 0x50)
    private val elNal = b(0x7E, 0x01, 0x45, 0x4C)

    @Test
    fun annexBStripsHdr10PlusSei() {
        val sample = annexB(hdr10PlusSei, slice)
        val out = process(sample, nalFormat = 0, stripHdr10Plus = true)
        assertFalse(containsSubsequence(out, byteArrayOf(0xB5.toByte(), 0x00, 0x3C.toByte())))
        assertTrue(containsSubsequence(out, slice))
    }

    @Test
    fun lengthDelimitedStillStripsHdr10PlusSei() {
        val sample = lengthDelimited(hdr10PlusSei, slice)
        val out = process(sample, nalFormat = 1, stripHdr10Plus = true)
        assertFalse(containsSubsequence(out, byteArrayOf(0xB5.toByte(), 0x00, 0x3C.toByte())))
        assertTrue(containsSubsequence(out, slice))
    }

    @Test
    fun malformedLengthDelimitedReturnsZero() {
        // First 4 bytes parse as length 0x0000014E, far beyond the sample: must abort, not reinterpret.
        val sample = b(0x00, 0x00, 0x01, 0x4E, 0x01, 0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x80)
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample, sample.size, 1, 4, false, 1, -1, false, true
        )
        assertEquals(0, written)
    }

    @Test
    fun annexBStripsDvRpuAndEnhancementLayerNals() {
        val sample = annexB(rpuNal, elNal, slice)
        val out = process(sample, nalFormat = 0, stripDoviRpu = true)
        assertFalse(containsSubsequence(out, rpuNal))
        assertFalse(containsSubsequence(out, elNal))
        assertTrue(containsSubsequence(out, slice))
    }

    @Test
    fun seiTrailingPaddingDoesNotSwallowMessages() {
        // user_data_unregistered-ish message (type 5) + HDR10+ + 0x80 + zero padding
        val mixedSei = b(
            0x4E, 0x01,
            0x05, 0x01, 0x11,
            0x04, 0x06, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04,
            0x80, 0x00
        )
        val sample = annexB(mixedSei, slice)
        val out = process(sample, nalFormat = 0, stripHdr10Plus = true)
        assertFalse(containsSubsequence(out, byteArrayOf(0xB5.toByte(), 0x00, 0x3C.toByte())))
        assertTrue(containsSubsequence(out, slice))
        // The non-HDR10+ SEI message (type 5, size 1, payload 0x11) must be preserved.
        assertTrue(containsSubsequence(out, b(0x05, 0x01, 0x11)))
    }

    @Test
    fun h264SampleIsUntouchedByHdr10PlusStrip() {
        // H.264 SPS/PPS/IDR: no HEVC type-39/40 NAL headers, so nothing may change.
        val sample = annexB(
            b(0x67, 0x42, 0x00, 0x1E),
            b(0x68, 0xCE, 0x06, 0xE2),
            b(0x65, 0x88, 0x84, 0x00)
        )
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample, sample.size, 0, 4, false, 1, -1, false, true
        )
        assertEquals(0, written)
    }

    @Test
    fun allFlagsOffReturnsZero() {
        val sample = annexB(hdr10PlusSei, slice)
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample, sample.size, 0, 4, false, 1, -1, false, false
        )
        assertEquals(0, written)
    }
}
