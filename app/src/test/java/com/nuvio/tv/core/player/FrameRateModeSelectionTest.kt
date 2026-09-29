package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.FrameRateUtils.DisplayModeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateModeSelectionTest {

    private val film = 24000f / 1001f
    private val ntsc30 = 30000f / 1001f
    private val ntsc60 = 60000f / 1001f

    // Mode tables read from two real devices; mode 3 was active on both.
    private val stick = listOf(
        mode(1, 1920, 1080, 60.000004f),
        mode(2, 1920, 1080, 59.94f),
        mode(3, 3840, 2160, 59.94f),
        mode(4, 1280, 720, 59.94f),
        mode(5, 3840, 2160, 50f),
        mode(6, 3840, 2160, 60.000004f),
        mode(7, 3840, 2160, 30.000002f),
        mode(8, 3840, 2160, 29.97f),
        mode(9, 3840, 2160, 25f),
        mode(10, 3840, 2160, 24.000002f),
        mode(11, 3840, 2160, 23.976f),
        mode(12, 1920, 1080, 50f),
        mode(13, 1920, 1080, 29.97f),
        mode(14, 1920, 1080, 23.976f),
        mode(15, 1920, 1080, 24.000002f),
        mode(16, 1920, 1080, 25f),
        mode(17, 1920, 1080, 30.000002f),
        mode(18, 1280, 720, 50f),
        mode(19, 1280, 720, 60.000004f),
        mode(20, 720, 576, 50f),
        mode(21, 720, 480, 60.000004f)
    )

    private val am9 = listOf(
        mode(1, 1920, 1080, 119.88013f),
        mode(2, 1920, 1080, 120.00001f),
        mode(3, 3840, 2160, 59.94006f),
        mode(4, 3840, 2160, 60.000004f),
        mode(5, 3840, 2160, 50f),
        mode(6, 3840, 2160, 29.97003f),
        mode(7, 3840, 2160, 30.000002f),
        mode(8, 3840, 2160, 25f),
        mode(9, 3840, 2160, 23.976025f),
        mode(10, 3840, 2160, 24.000002f),
        mode(11, 1920, 1080, 100f),
        mode(12, 1920, 1080, 59.94006f),
        mode(13, 1920, 1080, 60.000004f),
        mode(14, 1920, 1080, 50f),
        mode(15, 1920, 1080, 29.97003f),
        mode(16, 1920, 1080, 30.000002f),
        mode(17, 1920, 1080, 25f),
        mode(18, 1920, 1080, 23.976025f),
        mode(19, 1920, 1080, 24.000002f),
        mode(20, 1280, 720, 59.94006f),
        mode(21, 1280, 720, 60.000004f),
        mode(22, 1280, 720, 50f)
    )

    @Test
    fun `sd 25 fps gets 720p50 instead of 480p60`() {
        assertEquals(18, pick(stick, 25f, 640, 480))
        assertEquals(18, pick(stick, 25f, 624, 464))
        assertEquals(18, pick(stick, 25f, 640, 360))
    }

    @Test
    fun `sd video rates stay at 720p when it shows them exactly or doubled`() {
        assertEquals(4, pick(stick, ntsc30, 640, 480))
        assertEquals(18, pick(stick, 50f, 640, 480))
        assertEquals(4, pick(stick, ntsc60, 640, 480))
        assertEquals(22, pick(am9, 25f, 640, 480))
        assertEquals(20, pick(am9, ntsc30, 640, 480))
        assertEquals(22, pick(am9, 50f, 640, 480))
        assertEquals(20, pick(am9, ntsc60, 640, 480))
    }

    @Test
    fun `sd film rates get the exact rate at 1080p`() {
        assertEquals(14, pick(stick, film, 640, 480))
        assertEquals(15, pick(stick, 24f, 640, 480))
        assertEquals(18, pick(am9, film, 640, 480))
        assertEquals(19, pick(am9, 24f, 640, 480))
    }

    @Test
    fun `hd and uhd content that already matched is unchanged`() {
        assertEquals(16, pick(stick, 25f, 1920, 1080))
        assertEquals(11, pick(stick, film, 3840, 2160))
        assertEquals(18, pick(stick, 25f, 1280, 720))
        assertEquals(17, pick(am9, 25f, 1920, 1080))
        assertEquals(9, pick(am9, film, 3840, 2160))
        assertEquals(22, pick(am9, 25f, 1280, 720))
    }

    @Test
    fun `720p film gets its exact rate instead of 60 Hz`() {
        assertEquals(14, pick(stick, film, 1280, 720))
        assertEquals(18, pick(am9, film, 1280, 720))
    }

    @Test
    fun `resolution matching off keeps the same-size choice`() {
        assertEquals(9, pick(stick, 25f, 640, 480, resolutionMatching = false))
        assertEquals(8, pick(am9, 25f, 640, 480, resolutionMatching = false))
        assertEquals(11, pick(stick, film, 1920, 1080, resolutionMatching = false))
    }

    @Test
    fun `resolution matching off with one same-size mode keeps the current mode`() {
        val modes = listOf(mode(1, 1920, 1080, 60f), mode(2, 1280, 720, 50f))
        assertNull(pick(modes, 25f, 640, 480, resolutionMatching = false, activeId = 1))
    }

    @Test
    fun `unknown video size uses the same-size modes`() {
        assertEquals(9, pick(stick, 25f, null, null))
    }

    @Test
    fun `floor applies even when no rate matches above it`() {
        val modes = listOf(mode(1, 720, 480, 60f), mode(2, 720, 576, 50f), mode(3, 1920, 1080, 60f))
        assertEquals(3, pick(modes, 25f, 640, 480))
    }

    @Test
    fun `no floor on a display without 720p or larger`() {
        val modes = listOf(mode(1, 720, 480, 60f), mode(2, 720, 576, 50f))
        assertEquals(2, pick(modes, 25f, 640, 480, activeId = 1))
    }

    @Test
    fun `video larger than every mode uses the largest size`() {
        assertEquals(10, pick(stick, 24f, 7680, 4320))
    }

    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float) =
        DisplayModeSpec(modeId = id, width = width, height = height, refreshRate = refreshRate)

    private fun pick(
        modes: List<DisplayModeSpec>,
        frameRate: Float,
        videoWidth: Int?,
        videoHeight: Int?,
        resolutionMatching: Boolean = true,
        activeId: Int = 3
    ): Int? = FrameRateUtils.selectDisplayMode(
        modes = modes,
        activeMode = modes.first { it.modeId == activeId },
        frameRate = frameRate,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        resolutionMatchingEnabled = resolutionMatching
    )?.modeId
}
