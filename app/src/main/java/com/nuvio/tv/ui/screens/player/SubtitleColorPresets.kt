package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Subtitle color presets offered by the in-player pickers
 * (the quick subtitle overlay and the subtitle style side panel).
 */
internal val SubtitleTextColorPresets = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

internal val SubtitleOutlineColorPresets = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

internal val SubtitleBackgroundColorPresets = listOf(
    Color.Black,
    Color(0xFF3A3A3A),
    Color.White,
    Color(0xFFFFD700),
    Color(0xFF1E3A8A)
)

internal const val SUBTITLE_BACKGROUND_DEFAULT_OPACITY = 0.75f

internal fun subtitleBackgroundColorFor(color: Color, currentBackground: Int): Int {
    val currentAlpha = Color(currentBackground).alpha
    return color.copy(alpha = if (currentAlpha > 0f) currentAlpha else SUBTITLE_BACKGROUND_DEFAULT_OPACITY).toArgb()
}

internal fun selectedSubtitleBackgroundPreset(background: Int): Int =
    if ((background ushr 24) > 0) background or 0xFF000000.toInt() else 0
