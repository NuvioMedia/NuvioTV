package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxRenderPolicyTest {

    @Test
    fun `amazon fire tv uses opaque black letterbox`() {
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox(manufacturer = "Amazon"))
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox(manufacturer = "amazon"))
    }

    @Test
    fun `google tv streamer uses opaque black letterbox`() {
        assertFalse(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Google",
                model = "Google TV Streamer",
                hardware = "mt8696",
                device = "kirkwood",
                socModel = "MT8696"
            )
        )
    }

    @Test
    fun `devices with mt8696 soc or kirkwood codename use opaque black letterbox`() {
        assertFalse(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Google",
                model = "Generic TV",
                device = "kirkwood"
            )
        )
        assertFalse(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Google",
                model = "Generic TV",
                socModel = "MT8696"
            )
        )
        assertFalse(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Other",
                hardware = "mt8696"
            )
        )
    }

    @Test
    fun `sony bravia and xiaomi use transparent letterbox`() {
        assertTrue(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Sony",
                model = "BRAVIA 4K VH2",
                hardware = "ursa",
                device = "BRAVIA_VH2_4K"
            )
        )
        assertTrue(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Xiaomi",
                model = "MiBOX4",
                hardware = "amlogic",
                device = "once"
            )
        )
    }

    @Test
    fun `generic non-mt8696 devices default to transparent letterbox`() {
        assertTrue(
            LetterboxRenderPolicy.defaultTransparentLetterbox(
                manufacturer = "Nvidia",
                model = "SHIELD Android TV",
                hardware = "foster",
                device = "mdarcy"
            )
        )
    }
}
