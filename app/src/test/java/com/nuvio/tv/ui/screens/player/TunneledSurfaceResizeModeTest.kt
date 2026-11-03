package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TunneledSurfaceResizeModeTest {

    @Test
    fun exoSurfaceLayout_staysFitWithoutTunneling() {
        AspectMode.entries.forEach { mode ->
            assertEquals(
                ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT),
                exoSurfaceLayout(tunnelingEnabled = false, aspectMode = mode)
            )
        }
    }

    @Test
    fun exoSurfaceLayout_laysOutEveryModeAsAFrameSizeWhileTunneling() {
        val expected = mapOf(
            AspectMode.ORIGINAL to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT),
            AspectMode.FULL_SCREEN to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            AspectMode.STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FILL),
            AspectMode.SLIGHT_ZOOM to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.15f),
            AspectMode.CINEMA_ZOOM to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.33f),
            AspectMode.VERTICAL_STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
            AspectMode.HORIZONTAL_STRETCH to ExoSurfaceLayout(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH)
        )
        assertEquals(AspectMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, layout) ->
            assertEquals(layout, exoSurfaceLayout(tunnelingEnabled = true, aspectMode = mode))
        }
    }

    @Test
    fun aspectModeAppliedToExoSurface_dropsScaleModesWhileTunneling() {
        assertEquals(
            AspectMode.CINEMA_ZOOM,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = false,
                aspectMode = AspectMode.CINEMA_ZOOM
            )
        )
        assertEquals(
            AspectMode.ORIGINAL,
            aspectModeAppliedToExoSurface(
                tunnelingEnabled = true,
                aspectMode = AspectMode.CINEMA_ZOOM
            )
        )
    }
}
