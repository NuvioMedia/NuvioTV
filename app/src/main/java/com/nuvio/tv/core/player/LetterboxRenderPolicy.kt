package com.nuvio.tv.core.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object PlayerWindowBackdrop {

    private var transparentRequests by mutableIntStateOf(0)

    val isTransparentRequested: Boolean
        get() = transparentRequests > 0

    fun acquireTransparent() {
        transparentRequests++
    }

    fun releaseTransparent() {
        transparentRequests = (transparentRequests - 1).coerceAtLeast(0)
    }
}
