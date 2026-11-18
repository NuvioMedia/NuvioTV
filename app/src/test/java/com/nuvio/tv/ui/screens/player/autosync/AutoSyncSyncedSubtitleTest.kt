package com.nuvio.tv.ui.screens.player.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoSyncSyncedSubtitleTest {

    @Test
    fun `a run that ends late keeps the label of the run that replaced it`() {
        val first = AutoSyncSyncedSubtitle.startSyncing("https://subs/a.srt", audio = false)
        val second = AutoSyncSyncedSubtitle.startSyncing("https://subs/a.srt", audio = true)

        AutoSyncSyncedSubtitle.stopSyncing(first)
        assertSame(second, AutoSyncSyncedSubtitle.syncing.value)

        AutoSyncSyncedSubtitle.stopSyncing(second)
        assertNull(AutoSyncSyncedSubtitle.syncing.value)
    }

    @Test
    fun `marking synced ends the syncing label and records the source`() {
        AutoSyncSyncedSubtitle.startSyncing("https://subs/b.srt", audio = true)
        AutoSyncSyncedSubtitle.mark("https://subs/b.srt", audio = true)

        assertNull(AutoSyncSyncedSubtitle.syncing.value)
        assertEquals("https://subs/b.srt", AutoSyncSyncedSubtitle.url.value)
        assertTrue(AutoSyncSyncedSubtitle.byAudio.value)

        AutoSyncSyncedSubtitle.mark("https://subs/b.srt")
        assertFalse(AutoSyncSyncedSubtitle.byAudio.value)
    }
}
