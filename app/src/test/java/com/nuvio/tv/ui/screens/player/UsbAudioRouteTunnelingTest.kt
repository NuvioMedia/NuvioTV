package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioRouteTunnelingTest {

    @Test
    fun usbOutputTypes_areUsb() {
        assertTrue(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
    }

    @Test
    fun hdmiSpeakerAndBluetooth_areNotUsb() {
        assertFalse(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_HDMI))
        assertFalse(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_HDMI_ARC))
        assertFalse(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioOutputRouteDetector.isUsbType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }

    @Test
    fun routeBuiltWithoutUsbFlag_isNotUsb() {
        val route = AudioOutputRoute(key = "type:hdmi|name:tv", label = "TV", isBluetooth = false)
        assertFalse(route.isUsb)
    }
}
