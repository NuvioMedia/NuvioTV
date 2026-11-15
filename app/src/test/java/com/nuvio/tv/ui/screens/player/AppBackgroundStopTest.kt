package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackgroundStopTest {

    @Test
    fun `activity stopped while the player is shown counts as background`() {
        assertTrue(isAppBackgroundStop(isFinishing = false, isChangingConfigurations = false, hostState = Lifecycle.State.CREATED))
    }

    @Test
    fun `navigating away from the player inside a visible activity does not`() {
        assertFalse(isAppBackgroundStop(isFinishing = false, isChangingConfigurations = false, hostState = Lifecycle.State.RESUMED))
        assertFalse(isAppBackgroundStop(isFinishing = false, isChangingConfigurations = false, hostState = Lifecycle.State.STARTED))
    }

    @Test
    fun `finishing or recreating activity keeps the normal release path`() {
        assertFalse(isAppBackgroundStop(isFinishing = true, isChangingConfigurations = false, hostState = Lifecycle.State.CREATED))
        assertFalse(isAppBackgroundStop(isFinishing = false, isChangingConfigurations = true, hostState = Lifecycle.State.CREATED))
    }
}
