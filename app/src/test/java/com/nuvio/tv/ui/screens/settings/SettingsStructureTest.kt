package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStructureTest {

    @Test
    fun `playback sections follow the documented order`() {
        assertEquals(
            listOf(
                PlaybackSection.PLAYER,
                PlaybackSection.STREAM_SELECTION,
                PlaybackSection.UP_NEXT,
                PlaybackSection.SKIP_SEGMENTS,
                PlaybackSection.PLAYER_INTERFACE,
                PlaybackSection.AUDIO,
                PlaybackSection.SUBTITLES,
                PlaybackSection.VIDEO,
                PlaybackSection.BUFFER_NETWORK,
                PlaybackSection.P2P
            ),
            visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER))
        )
    }

    @Test
    fun `buffer and network is only shown for exoplayer based engines`() {
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER)))
        assertTrue(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.AUTO)))
        assertFalse(PlaybackSection.BUFFER_NETWORK in visiblePlaybackSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER)))
    }

    @Test
    fun `audio and video are separate sections`() {
        val sections = visiblePlaybackSections(PlayerSettings())
        assertTrue(PlaybackSection.AUDIO in sections)
        assertTrue(PlaybackSection.VIDEO in sections)
    }

    @Test
    fun `layout sections follow the documented order`() {
        assertEquals(
            listOf(
                LayoutSection.HOME_LAYOUT,
                LayoutSection.HOME_CONTENT,
                LayoutSection.SIDEBAR,
                LayoutSection.CONTINUE_WATCHING,
                LayoutSection.FOCUSED_POSTER,
                LayoutSection.POSTER_CARD,
                LayoutSection.CARD_DEPTH,
                LayoutSection.CUSTOM_POSTERS,
                LayoutSection.DETAIL_PAGE,
                LayoutSection.STREAMS
            ),
            visibleLayoutSections(essentialMode = false, focusedPosterHasOptions = true)
        )
    }

    @Test
    fun `essential layout only offers the home layout`() {
        assertEquals(
            listOf(LayoutSection.HOME_LAYOUT),
            visibleLayoutSections(essentialMode = true, focusedPosterHasOptions = true)
        )
    }

    @Test
    fun `focused poster section is hidden when it has nothing to show`() {
        assertFalse(
            LayoutSection.FOCUSED_POSTER in visibleLayoutSections(essentialMode = false, focusedPosterHasOptions = false)
        )
    }

    @Test
    fun `focused poster has no options on the grid layout`() {
        assertFalse(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.GRID).focusedPosterHasOptions())
        assertTrue(LayoutSettingsUiState(selectedLayout = com.nuvio.tv.domain.model.HomeLayout.CLASSIC).focusedPosterHasOptions())
    }
}
