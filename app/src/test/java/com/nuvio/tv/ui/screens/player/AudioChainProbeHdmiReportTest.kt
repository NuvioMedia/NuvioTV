package com.nuvio.tv.ui.screens.player

import android.media.AudioFormat
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChainProbeHdmiReportTest {

    private val nothingClaimed =
        DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)

    @Test
    fun unplugged_deniesEveryEncoding_andIsNotCacheable() {
        val direct = AudioChainProbe.directSupportFromHdmiPlugReport(plugState = 0, encodings = null)
        assertEquals(nothingClaimed, direct)
        assertTrue(AudioChainProbe.ChainSnapshot(direct = direct, maxPcmChannels = null).deniesEveryEncoding())
    }

    @Test
    fun pluggedWithoutAList_orNoPlugState_isUnknown() {
        assertNull(AudioChainProbe.directSupportFromHdmiPlugReport(plugState = 1, encodings = null))
        assertNull(AudioChainProbe.directSupportFromHdmiPlugReport(plugState = 1, encodings = IntArray(0)))
        assertNull(
            AudioChainProbe.directSupportFromHdmiPlugReport(
                plugState = -1,
                encodings = intArrayOf(AudioFormat.ENCODING_AC3)
            )
        )
    }

    @Test
    fun pluggedList_mapsEachFormatGroup() {
        val tvOnPlainArc = AudioChainProbe.directSupportFromHdmiPlugReport(
            plugState = 1,
            encodings = intArrayOf(
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_AC3,
                AudioFormat.ENCODING_E_AC3
            )
        )
        assertEquals(
            DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = false, dtsHd = false),
            tvOnPlainArc
        )

        val losslessChain = AudioChainProbe.directSupportFromHdmiPlugReport(
            plugState = 1,
            encodings = intArrayOf(
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_E_AC3_JOC,
                AudioFormat.ENCODING_DOLBY_TRUEHD,
                AudioFormat.ENCODING_DTS,
                AudioFormat.ENCODING_DTS_HD
            )
        )
        assertEquals(
            DirectSupport(ac3 = false, eac3 = true, trueHd = true, dts = true, dtsHd = true),
            losslessChain
        )
    }

    @Test
    fun pcmOnlyList_deniesEveryEncoding() {
        val direct = AudioChainProbe.directSupportFromHdmiPlugReport(
            plugState = 1,
            encodings = intArrayOf(AudioFormat.ENCODING_PCM_16BIT)
        )
        assertEquals(nothingClaimed, direct)
    }

    @Test
    fun auto_deniesWhatTheReportLeavesOut_andAllowsEverythingWithoutAReport() {
        val fromReport = autoPolicy(
            AudioChainProbe.directSupportFromHdmiPlugReport(
                plugState = 1,
                encodings = intArrayOf(
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioFormat.ENCODING_AC3,
                    AudioFormat.ENCODING_E_AC3
                )
            )
        )
        assertTrue(fromReport.allowAc3)
        assertTrue(fromReport.allowEac3)
        assertFalse(fromReport.allowTrueHd)
        assertFalse(fromReport.allowDts)
        assertFalse(fromReport.allowDtsHd)

        assertEquals(AudioPassthroughPolicy.ALLOW_ALL, autoPolicy(null))
    }

    private fun autoPolicy(direct: DirectSupport?): AudioPassthroughPolicy =
        SurroundFormatResolver.resolve(
            manualMode = false,
            allowAc3 = true,
            allowEac3 = true,
            allowTrueHd = true,
            allowDts = true,
            allowDtsHd = true,
            manualTranscodePreferred = false,
            manualChannelTargetChannels = null,
            direct = direct,
            rawMaxPcmChannels = null,
            routeIsBluetooth = false,
            routeIsHdmiArc = false,
            softwareDecodersAvailable = true,
            forceOpticalActive = false,
            learnedDeniedGroups = emptySet()
        ).policy
}
