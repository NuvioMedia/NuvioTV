package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.AudioLanguageOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "original audio" re-resolution that happens when the original content
 * language becomes known after the player already started (TMDB enrichment) or when it
 * is known upfront (navigation arguments).
 */
class OriginalAudioLanguageResolutionTest {

    @Test
    fun appliesWhenResolvedListDiffersFromAppliedOne() {
        assertTrue(
            shouldApplyResolvedAudioLanguages(
                resolved = listOf("en", "pt"),
                currentlyApplied = listOf("pt-br")
            )
        )
    }

    @Test
    fun doesNotApplyWhenResolvedListIsAlreadyApplied() {
        assertFalse(
            shouldApplyResolvedAudioLanguages(
                resolved = listOf("en", "pt"),
                currentlyApplied = listOf("en", "pt")
            )
        )
    }

    @Test
    fun doesNotApplyForEmptyResolvedList() {
        assertFalse(
            shouldApplyResolvedAudioLanguages(
                resolved = emptyList(),
                currentlyApplied = listOf("pt-br")
            )
        )
    }

    @Test
    fun resolutionIsOnlyEnabledForTheOriginalAudioPreference() {
        assertTrue(
            isOriginalAudioResolutionEnabled(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                hasUserAudioSelection = false
            )
        )
        assertFalse(
            isOriginalAudioResolutionEnabled(
                preferredAudioLanguage = AudioLanguageOption.DEVICE,
                hasUserAudioSelection = false
            )
        )
        assertFalse(
            isOriginalAudioResolutionEnabled(
                preferredAudioLanguage = AudioLanguageOption.DEFAULT,
                hasUserAudioSelection = false
            )
        )
    }

    @Test
    fun resolutionIsDisabledWhenUserSelectedAnAudioTrack() {
        assertFalse(
            isOriginalAudioResolutionEnabled(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                hasUserAudioSelection = true
            )
        )
    }

    @Test
    fun originalPreferenceUsesTheContentLanguageWhenKnown() {
        val resolved = resolvePreferredAudioLanguages(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = "pt",
            deviceLanguages = listOf("pt-br"),
            contentOriginalLanguage = "en"
        )

        assertTrue(resolved.contains("en"))
        assertTrue(resolved.contains("pt"))
    }

    @Test
    fun originalPreferenceFallsBackToDeviceLanguagesWhenLanguageIsUnknown() {
        val resolved = resolvePreferredAudioLanguages(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = null,
            deviceLanguages = listOf("pt-br"),
            contentOriginalLanguage = null
        )

        assertTrue(resolved.contains("pt-br"))
    }
}
