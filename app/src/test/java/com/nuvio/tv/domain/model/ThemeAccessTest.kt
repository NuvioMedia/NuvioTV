package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun standardUsersCannotAccessSupporterThemes() {
        val availableThemes = availableAppThemes(CosmeticEntitlements.None)

        assertFalse(AppTheme.GOLD in availableThemes)
        assertFalse(AppTheme.JADE in availableThemes)
        assertFalse(AppTheme.ROSE_GOLD in availableThemes)
        assertFalse(AppTheme.ARCTIC_BLUE in availableThemes)
        assertFalse(AppTheme.GRAPHITE in availableThemes)
        assertEquals(AppTheme.WHITE, resolveAppTheme(AppTheme.GOLD, CosmeticEntitlements.None))
    }

    @Test
    fun supporterAccessUnlocksAllThemesAndDefaultsToGold() {
        val entitlements = CosmeticEntitlements.SupporterPreview
        val availableThemes = availableAppThemes(entitlements)

        assertTrue(AppTheme.GOLD in availableThemes)
        assertTrue(AppTheme.JADE in availableThemes)
        assertTrue(AppTheme.ROSE_GOLD in availableThemes)
        assertTrue(AppTheme.ARCTIC_BLUE in availableThemes)
        assertTrue(AppTheme.GRAPHITE in availableThemes)
        assertEquals(AppTheme.GOLD, resolveAppTheme(null, entitlements))
    }

    @Test
    fun individualThemeEntitlementsCanBeGrantedSeparately() {
        val entitlements = CosmeticEntitlements(
            unlocked = setOf(CosmeticEntitlement.ARCTIC_BLUE_THEME)
        )

        assertTrue(AppTheme.ARCTIC_BLUE in availableAppThemes(entitlements))
        assertFalse(AppTheme.GOLD in availableAppThemes(entitlements))
        assertEquals(AppTheme.ARCTIC_BLUE, resolveAppTheme(null, entitlements))
    }

    @Test
    fun explicitThemeSelectionIsPreservedForSupporters() {
        assertEquals(
            AppTheme.OCEAN,
            resolveAppTheme(AppTheme.OCEAN, CosmeticEntitlements.SupporterPreview)
        )
    }
}
