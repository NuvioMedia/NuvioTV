package com.nuvio.tv.ui.screens.player

import java.util.Locale

internal fun PlayerRuntimeController.shouldUseMpvHi10pGnextSoftwareFallback(): Boolean {
    if (!mpvHi10pGnextSoftwareFallbackEnabledSetting) return false
    return mpvPlaybackHintsIndicateHi10p(
        currentFilename,
        currentStreamDescription,
        _uiState.value.currentStreamName,
        currentStreamUrl,
    )
}

internal fun mpvPlaybackHintsIndicateHi10p(vararg hints: String?): Boolean =
    hints.filterNotNull().any(String::indicatesH264Hi10p)

private fun String.indicatesH264Hi10p(): Boolean {
    val value = lowercase(Locale.US)
    val tenBit = value.contains("10bit") || value.contains("10-bit") || value.contains("10 bit")
    val h264 = value.contains("x264") || value.contains("h264") ||
        value.contains("h.264") || value.contains("avc")
    val hevc = value.contains("x265") || value.contains("h265") ||
        value.contains("h.265") || value.contains("hevc")
    return value.contains("hi10") || (tenBit && h264 && !hevc)
}
