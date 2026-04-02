package com.nuvio.tv.ui.screens.player

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R

enum class AspectMode(@StringRes val labelResId: Int) {
    ORIGINAL(R.string.player_aspect_fit),
    FULL_SCREEN(R.string.player_aspect_crop),
    SLIGHT_ZOOM(R.string.player_aspect_mode_slight_zoom),
    CINEMA_ZOOM(R.string.player_aspect_mode_cinema_zoom),
    VERTICAL_STRETCH(R.string.player_aspect_fit_height),
    HORIZONTAL_STRETCH(R.string.player_aspect_fit_width)
}

internal fun nextAspectMode(current: AspectMode): AspectMode {
    val modes = AspectMode.entries
    val nextIndex = (modes.indexOf(current) + 1) % modes.size
    return modes[nextIndex]
}

internal fun aspectModeLabel(mode: AspectMode, getString: (Int) -> String): String =
    getString(mode.labelResId)

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    val targetView = resolveVideoSurfaceView(playerView) ?: playerView
    playerView.scaleX = 1.0f
    playerView.scaleY = 1.0f

    when (mode) {
        AspectMode.ORIGINAL -> {
            targetView.scaleX = 1.0f
            targetView.scaleY = 1.0f
        }

        AspectMode.FULL_SCREEN -> applyCoverAspectScale(playerView, targetView)

        AspectMode.SLIGHT_ZOOM -> {
            targetView.scaleX = 1.15f
            targetView.scaleY = 1.15f
        }

        AspectMode.CINEMA_ZOOM -> {
            targetView.scaleX = 1.33f
            targetView.scaleY = 1.33f
        }

        AspectMode.VERTICAL_STRETCH -> {
            targetView.scaleX = 1.0f
            targetView.scaleY = 1.33f
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            targetView.scaleX = 1.3333f
            targetView.scaleY = 1.0f
        }
    }
}

private fun applyCoverAspectScale(playerView: PlayerView, targetView: View) {
    val videoSize = playerView.player?.videoSize
    val videoAspect = if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        0f
    }

    val viewAspect = if (playerView.width > 0 && playerView.height > 0) {
        playerView.width.toFloat() / playerView.height.toFloat()
    } else {
        0f
    }

    if (videoAspect > 0f && viewAspect > 0f) {
        if (videoAspect > viewAspect) {
            targetView.scaleX = 1.0f
            targetView.scaleY = videoAspect / viewAspect
        } else {
            targetView.scaleX = viewAspect / videoAspect
            targetView.scaleY = 1.0f
        }
    } else {
        targetView.scaleX = 1.0f
        targetView.scaleY = 1.0f
    }
}

private fun resolveVideoSurfaceView(playerView: PlayerView): View? {
    return findVideoSurfaceView(playerView)
}

private fun findVideoSurfaceView(view: View): View? {
    return when (view) {
        is SurfaceView, is TextureView -> view
        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                val child = findVideoSurfaceView(view.getChildAt(index))
                if (child != null) {
                    return child
                }
            }
            null
        }

        else -> null
    }
}
