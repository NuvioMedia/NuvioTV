package com.nuvio.tv.ui.theme

import androidx.annotation.DrawableRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme

@get:DrawableRes
val AppTheme.brandWordmarkResource: Int
    get() = when (this) {
        AppTheme.GOLD -> R.drawable.app_logo_wordmark_gold
        AppTheme.JADE -> R.drawable.app_logo_wordmark_jade
        AppTheme.ROSE_GOLD -> R.drawable.app_logo_wordmark_rose_gold
        AppTheme.ARCTIC_BLUE -> R.drawable.app_logo_wordmark_arctic_blue
        AppTheme.GRAPHITE -> R.drawable.app_logo_wordmark_graphite
        else -> R.drawable.app_logo_wordmark
    }
