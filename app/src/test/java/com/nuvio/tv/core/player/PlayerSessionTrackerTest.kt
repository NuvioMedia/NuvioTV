package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionTrackerTest {

    @Test
    fun `stays open until every overlapping session closes`() = runTest {
        val previous = PlayerSessionTracker.open()
        val next = PlayerSessionTracker.open()

        previous.close()
        assertTrue(PlayerSessionTracker.isSessionOpen.first())

        next.close()
        assertFalse(PlayerSessionTracker.isSessionOpen.first())
    }

    @Test
    fun `closing a session twice does not close another one`() = runTest {
        val exited = PlayerSessionTracker.open()
        exited.close()
        val active = PlayerSessionTracker.open()

        exited.close()
        assertTrue(PlayerSessionTracker.isSessionOpen.first())

        active.close()
        assertFalse(PlayerSessionTracker.isSessionOpen.first())
    }
}
