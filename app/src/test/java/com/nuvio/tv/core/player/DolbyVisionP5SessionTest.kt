package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionP5SessionTest {

    @Test
    fun mappingAt_takesTheSampleAtOrBeforeTheFrame() {
        val session = DolbyVisionP5Session(active = false)
        session.onSampleMapping(1_000_000L, 11L)
        session.onSampleMapping(2_000_000L, 22L)
        assertEquals(11L, session.mappingAt(1_500_000L))
        assertEquals(22L, session.mappingAt(2_000_000L))
        assertEquals(22L, session.mappingAt(9_000_000L))
    }

    @Test
    fun mappingAt_beforeEverySampleTakesTheFirstMapping() {
        val session = DolbyVisionP5Session(active = false)
        session.onSampleMapping(1_000_000L, 11L)
        assertEquals(11L, session.mappingAt(0L))
    }

    @Test
    fun mappingAt_withoutSamplesIsNone() {
        assertEquals(0L, DolbyVisionP5Session(active = false).mappingAt(0L))
    }

    @Test
    fun profile5Rpu_detectsOnceAndNotifies() {
        val session = DolbyVisionP5Session(active = false)
        var notified = 0
        session.onDetected = { notified++ }
        session.onRpuResult(DolbyVisionP5Native.OTHER_PROFILE_BASE - 8)
        assertFalse(session.detected)
        session.onRpuResult(1234L)
        session.onRpuResult(1234L)
        assertTrue(session.detected)
        assertEquals(1, notified)
    }

    @Test
    fun status_reportsTheFirstMissingStep() {
        val session = DolbyVisionP5Session(active = false)
        assertEquals(DolbyVisionP5Session.Status.NoFrames, session.status())
        session.onEffectCreated(hdr = false)
        session.onFrameDrawn(mapped = false)
        assertEquals(DolbyVisionP5Session.Status.PipelineNotHdr, session.status())

        val other = DolbyVisionP5Session(active = false)
        other.onEffectCreated(hdr = true)
        other.onFrameDrawn(mapped = false)
        assertEquals(DolbyVisionP5Session.Status.NoRpu, other.status())
        other.onRpuResult(DolbyVisionP5Native.OTHER_PROFILE_BASE - 8)
        assertEquals(DolbyVisionP5Session.Status.OtherProfile(8), other.status())

        val unreadable = DolbyVisionP5Session(active = false)
        unreadable.onEffectCreated(hdr = true)
        unreadable.onFrameDrawn(mapped = false)
        unreadable.onRpuResult(DolbyVisionP5Native.RPU_UNREADABLE)
        assertEquals(DolbyVisionP5Session.Status.RpuUnreadable, unreadable.status())

        val noLut = DolbyVisionP5Session(active = false)
        noLut.onEffectCreated(hdr = true)
        noLut.onFrameDrawn(mapped = false)
        noLut.onRpuResult(42L)
        assertEquals(DolbyVisionP5Session.Status.NoLut, noLut.status())
    }
}
