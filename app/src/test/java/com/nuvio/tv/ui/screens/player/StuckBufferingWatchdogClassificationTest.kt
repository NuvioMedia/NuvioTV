package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckBufferingWatchdogClassificationTest {

    @Test
    fun `watchdog message under 8000 is the watchdog`() {
        assertTrue(
            isStuckBufferingWatchdog(
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                "Unexpected runtime error Playback stuck buffering and not loading "
            )
        )
    }

    @Test
    fun `message matching is case-insensitive`() {
        assertTrue(
            isStuckBufferingWatchdog(
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                "PLAYBACK STUCK BUFFERING AND NOT LOADING"
            )
        )
    }

    @Test
    fun `other 8000 runtime checks are not the watchdog`() {
        assertFalse(
            isStuckBufferingWatchdog(
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                "Unexpected runtime error java.lang.IllegalStateException "
            )
        )
    }

    @Test
    fun `watchdog message under another code is not the watchdog`() {
        assertFalse(
            isStuckBufferingWatchdog(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                "Playback stuck buffering and not loading"
            )
        )
    }

    @Test
    fun `empty message under 8000 is not the watchdog`() {
        assertFalse(isStuckBufferingWatchdog(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, ""))
    }
}
