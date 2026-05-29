package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionBaseLayerPolicyTest {

    private fun resolve(
        hdrCapsKnown: Boolean = true,
        displayDv: Boolean = false,
        displayHdr10: Boolean = false,
        displayHdr10Plus: Boolean = false,
        displayHlg: Boolean = false,
        codecSupportsDvheDtb: Boolean = false,
        codecSupportsDvheStn: Boolean = false,
        codecSupportsDvheSt: Boolean = false,
        isAmazonFireTv: Boolean = false,
        isSamsung: Boolean = false,
        isXiaomi: Boolean = false,
        bridgeReady: Boolean = false,
        apiLevel: Int = 30
    ) = DolbyVisionBaseLayerPolicy.resolveFromCapabilities(
        hdrCapsKnown = hdrCapsKnown,
        displayDv = displayDv,
        displayHdr10 = displayHdr10,
        displayHdr10Plus = displayHdr10Plus,
        displayHlg = displayHlg,
        codecSupportsDvheDtb = codecSupportsDvheDtb,
        codecSupportsDvheStn = codecSupportsDvheStn,
        codecSupportsDvheSt = codecSupportsDvheSt,
        isAmazonFireTv = isAmazonFireTv,
        isSamsung = isSamsung,
        isXiaomi = isXiaomi,
        bridgeReady = bridgeReady,
        apiLevel = apiLevel
    )

    // ── Unknown caps ──

    @Test
    fun `unknown caps returns STRIP_BEST_EFFORT regardless of other flags`() {
        val r = resolve(
            hdrCapsKnown = false,
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_BEST_EFFORT, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertTrue(r.mapToHevc)
    }

    // ── NATIVE_DV7: native DV7 decoder (Shield) ──

    @Test
    fun `DV display with native DvheDtb decoder returns NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
        assertFalse(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Amazon device with native DvheDtb prefers NATIVE_DV7 over conversion`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    @Test
    fun `Xiaomi device with native DvheDtb prefers NATIVE_DV7 over conversion`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheDtb = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    // ── CONVERT_TO_DV81: Fire TV Karat (Amazon-gated) ──

    @Test
    fun `Fire TV on DV display with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Fire TV on DV display without bridge falls through to NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = false
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    @Test
    fun `Fire TV on DV display without DV81 decoder falls through to NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    // ── CONVERT_TO_DV81: Xiaomi Mi Box (relaxed, no codec check) ──

    @Test
    fun `Xiaomi on DV display with bridge converts even without DvheSt`() {
        // Mi Box S: displayDv comes from LG/Samsung TV, codecSupportsDvheSt is false
        // because the Amlogic decoder doesn't advertise Profile 8. Relaxed branch
        // fires on manufacturer + bridge alone.
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Xiaomi on DV display with DvheSt also converts`() {
        // Future Xiaomi device that DOES advertise Profile 8. The Amazon-gated
        // branch won't fire (not Amazon), but the Xiaomi branch catches it.
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Xiaomi on DV display without bridge falls through to NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = false
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    @Test
    fun `Xiaomi on HDR10 display with bridge converts`() {
        // Mi Box connected to HDR10-only TV: bridge converts so the hidden
        // DV decoder can emit HDR10. Better than STRIP which may fail on
        // some Amlogic firmware.
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Xiaomi on HDR10 display without bridge strips to HDR10`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            isXiaomi = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    // ── NATIVE_DV7 catch-all: non-Amazon non-Xiaomi devices on DV display ──

    @Test
    fun `non-Amazon device on DV display with DV81 decoder falls through to NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = false,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
        assertFalse(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Samsung device on DV display still falls through to NATIVE_DV7`() {
        val r = resolve(
            displayDv = true,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.NATIVE_DV7, r.decision)
    }

    // ── CONVERT_TO_DV81: HDR10 fallback (Samsung + Amazon) ──

    @Test
    fun `Samsung HDR10 panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
        assertTrue(r.divertsFromNativeDv7)
        assertFalse(r.mapToHevc)
    }

    @Test
    fun `Samsung HDR10Plus panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10Plus = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    @Test
    fun `Fire TV on HDR10 panel with DV81 decoder and bridge converts`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            bridgeReady = true
        )
        assertEquals(Decision.CONVERT_TO_DV81, r.decision)
    }

    // ── STRIP_TO_HDR10: Google TV Streamer + Samsung TV (non-intervened) ──

    @Test
    fun `Generic HDR10 panel with DV81 decoder strips when non-Samsung non-Amazon non-Xiaomi`() {
        val r = resolve(
            displayDv = false,
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = false,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `Samsung HDR10 panel without bridge strips to HDR10`() {
        val r = resolve(
            displayHdr10 = true,
            codecSupportsDvheSt = true,
            isSamsung = true,
            bridgeReady = false
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `Samsung HDR10 panel with bridge but no DV81 decoder strips to HDR10`() {
        val r = resolve(
            displayHdr10 = true,
            codecSupportsDvheSt = false,
            isSamsung = true,
            bridgeReady = true
        )
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    @Test
    fun `HDR10 panel with nothing useful strips to HDR10`() {
        val r = resolve(displayHdr10 = true)
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `HDR10Plus panel with nothing useful strips to HDR10`() {
        val r = resolve(displayHdr10Plus = true)
        assertEquals(Decision.STRIP_TO_HDR10, r.decision)
    }

    // ── STRIP_AND_TONEMAP ──

    @Test
    fun `SDR-only display returns STRIP_AND_TONEMAP`() {
        val r = resolve(codecSupportsDvheSt = true, bridgeReady = true)
        assertEquals(Decision.STRIP_AND_TONEMAP, r.decision)
        assertTrue(r.mapToHevc)
    }

    @Test
    fun `HLG-only display returns STRIP_AND_TONEMAP`() {
        val r = resolve(displayHlg = true, bridgeReady = true)
        assertEquals(Decision.STRIP_AND_TONEMAP, r.decision)
    }

    // ── Result field propagation ──

    @Test
    fun `result preserves all input fields`() {
        val r = resolve(
            hdrCapsKnown = true,
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            displayHlg = false,
            codecSupportsDvheDtb = false,
            codecSupportsDvheStn = true,
            codecSupportsDvheSt = true,
            isAmazonFireTv = true,
            isSamsung = false,
            isXiaomi = false,
            bridgeReady = true,
            apiLevel = 33
        )
        assertTrue(r.hdrCapsKnown)
        assertTrue(r.displayDv)
        assertTrue(r.displayHdr10)
        assertTrue(r.displayHdr10Plus)
        assertFalse(r.displayHlg)
        assertFalse(r.codecSupportsDvheDtb)
        assertTrue(r.codecSupportsDvheStn)
        assertTrue(r.codecSupportsDvheSt)
        assertTrue(r.isAmazonFireTv)
        assertFalse(r.isSamsung)
        assertFalse(r.isXiaomi)
        assertTrue(r.bridgeReady)
        assertEquals(33, r.apiLevel)
    }
}