package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun CommentsSection(
    comments: List<TraktCommentReview>,
    isLoading: Boolean,
    error: String?,
    upFocusRequester: FocusRequester? = null,
    onRetry: () -> Unit,
    onCommentClick: (TraktCommentReview) -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)
    val firstItemFocusRequester = remember { FocusRequester() }
    val upFocusModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.trakt_logo_wordmark),
                contentDescription = "Trakt",
                modifier = Modifier
                    .offset(y = (-1).dp)
                    .width(47.dp)
                    .height(20.dp),
                colorFilter = ColorFilter.tint(NuvioColors.TextPrimary)
            )
            Text(
                text = stringResource(R.string.detail_comments_title),
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.detail_comments_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))

        when {
            isLoading -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer { firstItemFocusRequester },
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(3) { index ->
                        LoadingCommentCard(
                            shape = cardShape,
                            modifier = Modifier.then(
                                if (index == 0) {
                                    Modifier
                                        .focusRequester(firstItemFocusRequester)
                                        .then(upFocusModifier)
                                } else {
                                    Modifier.then(upFocusModifier)
                                }
                            )
                        )
                    }
                }
            }

            !error.isNullOrBlank() -> {
                Column(
                    modifier = Modifier.padding(horizontal = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .focusRequester(firstItemFocusRequester)
                            .then(upFocusModifier),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            comments.isEmpty() -> {
                Text(
                    text = stringResource(R.string.detail_comments_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp)
                )
            }

            else -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer { firstItemFocusRequester },
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(comments, key = { it.id }) { review ->
                        val isFirst = comments.firstOrNull()?.id == review.id
                        CommentCard(
                            review = review,
                            shape = cardShape,
                            modifier = Modifier
                                .then(
                                    if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                                )
                                .then(upFocusModifier),
                            onClick = { onCommentClick(review) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CommentCard(
    review: TraktCommentReview,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bodyText = if (review.hasSpoilerContent) {
        stringResource(R.string.detail_comments_spoiler_hidden)
    } else {
        review.comment
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(360.dp)
            .height(230.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = review.authorDisplayName,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (review.review) {
                    CommentChip(text = stringResource(R.string.detail_comments_badge_review))
                }
                if (review.hasSpoilerContent) {
                    CommentChip(text = stringResource(R.string.detail_comments_badge_spoiler))
                }
                review.rating?.let { rating ->
                    CommentChip(text = stringResource(R.string.detail_comments_badge_rating, rating))
                }
            }

            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = NuvioColors.TextSecondary,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Text(
                text = stringResource(R.string.detail_comments_likes, review.likes),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CommentChip(text: String) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .background(
                color = NuvioColors.BackgroundElevated,
                shape = shape
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioColors.TextPrimary,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun CommentOverlay(
    review: TraktCommentReview,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }
    val mainContentFocusRequester = remember { FocusRequester() }
    val commentScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var isSpoilerRevealed by rememberSaveable(review.id) { mutableStateOf(!review.hasSpoilerContent) }
    val commentText = if (review.hasSpoilerContent && !isSpoilerRevealed) {
        stringResource(R.string.detail_comments_spoiler_hidden)
    } else {
        review.comment
    }
    val commentStyle = readerCommentStyle(commentText.length)
    val overlayLabels = buildList {
        if (review.review) add(stringResource(R.string.detail_comments_badge_review))
        if (review.hasSpoilerContent) add(stringResource(R.string.detail_comments_badge_spoiler))
        review.rating?.let { add(stringResource(R.string.detail_comments_badge_rating, it)) }
    }

    LaunchedEffect(review.id) {
        mainContentFocusRequester.requestFocus()
        withFrameNanos { }
        commentScrollState.scrollTo(0)
        withFrameNanos { }
        commentScrollState.scrollTo(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF070707),
                            Color(0xFF101010),
                            Color(0xFF151515)
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.detail_comments_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.82f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = review.authorDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        review.authorUsername
                            ?.takeIf { it.isNotBlank() }
                            ?.let { username ->
                                Text(
                                    text = "@$username",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.62f)
                                )
                            }
                        if (overlayLabels.isNotEmpty()) {
                            OverlayMetaRow(labels = overlayLabels)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(commentScrollState)
                            .focusRequester(mainContentFocusRequester)
                            .focusable()
                            .focusProperties {
                                up = primaryFocusRequester
                            }
                            .onPreviewKeyEvent { event ->
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == Key.DirectionDown && commentScrollState.value < commentScrollState.maxValue -> {
                                        coroutineScope.launch {
                                            commentScrollState.animateScrollTo(
                                                (commentScrollState.value + 260).coerceAtMost(commentScrollState.maxValue)
                                            )
                                        }
                                        true
                                    }
                                    event.key == Key.DirectionUp && commentScrollState.value > 0 -> {
                                        coroutineScope.launch {
                                            commentScrollState.animateScrollTo(
                                                (commentScrollState.value - 260).coerceAtLeast(0)
                                            )
                                        }
                                        true
                                    }
                                    !isSpoilerRevealed && (
                                        event.key == Key.DirectionCenter ||
                                            event.key == Key.Enter ||
                                            event.key == Key.NumPadEnter
                                        ) -> {
                                        isSpoilerRevealed = true
                                        true
                                    }
                                    else -> false
                                }
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = commentText,
                            style = commentStyle,
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.detail_comments_likes, review.likes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                        if (review.hasSpoilerContent && !isSpoilerRevealed) {
                            Text(
                                text = stringResource(R.string.detail_comments_reveal_spoiler_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.62f)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(168.dp)
                    .padding(top = 6.dp, end = 4.dp)
                    .focusRequester(primaryFocusRequester)
                    .focusable()
                    .focusProperties {
                        down = mainContentFocusRequester
                    },
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trakt_logo_wordmark),
                    contentDescription = "Trakt",
                    modifier = Modifier.width(168.dp),
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.92f))
                )
                Text(
                    text = stringResource(R.string.detail_comments_back_hint),
                    modifier = Modifier.padding(start = 18.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.34f)
                )
            }
        }
    }
}

@Composable
private fun OverlayMetaRow(labels: List<String>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Color.White.copy(alpha = 0.42f), CircleShape)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun readerCommentStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 160 -> typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 48.sp)
        length <= 280 -> typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 38.sp)
        length <= 420 -> typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 31.sp)
        length <= 650 -> typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp)
        length <= 900 -> typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 23.sp)
        else -> typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp)
    }
}

private fun commentMaxLines(length: Int): Int = when {
    length <= 160 -> 7
    length <= 280 -> 10
    length <= 420 -> 13
    length <= 650 -> 17
    length <= 900 -> 22
    else -> 28
}

@Composable
private fun LoadingCommentCard(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(360.dp)
            .height(230.dp)
            .background(
                color = NuvioColors.BackgroundCard,
                shape = shape
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(18.dp)
                .background(NuvioColors.BackgroundElevated, shape = shape)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(24.dp)
                        .background(NuvioColors.BackgroundElevated, shape = shape)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(NuvioColors.BackgroundElevated, shape = shape)
        )
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(16.dp)
                .background(NuvioColors.BackgroundElevated, shape = shape)
        )
    }
}
