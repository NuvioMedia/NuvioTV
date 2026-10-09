package com.nuvio.tv.core.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteCommandLedgerTest {
    private val state = RemoteSnapshot(deviceId = "tv", deviceName = "Living room", sessionId = "movie-123456789", state = "playing", canSeek = true)
    @Test fun repeatedCommandExecutesOnceAndCannotBeChanged() {
        val ledger = RemoteCommandLedger()
        var count = 0
        val command = RemoteCommand("request-123456789", state.sessionId!!, "pause")
        repeat(3) { assertTrue(ledger.execute(state, command) { count++; true }) }
        assertFalse(ledger.execute(state, command.copy(action = "play")) { count++; true })
        assertEquals(1, count)
    }
    @Test fun staleSessionAndNonSeekableCommandsAreRejected() {
        val ledger = RemoteCommandLedger()
        val command = RemoteCommand("request-123456789", state.sessionId!!, "seek", 1000)
        val never: (RemoteCommand) -> Boolean = { error("must not reach player") }
        assertFalse(ledger.execute(state.copy(sessionId = "next-episode"), command, never))
        assertFalse(ledger.execute(state.copy(canSeek = false), command, never))
        assertFalse(ledger.execute(state.copy(state = "idle"), command, never))
        assertFalse(ledger.execute(state, command.copy(positionMs = -1), never))
    }
    @Test fun fullLedgerRejectsNewRequestsWithoutReplayingOldOnes() {
        val ledger = RemoteCommandLedger(limit = 1)
        var count = 0
        val command = RemoteCommand("request-123456789", state.sessionId!!, "play")
        assertTrue(ledger.execute(state, command) { count++; true })
        assertFalse(ledger.execute(state, command.copy(requestId = "another-request")) { count++; true })
        assertTrue(ledger.execute(state, command) { count++; true })
        assertEquals(1, count)
        ledger.clear()
        assertTrue(ledger.execute(state.copy(sessionId = "episode-2"), command.copy(sessionId = "episode-2")) { count++; true })
        assertEquals(2, count)
    }
    @Test fun rejectedPlayerCommandIsNotRetried() {
        val ledger = RemoteCommandLedger()
        var count = 0
        val command = RemoteCommand("request-123456789", state.sessionId!!, "play")
        repeat(2) { assertFalse(ledger.execute(state, command) { count++; false }) }
        assertEquals(1, count)
    }
}
