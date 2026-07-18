package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SubtitleTimingAlignerFixtureTest {
    @Test
    fun `aligns file-backed subtitles with a constant offset`() {
        val target = fixture("episode-addon.srt")
        val reference = fixture("episode-embedded-constant.srt")

        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, target.cues))

        assertEquals(1, model.segments.size)
        assertTrue(abs(model.segments.single().offsetMs - 4_500L) <= 250L)
    }

    @Test
    fun `aligns an episode when the video contains a previous episode recap`() {
        val target = fixture("episode-addon.srt")
        val reference = fixture("episode-embedded-recap.srt")

        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, target.cues))

        assertEquals(1, model.segments.size)
        assertTrue(abs(model.segments.single().offsetMs - 30_000L) <= 250L)
    }

    @Test
    fun `aligns regular addon subtitles against an SDH reference file`() {
        val target = fixture("episode-addon.srt")
        val reference = fixture("episode-embedded-sdh.srt")

        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, target.cues))

        assertEquals(1, model.segments.size)
        assertTrue(abs(model.segments.single().offsetMs - 2_500L) <= 250L)
    }

    @Test
    fun `aligns file-backed subtitles across an inserted ad break`() {
        val target = fixture("ad-break-addon.srt")
        val reference = fixture("ad-break-embedded.srt")

        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, target.cues))

        assertTrue(model.segments.size >= 2)
        assertTrue(model.segments.any { abs(it.offsetMs - 2_000L) <= 250L })
        assertTrue(model.segments.any { abs(it.offsetMs - 32_000L) <= 250L })
    }

    @Test
    fun `rejects an unrelated subtitle file`() {
        val target = fixture("episode-addon.srt")
        val unrelated = fixture("unrelated-embedded.srt")

        val model = SubtitleTimingAligner.align(unrelated.cues, target.cues)
        assertNull(model.toString(), model)
    }

    @Test
    fun `rejects unsupported 23976 to 25 fps timing drift`() {
        val target = fixture("fps-23976-addon.srt")
        val reference = fixture("fps-25-embedded.srt")

        assertNull(SubtitleTimingAligner.align(reference.cues, target.cues))
    }

    private fun fixture(name: String): SrtDocument {
        val resource = requireNotNull(javaClass.getResource("/subtitle-sync/$name")) {
            "Missing subtitle fixture: $name"
        }
        return SrtDocument.parse(resource.readText())
    }
}
