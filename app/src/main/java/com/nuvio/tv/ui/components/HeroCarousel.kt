package com.nuvio.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_INTERVAL_MS = 5000L
private val YEAR_REGEX = Regex("""\b\d{4}\b""")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var activeIndex by remember { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(activeIndex, isFocused) {
        if (!isFocused) return@LaunchedEffect
        items.getOrNull(activeIndex)?.let { onItemFocus(it) }
    }

    // Auto-advance when not focused
    LaunchedEffect(isFocused, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(AUTO_ADVANCE_INTERVAL_MS)
            if (!isFocused) {
                activeIndex = (activeIndex + 1) % items.size
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onFocusChanged { isFocused = it.hasFocus || it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (activeIndex > 0) {
                                activeIndex--
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (activeIndex < items.size - 1) {
                                activeIndex++
                                true
                            } else false
                        }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onItemClick(items[activeIndex])
                    true
                } else {
                    false
                }
            }
    ) {
        // Crossfade between slides
        Crossfade(
            targetState = activeIndex,
            animationSpec = tween(500),
            label = "heroSlide"
        ) { index ->
            val item = items.getOrNull(index) ?: return@Crossfade
            HeroCarouselSlide(item = item)
        }

        // Indicator dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { index, _ ->
                val isActive = index == activeIndex
                val dotWidth = when {
                    isFocused && isActive -> 32.dp
                    isActive -> 24.dp
                    else -> 12.dp
                }
                val dotHeight = if (isFocused && isActive) 6.dp else 4.dp
                Box(
                    modifier = Modifier
                        .width(dotWidth)
                        .height(dotHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                isFocused && isActive -> NuvioColors.FocusRing
                                isFocused -> NuvioColors.FocusRing.copy(alpha = 0.4f)
                                isActive -> NuvioColors.FocusRing
                                else -> Color.White.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroCarouselSlide(
    item: MetaPreview
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val requestWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val requestHeightPx = remember(density) { with(density) { 400.dp.roundToPx() } }

    val bgColor = NuvioColors.Background
    val bottomGradient = remember(bgColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.3f to Color.Transparent,
                0.6f to bgColor.copy(alpha = 0.5f),
                0.8f to bgColor.copy(alpha = 0.85f),
                1.0f to bgColor
            )
        )
    }
    val leftGradient = remember(bgColor) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to bgColor.copy(alpha = 0.98f),
                0.16f to bgColor.copy(alpha = 0.88f),
                0.34f to bgColor.copy(alpha = 0.56f),
                0.56f to bgColor.copy(alpha = 0.20f),
                0.72f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Background image
        // Background image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.background)
                .crossfade(false)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build(),
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )

        // Bottom gradient for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomGradient)
        )

        // Left gradient for extra text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(leftGradient)
        )

        // Content overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 48.dp, end = 48.dp)
                .fillMaxWidth(0.5f)
        ) {
            // Title logo or text title
            if (!item.logo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.logo)
                        .crossfade(false)
                        .size(width = requestWidthPx, height = with(density) { 80.dp.roundToPx() })
                        .build(),
                    contentDescription = item.name,
                    modifier = Modifier
                        .height(80.dp)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Meta info row: IMDB rating + year + genres
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.imdbRating?.let { rating ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val context = LocalContext.current
                        val imdbModel = remember {
                            ImageRequest.Builder(context)
                                .data(com.nuvio.tv.R.raw.imdb_logo_2016)
                                .decoderFactory(SvgDecoder.Factory())
                                .build()
                        }
                        AsyncImage(
                            model = imdbModel,
                            contentDescription = "IMDB",
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                        val ratingText = remember(rating) { String.format("%.1f", rating) }
                        Text(
                            text = ratingText,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                val releaseYear = remember(item.releaseInfo) {
                    item.releaseInfo?.let { releaseInfo ->
                        YEAR_REGEX.find(releaseInfo)?.value ?: releaseInfo.split("-").firstOrNull()
                    }?.trim()?.takeIf { it.isNotEmpty() }
                }
                releaseYear?.let { year ->
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            if (item.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item.genres.take(3).forEach { genre ->
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            item.description?.let { desc ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
