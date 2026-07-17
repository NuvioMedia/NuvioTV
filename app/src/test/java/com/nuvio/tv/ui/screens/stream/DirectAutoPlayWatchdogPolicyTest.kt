package com.nuvio.tv.ui.screens.stream

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAutoPlayWatchdogPolicyTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `watchdog waits until host returns to foreground`() = runTest {
        val foreground = MutableStateFlow(false)
        val waiter = async {
            DirectAutoPlayWatchdogPolicy.awaitForeground(foreground)
        }

        runCurrent()
        assertFalse(waiter.isCompleted)

        foreground.value = true
        runCurrent()

        assertTrue(waiter.isCompleted)
    }

    @Test
    fun `resolved visible overlay is torn down only in foreground`() {
        assertTrue(
            DirectAutoPlayWatchdogPolicy.shouldTearDownResolvedTarget(
                resolvedAutoPlayTarget = true,
                hostInForeground = true,
                showDirectAutoPlayOverlay = true,
                externalPlayerOverlayVisible = false
            )
        )
        assertFalse(
            DirectAutoPlayWatchdogPolicy.shouldTearDownResolvedTarget(
                resolvedAutoPlayTarget = true,
                hostInForeground = false,
                showDirectAutoPlayOverlay = true,
                externalPlayerOverlayVisible = false
            )
        )
    }
}
