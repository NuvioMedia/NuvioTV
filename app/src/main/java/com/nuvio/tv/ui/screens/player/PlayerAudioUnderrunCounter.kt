package com.nuvio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicInteger

// The analytics diagnostics hold their underrun fields as plain vars written from the analytics
// thread, so the overlay counts here rather than sampling those across threads every second.
internal object PlayerAudioUnderrunCounter {
    private val count = AtomicInteger(0)

    fun reset() = count.set(0)

    fun record() {
        count.incrementAndGet()
    }

    fun current(): Int = count.get()
}
