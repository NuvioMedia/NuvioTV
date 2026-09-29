package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLoadingPolicyTest {
    @Test
    fun `profile selection transitions through loading into experience setup`() {
        assertFalse(show(StartupDestination.ProfileSelection))
        assertTrue(show(StartupDestination.Loading))
        assertFalse(show(startupDestinationForRoute(Screen.ExperienceModeSelection.route)))
        assertFalse(show(startupDestinationForRoute(Screen.LayoutSelection.route)))
        assertTrue(show(startupDestinationForRoute(Screen.Home.route)))
        assertFalse(show(StartupDestination.Home, complete = true))
    }

    @Test
    fun `auth and essential addon setup remain visible before home is ready`() {
        assertFalse(show(StartupDestination.Setup))
        assertTrue(show(StartupDestination.Home))
    }

    @Test
    fun `single and remembered profiles show loading without requiring a click`() {
        assertTrue(show(StartupDestination.Loading))
        assertTrue(show(StartupDestination.Home))
    }

    @Test
    fun `pin entry and failed selection return to an uncovered profile screen`() {
        assertFalse(show(StartupDestination.ProfileSelection))
        assertTrue(show(StartupDestination.Loading))
        assertFalse(show(StartupDestination.ProfileSelection))
    }

    @Test
    fun `direct launches do not wait for home`() {
        listOf(Screen.Detail.route, Screen.Stream.route, Screen.Player.route, Screen.Settings.route).forEach { route ->
            assertEquals(StartupDestination.Content, startupDestinationForRoute(route))
            assertFalse(show(startupDestinationForRoute(route)))
        }
    }

    @Test
    fun `unknown navigation destination keeps loading until a route is available`() {
        assertTrue(show(startupDestinationForRoute(null)))
    }

    @Test
    fun `disabled splash leaves home responsible for its spinner`() {
        StartupDestination.entries.forEach { destination ->
            assertFalse(shouldShowStartupSplash(false, false, destination))
        }
        assertTrue(shouldShowHomeStartupLoader(true, false, false))
        assertFalse(shouldShowHomeStartupLoader(false, false, false))
    }

    @Test
    fun `startup uses exactly one loader then releases ownership`() {
        assertTrue(show(StartupDestination.Home))
        assertFalse(shouldShowHomeStartupLoader(true, true, false))
        assertFalse(show(StartupDestination.Home, complete = true))
        assertFalse(shouldShowHomeStartupLoader(false, true, true))
        assertTrue(shouldShowHomeStartupLoader(true, true, true))
    }

    @Test
    fun `completed startup does not cover later loading`() {
        StartupDestination.entries.forEach { destination ->
            assertFalse(show(destination, complete = true))
        }
    }

    private fun show(destination: StartupDestination, complete: Boolean = false): Boolean =
        shouldShowStartupSplash(true, complete, destination)
}
