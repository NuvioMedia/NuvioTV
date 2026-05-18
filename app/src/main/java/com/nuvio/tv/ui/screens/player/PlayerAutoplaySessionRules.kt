package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode

internal fun nextConsecutiveAutoPlayCount(
    currentCount: Int,
    isAutoPlay: Boolean,
): Int = if (isAutoPlay) currentCount + 1 else 0

internal fun shouldAutoAdvanceAtEndOfEpisode(
    streamAutoPlayNextEpisodeEnabled: Boolean,
    streamAutoPlayMode: StreamAutoPlayMode,
): Boolean = streamAutoPlayNextEpisodeEnabled ||
    streamAutoPlayMode != StreamAutoPlayMode.MANUAL
