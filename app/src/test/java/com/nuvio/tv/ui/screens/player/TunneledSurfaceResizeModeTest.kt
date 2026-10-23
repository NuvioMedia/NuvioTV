package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class TunneledSurfaceResizeModeTest {

    @Test
    fun exoSurfaceResizeMode_staysFitUnlessTunnelingOptsIntoFill() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = false
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = false,
                tunneledSurfaceFill = true
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = true,
                tunneledSurfaceFill = false
            )
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            PlayerDisplayModeUtils.exoSurfaceResizeMode(
                tunnelingEnabled = true,
                tunneledSurfaceFill = true
            )
        )
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
