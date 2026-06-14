package com.nuvio.tv.ui.screens.player

import android.graphics.Rect
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
    STRETCH(R.string.player_aspect_stretch),
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

internal data class AspectScale(val scaleX: Float, val scaleY: Float)

internal fun aspectModeNeedsVideoAspect(mode: AspectMode): Boolean {
    return when (mode) {
        AspectMode.FULL_SCREEN,
        AspectMode.STRETCH,
        AspectMode.VERTICAL_STRETCH,
        AspectMode.HORIZONTAL_STRETCH -> true

        AspectMode.ORIGINAL,
        AspectMode.SLIGHT_ZOOM,
        AspectMode.CINEMA_ZOOM -> false
    }
}

internal fun readViewAspectRatio(width: Int, height: Int): Float {
    return if (width > 0 && height > 0) {
        width.toFloat() / height.toFloat()
    } else {
        0f
    }
}

internal fun readExoVideoAspectRatio(playerView: PlayerView): Float? {
    val videoSize = playerView.player?.videoSize
    return if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        null
    }
}

private data class FormatCache(
    val width: Int,
    val height: Int,
    val mime: String,
    val codecs: String,
    val is4kDolby: Boolean
)

private fun checkExo4kMovie(playerView: PlayerView): Boolean {
    val player = playerView.player ?: return false
    
    // 1. Get info from active format
    val videoFormat = (player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat
    var width = videoFormat?.width ?: 0
    var height = videoFormat?.height ?: 0
    var mime = videoFormat?.sampleMimeType ?: ""
    var codecs = videoFormat?.codecs ?: ""
    
    // 2. Fallback to track formats if active format is missing dimensions
    if (width <= 0 || height <= 0) {
        try {
            val tracks = player.currentTracks
            for (group in tracks.groups) {
                if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val format = group.getTrackFormat(i)
                            width = format.width
                            height = format.height
                            mime = format.sampleMimeType ?: ""
                            codecs = format.codecs ?: ""
                            break
                        }
                    }
                }
                if (width > 0) break
            }
        } catch (e: Exception) {
            // ignore
        }
    }
    
    // Check view tag cache
    val cache = playerView.getTag(R.id.player_view_4k_dolby_cache_tag) as? FormatCache
    if (cache != null &&
        cache.width == width &&
        cache.height == height &&
        cache.mime == mime &&
        cache.codecs == codecs
    ) {
        return cache.is4kDolby
    }
    
    val is4kDolby = is4kDolbyOrHevc(width, height, mime, codecs)
    playerView.setTag(
        R.id.player_view_4k_dolby_cache_tag,
        FormatCache(width, height, mime, codecs, is4kDolby)
    )
    
    return is4kDolby
}


internal fun is4kDolbyOrHevc(
    width: Int,
    height: Int,
    mime: String,
    codecs: String
): Boolean {
    val is4k = width >= 3840 || height >= 2160
    
    val isDolby = mime.contains("dolby", ignoreCase = true) || 
                  mime.contains("dv", ignoreCase = true) || 
                  codecs.contains("dv", ignoreCase = true)

    val isHevc = mime.contains("hevc", ignoreCase = true) ||
                 codecs.contains("hvc", ignoreCase = true) ||
                 codecs.contains("hev", ignoreCase = true)
                  
    return is4k && (isDolby || isHevc)
}


internal fun resolveAspectScale(
    mode: AspectMode,
    viewAspect: Float,
    videoAspect: Float?,
    is4kDolby: Boolean = false
): AspectScale {
    if (viewAspect <= 0f) {
        return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
    }

    // Assume active video is 21:9 (2.39f aspect ratio) inside a 16:9 container for letterboxed 4k Dolby/HEVC content.
    val isLetterbox4kDolby = is4kDolby && (videoAspect == null || videoAspect < 1.85f)

    return when (mode) {
        AspectMode.ORIGINAL -> AspectScale(scaleX = 1.0f, scaleY = 1.0f)

        AspectMode.FULL_SCREEN -> {
            if (isLetterbox4kDolby) {
                // Zoom active 2.39f area to fill height (crops sides on 16:9 screen, fits perfectly on 21:9 screen).
                val scale = maxOf(viewAspect, 2.39f) / 1.777f
                AspectScale(scaleX = scale, scaleY = scale)
            } else {
                val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                    ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                val uniformScale = if (safeVideoAspect > viewAspect) {
                    safeVideoAspect / viewAspect
                } else {
                    viewAspect / safeVideoAspect
                }
                AspectScale(scaleX = uniformScale, scaleY = uniformScale)
            }
        }

        AspectMode.STRETCH -> {
            if (isLetterbox4kDolby) {
                // Stretch height to fill screen vertically and width to fill screen horizontally.
                val scaleX = viewAspect / 1.777f
                val scaleY = 2.39f / 1.777f
                AspectScale(scaleX = scaleX, scaleY = scaleY)
            } else {
                val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                    ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                if (safeVideoAspect > viewAspect) {
                    AspectScale(scaleX = 1.0f, scaleY = safeVideoAspect / viewAspect)
                } else {
                    AspectScale(scaleX = viewAspect / safeVideoAspect, scaleY = 1.0f)
                }
            }
        }

        AspectMode.SLIGHT_ZOOM -> {
            if (isLetterbox4kDolby) {
                val baseScale = if (viewAspect > 1.85f) viewAspect / 1.777f else 1.0f
                AspectScale(scaleX = baseScale * 1.15f, scaleY = baseScale * 1.15f)
            } else {
                AspectScale(scaleX = 1.15f, scaleY = 1.15f)
            }
        }

        AspectMode.CINEMA_ZOOM -> {
            if (isLetterbox4kDolby) {
                val baseScale = if (viewAspect > 1.85f) viewAspect / 1.777f else 1.0f
                AspectScale(scaleX = baseScale * 1.33f, scaleY = baseScale * 1.33f)
            } else {
                AspectScale(scaleX = 1.33f, scaleY = 1.33f)
            }
        }

        AspectMode.VERTICAL_STRETCH -> {
            if (isLetterbox4kDolby) {
                AspectScale(scaleX = 1.0f, scaleY = 2.39f / 1.777f)
            } else {
                val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                    ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                if (safeVideoAspect > viewAspect) {
                    val uniformScale = safeVideoAspect / viewAspect
                    AspectScale(scaleX = uniformScale, scaleY = uniformScale)
                } else {
                    AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                }
            }
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            if (isLetterbox4kDolby) {
                AspectScale(scaleX = viewAspect / 1.777f, scaleY = 1.0f)
            } else {
                val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                    ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                if (safeVideoAspect < viewAspect) {
                    val uniformScale = viewAspect / safeVideoAspect
                    AspectScale(scaleX = uniformScale, scaleY = uniformScale)
                } else {
                    AspectScale(scaleX = 1.0f, scaleY = 1.0f)
                }
            }
        }
    }
}

internal fun applyExoAspectMode(playerView: PlayerView, mode: AspectMode) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = resolveVideoSurfaceView(playerView)
    val targetView = contentFrame ?: surfaceView ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)

    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    val is4kDolby = checkExo4kMovie(playerView)
    applyAspectScale(targetView, mode, viewAspect, videoAspect, is4kDolby)
    centerTargetInPlayer(playerView, targetView)
}

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    val targetView = resolveVideoSurfaceView(playerView) ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)
    resetAspectTransform(playerView)

    val is4kDolby = checkExo4kMovie(playerView)
    applyAspectScale(targetView, mode, viewAspect, videoAspect, is4kDolby)
}

internal fun addExoAspectLayoutChangeListener(
    playerView: PlayerView,
    listener: View.OnLayoutChangeListener
): () -> Unit {
    val targets = linkedSetOf<View>()
    targets.add(playerView)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(targets::add)
    resolveVideoSurfaceView(playerView)?.let(targets::add)
    targets.forEach { it.addOnLayoutChangeListener(listener) }
    return {
        targets.forEach { it.removeOnLayoutChangeListener(listener) }
    }
}

private fun applyAspectScale(
    targetView: View,
    mode: AspectMode,
    viewAspect: Float,
    videoAspect: Float?,
    is4kDolby: Boolean = false
) {
    val scale = resolveAspectScale(
        mode = mode,
        viewAspect = viewAspect,
        videoAspect = videoAspect,
        is4kDolby = is4kDolby
    )
    targetView.scaleX = scale.scaleX
    targetView.scaleY = scale.scaleY
}

private fun resetAspectTransform(view: View) {
    view.scaleX = 1.0f
    view.scaleY = 1.0f
    view.translationX = 0.0f
    view.translationY = 0.0f
    if (view.width > 0) {
        view.pivotX = view.width / 2.0f
    }
    if (view.height > 0) {
        view.pivotY = view.height / 2.0f
    }
}

private fun centerTargetInPlayer(playerView: PlayerView, targetView: View) {
    if (
        targetView === playerView ||
        playerView.width <= 0 ||
        playerView.height <= 0 ||
        targetView.width <= 0 ||
        targetView.height <= 0
    ) {
        return
    }

    val targetRect = Rect(0, 0, targetView.width, targetView.height)
    playerView.offsetDescendantRectToMyCoords(targetView, targetRect)
    val playerCenterX = playerView.width / 2.0f
    val playerCenterY = playerView.height / 2.0f
    val targetCenterX = targetRect.left + targetRect.width() / 2.0f
    val targetCenterY = targetRect.top + targetRect.height() / 2.0f
    targetView.translationX = playerCenterX - targetCenterX
    targetView.translationY = playerCenterY - targetCenterY
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
