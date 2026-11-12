package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the stale forced-subtitle race:
 *
 * While the original language of the meta is still unknown the "Original" audio
 * preference falls back to the device locale, so the PT-BR audio track is selected first
 * and the forced PT-BR subtitle gets locked in (`autoSubtitleSelected = true`). Once TMDB
 * enrichment supplies `original_language = eng` the audio switches to English, but nothing
 * re-validated the subtitle: the stale forced PT-BR subtitle kept "satisfying" the PT-BR
 * subtitle target forever.
 *
 * Expected: with English audio the default (full, non-forced) PT-BR subtitle is selected.
 */
class ForcedSubtitleInvariantTest {

    private val ptBrForcedTrack = TrackInfo(
        index = 0,
        name = "Portuguese (Brazil) - Forced",
        language = "pt-br",
        trackId = "0",
        isForced = true
    )

    private val ptBrFullTrack = TrackInfo(
        index = 1,
        name = "Portuguese (Brazil)",
        language = "pt-br",
        trackId = "1",
        isForced = false
    )

    private val ptBrAudioTrack = TrackInfo(
        index = 0,
        name = "Portuguese (Brazil)",
        language = "pt-br",
        trackId = "a0",
        isSelected = true
    )

    private val engAudioTrack = TrackInfo(
        index = 1,
        name = "English",
        language = "eng",
        trackId = "a1",
        isSelected = true
    )

    private fun forcedSubtitleStyle(
        preferred: String = "pt-br",
        secondary: String? = "eng",
        useForced: Boolean = true
    ) = SubtitleStyleSettings(
        preferredLanguage = preferred,
        isPreferredLanguageSystemDefault = false,
        secondaryPreferredLanguage = secondary,
        useForcedSubtitles = useForced
    )

    /**
     * Creates a mocked controller whose UI state is fully controlled by the test.
     * The auto-select lock is emulated through a mutable flag so the controller can
     * observe its own writes.
     */
    private class Harness(
        val controller: PlayerRuntimeController,
        val state: MutableStateFlow<PlayerUiState>
    ) {
        var autoSubtitleSelected: Boolean = true
    }

    private fun harness(initialState: PlayerUiState): Harness {
        val stateFlow = MutableStateFlow(initialState)
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val harness = Harness(controller, stateFlow)
        every { controller._uiState } returns stateFlow
        every { controller.isUserExplicitSubtitleSelection } returns false
        every { controller.autoSubtitleSelected } answers { harness.autoSubtitleSelected }
        every { controller.autoSubtitleSelected = any() } answers {
            harness.autoSubtitleSelected = firstArg<Boolean>()
        }
        every { controller.hasScannedTextTracksOnce } returns true
        every { controller.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        // No subtitle view is attached in unit tests.
        every { controller.exoSubtitleViewRef } returns null
        return harness
    }

    // -------------------------------------------------------------------------------------
    // Pure predicate helpers
    // -------------------------------------------------------------------------------------

    @Test
    fun forcedSelectionWithMismatchedAudioRequestsDowngrade() {
        val action = resolveForcedSubtitleInvariantAction(
            isPrimarySatisfied = true,
            currentSelectionIsForced = true,
            useForcedSubtitles = true,
            primaryTarget = "pt-br",
            selectedAudioTrack = engAudioTrack
        )

        assertEquals(ForcedSubtitleInvariantAction.DOWNGRADE, action)
    }

    @Test
    fun nonForcedSelectionWithMatchingAudioRequestsUpgrade() {
        val action = resolveForcedSubtitleInvariantAction(
            isPrimarySatisfied = true,
            currentSelectionIsForced = false,
            useForcedSubtitles = true,
            primaryTarget = "pt-br",
            selectedAudioTrack = ptBrAudioTrack
        )

        assertEquals(ForcedSubtitleInvariantAction.UPGRADE, action)
    }

    @Test
    fun forcedSelectionWithMatchingAudioKeepsSelection() {
        val action = resolveForcedSubtitleInvariantAction(
            isPrimarySatisfied = true,
            currentSelectionIsForced = true,
            useForcedSubtitles = true,
            primaryTarget = "pt-br",
            selectedAudioTrack = ptBrAudioTrack
        )

        assertEquals(ForcedSubtitleInvariantAction.KEEP, action)
    }

    @Test
    fun nonForcedSelectionWithMismatchedAudioKeepsSelection() {
        val action = resolveForcedSubtitleInvariantAction(
            isPrimarySatisfied = true,
            currentSelectionIsForced = false,
            useForcedSubtitles = true,
            primaryTarget = "pt-br",
            selectedAudioTrack = engAudioTrack
        )

        assertEquals(ForcedSubtitleInvariantAction.KEEP, action)
    }

    @Test
    fun invariantIsIgnoredWithoutForcedSubtitlesOrWithoutPrimaryTarget() {
        assertEquals(
            ForcedSubtitleInvariantAction.KEEP,
            resolveForcedSubtitleInvariantAction(
                isPrimarySatisfied = true,
                currentSelectionIsForced = true,
                useForcedSubtitles = false,
                primaryTarget = "pt-br",
                selectedAudioTrack = engAudioTrack
            )
        )
        assertEquals(
            ForcedSubtitleInvariantAction.KEEP,
            resolveForcedSubtitleInvariantAction(
                isPrimarySatisfied = true,
                currentSelectionIsForced = true,
                useForcedSubtitles = true,
                primaryTarget = null,
                selectedAudioTrack = engAudioTrack
            )
        )
        // Unknown audio track -> cannot prove a violation.
        assertEquals(
            ForcedSubtitleInvariantAction.KEEP,
            resolveForcedSubtitleInvariantAction(
                isPrimarySatisfied = true,
                currentSelectionIsForced = true,
                useForcedSubtitles = true,
                primaryTarget = "pt-br",
                selectedAudioTrack = null
            )
        )
    }

    @Test
    fun currentSelectionForcednessIsDetectedForInternalAndAddonTracks() {
        assertTrue(
            currentSubtitleSelectionIsForced(
                selectedAddonSubtitle = null,
                selectedSubtitleTrackIndex = 0,
                subtitleTracks = listOf(ptBrForcedTrack, ptBrFullTrack)
            )
        )
        assertFalse(
            currentSubtitleSelectionIsForced(
                selectedAddonSubtitle = null,
                selectedSubtitleTrackIndex = 1,
                subtitleTracks = listOf(ptBrForcedTrack, ptBrFullTrack)
            )
        )
        assertFalse(
            currentSubtitleSelectionIsForced(
                selectedAddonSubtitle = null,
                selectedSubtitleTrackIndex = -1,
                subtitleTracks = listOf(ptBrForcedTrack, ptBrFullTrack)
            )
        )
        assertTrue(
            currentSubtitleSelectionIsForced(
                selectedAddonSubtitle = Subtitle(
                    id = "forced-pt-br",
                    url = "http://example.com/forced-pt-br.srt",
                    lang = "pt-br",
                    addonName = "OpenSubtitles",
                    addonLogo = null
                ),
                selectedSubtitleTrackIndex = -1,
                subtitleTracks = listOf(ptBrFullTrack)
            )
        )
        assertFalse(
            currentSubtitleSelectionIsForced(
                selectedAddonSubtitle = Subtitle(
                    id = "pt-br",
                    url = "http://example.com/pt-br.srt",
                    lang = "pt-br",
                    addonName = "OpenSubtitles",
                    addonLogo = null
                ),
                selectedSubtitleTrackIndex = -1,
                subtitleTracks = listOf(ptBrFullTrack)
            )
        )
    }

    // -------------------------------------------------------------------------------------
    // Controller level behaviour
    // -------------------------------------------------------------------------------------

    /**
     * Test 1: a forced PT-BR subtitle is locked in and the audio switches to English.
     * The stale forced subtitle must be replaced by the full (non-forced) PT-BR subtitle.
     */
    @Test
    fun forcedPtBrSubtitleIsDowngradedWhenAudioSwitchesToEnglish() {
        val harness = harness(
            PlayerUiState(
                subtitleStyle = forcedSubtitleStyle(),
                audioTracks = listOf(ptBrAudioTrack, engAudioTrack.copy(isSelected = true)),
                selectedAudioTrackIndex = 1,
                subtitleTracks = listOf(ptBrForcedTrack, ptBrFullTrack),
                selectedSubtitleTrackIndex = 0
            )
        )

        harness.controller.tryAutoSelectPreferredSubtitleFromAvailableTracks()

        val state = harness.state.value
        assertEquals(
            "expected the full (non-forced) PT-BR subtitle to be selected",
            1,
            state.selectedSubtitleTrackIndex
        )
        assertFalse(state.subtitleTracks[state.selectedSubtitleTrackIndex].isForced)
        assertEquals(null, state.selectedAddonSubtitle)
        assertTrue(harness.autoSubtitleSelected)
    }

    /**
     * Test 2: a non-forced PT-BR subtitle is locked in and the audio becomes PT-BR.
     * The controller attempts an upgrade to the forced variant, but when no forced track
     * exists the current non-forced selection is kept (subtitles are never disabled).
     */
    @Test
    fun nonForcedPtBrSubtitleIsKeptWhenNoForcedUpgradeExists() {
        val harness = harness(
            PlayerUiState(
                subtitleStyle = forcedSubtitleStyle(),
                audioTracks = listOf(ptBrAudioTrack),
                selectedAudioTrackIndex = 0,
                subtitleTracks = listOf(ptBrFullTrack),
                selectedSubtitleTrackIndex = 0
            )
        )

        harness.controller.tryAutoSelectPreferredSubtitleFromAvailableTracks()

        val state = harness.state.value
        assertEquals(
            "the non-forced selection must be kept when no forced track is available",
            0,
            state.selectedSubtitleTrackIndex
        )
        assertFalse(state.subtitleTracks[state.selectedSubtitleTrackIndex].isForced)
        assertTrue(harness.autoSubtitleSelected)
    }

    /**
     * Test 3: nothing changed (forced PT-BR subtitle + PT-BR audio) - the guard still
     * early-returns and the current selection is untouched.
     */
    @Test
    fun forcedPtBrSubtitleWithMatchingAudioIsKept() {
        val harness = harness(
            PlayerUiState(
                subtitleStyle = forcedSubtitleStyle(),
                audioTracks = listOf(ptBrAudioTrack),
                selectedAudioTrackIndex = 0,
                subtitleTracks = listOf(ptBrForcedTrack, ptBrFullTrack),
                selectedSubtitleTrackIndex = 0
            )
        )

        harness.controller.tryAutoSelectPreferredSubtitleFromAvailableTracks()

        val state = harness.state.value
        assertEquals(0, state.selectedSubtitleTrackIndex)
        assertTrue(state.subtitleTracks[state.selectedSubtitleTrackIndex].isForced)
        assertTrue(harness.autoSubtitleSelected)
    }
}
