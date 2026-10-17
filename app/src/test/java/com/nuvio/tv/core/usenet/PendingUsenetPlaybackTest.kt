package com.nuvio.tv.core.usenet

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUsenetPlaybackTest {
    @Test fun `dismissal and background cleanup release an unlaunched selection only once`() {
        val released = mutableListOf<String>()
        val pending = PendingUsenetPlayback { released += it }
        pending.replace("session-a")
        pending.release() // Player choice dismissed.
        pending.release() // Results screen subsequently stops/disposes.
        assertEquals(listOf("session-a"), released)
    }

    @Test fun `successful player launch transfers ownership before screen cleanup`() {
        val released = mutableListOf<String>()
        val pending = PendingUsenetPlayback { released += it }
        pending.replace("session-a")
        pending.handoff("session-a")
        pending.release()
        assertEquals(emptyList<String>(), released)
    }

    @Test fun `replacement releases old selection and stale handoff cannot adopt the new one`() {
        val released = mutableListOf<String>()
        val pending = PendingUsenetPlayback { released += it }
        pending.replace("session-a")
        pending.replace("session-b")
        pending.handoff("session-a")
        pending.release("session-a")
        assertEquals(listOf("session-a"), released)
        pending.release()
        assertEquals(listOf("session-a", "session-b"), released)
    }
}
