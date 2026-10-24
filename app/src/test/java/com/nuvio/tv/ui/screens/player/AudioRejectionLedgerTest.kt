package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRejectionLedgerTest {

    private val route = "type:hdmi|name:box"
    private val persisted = setOf("$route::TRUEHD", "$route::DTS_HD", "type:hdmi|name:other::AC3", "garbage")

    @Test
    fun learnedFor_filtersByRouteAndParsesGroups() {
        val ledger = AudioRejectionLedger()
        assertEquals(setOf(Group.TRUEHD, Group.DTS_HD), ledger.learnedFor(route, persisted))
        assertEquals(setOf(Group.AC3), ledger.learnedFor("type:hdmi|name:other", persisted))
        assertTrue(ledger.learnedFor(null, persisted).isEmpty())
    }

    @Test
    fun verifiedEntries_dropOutOfTheLearnedSetUntilInvalidated() {
        val ledger = AudioRejectionLedger()
        ledger.markVerified("$route::TRUEHD")
        assertEquals(setOf(Group.DTS_HD), ledger.learnedFor(route, persisted))
        ledger.invalidate()
        assertEquals(setOf(Group.TRUEHD, Group.DTS_HD), ledger.learnedFor(route, persisted))
    }

    @Test
    fun entriesToProbe_runsOncePerRouteUntilInvalidated() {
        val ledger = AudioRejectionLedger()
        assertEquals(listOf("$route::TRUEHD", "$route::DTS_HD"), ledger.entriesToProbe(route, persisted))
        assertTrue(ledger.entriesToProbe(route, persisted).isEmpty())
        ledger.invalidate()
        ledger.markVerified("$route::TRUEHD")
        assertEquals(listOf("$route::DTS_HD"), ledger.entriesToProbe(route, persisted))
    }

    @Test
    fun pendingRejection_commitsOnlyForTheSameStream() {
        val ledger = AudioRejectionLedger()
        ledger.stashPending("url-a", "$route::TRUEHD")
        assertNull(ledger.takePendingFor("url-b"))
        assertNull(ledger.takePendingFor("url-a"))
        ledger.stashPending("url-a", "$route::TRUEHD")
        assertEquals("$route::TRUEHD", ledger.takePendingFor("url-a"))
        assertNull(ledger.takePendingFor("url-a"))
        ledger.stashPending("url-a", "$route::DTS_HD")
        ledger.dropPending()
        assertNull(ledger.takePendingFor("url-a"))
    }

    @Test
    fun entryHelpers_roundTrip() {
        val entry = AudioRejectionLedger.entry(route, Group.EAC3)
        assertEquals("$route::EAC3", entry)
        assertEquals(Group.EAC3, AudioRejectionLedger.groupOf(entry))
        assertEquals(route, AudioRejectionLedger.routeOf(entry))
        assertNull(AudioRejectionLedger.groupOf("garbage"))
        assertNull(AudioRejectionLedger.routeOf("garbage"))
    }
}
