package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerItemRefTest {
    @Test
    fun roundTripsOpaqueIds() {
        listOf("5f1c0d2e9a8b4c3d", "12345", "a:b%c", "movie/42").forEach { itemId ->
            val ref = ServerItemRef("c1a2b3", itemId)
            assertEquals(ref, ServerItemRef.parse(ref.encode()))
        }
    }

    @Test
    fun equalItemIdsOnDifferentConnectionsDoNotCollide() {
        assertNotEquals(ServerItemRef("c111", "42").encode(), ServerItemRef("c222", "42").encode())
    }

    @Test
    fun ignoresNonServerIds() {
        assertNull(ServerItemRef.parse("tt0111161"))
        assertNull(ServerItemRef.parse("tmdb:550"))
        assertNull(ServerItemRef.parse("srv1:"))
        assertNull(ServerItemRef.parse("srv1:c1:"))
        assertFalse(ServerItemRef.isServerId("tt0111161"))
        assertTrue(ServerItemRef.isServerId(ServerItemRef("c1", "x").encode()))
    }

    @Test
    fun encodedIdsCarryNoExternalIdentity() {
        assertFalse(parseTrackingExternalIds(ServerItemRef("c1", "12345").encode()).hasAny)
    }

    @Test
    fun encodedEpisodeIdsDoNotParseAsSeasonAndEpisode() {
        val parts = ServerItemRef("c9f", "7").encode().split(":")
        assertNull(parts[parts.size - 2].toIntOrNull())
    }
}
