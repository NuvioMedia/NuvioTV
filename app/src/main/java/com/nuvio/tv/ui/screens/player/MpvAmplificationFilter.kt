package com.nuvio.tv.ui.screens.player

import java.util.Locale

/**
 * mpv audio filter for Amplification: the gain, then a peak limiter at the same ceiling as
 * [GainAudioProcessor], so loud peaks are held back instead of clipped. Empty at 0 dB, which
 * leaves the mpv audio path as it was.
 */
internal fun mpvAmplificationFilter(gainDb: Int): String {
    if (gainDb <= 0) return ""
    val limit = String.format(Locale.ROOT, "%.4f", GainAudioProcessor.LIMITER_CEILING)
    return "lavfi=[volume=volume=${gainDb}dB:precision=float,alimiter=limit=$limit:attack=1:release=100:level=0]"
}
