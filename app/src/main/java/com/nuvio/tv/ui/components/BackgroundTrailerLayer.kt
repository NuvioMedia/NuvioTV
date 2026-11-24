package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.nuvio.tv.core.player.BackgroundTrailerController
import com.nuvio.tv.core.player.BackgroundTrailerRequest
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.max

private const val BOUNDS_ANIMATION_MS = 450

@Composable
fun BackgroundTrailerLayer(
    controller: BackgroundTrailerController,
    modifier: Modifier = Modifier
) {
    val request = controller.activeRequest ?: return
    val latestRequest by rememberUpdatedState(request)
    var layerRect by remember { mutableStateOf<Rect?>(null) }
    var fromRect by remember { mutableStateOf<Rect?>(null) }
    var toRect by remember { mutableStateOf<Rect?>(null) }
    var fromZoom by remember { mutableStateOf(1f) }
    var toZoom by remember { mutableStateOf(1f) }
    val progress = remember { Animatable(1f) }
    var shownVideoUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        onDispose { controller.clearRendered() }
    }

    LaunchedEffect(request.videoUrl, request.bounds, request.overscanZoom, layerRect) {
        val full = layerRect ?: return@LaunchedEffect
        val targetRect = request.bounds ?: full
        val targetZoom = request.overscanZoom
        val currentTo = toRect
        if (currentTo == null || shownVideoUrl != request.videoUrl) {
            shownVideoUrl = request.videoUrl
            fromRect = targetRect
            toRect = targetRect
            fromZoom = targetZoom
            toZoom = targetZoom
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (currentTo == targetRect && toZoom == targetZoom) return@LaunchedEffect
        fromRect = lerpRect(fromRect ?: currentTo, currentTo, progress.value)
        fromZoom = lerp(fromZoom, toZoom, progress.value)
        toRect = targetRect
        toZoom = targetZoom
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = BOUNDS_ANIMATION_MS, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val rect = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
                if (layerRect != rect) layerRect = rect
            }
            .background(NuvioTheme.colors.Background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val full = layerRect
                    val start = fromRect
                    val end = toRect
                    if (full != null && start != null && end != null) {
                        val rect = lerpRect(start, end, progress.value)
                        clip = true
                        shape = LayerRectShape(rect.translate(-full.left, -full.top))
                    } else {
                        clip = false
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val full = layerRect
                        val start = fromRect
                        val end = toRect
                        if (full != null && start != null && end != null && full.width > 0f && full.height > 0f) {
                            val rect = lerpRect(start, end, progress.value)
                            val cover = max(rect.width / full.width, rect.height / full.height)
                            val scale = cover * lerp(fromZoom, toZoom, progress.value)
                            scaleX = scale
                            scaleY = scale
                            translationX = rect.center.x - full.center.x
                            translationY = rect.center.y - full.center.y
                        }
                    }
            ) {
                TrailerPlayer(
                    trailerUrl = request.videoUrl,
                    trailerAudioUrl = request.audioUrl,
                    isPlaying = request.isPlaying,
                    isPaused = request.isPaused,
                    onEnded = { latestRequest.onEnded() },
                    onError = { latestRequest.onEnded() },
                    onFirstFrameRendered = {
                        controller.markRendered(latestRequest.videoUrl)
                        latestRequest.onFirstFrameRendered()
                    },
                    muted = request.muted,
                    seekRequestToken = request.seekRequestToken,
                    seekDeltaMs = request.seekDeltaMs,
                    onProgressChanged = { position, duration -> latestRequest.onProgressChanged(position, duration) },
                    onRemoteKey = { keyCode, action, repeatCount -> latestRequest.onRemoteKey(keyCode, action, repeatCount) },
                    cropToFill = request.cropToFill,
                    autoCropLetterbox = request.autoCropLetterbox,
                    detectLetterbox = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = latestRequest.alpha()
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                )
            }
        }
    }
}

@Composable
fun BackgroundTrailerRequestEffect(
    controller: BackgroundTrailerController,
    owner: String,
    request: BackgroundTrailerRequest?
) {
    SideEffect { controller.update(owner, request) }
    DisposableEffect(controller, owner) {
        onDispose { controller.release(owner) }
    }
}

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    offset = Offset(lerp(start.left, stop.left, fraction), lerp(start.top, stop.top, fraction)),
    size = Size(lerp(start.width, stop.width, fraction), lerp(start.height, stop.height, fraction))
)

private class LayerRectShape(private val rect: Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(rect)
}
