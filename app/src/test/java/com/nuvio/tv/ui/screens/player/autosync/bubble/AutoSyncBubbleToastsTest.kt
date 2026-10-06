package com.nuvio.tv.ui.screens.player.autosync.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncBubbleToastsTest {
    @Test
    fun workingShowsWhatIsHappeningNow() {
        assertEquals(
            "Analyzing…" to null,
            AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"),
        )
        assertEquals(
            "Syncing to the audio instead…" to null,
            AutoSyncBubbleToasts.split(
                AutoSyncBubbleKind.Working,
                "Auto Sync • No match found • syncing to the audio instead…",
            ),
        )
    }

    @Test
    fun failureKeepsTheRestAsItsExplanation() {
        assertEquals(
            "No embedded subtitles to compare with" to "Subtitle timing not changed",
            AutoSyncBubbleToasts.split(
                AutoSyncBubbleKind.Failure,
                "Auto Sync • No embedded subtitles to compare with • subtitle timing not changed",
            ),
        )
        assertEquals(
            "Subtitles synced" to null,
            AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced"),
        )
    }

    @Test
    fun audioSyncMessagesWithoutPartsStayWhole() {
        val text = "Couldn't confirm subtitle sync, restored original timing"
        assertEquals(text to null, AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Failure, text))
    }

    @Test
    fun postsOnlyWhileAPlayerIsShowingAndKeepsARunInOneBubble() {
        assertFalse(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
        AutoSyncBubbleToasts.attachHost()
        try {
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            val working = AutoSyncBubbleToasts.current.value!!
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced"))
            val done = AutoSyncBubbleToasts.current.value!!
            assertEquals(working.session, done.session)

            // A new message after a result starts a new bubble.
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            assertNotEquals(done.session, AutoSyncBubbleToasts.current.value!!.session)

            AutoSyncBubbleToasts.finished(done.id) // stale: leaves the new bubble alone
            assertNotNull(AutoSyncBubbleToasts.current.value)
        } finally {
            AutoSyncBubbleToasts.detachHost()
        }
        assertNull(AutoSyncBubbleToasts.current.value)
        assertFalse(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
    }

    @Test
    fun aMessageDuringTheFadeStartsAFreshBubble() {
        AutoSyncBubbleToasts.attachHost()
        try {
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            val working = AutoSyncBubbleToasts.current.value!!
            AutoSyncBubbleToasts.leaving(working.session) // the timed-out bubble starts fading

            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            val fresh = AutoSyncBubbleToasts.current.value!!
            assertNotEquals(working.session, fresh.session)

            AutoSyncBubbleToasts.finished(working.id) // the old fade ends: the new bubble stays
            assertEquals(fresh, AutoSyncBubbleToasts.current.value)

            // The fresh run keeps its later messages in one bubble.
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced"))
            assertEquals(fresh.session, AutoSyncBubbleToasts.current.value!!.session)
        } finally {
            AutoSyncBubbleToasts.detachHost()
        }
    }
}
