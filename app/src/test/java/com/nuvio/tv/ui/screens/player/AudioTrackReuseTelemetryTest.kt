package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackReuseTelemetryTest {

    @Test
    fun `record bumps counters and emits line for reuse`() {
        val t = AudioTrackReuseTelemetry()
        val line = t.record(AudioTrackReuseOutcome.REUSED_PASSTHROUGH, "audio/eac3")
        assertTrue(line!!.startsWith("AUDIO_TRACK_REUSE: result=REUSED_PASSTHROUGH"))
        assertTrue(line.contains("mime=audio/eac3"))
        assertEquals(1, t.snapshot().okPassthrough)
        assertEquals(1, t.snapshot().reuseOk)
    }

    @Test
    fun `not eligible only counts without raw line`() {
        val t = AudioTrackReuseTelemetry()
        assertNull(t.record(AudioTrackReuseOutcome.SKIPPED_NOT_ELIGIBLE))
        assertEquals(1, t.snapshot().skipNotEligible)
        assertEquals(0, t.snapshot().reuseOk)
    }

    @Test
    fun `summary includes all buckets`() {
        val t = AudioTrackReuseTelemetry()
        t.record(AudioTrackReuseOutcome.REUSED_TUNNEL, "audio/raw")
        t.record(AudioTrackReuseOutcome.FAILED_REFLECTION)
        t.record(AudioTrackReuseOutcome.SKIPPED_NOT_ELIGIBLE)
        val summary = t.summaryLine()
        assertTrue(summary.startsWith("AUDIO_TRACK_REUSE_SUMMARY:"))
        assertTrue(summary.contains("okTunnel=1"))
        assertTrue(summary.contains("fail=1"))
        assertTrue(summary.contains("skipEligible=1"))
        assertTrue(summary.contains("total=3"))
        assertTrue(summary.contains("reuseOk=1"))
    }

    @Test
    fun `reset clears session`() {
        val t = AudioTrackReuseTelemetry()
        t.record(AudioTrackReuseOutcome.REUSED_PASSTHROUGH)
        t.reset()
        assertEquals(0, t.snapshot().totalAttempts)
    }
}
