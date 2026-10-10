package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper

/**
 * Trailers render through a TextureView on top of the home UI, so a 4K trailer costs a few hundred
 * MB of decoder and GPU memory that full-screen playback (SurfaceView) never pays. The "4K trailers"
 * setting decides whether trailers may go above 1080p; it starts off on devices with 2 GB of RAM or less.
 */
object TrailerVideoPolicy {
    const val MAX_TRAILER_HEIGHT_WITHOUT_4K = 1080

    // Same 2 GB / 3 GB boundary as NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel. Devices
    // report less than their marketed RAM (a 2 GB stick ~1.8-1.95 GB, a 3 GB SHIELD ~2.88 GB).
    private const val UP_TO_2GB_THRESHOLD_BYTES = 2_355L * 1024L * 1024L

    /** Default for the "4K trailers" setting: off up to 2 GB of RAM, on above that or when unknown. */
    fun default4kTrailers(context: Context): Boolean {
        val totalRam = NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context)
        return totalRam !in 1 until UP_TO_2GB_THRESHOLD_BYTES
    }

    /** Tallest trailer video to play; [Int.MAX_VALUE] means no cap. */
    fun maxTrailerVideoHeight(allow4k: Boolean): Int =
        if (allow4k) Int.MAX_VALUE else MAX_TRAILER_HEIGHT_WITHOUT_4K
}
