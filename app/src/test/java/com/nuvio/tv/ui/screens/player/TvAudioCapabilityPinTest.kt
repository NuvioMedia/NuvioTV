package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAudioCapabilityPinTest {

    private fun claims(vararg encodings: Int): (Int) -> Boolean = { it in encodings }

    @Test
    fun aTvChainThatClaimsABitstreamFormat_isPinned() {
        assertTrue(TvAudioCapabilityPin.shouldPin(true, claims(C.ENCODING_PCM_16BIT, C.ENCODING_AC3)))
        assertTrue(TvAudioCapabilityPin.shouldPin(true, claims(C.ENCODING_DOLBY_TRUEHD)))
        assertTrue(TvAudioCapabilityPin.shouldPin(true, claims(C.ENCODING_E_AC3_JOC, C.ENCODING_DTS_HD)))
    }

    @Test
    fun aPcmOnlyAnswer_isNotPinned() {
        assertFalse(TvAudioCapabilityPin.shouldPin(true, claims(C.ENCODING_PCM_16BIT)))
        assertFalse(TvAudioCapabilityPin.shouldPin(true, claims()))
    }

    @Test
    fun aPhoneOrTablet_isNotPinned() {
        assertFalse(TvAudioCapabilityPin.shouldPin(false, claims(C.ENCODING_AC3, C.ENCODING_E_AC3)))
    }

    @Test
    fun anOutputThatIsNotBluetooth_touchesTheOutputChain() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )) {
            assertTrue(TvAudioCapabilityPin.touchesOutputChain(false, listOf(type)))
        }
    }

    @Test
    fun aRouteChange_touchesTheOutputChain() {
        assertTrue(TvAudioCapabilityPin.touchesOutputChain(true, emptyList()))
        assertTrue(TvAudioCapabilityPin.touchesOutputChain(true, listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun anInputOrBluetoothDeviceOffTheRoute_leavesTheOutputChainAlone() {
        assertFalse(TvAudioCapabilityPin.touchesOutputChain(false, emptyList()))
        assertFalse(
            TvAudioCapabilityPin.touchesOutputChain(
                false,
                listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            )
        )
    }

    @Test
    fun aBluetoothDeviceBesideAnHdmiSink_stillTouchesTheOutputChain() {
        assertTrue(
            TvAudioCapabilityPin.touchesOutputChain(
                false,
                listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HDMI)
            )
        )
    }
}
