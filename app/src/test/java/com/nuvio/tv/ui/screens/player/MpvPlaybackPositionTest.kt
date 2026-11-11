package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlaybackPositionTest {

    @Test
    fun `zero video timestamp while audio plays uses the audio clock`() {
        // Logged on an HLS stream: time-pos=0.000 frame=0 seeking=false audio-pts=123.785
        assertEquals(123.785, mpvPlaybackPositionSec(0.0, 123.785, seeking = false, audioDelaySec = 0.0), 0.0)
    }

    @Test
    fun `normal playback keeps the video position`() {
        assertEquals(124.333, mpvPlaybackPositionSec(124.333, 124.291, seeking = false, audioDelaySec = 0.0), 0.0)
    }

    @Test
    fun `seek target is trusted while seeking`() {
        // A backward seek: time-pos already holds the target, audio may still report the old spot.
        assertEquals(0.0, mpvPlaybackPositionSec(0.0, 158.194, seeking = true, audioDelaySec = 0.0), 0.0)
    }

    @Test
    fun `missing audio clock keeps the video position`() {
        assertEquals(116.418, mpvPlaybackPositionSec(116.418, null, seeking = false, audioDelaySec = 0.0), 0.0)
    }

    @Test
    fun `video ahead of audio is never replaced`() {
        // Audio track ending before the video must not pull the position back.
        assertEquals(1400.0, mpvPlaybackPositionSec(1400.0, 1300.0, seeking = false, audioDelaySec = 0.0), 0.0)
    }

    @Test
    fun `audio delay widens the tolerance`() {
        assertEquals(100.0, mpvPlaybackPositionSec(100.0, 125.0, seeking = false, audioDelaySec = 20.0), 0.0)
        assertEquals(125.0, mpvPlaybackPositionSec(100.0, 125.0, seeking = false, audioDelaySec = 5.0), 0.0)
    }
}
