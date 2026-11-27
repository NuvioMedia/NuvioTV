package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearKeyLavfOptionsTest {

    @Test
    fun `the DASH and MP4 demuxers get the pairs as an FFmpeg dictionary in mpv length quoting`() {
        val keys = "${"a".repeat(32)}:${"b".repeat(32)},${"c".repeat(32)}:${"d".repeat(32)}"
        val dict = "${"a".repeat(32)}=${"b".repeat(32)}:${"c".repeat(32)}=${"d".repeat(32)}"
        assertEquals(
            "cenc_decryption_keys=%${dict.length}%$dict,decryption_keys=%${dict.length}%$dict",
            clearKeyLavfOptions(keys)
        )
    }
}
