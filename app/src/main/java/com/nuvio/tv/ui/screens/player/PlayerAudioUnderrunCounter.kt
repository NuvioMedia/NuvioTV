package com.nuvio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicInteger

internal object PlayerAudioUnderrunCounter {
    private val count = AtomicInteger(0)

    fun reset() = count.set(0)

    fun record() {
        count.incrementAndGet()
    }

    fun current(): Int = count.get()
}
