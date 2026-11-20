package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.SubtitleFont
import com.nuvio.tv.core.player.SubtitleFontStore
import com.nuvio.tv.core.player.SubtitleFonts

@Composable
internal fun rememberSubtitleFonts(): List<SubtitleFont> {
    val context = LocalContext.current
    val store = remember(context) { SubtitleFontStore.get(context) }
    val fonts by store.fonts.collectAsState()
    return fonts
}

internal fun subtitleFontIds(fonts: List<SubtitleFont>): List<String> = SubtitleFonts.builtIn + fonts.map { it.id }

@Composable
internal fun subtitleFontLabel(fontId: String, fonts: List<SubtitleFont>): String = when (fontId) {
    SubtitleFonts.SERIF -> stringResource(R.string.sub_font_serif)
    SubtitleFonts.MONO -> stringResource(R.string.sub_font_mono)
    else -> fonts.firstOrNull { it.id == fontId }?.name ?: stringResource(R.string.sub_font_default)
}
