package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaReview
import com.nuvio.tv.domain.model.MetaReviewSource
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalTextApi::class)
@Composable
fun ReviewsSection(
    reviews: List<MetaReview>,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    title: String = "Reviews",
    isSeriesContent: Boolean = false,
    enableExpandableCards: Boolean = false,
    upFocusRequester: FocusRequester? = null,
    onReviewFocused: ((Int) -> Unit)? = null,
    onExpandedReviewOverlayChanged: (ReviewOverlayState?) -> Unit = {}
) {
    val hasTitle = title.isNotBlank()

    DisposableEffect(Unit) {
        onDispose {
            onExpandedReviewOverlayChanged(null)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false }
            .zIndex(4f)
            .padding(top = if (hasTitle) 14.dp else 6.dp, bottom = 8.dp)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        }

        when {
            isLoading -> {
                Text(
                    text = stringResource(R.string.reviews_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }

            !error.isNullOrBlank() -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }

            reviews.isEmpty() -> {
                Text(
                    text = stringResource(R.string.reviews_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }

            else -> {
                val firstItemFocusRequester = remember { FocusRequester() }
                var activePopupReviewKey by remember { mutableStateOf<String?>(null) }
                var settledPopupReviewKey by remember { mutableStateOf<String?>(null) }
                val reviewsListState = rememberLazyListState()
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                val cardWidth = 440.dp
                val baseCardHeight = 220.dp
                val cardHorizontalPadding = 14.dp
                val baseTextHeight = 128.dp
                val rowViewportHeight = baseCardHeight + 12.dp
                val maxCardHeight = (screenHeight - 72.dp).coerceAtLeast(220.dp)
                val maxTextHeight = (maxCardHeight - 90.dp).coerceAtLeast(baseTextHeight)
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val popupAnchors = remember { mutableStateMapOf<String, ReviewPopupAnchor>() }
                val spoilerRevealStates = remember { mutableStateMapOf<String, Boolean>() }
                val scrollPauseStates = remember { mutableStateMapOf<String, Boolean>() }
                val measuredTextHeights = remember { mutableStateMapOf<String, androidx.compose.ui.unit.Dp>() }
                val activePopupReview = remember(reviews, activePopupReviewKey) {
                    reviews.firstOrNull { "${it.source}:${it.id}" == activePopupReviewKey }
                }
                val activePopupAnchor = activePopupReviewKey?.let { popupAnchors[it] }
                val activeIsPaused = activePopupReviewKey?.let { scrollPauseStates[it] } ?: false
                val activeSpoilerRevealed = activePopupReview?.let { review ->
                    spoilerRevealStates[activePopupReviewKey] ?: !review.hasSpoiler
                } ?: true
                val activeMeasuredTextHeight = activePopupReviewKey?.let { measuredTextHeights[it] } ?: baseTextHeight
                val activeCanExpand = enableExpandableCards &&
                    activePopupReview != null &&
                    activeSpoilerRevealed &&
                    activeMeasuredTextHeight > baseTextHeight + 1.dp

                LaunchedEffect(activePopupReviewKey, activeCanExpand, reviewsListState.isScrollInProgress) {
                    settledPopupReviewKey = null
                    val key = activePopupReviewKey
                    if (key != null && activeCanExpand && !reviewsListState.isScrollInProgress) {
                        delay(300L)
                        if (activePopupReviewKey == key && activeCanExpand && !reviewsListState.isScrollInProgress) {
                            settledPopupReviewKey = key
                        }
                    }
                }

                val shouldRenderPopupOverlay = activePopupReviewKey != null &&
                    activePopupAnchor != null &&
                    activeCanExpand &&
                    settledPopupReviewKey == activePopupReviewKey &&
                    !reviewsListState.isScrollInProgress
                val activeExpandedTextTargetHeight = activeMeasuredTextHeight.coerceAtMost(maxTextHeight)
                val activeAnimatedTextHeight by animateDpAsState(
                    targetValue = if (shouldRenderPopupOverlay) {
                        activeExpandedTextTargetHeight
                    } else {
                        baseTextHeight
                    },
                    animationSpec = tween(
                        durationMillis = 680,
                        easing = FastOutSlowInEasing
                    ),
                    label = "activeReviewTextHeight"
                )
                val activePopupTransitionAlpha by animateFloatAsState(
                    targetValue = if (shouldRenderPopupOverlay) 1f else 0f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "activeReviewPopupTransitionAlpha"
                )
                val activeCardExtraHeight = (activeAnimatedTextHeight - baseTextHeight).coerceAtLeast(0.dp)
                val activeExpandedCardHeight = baseCardHeight + activeCardExtraHeight
                val activeCardExtraHeightPx = with(density) { activeCardExtraHeight.roundToPx() }
                val expansionUpWeight = if (isSeriesContent) 1f else 1.14f
                val expansionDownWeight = if (isSeriesContent) 1.6f else 1f
                val expansionOffsetDivisor = expansionUpWeight + expansionDownWeight

                SideEffect {
                    onExpandedReviewOverlayChanged(
                        if (shouldRenderPopupOverlay && activePopupReview != null && activePopupAnchor != null) {
                            val overlayKey = activePopupReviewKey ?: return@SideEffect
                            ReviewOverlayState(
                                review = activePopupReview,
                                isSpoilerRevealed = activeSpoilerRevealed,
                                viewportHeight = activeAnimatedTextHeight,
                                isPaused = activeIsPaused,
                                reviewKey = overlayKey,
                                x = activePopupAnchor.x,
                                y = activePopupAnchor.y - ((activeCardExtraHeightPx * expansionUpWeight) / expansionOffsetDivisor).roundToInt(),
                                width = cardWidth,
                                height = activeExpandedCardHeight,
                                alpha = activePopupTransitionAlpha,
                                onTogglePause = {
                                    val current = scrollPauseStates[overlayKey] ?: false
                                    scrollPauseStates[overlayKey] = !current
                                }
                            )
                        } else {
                            null
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowViewportHeight)
                        .graphicsLayer { clip = false }
                        .zIndex(if (shouldRenderPopupOverlay) 2f else 0f)
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { clip = false }
                            .onPreviewKeyEvent { keyEvent ->
                                val nativeEvent = keyEvent.nativeKeyEvent
                                if (nativeEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                                    when (nativeEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            activePopupReviewKey = null
                                            settledPopupReviewKey = null
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                                        AndroidKeyEvent.KEYCODE_ENTER,
                                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                            val overlayKey = activePopupReviewKey
                                            if (shouldRenderPopupOverlay && overlayKey != null) {
                                                val isPaused = scrollPauseStates[overlayKey] ?: false
                                                scrollPauseStates[overlayKey] = !isPaused
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                    }
                                }
                                false
                            }
                            .focusRestorer { firstItemFocusRequester },
                        state = reviewsListState,
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(
                            items = reviews,
                            key = { _, item -> "${item.source}:${item.id}" }
                        ) { index, review ->
                            val reviewKey = "${review.source}:${review.id}"
                            var isCardFocused by remember(reviewKey) { mutableStateOf(false) }
                            var isScrollPaused by rememberSaveable(reviewKey) {
                                mutableStateOf(scrollPauseStates[reviewKey] ?: false)
                            }
                            var isSpoilerRevealed by rememberSaveable(reviewKey, review.hasSpoiler) {
                                mutableStateOf(spoilerRevealStates[reviewKey] ?: !review.hasSpoiler)
                            }
                            LaunchedEffect(scrollPauseStates[reviewKey]) {
                                val mappedPaused = scrollPauseStates[reviewKey] ?: false
                                if (mappedPaused != isScrollPaused) {
                                    isScrollPaused = mappedPaused
                                }
                            }
                            val textMeasureWidthPx = remember(cardWidth, cardHorizontalPadding, density) {
                                with(density) { (cardWidth - cardHorizontalPadding * 2).roundToPx() }
                            }
                            val measuredTextHeight = remember(review.content, textMeasureWidthPx, bodyStyle, density) {
                                with(density) {
                                    textMeasurer.measure(
                                        text = AnnotatedString(review.content),
                                        style = bodyStyle,
                                        constraints = Constraints(maxWidth = textMeasureWidthPx)
                                    ).size.height.toDp()
                                }
                            }
                            SideEffect {
                                spoilerRevealStates[reviewKey] = isSpoilerRevealed
                                scrollPauseStates[reviewKey] = isScrollPaused
                                measuredTextHeights[reviewKey] = measuredTextHeight
                            }
                            val canExpandCard = enableExpandableCards &&
                                isSpoilerRevealed &&
                                measuredTextHeight > baseTextHeight + 1.dp
                            val shouldRenderPopupForCard = canExpandCard &&
                                isCardFocused &&
                                activePopupReviewKey == reviewKey &&
                                settledPopupReviewKey == reviewKey &&
                                !reviewsListState.isScrollInProgress &&
                                popupAnchors[reviewKey] != null
                            val expandedTextTargetHeight = measuredTextHeight.coerceAtMost(maxTextHeight)
                            val animatedTextHeight by animateDpAsState(
                                targetValue = if (shouldRenderPopupForCard) {
                                    expandedTextTargetHeight
                                } else {
                                    baseTextHeight
                                },
                                animationSpec = tween(
                                    durationMillis = 680,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "reviewTextHeight"
                            )
                            val popupTransitionAlpha by animateFloatAsState(
                                targetValue = if (shouldRenderPopupForCard) 1f else 0f,
                                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                label = "reviewPopupTransitionAlpha"
                            )
                            val baseCardAlpha = 1f - popupTransitionAlpha
                            val cardModifier = Modifier
                                .width(cardWidth)
                                .height(baseCardHeight)
                                .graphicsLayer { alpha = baseCardAlpha }
                                .then(
                                    if (upFocusRequester != null) {
                                        Modifier.focusProperties { up = upFocusRequester }
                                    } else {
                                        Modifier
                                    }
                                )
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(firstItemFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .onFocusChanged { state ->
                                    val nowFocused = state.isFocused || state.hasFocus
                                    isCardFocused = nowFocused
                                    if (nowFocused) {
                                        activePopupReviewKey = reviewKey
                                        onReviewFocused?.invoke(index)
                                    } else if (activePopupReviewKey == reviewKey) {
                                        activePopupReviewKey = null
                                        settledPopupReviewKey = null
                                    }
                                }

                            Box(
                                modifier = Modifier
                                    .width(cardWidth)
                                    .height(baseCardHeight)
                                    .zIndex(if (shouldRenderPopupForCard) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInRoot()
                                        popupAnchors[reviewKey] = ReviewPopupAnchor(
                                            x = position.x.roundToInt(),
                                            y = position.y.roundToInt()
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    onClick = {
                                        if (review.hasSpoiler && !isSpoilerRevealed) {
                                            isSpoilerRevealed = true
                                            isScrollPaused = false
                                        } else {
                                            isScrollPaused = !isScrollPaused
                                        }
                                        scrollPauseStates[reviewKey] = isScrollPaused
                                    },
                                    modifier = cardModifier,
                                    shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.colors(
                                        containerColor = NuvioColors.BackgroundCard,
                                        focusedContainerColor = NuvioColors.BackgroundCard
                                    ),
                                    border = CardDefaults.border(
                                        focusedBorder = Border(
                                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                    ),
                                    scale = CardDefaults.scale(focusedScale = 1f)
                                ) {
                                    ReviewCardContent(
                                        review = review,
                                        isSpoilerRevealed = isSpoilerRevealed,
                                        viewportHeight = animatedTextHeight,
                                        isFocused = isCardFocused && !shouldRenderPopupForCard,
                                        isPaused = isScrollPaused
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ReviewOverlayState(
    val review: MetaReview,
    val isSpoilerRevealed: Boolean,
    val viewportHeight: androidx.compose.ui.unit.Dp,
    val isPaused: Boolean,
    val reviewKey: String,
    val x: Int,
    val y: Int,
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
    val alpha: Float,
    val onTogglePause: () -> Unit
)

private data class ReviewPopupAnchor(
    val x: Int,
    val y: Int
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExpandedReviewOverlay(
    overlayState: ReviewOverlayState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false }
    ) {
        Card(
            onClick = overlayState.onTogglePause,
            modifier = Modifier
                .offset { IntOffset(overlayState.x, overlayState.y) }
                .width(overlayState.width)
                .height(overlayState.height)
                .graphicsLayer { alpha = overlayState.alpha }
                .zIndex(300f),
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            ReviewCardContent(
                review = overlayState.review,
                isSpoilerRevealed = overlayState.isSpoilerRevealed,
                viewportHeight = overlayState.viewportHeight,
                isFocused = true,
                isPaused = overlayState.isPaused
            )
        }
    }
}

@Composable
private fun ReviewCardContent(
    review: MetaReview,
    isSpoilerRevealed: Boolean,
    viewportHeight: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    isPaused: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = review.author,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            review.rating?.let { rating ->
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    ReviewRatingBadge(rating = rating)
                }
            }

            if (review.hasSpoiler) {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    ReviewSpoilerBadge()
                }
            }

            Box(modifier = Modifier.padding(start = 8.dp)) {
                ReviewSourceBadge(source = review.source)
            }
        }

        val date = (review.updatedAt ?: review.createdAt)?.take(10)
        if (!date.isNullOrBlank()) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
        }

        if (review.hasSpoiler && !isSpoilerRevealed) {
            HiddenSpoilerReviewText()
        } else {
            AutoScrollingReviewText(
                text = review.content,
                viewportHeight = viewportHeight,
                isFocused = isFocused,
                isPaused = isPaused
            )
        }
    }
}

@Composable
private fun ReviewSpoilerBadge() {
    Box(
        modifier = Modifier
            .background(
                color = Color(0xFF4A1919),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = "SPOILER",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFF9A9A)
        )
    }
}

@Composable
private fun ReviewSourceBadge(source: MetaReviewSource) {
    val (label, contentColor, backgroundColor) = when (source) {
        MetaReviewSource.TMDB -> Triple(
            "TMDB",
            Color(0xFF7FCBFF),
            Color(0xFF163247)
        )
        MetaReviewSource.TRAKT -> Triple(
            "TRAKT",
            Color(0xFFF35A5A),
            Color(0xFF351518)
        )
    }

    Box(
        modifier = Modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
private fun ReviewRatingBadge(rating: Double) {
    val contentColor: Color = when {
        rating >= 8.0 -> NuvioColors.Success
        rating >= 6.0 -> NuvioColors.Rating
        else -> NuvioColors.Error
    }

    Box(
        modifier = Modifier
            .background(
                color = contentColor.copy(alpha = 0.18f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = String.format("%.1f/10", rating),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

@Composable
private fun HiddenSpoilerReviewText() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .background(
                color = Color.Black.copy(alpha = 0.84f),
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.reviews_spoiler_hidden),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.reviews_spoiler_reveal_hint),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun AutoScrollingReviewText(
    text: String,
    viewportHeight: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    isPaused: Boolean
) {
    val scrollState = rememberScrollState()
    var shouldDelayOnStart by remember(text) { mutableStateOf(true) }

    LaunchedEffect(text) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(isFocused, text) {
        if (!isFocused) {
            scrollState.stopScroll()
            scrollState.scrollTo(0)
            shouldDelayOnStart = true
        }
    }

    LaunchedEffect(isPaused) {
        if (isPaused) {
            scrollState.stopScroll()
        }
    }

    LaunchedEffect(isFocused, isPaused, text, scrollState.maxValue) {
        if (!isFocused || isPaused) return@LaunchedEffect
        if (scrollState.maxValue <= 0) return@LaunchedEffect

        if (shouldDelayOnStart) {
            delay(4_200L)
            shouldDelayOnStart = false
        }
        val distancePx = (scrollState.maxValue - scrollState.value).coerceAtLeast(0)
        if (distancePx <= 0) return@LaunchedEffect

        val pixelsPerSecond = 28.8f
        val durationMs = ((distancePx / pixelsPerSecond) * 1_000f)
            .roundToInt()
            .coerceAtLeast(800)

        scrollState.animateScrollTo(
            value = scrollState.maxValue,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewportHeight)
            .clipToBounds()
            .verticalScroll(scrollState, enabled = false)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )
    }
}
