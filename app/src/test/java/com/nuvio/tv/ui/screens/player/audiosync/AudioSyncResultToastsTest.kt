package com.nuvio.tv.ui.screens.player.audiosync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncResultToastsTest {
    @Test
    fun refinementsAndCutChangesDoNotRepeatSuccess() {
        val results = AudioSyncResultToasts()
        assertTrue(results.shouldShow(true))
        repeat(10) { assertFalse(results.shouldShow(true)) }
        assertTrue(results.shouldShow(false))
        assertFalse(results.shouldShow(false))
        assertTrue(results.shouldShow(true))
    }

    @Test
    fun anotherSubtitleGetsItsOwnResult() {
        val results = AudioSyncResultToasts()
        assertTrue(results.shouldShow(false))
        results.reset()
        assertTrue(results.shouldShow(false))
    }
}
