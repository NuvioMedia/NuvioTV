package com.nuvio.tv.core.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePlaybackStateTest {
    @Test fun pausingWhileBufferingExposesPlayInsteadOfAnotherPause() {
        assertEquals("buffering", remotePlaybackState(playIntent = true, buffering = true, playing = false))
        assertEquals("paused", remotePlaybackState(playIntent = false, buffering = true, playing = false))
        assertEquals("playing", remotePlaybackState(playIntent = true, buffering = false, playing = true))
    }
}
