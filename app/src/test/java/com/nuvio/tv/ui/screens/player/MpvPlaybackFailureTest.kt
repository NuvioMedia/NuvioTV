package com.nuvio.tv.ui.screens.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvPlaybackFailureTest {
    @Test fun `explicit stream failure exposes the engine error`() {
        assertEquals("loading failed", mpvPlaybackFailure(MPVNode.MapNode(mapOf(
            "reason" to MPVNode.StringNode("error"), "error" to MPVNode.StringNode("loading failed")
        ))))
    }

    @Test fun `normal completion stop and redirects never trigger another stream`() {
        for (reason in listOf("eof", "stop", "quit", "redirect", "unknown")) {
            assertNull(mpvPlaybackFailure(MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode(reason)))))
        }
        assertNull(mpvPlaybackFailure(MPVNode.None))
    }
}
