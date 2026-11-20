package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forced passthrough must advertise every bitstream the issue relies on, and nothing else. */
class ForcedPassthroughCapabilitiesTest {

    @Test
    fun `pins the receiver bitstream set plus 16 bit pcm`() {
        val encodings = ForcedPassthroughCapabilities.encodings.toList()

        assertTrue(encodings.contains(C.ENCODING_PCM_16BIT))
        assertTrue(encodings.contains(C.ENCODING_AC3))
        assertTrue(encodings.contains(C.ENCODING_E_AC3))
        assertTrue(encodings.contains(C.ENCODING_E_AC3_JOC))
        assertTrue(encodings.contains(C.ENCODING_DTS))
        assertTrue(encodings.contains(C.ENCODING_DTS_HD))
        assertTrue(encodings.contains(C.ENCODING_DOLBY_TRUEHD))
        assertEquals(7, encodings.size)
        assertFalse(encodings.contains(C.ENCODING_AC4))
    }
}
