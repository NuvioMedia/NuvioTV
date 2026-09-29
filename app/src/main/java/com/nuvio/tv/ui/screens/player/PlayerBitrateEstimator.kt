package com.nuvio.tv.ui.screens.player

// Matroska carries no per track bitrate element, so its extractor leaves Format.bitrate unset and
// every consumer of it reads nothing; file size over duration is the only rate a mkv can report.
internal object PlayerBitrateEstimator {

    fun fileBitrateBps(fileSizeBytes: Long?, durationMs: Long): Int? {
        if (fileSizeBytes == null || fileSizeBytes <= 0L || durationMs <= 0L) return null
        val bps = fileSizeBytes * 8.0 / (durationMs / 1000.0)
        if (bps <= 0.0 || bps >= Int.MAX_VALUE.toDouble()) return null
        return bps.toInt()
    }
}
