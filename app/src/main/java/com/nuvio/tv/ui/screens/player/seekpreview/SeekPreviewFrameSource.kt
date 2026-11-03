package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap

internal interface SeekPreviewFrameSource {
    fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap?

    fun cancel() = Unit

    fun close()
}

internal interface SeekPreviewFeed {
    fun want(positionMs: Long, showingMs: Long?)

    fun prepare()

    fun close()
}
