package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TunneledSurfaceResizeModeTest {

    @Test
    fun exoSurfaceLayout_laysOutEveryModeAsAFrameSize() {
        val expected = mapOf(
            AspectMode.ORIGINAL to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT),
            AspectMode.FULL_SCREEN to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            AspectMode.STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FILL),
            AspectMode.FORCE_STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FILL),
            AspectMode.SLIGHT_ZOOM to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.15f),
            AspectMode.CINEMA_ZOOM to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.33f),
            AspectMode.VERTICAL_STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
            AspectMode.HORIZONTAL_STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH)
        )
        assertEquals(AspectMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, layout) ->
            assertEquals(layout, exoSurfaceLayout(aspectMode = mode))
        }
    }

    @Test
    fun forceStretch_stretchesLetterboxedWideFrameVertically() {
        val layout = exoSurfaceLayout(
            aspectMode = AspectMode.FORCE_STRETCH,
            videoAspect = 16f / 9f,
            viewAspect = 16f / 9f
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, layout.resizeMode)
        assertEquals(1f, layout.zoom, 0.0001f)
        assertEquals(2.39f / (16f / 9f), layout.verticalZoom, 0.0001f)
    }

    @Test
    fun forceStretch_fillsLikeStretchWhenFrameIsAlreadyScope() {
        assertEquals(1f, forceStretchVerticalScale(videoAspect = 2.4f, viewAspect = 16f / 9f), 0.0001f)
        assertEquals(1f, forceStretchVerticalScale(videoAspect = 16f / 9f, viewAspect = 0f), 0.0001f)
    }
}
