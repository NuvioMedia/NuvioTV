package com.nuvio.tv.ui.screens.stream

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal object DirectAutoPlayWatchdogPolicy {
    suspend fun awaitForeground(hostInForeground: StateFlow<Boolean>) {
        hostInForeground.first { it }
    }

    fun shouldTearDownResolvedTarget(
        resolvedAutoPlayTarget: Boolean,
        hostInForeground: Boolean,
        showDirectAutoPlayOverlay: Boolean,
        externalPlayerOverlayVisible: Boolean
    ): Boolean {
        return resolvedAutoPlayTarget &&
            hostInForeground &&
            (showDirectAutoPlayOverlay || externalPlayerOverlayVisible)
    }
}
