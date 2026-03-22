package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun CommentsSection(
    stateKey: String,
    comments: List<TraktCommentReview>,
    isLoading: Boolean,
    error: String?,
    upFocusRequester: FocusRequester? = null,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)
    val firstItemFocusRequester = remember { FocusRequester() }
    val upFocusModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }
    var revealedSpoilerIds by rememberSaveable(stateKey) { mutableStateOf(emptySet<Long>()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.detail_comments_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
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
                            isRevealed = review.id in revealedSpoilerIds,
                            shape = cardShape,
                            modifier = Modifier
                                .then(
                                    if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                                )
                                .then(upFocusModifier),
                            onClick = {
                                if (review.hasSpoilerContent) {
                                    revealedSpoilerIds = if (review.id in revealedSpoilerIds) {
                                        revealedSpoilerIds - review.id
                                    } else {
                                        revealedSpoilerIds + review.id
                                    }
                                }
                            }
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
    isRevealed: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bodyText = if (review.hasSpoilerContent && !isRevealed) {
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
                text = stringResource(
                    R.string.detail_comments_stats,
                    review.likes,
                    review.replies
                ),
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
