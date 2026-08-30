package com.nuvio.tv.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMarqueeTextTest {

    /**
     * Guards against the marquee going unbounded again: on a TV the focus can rest on the same item
     * indefinitely, and an unbounded marquee keeps the frame clock running for exactly as long. The
     * count has to stay small and finite -- enough passes to read a long label, few enough that the
     * screen falls silent on its own.
     */
    @Test
    fun `the focus marquee stops on its own`() {
        assertTrue("marquee must be bounded", MarqueeIterations in 1..5)
    }
}
