package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubtitleStyleHdrOverrideTest {

    private val style = SubtitleStyleSettings(
        textColor = 0xFFFFFFFF.toInt(),
        backgroundColor = 0x80646464.toInt(),
        outlineColor = 0xFFFFFFFF.toInt(),
        hdrStyleEnabled = true,
        hdrTextColor = 0xFFFFFF00.toInt(),
        hdrBrightness = 100
    )

    @Test
    fun `sdr video keeps the base style`() {
        assertSame(style, style.forVideo(isHdrVideo = false))
    }

    @Test
    fun `hdr video keeps the base style when the override is disabled`() {
        val disabled = style.copy(hdrStyleEnabled = false)
        assertSame(disabled, disabled.forVideo(isHdrVideo = true))
    }

    @Test
    fun `full hdr brightness swaps in the hdr text color undimmed`() {
        val effective = style.forVideo(isHdrVideo = true)
        assertEquals(0xFFFFFF00.toInt(), effective.textColor)
        assertEquals(style.backgroundColor, effective.backgroundColor)
        assertEquals(style.outlineColor, effective.outlineColor)
    }

    @Test
    fun `zero hdr brightness dims every color to 30 percent and keeps alpha`() {
        val effective = style.copy(hdrBrightness = 0).forVideo(isHdrVideo = true)
        assertEquals(0xFF4C4C00.toInt(), effective.textColor)
        assertEquals(0x801E1E1E.toInt(), effective.backgroundColor)
        assertEquals(0xFF4C4C4C.toInt(), effective.outlineColor)
    }

    @Test
    fun `default hdr brightness dims to 58 percent`() {
        val effective = style.copy(hdrBrightness = SubtitleStyleSettings.HDR_BRIGHTNESS_DEFAULT)
            .forVideo(isHdrVideo = true)
        assertEquals(0xFF939300.toInt(), effective.textColor)
    }

    @Test
    fun `editable style shows the undimmed hdr text color only on hdr video`() {
        assertEquals(0xFFFFFF00.toInt(), style.copy(hdrBrightness = 0).editableForVideo(isHdrVideo = true).textColor)
        assertEquals(style.textColor, style.editableForVideo(isHdrVideo = false).textColor)
    }

    @Test
    fun `player controls stay at full brightness unless every condition holds`() {
        val dimming = style.copy(hdrDimPlayerControls = true, hdrBrightness = 0)
        assertEquals(1f, style.copy(hdrBrightness = 0).playerControlsBrightness(isHdrVideo = true), 0f)
        assertEquals(1f, dimming.playerControlsBrightness(isHdrVideo = false), 0f)
        assertEquals(1f, dimming.copy(hdrStyleEnabled = false).playerControlsBrightness(isHdrVideo = true), 0f)
    }

    @Test
    fun `player controls share the hdr brightness mapping`() {
        val dimming = style.copy(hdrDimPlayerControls = true)
        assertEquals(0.3f, dimming.copy(hdrBrightness = 0).playerControlsBrightness(isHdrVideo = true), 0.0001f)
        assertEquals(0.58f, dimming.copy(hdrBrightness = 40).playerControlsBrightness(isHdrVideo = true), 0.0001f)
        assertEquals(1f, dimming.copy(hdrBrightness = 100).playerControlsBrightness(isHdrVideo = true), 0.0001f)
    }
}
