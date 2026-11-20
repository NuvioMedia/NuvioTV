package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AudioChainProbePcmSinkTest {

    private val hdmiTypes = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    @Test
    fun routedToHdmi_readsEveryHdmiSink() {
        val types = AudioChainProbe.pcmSinkTypes(
            routedType = AudioDeviceInfo.TYPE_HDMI,
            connectedTypes = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC)
        )
        assertEquals(hdmiTypes, types)
    }

    @Test
    fun routedToUsb_readsTheUsbSinkNotTheTv() {
        val types = AudioChainProbe.pcmSinkTypes(
            routedType = AudioDeviceInfo.TYPE_USB_DEVICE,
            connectedTypes = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_USB_DEVICE)
        )
        assertEquals(usbTypes, types)
    }

    @Test
    fun routedElsewhere_readsNothing() {
        assertNull(
            AudioChainProbe.pcmSinkTypes(
                routedType = AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                connectedTypes = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            )
        )
    }

    @Test
    fun noRoutedDevice_usbConnected_winsOverHdmi() {
        val types = AudioChainProbe.pcmSinkTypes(
            routedType = null,
            connectedTypes = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_USB_HEADSET)
        )
        assertEquals(usbTypes, types)
    }

    @Test
    fun noRoutedDevice_noUsb_keepsHdmi() {
        val types = AudioChainProbe.pcmSinkTypes(
            routedType = null,
            connectedTypes = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )
        assertEquals(hdmiTypes, types)
    }

    @Test
    fun multichannelUsbDac_behindStereoTv_keepsSurroundTarget() {
        val nothingClaimed =
            DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)
        val resolution = SurroundFormatResolver.resolve(
            manualMode = false,
            allowAc3 = true,
            allowEac3 = true,
            allowTrueHd = true,
            allowDts = true,
            allowDtsHd = true,
            manualTranscodePreferred = false,
            manualChannelTargetChannels = null,
            direct = nothingClaimed,
            rawMaxPcmChannels = 6,
            routeIsBluetooth = false,
            routeIsHdmiArc = false,
            softwareDecodersAvailable = true,
            forceOpticalActive = false,
            learnedDeniedGroups = emptySet()
        )
        assertEquals(6, resolution.inferredChannelTarget)
        assertFalse(resolution.transcodePreferred)
    }
}
