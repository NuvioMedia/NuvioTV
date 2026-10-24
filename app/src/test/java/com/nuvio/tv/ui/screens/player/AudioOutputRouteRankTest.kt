package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRouteRankTest {

    @Test
    fun theRealHdmiPortIsPreferredOverEarcThenArc() {
        val hdmi = AudioOutputRouteDetector.routeRank(AudioDeviceInfo.TYPE_HDMI)
        val earc = AudioOutputRouteDetector.routeRank(AudioDeviceInfo.TYPE_HDMI_EARC)
        val arc = AudioOutputRouteDetector.routeRank(AudioDeviceInfo.TYPE_HDMI_ARC)
        assertTrue(hdmi < earc)
        assertTrue(earc < arc)
        assertTrue(arc < AudioOutputRouteDetector.routeRank(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(AudioOutputRouteDetector.routeRank(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) < hdmi)
    }
}
