package com.omnio.tv.ui.screens.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos

suspend fun FocusRequester.requestFocusAfterFrames(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    repeat(4) { attempt ->
        val requested = runCatching {
            requestFocus()
            true
        }.getOrDefault(false)
        if (requested) return
        if (attempt < 3) {
            withFrameNanos { }
        }
    }
}
