package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class DrawerFocusRequesterStateTest {

    @Test
    fun `syncRouteMap preserves existing identities when discover route is inserted`() {
        val home = Any()
        val search = Any()
        val library = Any()
        val addons = Any()
        val settings = Any()
        val requesters = linkedMapOf(
            "home" to home,
            "search" to search,
            "library" to library,
            "addons" to addons,
            "settings" to settings
        )

        syncRouteMap(
            existing = requesters,
            routes = listOf("home", "discover", "search", "library", "addons", "settings"),
            create = { Any() }
        )

        assertSame(home, requesters.getValue("home"))
        assertSame(search, requesters.getValue("search"))
        assertSame(library, requesters.getValue("library"))
        assertSame(addons, requesters.getValue("addons"))
        assertSame(settings, requesters.getValue("settings"))
        assertNotNull(requesters["discover"])
    }

    @Test
    fun `syncRouteMap drops stale routes when a route is removed`() {
        val home = Any()
        val discover = Any()
        val search = Any()
        val library = Any()
        val addons = Any()
        val settings = Any()
        val requesters = linkedMapOf(
            "home" to home,
            "discover" to discover,
            "search" to search,
            "library" to library,
            "addons" to addons,
            "settings" to settings
        )

        syncRouteMap(
            existing = requesters,
            routes = listOf("home", "search", "library", "addons", "settings"),
            create = { Any() }
        )

        assertFalse(requesters.containsKey("discover"))
        assertSame(home, requesters.getValue("home"))
        assertSame(search, requesters.getValue("search"))
        assertSame(library, requesters.getValue("library"))
        assertSame(addons, requesters.getValue("addons"))
        assertSame(settings, requesters.getValue("settings"))
    }

    @Test
    fun `syncRouteMap is a no-op when routes match existing entries`() {
        val home = Any()
        val discover = Any()
        val search = Any()
        val library = Any()
        val addons = Any()
        val settings = Any()
        val requesters = linkedMapOf(
            "home" to home,
            "discover" to discover,
            "search" to search,
            "library" to library,
            "addons" to addons,
            "settings" to settings
        )

        syncRouteMap(
            existing = requesters,
            routes = listOf("home", "discover", "search", "library", "addons", "settings"),
            create = { Any() }
        )

        assertEquals(6, requesters.size)
        assertSame(home, requesters.getValue("home"))
        assertSame(discover, requesters.getValue("discover"))
        assertSame(search, requesters.getValue("search"))
        assertSame(library, requesters.getValue("library"))
        assertSame(addons, requesters.getValue("addons"))
        assertSame(settings, requesters.getValue("settings"))
    }
}
