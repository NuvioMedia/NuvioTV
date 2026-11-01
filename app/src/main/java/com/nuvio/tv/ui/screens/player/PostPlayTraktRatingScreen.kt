@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.TRAKT_MAX_RATING
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val POST_PLAY_RATING_STAR_COUNT = TRAKT_MAX_RATING / 2
private val POST_PLAY_RATING_CELL = 72.dp
private val POST_PLAY_RATING_CELL_GAP = 8.dp
private val POST_PLAY_RATING_STAR = 48.dp

@Composable
internal fun PostPlayTraktRatingScreen(
    state: PostPlayTraktRatingUiState,
    title: String,
    logo: String?,
    backdrop: String?,
    releaseYear: String?,
    onStep: (Int) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectorFocusRequester = remember { FocusRequester() }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = tween(360)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier
    ) {
        LaunchedEffect(Unit) {
            // Let the enter animation attach the selector before moving focus into it.
            delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong() / 2)
            runCatching { selectorFocusRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (!backdrop.isNullOrBlank()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isRtl) {
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.2f),
                                0.45f to Color.Black.copy(alpha = 0.78f),
                                1f to Color.Black.copy(alpha = 0.94f)
                            )
                        } else {
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.94f),
                                0.55f to Color.Black.copy(alpha = 0.78f),
                                1f to Color.Black.copy(alpha = 0.2f)
                            )
                        }
                    )
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = NuvioTheme.spacing.screen.overscanHorizontal,
                        bottom = NuvioTheme.spacing.screen.overscanVertical
                    )
            ) {
                PostPlayRatingHeader(title = title, logo = logo, releaseYear = releaseYear)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxl))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.player_post_play_rating_title),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.alignByBaseline()
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                    Box(modifier = Modifier.alignByBaseline()) {
                        PostPlayRatingStatus(state = state)
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PostPlayRatingSelector(
                        state = state,
                        isRtl = isRtl,
                        focusRequester = selectorFocusRequester,
                        onStep = onStep,
                        onSubmit = onSubmit,
                        onSkip = onSkip
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xl))
                    PostPlayRatingScore(rating = state.selectedRating)
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
                PostPlayRatingHints()
            }
        }
    }
}

@Composable
private fun PostPlayRatingHeader(
    title: String,
    logo: String?,
    releaseYear: String?
) {
    Text(
        text = stringResource(R.string.player_post_play_rating_eyebrow).uppercase(),
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !logoFailed) {
        AsyncImage(
            model = logo,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .heightIn(max = 96.dp)
        )
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.55f)
        )
    }
    if (!releaseYear.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = releaseYear,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PostPlayRatingSelector(
    state: PostPlayTraktRatingUiState,
    isRtl: Boolean,
    focusRequester: FocusRequester,
    onStep: (Int) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val description = stringResource(R.string.player_post_play_rating_cd, state.selectedRating)
    val activeIndex = (state.selectedRating + 1) / 2 - 1
    val cellStep = POST_PLAY_RATING_CELL + POST_PLAY_RATING_CELL_GAP
    val highlightOffset by animateDpAsState(
        targetValue = cellStep * activeIndex,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "postPlayRatingHighlight"
    )
    val highlightAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(160),
        label = "postPlayRatingHighlightAlpha"
    )

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .semantics { contentDescription = description }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                            val forward = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                            onStep(if (forward != isRtl) 1 else -1)
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (native.action == AndroidKeyEvent.ACTION_UP) onSubmit()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BACK -> {
                        if (native.action == AndroidKeyEvent.ACTION_UP) onSkip()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        Box(
            modifier = Modifier
                .offset(x = if (isRtl) -highlightOffset else highlightOffset)
                .size(POST_PLAY_RATING_CELL)
                .graphicsLayer { alpha = highlightAlpha }
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), RoundedCornerShape(18.dp))
        )
        PostPlayRatingStars(rating = state.selectedRating, isRtl = isRtl)
    }
}

@Composable
private fun PostPlayRatingStars(rating: Int, isRtl: Boolean) {
    // Remember the previous value so only a change (not the first frame) bumps the active star.
    var previousRating by remember { mutableIntStateOf(rating) }
    val bumpedStar = if (rating != previousRating) (rating + 1) / 2 - 1 else -1
    LaunchedEffect(rating) { previousRating = rating }

    Row(horizontalArrangement = Arrangement.spacedBy(POST_PLAY_RATING_CELL_GAP)) {
        repeat(POST_PLAY_RATING_STAR_COUNT) { index ->
            // Each star covers two rating points, so odd ratings render a half star.
            val fill by animateFloatAsState(
                targetValue = ((rating - index * 2) / 2f).coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 140),
                label = "postPlayRatingStarFill"
            )
            val bump = remember { Animatable(1f) }
            LaunchedEffect(rating) {
                if (index == bumpedStar) {
                    bump.snapTo(1.18f)
                    bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                } else if (bump.value != 1f) {
                    // A fast key repeat can cancel a bump halfway; settle that star back.
                    bump.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                }
            }
            Box(
                modifier = Modifier
                    .size(POST_PLAY_RATING_CELL)
                    .graphicsLayer {
                        scaleX = bump.value
                        scaleY = bump.value
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.StarOutline,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.32f),
                    modifier = Modifier.size(POST_PLAY_RATING_STAR)
                )
                if (fill > 0f) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Rating,
                        modifier = Modifier
                            .size(POST_PLAY_RATING_STAR)
                            .drawWithContent {
                                val clipWidth = size.width * fill
                                if (isRtl) {
                                    clipRect(left = size.width - clipWidth) { this@drawWithContent.drawContent() }
                                } else {
                                    clipRect(right = clipWidth) { this@drawWithContent.drawContent() }
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun PostPlayRatingScore(rating: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = rating,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
            label = "postPlayRatingScore",
            modifier = Modifier.alignByBaseline()
        ) { value ->
            Text(
                text = value.toString(),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = stringResource(R.string.player_post_play_rating_out_of),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 18.sp,
            modifier = Modifier.alignByBaseline()
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = stringResource(postPlayRatingWordRes(rating)),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
    }
}

@Composable
private fun PostPlayRatingStatus(state: PostPlayTraktRatingUiState) {
    val line = when {
        state.status == PostPlayTraktRatingStatus.SAVING -> PostPlayRatingStatusLine.SAVING
        state.status == PostPlayTraktRatingStatus.FAILED -> PostPlayRatingStatusLine.FAILED
        state.status == PostPlayTraktRatingStatus.SAVED -> PostPlayRatingStatusLine.SAVED
        state.isSelectionSaved -> PostPlayRatingStatusLine.CURRENT
        else -> PostPlayRatingStatusLine.SYNCS
    }
    AnimatedContent(
        targetState = line,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        label = "postPlayRatingStatus"
    ) { current ->
        val (text, color) = when (current) {
            PostPlayRatingStatusLine.SYNCS ->
                stringResource(R.string.player_post_play_rating_syncs) to Color.White.copy(alpha = 0.6f)
            PostPlayRatingStatusLine.CURRENT ->
                stringResource(R.string.player_post_play_rating_current) to Color.White.copy(alpha = 0.75f)
            PostPlayRatingStatusLine.SAVING ->
                stringResource(R.string.action_saving) to Color.White.copy(alpha = 0.75f)
            PostPlayRatingStatusLine.SAVED ->
                stringResource(R.string.player_post_play_rating_saved) to Color.White
            PostPlayRatingStatusLine.FAILED ->
                stringResource(R.string.player_post_play_rating_failed) to NuvioTheme.colors.Error
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (current == PostPlayRatingStatusLine.SAVED || current == PostPlayRatingStatusLine.CURRENT) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
            }
            Text(text = text, color = color, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PostPlayRatingHints() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PostPlayRatingKeyIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
            PostPlayRatingKeyIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
            PostPlayRatingHintLabel(stringResource(R.string.player_post_play_rating_hint_adjust))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(Color.White, CircleShape)
                    .padding(horizontal = NuvioTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "OK", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            PostPlayRatingHintLabel(stringResource(R.string.player_post_play_rating_hint_save))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PostPlayRatingKeyIcon(Icons.AutoMirrored.Rounded.Undo)
            PostPlayRatingHintLabel(stringResource(R.string.player_post_play_rating_hint_skip))
        }
    }
}

@Composable
private fun PostPlayRatingKeyIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .border(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.25f), CircleShape)
            .background(Color.White.copy(alpha = 0.06f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PostPlayRatingHintLabel(text: String) {
    Text(text = text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
}

private enum class PostPlayRatingStatusLine { SYNCS, CURRENT, SAVING, SAVED, FAILED }

private fun postPlayRatingWordRes(rating: Int): Int = when {
    rating <= 2 -> R.string.player_post_play_rating_word_awful
    rating <= 4 -> R.string.player_post_play_rating_word_bad
    rating == 5 -> R.string.player_post_play_rating_word_meh
    rating == 6 -> R.string.player_post_play_rating_word_okay
    rating == 7 -> R.string.player_post_play_rating_word_good
    rating == 8 -> R.string.player_post_play_rating_word_great
    rating == 9 -> R.string.player_post_play_rating_word_superb
    else -> R.string.player_post_play_rating_word_masterpiece
}
