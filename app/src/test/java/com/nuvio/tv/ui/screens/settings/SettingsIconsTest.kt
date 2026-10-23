package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsIconsTest {

    @Test
    fun `playback section icons are unique`() {
        assertEquals(PlaybackSection.entries.size, PlaybackSection.entries.map { it.icon.name }.distinct().size)
    }

    @Test
    fun `layout section icons are unique`() {
        assertEquals(LayoutSection.entries.size, LayoutSection.entries.map { it.icon.name }.distinct().size)
    }
}
