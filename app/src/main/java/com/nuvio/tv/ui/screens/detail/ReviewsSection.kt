package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaReview
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ReviewsSection(
    reviews: List<MetaReview>,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    title: String = "Reviews",
    upFocusRequester: FocusRequester? = null
) {
    val hasTitle = title.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
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

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer { firstItemFocusRequester },
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = reviews,
                        key = { index, item -> "${item.id}|$index" }
                    ) { index, review ->
                        var isCardFocused by remember(review.id) { mutableStateOf(false) }
                        var isScrollPaused by rememberSaveable(review.id) { mutableStateOf(false) }
                        val cardModifier = Modifier
                            .width(440.dp)
                            .heightIn(min = 220.dp)
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
                                isCardFocused = state.isFocused || state.hasFocus
                            }

                        Card(
                            onClick = {
                                isScrollPaused = !isScrollPaused
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = review.author,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = NuvioColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    review.rating?.let { rating ->
                                        Text(
                                            text = String.format("%.1f/10", rating),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NuvioColors.TextTertiary
                                        )
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

                                AutoScrollingReviewText(
                                    text = review.content,
                                    isFocused = isCardFocused,
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

@Composable
private fun AutoScrollingReviewText(
    text: String,
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
            scrollState.scrollTo(0)
            shouldDelayOnStart = true
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

        val pixelsPerSecond = 20f
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
            .height(128.dp)
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
