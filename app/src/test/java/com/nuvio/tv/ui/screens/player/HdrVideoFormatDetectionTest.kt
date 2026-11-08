package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrVideoFormatDetectionTest {

    // VPS/SPS/PPS (Annex B) of two HEVC encodes: 1080p Main10 signalling PQ, 320x180 Main signalling BT.709.
    private val pqParameterSets = hex("0000000140010c01ffff02200000030090000003000003007895940900000001420101022000000300900000030000030078a003c0801107cad965654a4c2f016a12201208000003000800000300c040000000014401c073c189")
    private val sdrParameterSets = hex("0000000140010c01ffff01600000030090000003000003003c9594090000000142010101600000030090000003000003003ca00a080c1f3e595952930bc05a808080820000030002000003003010000000014401c073c189")

    private fun hevc(initializationData: ByteArray?, colorInfo: ColorInfo? = null): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setInitializationData(listOfNotNull(initializationData))
            .setColorInfo(colorInfo)
            .build()

    @Test
    fun `hevc without container colour info is detected as hdr from the sps`() {
        val format = hevc(pqParameterSets)
        assertEquals(C.COLOR_TRANSFER_ST2084, format.hevcSpsColorTransfer())
        assertTrue(format.isHdrVideoFormat())
    }

    @Test
    fun `sdr hevc stays sdr`() {
        val format = hevc(sdrParameterSets)
        assertEquals(C.COLOR_TRANSFER_SDR, format.hevcSpsColorTransfer())
        assertFalse(format.isHdrVideoFormat())
    }

    @Test
    fun `container colour info wins over the sps`() {
        val sdrContainer = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_SDR).build()
        assertFalse(hevc(pqParameterSets, sdrContainer).isHdrVideoFormat())
    }

    @Test
    fun `missing or unparsable initialization data is not hdr`() {
        assertNull(hevc(null).hevcSpsColorTransfer())
        assertFalse(hevc(byteArrayOf(0, 0, 1, 0x42, 1, 1)).isHdrVideoFormat())
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
