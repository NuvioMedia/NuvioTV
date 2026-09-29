package com.nuvio.tv.ui.theme

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBrandingTest {
    @Test
    fun supporterThemesUseMatchingWordmarks() {
        val expectedWordmarks = mapOf(
            AppTheme.GOLD to R.drawable.app_logo_wordmark_gold,
            AppTheme.JADE to R.drawable.app_logo_wordmark_jade,
            AppTheme.ROSE_GOLD to R.drawable.app_logo_wordmark_rose_gold,
            AppTheme.ARCTIC_BLUE to R.drawable.app_logo_wordmark_arctic_blue,
            AppTheme.GRAPHITE to R.drawable.app_logo_wordmark_graphite
        )

        expectedWordmarks.forEach { (theme, wordmark) ->
            assertEquals(wordmark, theme.brandWordmarkResource)
        }
    }

    @Test
    fun standardThemesUseDefaultWordmark() {
        val supporterThemes = setOf(
            AppTheme.GOLD,
            AppTheme.JADE,
            AppTheme.ROSE_GOLD,
            AppTheme.ARCTIC_BLUE,
            AppTheme.GRAPHITE
        )

        AppTheme.entries.filterNot(supporterThemes::contains).forEach { theme ->
            assertEquals(R.drawable.app_logo_wordmark, theme.brandWordmarkResource)
        }
    }
}
