package com.nuvio.tv.ui.screens.ambient

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BACKDROP_FADE_IN_MS = 900
private const val TITLE_DELAY_MS = 250L
private const val TITLE_FADE_IN_MS = 500
private const val GENRES_DELAY_MS = 350L
private const val GENRES_FADE_IN_MS = 450
private const val SLIDE_HOLD_MS = 10_000L
private const val GENRES_FADE_OUT_MS = 250
private const val TITLE_FADE_OUT_MS = 350
private const val BACKDROP_FADE_OUT_MS = 700
private const val BLACK_HOLD_MS = 300L
private const val ALL_FAILED_RETRY_MS = 15_000L
private const val EMPTY_POLL_MS = 500L
private const val MOTION_DURATION_MS = BACKDROP_FADE_IN_MS + TITLE_DELAY_MS.toInt() + TITLE_FADE_IN_MS +
    SLIDE_HOLD_MS.toInt() + GENRES_FADE_OUT_MS + TITLE_FADE_OUT_MS + BACKDROP_FADE_OUT_MS

private class LoadedSlide(
    val slide: AmbientSlide,
    val backdrop: ImageBitmap,
    val logo: ImageBitmap?
)

@Composable
fun AmbientSlideshow(
    slides: List<AmbientSlide>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val titleOffsetPx = with(density) { 18.dp.toPx() }
    val genresOffsetPx = with(density) { 12.dp.toPx() }

    var current by remember { mutableStateOf<LoadedSlide?>(null) }
    val backdropAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    val genresAlpha = remember { Animatable(0f) }
    val motionProgress = remember { Animatable(0f) }
    var motion by remember { mutableStateOf(nextAmbientMotion(null)) }

    val latestSlides by rememberUpdatedState(slides)

    LaunchedEffect(Unit) {
        coroutineScope {
            var index = 0
            var misses = 0
            var pending: Deferred<LoadedSlide?>? = null
            while (true) {
                val available = latestSlides
                if (available.isEmpty()) {
                    pending?.cancel()
                    pending = null
                    delay(EMPTY_POLL_MS)
                    continue
                }
                val loaded = (pending ?: async {
                    loadSlide(context, available[index % available.size], screenWidthPx, screenHeightPx)
                }).await()
                index += 1
                val upcoming = latestSlides
                pending = upcoming.takeIf { it.isNotEmpty() }?.let { list ->
                    val next = list[index % list.size]
                    async { loadSlide(context, next, screenWidthPx, screenHeightPx) }
                }
                if (loaded == null) {
                    misses += 1
                    if (misses >= available.size) {
                        misses = 0
                        delay(ALL_FAILED_RETRY_MS)
                    }
                    continue
                }
                misses = 0
                current = loaded
                motion = nextAmbientMotion(motion)
                motionProgress.snapTo(0f)
                val motionJob = launch {
                    motionProgress.animateTo(1f, tween(MOTION_DURATION_MS, easing = LinearEasing))
                }
                backdropAlpha.animateTo(1f, tween(BACKDROP_FADE_IN_MS, easing = FastOutSlowInEasing))
                delay(TITLE_DELAY_MS)
                val genresJob = launch {
                    delay(GENRES_DELAY_MS)
                    genresAlpha.animateTo(1f, tween(GENRES_FADE_IN_MS, easing = LinearOutSlowInEasing))
                }
                titleAlpha.animateTo(1f, tween(TITLE_FADE_IN_MS, easing = LinearOutSlowInEasing))
                delay(SLIDE_HOLD_MS)
                genresJob.cancel()
                genresAlpha.animateTo(0f, tween(GENRES_FADE_OUT_MS, easing = FastOutSlowInEasing))
                titleAlpha.animateTo(0f, tween(TITLE_FADE_OUT_MS, easing = FastOutSlowInEasing))
                backdropAlpha.animateTo(0f, tween(BACKDROP_FADE_OUT_MS, easing = FastOutSlowInEasing))
                motionJob.cancel()
                delay(BLACK_HOLD_MS)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
    ) {
        val loaded = current ?: return@Box
        Image(
            bitmap = loaded.backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = backdropAlpha.value
                    applyAmbientMotion(motion, motionProgress.value)
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backdropAlpha.value }
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.7f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 72.dp, end = 72.dp, bottom = 64.dp)
                .widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val titleModifier = Modifier.graphicsLayer {
                alpha = titleAlpha.value
                translationY = (1f - titleAlpha.value) * titleOffsetPx
            }
            if (loaded.logo != null) {
                Image(
                    bitmap = loaded.logo,
                    contentDescription = loaded.slide.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter,
                    modifier = titleModifier
                        .heightIn(max = 150.dp)
                        .widthIn(max = 460.dp)
                )
            } else {
                Text(
                    text = loaded.slide.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier
                )
            }
            if (loaded.slide.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = loaded.slide.genres.joinToString("  •  "),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        alpha = genresAlpha.value
                        translationY = (1f - genresAlpha.value) * genresOffsetPx
                    }
                )
            }
        }
    }
}

private suspend fun loadSlide(
    context: Context,
    slide: AmbientSlide,
    widthPx: Int,
    heightPx: Int
): LoadedSlide? = coroutineScope {
    val logo = slide.logoUrl?.let { url -> async { loadBitmap(context, url, null) } }
    val backdrop = loadBitmap(context, slide.backdropUrl, widthPx to heightPx) ?: run {
        logo?.cancel()
        return@coroutineScope null
    }
    LoadedSlide(slide, backdrop, logo?.await())
}

private suspend fun loadBitmap(
    context: Context,
    url: String,
    targetSize: Pair<Int, Int>?
): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .apply { targetSize?.let { (width, height) -> size(width, height) } }
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return runCatching { result.image.toBitmap().asImageBitmap() }.getOrNull()
}
