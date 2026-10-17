package com.nuvio.tv.ui.screens.player

/**
 * How far to lift unpositioned subtitles above the player's bottom UI.
 *
 * When the subtitle overlay or style panel is open, lift is disabled so the
 * configured bottom offset is shown and can be previewed while adjusting it.
 */
internal fun computeSubtitleLiftPx(
    previewConfiguredPosition: Boolean,
    controlsVisible: Boolean,
    controlsClearancePx: Float,
    fallbackControlsLiftPx: Float,
    skipVisible: Boolean,
    skipClearancePx: Float,
    postPlayVisible: Boolean,
    postPlayClearancePx: Float,
    seekVisible: Boolean,
    seekClearancePx: Float,
): Float {
    if (previewConfiguredPosition) return 0f
    return maxOf(
        if (controlsVisible) {
            controlsClearancePx.takeIf { it > 0f } ?: fallbackControlsLiftPx
        } else {
            0f
        },
        if (skipVisible) skipClearancePx else 0f,
        if (postPlayVisible) postPlayClearancePx else 0f,
        if (seekVisible) seekClearancePx else 0f,
    )
}
