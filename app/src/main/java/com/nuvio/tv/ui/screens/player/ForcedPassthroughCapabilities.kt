package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Capabilities pinned when the user forces passthrough. Media3 only attempts bitstream output for
 * encodings listed in [AudioCapabilities], and below API 29 it builds that list from the HDMI plug
 * broadcast, which some Android 7 TV boxes never populate (they report PCM only).
 */
internal object ForcedPassthroughCapabilities {

    /** 7.1 covers the bitstream profiles above, which are object-based or 8-channel at most. */
    const val MAX_CHANNEL_COUNT = 8

    val encodings = intArrayOf(
        C.ENCODING_PCM_16BIT,
        C.ENCODING_AC3,
        C.ENCODING_E_AC3,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_DTS,
        C.ENCODING_DTS_HD,
        C.ENCODING_DOLBY_TRUEHD
    )

    @Suppress("DEPRECATION") // Only public constructor that takes a plain encoding list.
    fun build(): AudioCapabilities = AudioCapabilities(encodings, MAX_CHANNEL_COUNT)
}
