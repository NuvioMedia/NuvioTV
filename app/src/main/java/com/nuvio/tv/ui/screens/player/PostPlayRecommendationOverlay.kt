@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.MDBListRatingsRow
import com.nuvio.tv.ui.components.PlayManualOverrideDialog
import com.nuvio.tv.ui.components.SynopsisDescription
import com.nuvio.tv.ui.components.SynopsisOverlay
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun PostPlayRecommendationOverlay(
    state: PostPlayRecommendationUiState,
    currentTitle: String,
    showManualPlayOption: Boolean,
    playFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onStopTrailer: () -> Unit,
    onPlay: (PostPlayRecommendation) -> Unit,
    onPlayManually: (PostPlayRecommendation) -> Unit,
    onOpenDetails: (PostPlayRecommendation) -> Unit,
    onPlayTrailer: () -> Unit,
    onTrailerEnded: () -> Unit,
    onPreviousRecommendation: () -> Unit,
    onNextRecommendation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recommendation = state.recommendation ?: return
    val trailerFocusRequester = remember(recommendation.id) { FocusRequester() }
    val descriptionFocusRequester = remember(recommendation.id) { FocusRequester() }
    val previousRecommendationFocusRequester = remember { FocusRequester() }
    val nextRecommendationFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val playPainter = rememberPostPlayRecommendationIcon(R.raw.ic_player_play)
    val detailsPainter = rememberVectorPainter(Icons.Default.Info)
    val trailerPainter = rememberPostPlayRecommendationIcon(R.raw.trailer_play_button)
    val opensDetails = remember(recommendation.contentType) {
        resolvePostPlayContentType(recommendation.contentType) == ContentType.SERIES
    }
    var showPlayOptionsDialog by remember(recommendation.id) { mutableStateOf(false) }
    var descriptionTruncated by remember(recommendation.id) { mutableStateOf(false) }
    var showSynopsisOverlay by remember(recommendation.id) { mutableStateOf(false) }
    var pendingNavigationFocusDirection by remember { mutableIntStateOf(0) }
    val logoHeight by animateDpAsState(
        targetValue = if (state.isTrailerPlaying) 60.dp else 92.dp,
        animationSpec = tween(600),
        label = "postPlayRecommendationLogoHeight"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (state.isTrailerPlaying) 0.48f else 0.76f,
        animationSpec = tween(600),
        label = "postPlayRecommendationLogoWidth"
    )
    val actionTopSpacing by animateDpAsState(
        targetValue = if (state.isTrailerPlaying) NuvioTheme.spacing.md else NuvioTheme.spacing.xl,
        animationSpec = tween(600),
        label = "postPlayRecommendationActionSpacing"
    )
    val horizontalScrim = if (isRtl) {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.22f),
            0.46f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Black.copy(alpha = 0.88f)
        )
    } else {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.88f),
            0.54f to Color.Black.copy(alpha = 0.16f),
            1f to Color.Black.copy(alpha = 0.22f)
        )
    }

    LaunchedEffect(
        state.isVisible,
        state.isTrailerPlaying,
        showPlayOptionsDialog
    ) {
        if (!state.isVisible || showPlayOptionsDialog) return@LaunchedEffect
        delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong())
        runCatching { playFocusRequester.requestFocus() }
    }

    LaunchedEffect(state.recommendationIndex, state.isChangingRecommendation) {
        if (state.isChangingRecommendation || pendingNavigationFocusDirection == 0) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val requester = when {
            pendingNavigationFocusDirection < 0 && state.canNavigatePrevious -> {
                previousRecommendationFocusRequester
            }
            pendingNavigationFocusDirection > 0 && state.canNavigateNext -> {
                nextRecommendationFocusRequester
            }
            state.canNavigatePrevious -> previousRecommendationFocusRequester
            state.canNavigateNext -> nextRecommendationFocusRequester
            else -> playFocusRequester
        }
        runCatching { requester.requestFocus() }
        pendingNavigationFocusDirection = 0
    }

    LaunchedEffect(recommendation.id, recommendation.backdrop, recommendation.logo) {
        recommendation.backdrop?.let { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(1280, 720)
                    .build()
            )
        }
        recommendation.logo?.let { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(512, 192)
                    .build()
            )
        }
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = tween(360)),
        exit = fadeOut(animationSpec = tween(220)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK && native.action == AndroidKeyEvent.ACTION_UP) {
                        if (state.isTrailerPlaying) onStopTrailer() else onBack()
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK && native.action == AndroidKeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent true
                    }
                    false
                }
        ) {
            AnimatedContent(
                targetState = recommendation,
                transitionSpec = {
                    (fadeIn(NuvioMotion.mediumTween()) togetherWith
                        fadeOut(NuvioMotion.mediumTween())).using(null)
                },
                contentKey = { it.id },
                label = "postPlayRecommendationBackdrop",
                modifier = Modifier.fillMaxSize()
            ) { displayedRecommendation ->
                AsyncImage(
                    model = displayedRecommendation.backdrop ?: displayedRecommendation.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                )
            }

            key(recommendation.trailerVideoUrl ?: recommendation.id) {
                TrailerPlayer(
                    trailerUrl = recommendation.trailerVideoUrl,
                    trailerAudioUrl = recommendation.trailerAudioUrl,
                    isPlaying = state.isTrailerPlaying,
                    onEnded = onTrailerEnded,
                    cropToFill = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(horizontalScrim)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.08f),
                            0.58f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.52f)
                    .padding(
                        start = NuvioTheme.spacing.screen.overscanHorizontal,
                        bottom = NuvioTheme.spacing.screen.overscanVertical
                    ),
                horizontalAlignment = Alignment.Start
            ) {
                PostPlayRecommendationSummary(
                    recommendation = recommendation,
                    currentTitle = currentTitle,
                    isTrailerPlaying = state.isTrailerPlaying,
                    logoHeight = logoHeight,
                    logoMaxWidth = logoMaxWidth,
                    descriptionFocusRequester = descriptionFocusRequester,
                    playerWindowFocusRequester = playerWindowFocusRequester,
                    playFocusRequester = playFocusRequester,
                    onShowSynopsis = { showSynopsisOverlay = true },
                    onDescriptionTruncationChanged = { descriptionTruncated = it }
                )

                Spacer(modifier = Modifier.height(actionTopSpacing))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val buttonSpacing = NuvioTheme.spacing.md
                    val navigationButtonSize = NuvioTheme.spacing.xxxl
                    val showTrailerButton = shouldShowPostPlayTrailerAction(
                        recommendation = recommendation,
                        isTrailerPlaying = state.isTrailerPlaying,
                        inAppTrailerPlaybackEnabled = AppFeaturePolicy.inAppTrailerPlaybackEnabled
                    )
                    val showNavigationButtons = state.recommendationCount > 1
                    val actionButtonCount = if (showTrailerButton) 2 else 1
                    val navigationButtonCount = if (showNavigationButtons) 2 else 0
                    val itemCount = actionButtonCount + navigationButtonCount
                    val availableActionWidth = maxWidth -
                        navigationButtonSize * navigationButtonCount -
                        buttonSpacing * (itemCount - 1)
                    val targetButtonWidth = minOf(
                        (maxWidth - buttonSpacing) / 2,
                        availableActionWidth / actionButtonCount
                    )
                    val buttonWidth by animateDpAsState(
                        targetValue = targetButtonWidth,
                        animationSpec = NuvioMotion.mediumTween(),
                        label = "postPlayRecommendationButtonWidth"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PostPlayRecommendationButton(
                            label = stringResource(
                                if (opensDetails) R.string.tmdb_details_title
                                else R.string.player_post_play_play
                            ),
                            painter = if (opensDetails) detailsPainter else playPainter,
                            primary = true,
                            onClick = {
                                if (opensDetails) onOpenDetails(recommendation)
                                else onPlay(recommendation)
                            },
                            onLongPress = if (!opensDetails && showManualPlayOption) {
                                { showPlayOptionsDialog = true }
                            } else {
                                null
                            },
                            focusRequester = playFocusRequester,
                            modifier = Modifier
                                .width(buttonWidth)
                                .focusProperties {
                                    if (!state.isTrailerPlaying) {
                                        when {
                                            descriptionTruncated -> up = descriptionFocusRequester
                                            !state.hasAutoPlayedTrailer -> up = playerWindowFocusRequester
                                        }
                                    }
                                }
                        )

                        AnimatedVisibility(
                            visible = showTrailerButton,
                            enter = fadeIn(NuvioMotion.mediumTween()) + expandHorizontally(
                                animationSpec = NuvioMotion.mediumTween(),
                                expandFrom = Alignment.Start
                            ),
                            exit = fadeOut(NuvioMotion.mediumTween()) + shrinkHorizontally(
                                animationSpec = NuvioMotion.mediumTween(),
                                shrinkTowards = Alignment.Start
                            )
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(buttonSpacing))
                                PostPlayRecommendationButton(
                                    label = trailerButtonLabel(state),
                                    painter = trailerPainter,
                                    primary = false,
                                    onClick = onPlayTrailer,
                                    focusRequester = trailerFocusRequester,
                                    modifier = Modifier
                                        .width(buttonWidth)
                                        .focusProperties {
                                            when {
                                                descriptionTruncated -> up = descriptionFocusRequester
                                                !state.hasAutoPlayedTrailer -> up = playerWindowFocusRequester
                                            }
                                        }
                                )
                            }
                        }

                        if (showNavigationButtons) {
                            Spacer(modifier = Modifier.width(buttonSpacing))
                            PostPlayRecommendationNavigationButton(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(
                                    R.string.player_post_play_previous_recommendation
                                ),
                                enabled = state.canNavigatePrevious,
                                focusRequester = previousRecommendationFocusRequester,
                                onClick = {
                                    pendingNavigationFocusDirection = -1
                                    onPreviousRecommendation()
                                },
                                modifier = Modifier.focusProperties {
                                    when {
                                        descriptionTruncated -> up = descriptionFocusRequester
                                        !state.hasAutoPlayedTrailer -> up = playerWindowFocusRequester
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(buttonSpacing))
                            PostPlayRecommendationNavigationButton(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(
                                    R.string.player_post_play_next_recommendation
                                ),
                                enabled = state.canNavigateNext,
                                focusRequester = nextRecommendationFocusRequester,
                                onClick = {
                                    pendingNavigationFocusDirection = 1
                                    onNextRecommendation()
                                },
                                modifier = Modifier.focusProperties {
                                    when {
                                        descriptionTruncated -> up = descriptionFocusRequester
                                        !state.hasAutoPlayedTrailer -> up = playerWindowFocusRequester
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.isVisible && !opensDetails && showManualPlayOption && showPlayOptionsDialog) {
        PlayManualOverrideDialog(
            title = recommendation.title,
            subtitle = stringResource(R.string.hero_play),
            onDismiss = { showPlayOptionsDialog = false },
            onPlayManually = {
                showPlayOptionsDialog = false
                onPlayManually(recommendation)
            }
        )
    }

    recommendation.description
        ?.takeIf { state.isVisible && showSynopsisOverlay && it.isNotBlank() }
        ?.let { description ->
            SynopsisOverlay(
                title = recommendation.title,
                description = description,
                onDismiss = { showSynopsisOverlay = false }
            )
        }
}

@Composable
private fun PostPlayRecommendationSummary(
    recommendation: PostPlayRecommendation,
    currentTitle: String,
    isTrailerPlaying: Boolean,
    logoHeight: Dp,
    logoMaxWidth: Float,
    descriptionFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    playFocusRequester: FocusRequester,
    onShowSynopsis: () -> Unit,
    onDescriptionTruncationChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val headerVisibility = remember {
        MutableTransitionState(!isTrailerPlaying)
    }
    val detailsVisibility = remember {
        MutableTransitionState(!isTrailerPlaying)
    }

    LaunchedEffect(isTrailerPlaying) {
        headerVisibility.targetState = !isTrailerPlaying
        detailsVisibility.targetState = !isTrailerPlaying
    }

    Column {
        AnimatedVisibility(
            visibleState = headerVisibility,
            enter = fadeIn(tween(600)) + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(240)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Column {
                Text(
                    text = if (currentTitle.isBlank()) {
                        stringResource(R.string.player_post_play_recommended)
                    } else {
                        stringResource(R.string.player_post_play_because, currentTitle)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            }
        }

        AnimatedContent(
            targetState = recommendation,
            transitionSpec = {
                (fadeIn(NuvioMotion.mediumTween()) togetherWith
                    fadeOut(NuvioMotion.mediumTween())).using(null)
            },
            contentKey = { it.id },
            label = "postPlayRecommendationTitle"
        ) { displayedRecommendation ->
            var logoLoadFailed by remember(displayedRecommendation.logo) { mutableStateOf(false) }
            val showLogo = !displayedRecommendation.logo.isNullOrBlank() && !logoLoadFailed
            if (showLogo) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(displayedRecommendation.logo)
                        .crossfade(true)
                        .build(),
                    contentDescription = displayedRecommendation.title,
                    onError = { logoLoadFailed = true },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth(logoMaxWidth)
                        .heightIn(max = logoHeight)
                )
            } else {
                AnimatedContent(
                    targetState = isTrailerPlaying,
                    transitionSpec = {
                        fadeIn(tween(600)) togetherWith fadeOut(tween(240))
                    },
                    label = "postPlayRecommendationTitleSize"
                ) { trailerPlaying ->
                    Text(
                        text = displayedRecommendation.title,
                        style = if (trailerPlaying) {
                            MaterialTheme.typography.headlineMedium
                        } else {
                            MaterialTheme.typography.displayMedium
                        },
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        AnimatedVisibility(
            visibleState = detailsVisibility,
            enter = fadeIn(tween(600)) + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(240)) + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            AnimatedContent(
                targetState = recommendation,
                transitionSpec = {
                    (fadeIn(NuvioMotion.mediumTween()) togetherWith
                        fadeOut(NuvioMotion.mediumTween())).using(null)
                },
                contentKey = { it.id },
                label = "postPlayRecommendationDetails"
            ) { displayedRecommendation ->
                val metadata = displayedRecommendation.metadataLine(context)
                val imdbRating = displayedRecommendation.rating
                    ?.takeIf { displayedRecommendation.showStandardRatings && it > 0f }
                val tmdbRating = displayedRecommendation.tmdbRating
                    ?.takeIf { displayedRecommendation.showStandardRatings && it > 0f }

                Column {
                    if (metadata.isNotBlank() || imdbRating != null || tmdbRating != null) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (metadata.isNotBlank()) {
                                Text(
                                    text = metadata,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NuvioTheme.extendedColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (metadata.isNotBlank() && (imdbRating != null || tmdbRating != null)) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NuvioTheme.extendedColors.textTertiary
                                )
                            }
                            if (imdbRating != null || tmdbRating != null) {
                                StandardRatingsRow(
                                    imdbRating = imdbRating,
                                    tmdbRating = tmdbRating
                                )
                            }
                        }
                    }

                    displayedRecommendation.mdbListRatings
                        ?.takeUnless { it.isEmpty() }
                        ?.let { ratings ->
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                            MDBListRatingsRow(ratings = ratings)
                        }

                    if (!displayedRecommendation.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                        SynopsisDescription(
                            description = displayedRecommendation.description,
                            onShowFullDescription = onShowSynopsis,
                            maxLines = 3,
                            focusRequester = descriptionFocusRequester,
                            upFocusRequester = playerWindowFocusRequester,
                            downFocusRequester = playFocusRequester,
                            onTruncationChanged = { truncated ->
                                if (displayedRecommendation.id == recommendation.id) {
                                    onDescriptionTruncationChanged(truncated)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.92f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardRatingsRow(
    imdbRating: Float?,
    tmdbRating: Float?
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        imdbRating?.let { rating ->
            ImdbRatingSourceLabel(
                logoModifier = Modifier.size(30.dp),
                textStyle = MaterialTheme.typography.labelLarge,
                textColor = NuvioTheme.extendedColors.textSecondary
            )
            Text(
                text = String.format(Locale.US, "%.1f", rating),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textSecondary
            )
        }
        if (imdbRating != null && tmdbRating != null) {
            Text(
                text = "•",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textTertiary
            )
        }
        tmdbRating?.let { rating ->
            AsyncImage(
                model = R.raw.mdblist_tmdb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(NuvioTheme.spacing.xl)
            )
            Text(
                text = (rating * 10).toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.extendedColors.textSecondary
            )
        }
    }
}

@Composable
private fun trailerButtonLabel(state: PostPlayRecommendationUiState): String {
    return when {
        state.isTrailerPlaying -> stringResource(R.string.player_post_play_trailer_playing)
        state.countdownSeconds != null -> stringResource(
            R.string.player_post_play_trailer_countdown,
            state.countdownSeconds
        )
        else -> stringResource(R.string.player_post_play_trailer)
    }
}

@Composable
private fun PostPlayRecommendationNavigationButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(NuvioTheme.spacing.xxxl)
            .focusRequester(focusRequester),
        colors = IconButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(NuvioTheme.spacing.xxl)
        )
    }
}

@Composable
private fun PostPlayRecommendationButton(
    label: String,
    painter: Painter,
    primary: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.spacing.xxl)
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    Button(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null &&
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                ) {
                    longPressTriggered = true
                    onLongPress()
                    return@onPreviewKeyEvent true
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isSelectOrMenuKey(native.keyCode)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color.White else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (primary) Color.White else NuvioTheme.colors.Secondary,
            contentColor = if (primary) Color.Black else NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (primary) Color.Black else NuvioTheme.colors.OnSecondary
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        contentPadding = PaddingValues(
            horizontal = NuvioTheme.spacing.lg,
            vertical = 14.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                },
                label = "postPlayRecommendationButtonLabel"
            ) { currentLabel ->
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun rememberPostPlayRecommendationIcon(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = remember(density) { with(density) { NuvioTheme.spacing.xl.roundToPx() } }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == AndroidKeyEvent.KEYCODE_MENU
}

private fun PostPlayRecommendation.metadataLine(context: android.content.Context): String {
    return buildList {
        genres.take(2)
            .map { localizedGenreLabel(context, it) }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" • ")
            ?.let(::add)
        releaseInfo?.takeIf { it.isNotBlank() }?.let(::add)
        formatHeroRuntime(runtime)?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  •  ")
}
