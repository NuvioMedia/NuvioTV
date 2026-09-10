package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Skip Intro / Skip Recap default-behavior fix: "Skip Intro"'s own
 * settings subtitle promises "detect intros and recaps", so a fresh PlayerSettings (nobody has
 * ever opened the granular Automatic Skipping section) must auto-skip both by default -
 * previously it defaulted to an empty set, so nothing ever auto-skipped even with the master
 * toggle on.
 */
class PlayerSettingsAutoSkipDefaultsTest {

    @Test
    fun `fresh PlayerSettings auto-skips intro and recap by default`() {
        val settings = PlayerSettings()
        assertEquals(
            setOf(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP),
            settings.autoSkipSegmentTypes
        )
    }

    @Test
    fun `fresh PlayerSettings does not auto-skip outro by default`() {
        val settings = PlayerSettings()
        assertEquals(false, AutoSkipSegmentType.OUTRO in settings.autoSkipSegmentTypes)
    }

    @Test
    fun `skip intro is enabled by default`() {
        assertEquals(true, PlayerSettings().skipIntroEnabled)
    }

    // ── AutoSkipSegmentType.fromSkipIntervalType mapping ───────────────────

    @Test
    fun `recap interval type maps to RECAP segment type`() {
        assertEquals(AutoSkipSegmentType.RECAP, AutoSkipSegmentType.fromSkipIntervalType("recap"))
    }

    @Test
    fun `op and mixed-op interval types map to INTRO segment type`() {
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("op"))
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("mixed-op"))
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("intro"))
    }

    @Test
    fun `ed, mixed-ed, outro and credits interval types map to OUTRO segment type`() {
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("ed"))
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("mixed-ed"))
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("outro"))
        assertEquals(AutoSkipSegmentType.OUTRO, AutoSkipSegmentType.fromSkipIntervalType("credits"))
    }

    @Test
    fun `unknown interval type maps to no segment type`() {
        assertNull(AutoSkipSegmentType.fromSkipIntervalType("something-else"))
    }

    @Test
    fun `interval type matching is case-insensitive`() {
        assertEquals(AutoSkipSegmentType.RECAP, AutoSkipSegmentType.fromSkipIntervalType("RECAP"))
        assertEquals(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.fromSkipIntervalType("Mixed-OP"))
    }
}
