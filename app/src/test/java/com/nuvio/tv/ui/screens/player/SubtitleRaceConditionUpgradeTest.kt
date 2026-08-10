package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRaceConditionUpgradeTest {

    @Test
    fun testSecondaryInternalMatchUpgradesToPrimaryAddonMatch() {
        val primaryTarget = "tr"
        val secondaryTarget = "en"

        // Currently selected language is "en" (secondary match from internal tracks)
        val currentSelectedLang = "en"
        val addonSubtitles = listOf(
            Subtitle(id = "sub-1", url = "http://example.com/tr.srt", lang = "tr", addonName = "OpenSubtitles", addonLogo = null)
        )

        val isPrimarySatisfied = currentSelectedLang != null &&
            PlayerSubtitleUtils.matchesLanguageCode(currentSelectedLang, primaryTarget)

        val hasBetterAddonMatch = !isPrimarySatisfied &&
            addonSubtitles.any { PlayerSubtitleUtils.matchesLanguageCode(it.lang, primaryTarget) }

        // Primary is NOT satisfied by "en", and "tr" addon subtitle exists => should upgrade!
        assertFalse(isPrimarySatisfied)
        assertTrue(hasBetterAddonMatch)
    }

    @Test
    fun testPrimaryInternalMatchDoesNotUpgradeToAddon() {
        val primaryTarget = "tr"

        // Currently selected language is already "tr" (primary match from internal tracks)
        val currentSelectedLang = "tr"
        val addonSubtitles = listOf(
            Subtitle(id = "sub-1", url = "http://example.com/tr.srt", lang = "tr", addonName = "OpenSubtitles", addonLogo = null)
        )

        val isPrimarySatisfied = currentSelectedLang != null &&
            PlayerSubtitleUtils.matchesLanguageCode(currentSelectedLang, primaryTarget)

        val hasBetterAddonMatch = !isPrimarySatisfied &&
            addonSubtitles.any { PlayerSubtitleUtils.matchesLanguageCode(it.lang, primaryTarget) }

        // Primary IS satisfied by "tr" internal track => should NOT upgrade or trigger re-select
        assertTrue(isPrimarySatisfied)
        assertFalse(hasBetterAddonMatch)
    }

    @Test
    fun testNoAddonMatchDoesNotTriggerUpgrade() {
        val primaryTarget = "tr"
        val currentSelectedLang = "en"

        // Addons only have "es" (neither primary nor secondary target match)
        val addonSubtitles = listOf(
            Subtitle(id = "sub-1", url = "http://example.com/es.srt", lang = "es", addonName = "OpenSubtitles", addonLogo = null)
        )

        val isPrimarySatisfied = currentSelectedLang != null &&
            PlayerSubtitleUtils.matchesLanguageCode(currentSelectedLang, primaryTarget)

        val hasBetterAddonMatch = !isPrimarySatisfied &&
            addonSubtitles.any { PlayerSubtitleUtils.matchesLanguageCode(it.lang, primaryTarget) }

        // Primary is not satisfied, but no better addon match exists => no upgrade
        assertFalse(isPrimarySatisfied)
        assertFalse(hasBetterAddonMatch)
    }
}
