package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.recompositionHighlighter
import coil3.request.transitionFactory
import kotlinx.coroutines.delay
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.TrailerPlayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import com.nuvio.tv.core.player.BackgroundTrailerRequest
import com.nuvio.tv.core.player.LocalBackgroundTrailerController
import com.nuvio.tv.ui.components.BackgroundTrailerRequestEffect
import com.nuvio.tv.ui.components.AutoScrollingText
import com.nuvio.tv.ui.components.HeroAgeRatingStatusBadges
import com.nuvio.tv.ui.components.heroAgeRatingBadgeText
import com.nuvio.tv.ui.components.rememberHeroStatusBadgeText

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    ModernHeroMediaLayer(
        heroBackdrop = { state().heroBackdrop },
        enrichmentActive = { state().enrichmentActive },
        shouldPlayHeroTrailer = { state().shouldPlayTrailer },
        heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
        heroTrailerUrl = { state().trailerUrl },
        heroTrailerAudioUrl = { state().trailerAudioUrl },
        heroTrailerPlaybackKey = { state().trailerPlaybackKey },
        muted = { state().trailerMuted },
        isFullScreen = isFullScreen,
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        modifier = modifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    ModernHeroGradientLayer(
        bgColor = bgColor,
        isFullScreen = isFullScreen,
        modifier = modifier
    )
}

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: () -> String?,
    enrichmentActive: () -> Boolean,
    shouldPlayHeroTrailer: () -> Boolean,
    heroTrailerFirstFrameRendered: () -> Boolean,
    heroTrailerUrl: () -> String?,
    heroTrailerAudioUrl: () -> String?,
    heroTrailerPlaybackKey: () -> String?,
    muted: () -> Boolean,
    isFullScreen: () -> Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val shouldPlay by remember { derivedStateOf { shouldPlayHeroTrailer() } }
    val trailerRendered by remember { derivedStateOf { heroTrailerFirstFrameRendered() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current

    // Backdrop URL is managed upstream (heroSceneStateLambda freezes it
    // during rapid nav / scroll). Only update when enrichment is not active
    val rawBackdrop by remember { derivedStateOf { heroBackdrop() } }
    val enriching by remember { derivedStateOf { enrichmentActive() } }
    var displayedBackdrop by remember { mutableStateOf(HeroBackdropState.lastDisplayedUrl ?: heroBackdrop()) }
    if (rawBackdrop != null && rawBackdrop != displayedBackdrop && !enriching) {
        displayedBackdrop = rawBackdrop!!
    }
    val imageModel = remember(
        localContext,
        displayedBackdrop,
        requestWidthPx,
        requestHeightPx
    ) {
        displayedBackdrop?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    // Keep HeroBackdropState in sync for navigation transitions.
    LaunchedEffect(displayedBackdrop) {
        displayedBackdrop?.let { HeroBackdropState.update(it) }
    }

    var heroMediaRect by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val rect = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
            if (heroMediaRect != rect) heroMediaRect = rect
        }
    ) {
        androidx.compose.animation.Crossfade(
            targetState = imageModel,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.overlay),
            label = "heroBackdropCrossfade"
        ) { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = 1f - transitionProgressState.value
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        val backgroundTrailerController = LocalBackgroundTrailerController.current
        if (backgroundTrailerController != null) {
            val fullScreenVal = isFullScreen()
            val mediaRect = heroMediaRect
            val trailerUrlVal = if (shouldPlay && (fullScreenVal || mediaRect != null)) heroTrailerUrl() else null
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            val latestOnTrailerEnded by rememberUpdatedState(onTrailerEnded)
            val latestOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
            val request = remember(trailerUrlVal, audioUrlVal, mutedVal, fullScreenVal, mediaRect) {
                trailerUrlVal?.let { url ->
                    BackgroundTrailerRequest(
                        videoUrl = url,
                        audioUrl = audioUrlVal,
                        muted = mutedVal,
                        autoCropLetterbox = fullScreenVal,
                        bounds = if (fullScreenVal) null else mediaRect,
                        overscanZoom = if (fullScreenVal) 1f else MODERN_TRAILER_OVERSCAN_ZOOM,
                        alpha = { transitionProgressState.value },
                        onEnded = { latestOnTrailerEnded() },
                        onFirstFrameRendered = { latestOnFirstFrameRendered() }
                    )
                }
            }
            BackgroundTrailerRequestEffect(
                controller = backgroundTrailerController,
                owner = HOME_HERO_TRAILER_OWNER,
                request = request
            )
        } else if (shouldPlay) {
            val trailerUrlVal = heroTrailerUrl()
            val playbackKeyVal = heroTrailerPlaybackKey()
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            key(playbackKeyVal ?: trailerUrlVal) {
                TrailerPlayer(
                    trailerUrl = trailerUrlVal,
                    trailerAudioUrl = audioUrlVal,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = onFirstFrameRendered,
                    muted = mutedVal,
                    cropToFill = true,
                    overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = transitionProgressState.value
                        }
                )
            }
        }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    isFullScreen: () -> Boolean,
    modifier: Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val colorStops = if (fullScreen) {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    )
                }
                val horizontalGradient = if (isRtl) {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = size.width,
                        endX = size.width - horizontalFadeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                }

                val bottomStripStartY = size.height * if (fullScreen) 0.64f else 0.82f
                val verticalGradient = Brush.verticalGradient(
                    colorStops = if (fullScreen) {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        )
                    } else {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.40f to bgColor.copy(alpha = 0.25f),
                            0.75f to bgColor.copy(alpha = 0.65f),
                            1.0f to bgColor
                        )
                    },
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    // 1. Horizontal fade (reversed in RTL)
                    val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(rectLeft, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    previewProvider: () -> HeroPreview?,
    enrichmentActive: () -> Boolean = { false },
    portraitMode: Boolean,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    descriptionAutoScrollEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // The providers read hero state that is rewritten on every focus move, often with an equal
    // value. Deriving the pair recomposes this block only when what it shows actually changes.
    val latestPreviewProvider = rememberUpdatedState(previewProvider)
    val latestEnrichmentActive = rememberUpdatedState(enrichmentActive)
    val displayInputs by remember {
        derivedStateOf { latestPreviewProvider.value() to latestEnrichmentActive.value() }
    }
    val currentPreview = displayInputs.first
    val isEnriching = displayInputs.second
    
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { Pair(previewProvider(), enrichmentActive()) }.collect { (p, e) ->
            if (!e && p != null) {
                if (stablePreview != p) stablePreview = p
            } else if (e) {
                if (stablePreview != null) stablePreview = null
            }
        }
    }

    val displayPreview = if (!isEnriching && currentPreview != null) currentPreview else stablePreview
    if (displayPreview == null) return
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(
            previewProvider = { displayPreview },
            portraitMode = portraitMode,
            showImdbRatings = showImdbRatings,
            mdbListShowOnHero = mdbListShowOnHero,
            mdbListRatingOrder = mdbListRatingOrder,
            trailerPlaying = trailerPlaying,
            descriptionAutoScrollEnabled = descriptionAutoScrollEnabled
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    portraitMode: Boolean,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    descriptionAutoScrollEnabled: Boolean = false
) {
    val preview = previewProvider() ?: return
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val descriptionMaxLines = 4
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = NuvioTheme.spacing.sm * titleScale
    val metaSpacing = NuvioTheme.spacing.sm * metaScale
    val imdbMetaSpacing = NuvioTheme.spacing.xs * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }

    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    // Same idea as in HeroTitleBlock: only a flip of the result should recompose this content.
    val latestTrailerPlaying = rememberUpdatedState(trailerPlaying)
    val trailerPlayingValue by remember { derivedStateOf { latestTrailerPlaying.value() } }
    val metaAlpha by animateFloatAsState(
        targetValue = if (trailerPlayingValue) 0f else 1f,
        animationSpec = tween(durationMillis = 480),
        label = "heroMetaFade"
    )
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    Column(
        modifier = if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else if (preview.title.isNotBlank()) {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val localizedStatus = rememberHeroStatusBadgeText(preview.statusText, preview.isSeries)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            localizedStatus,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = heroAgeRatingBadgeText(preview.ageRatingText),
                status = localizedStatus,
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val hasImdbRatingForLayout = !preview.imdbText.isNullOrBlank()
        val hasMdbListRatings = mdbListShowOnHero && preview.mdbListRatings != null && !preview.mdbListRatings.isEmpty()
        // When MDBList ratings are shown, don't reserve space for standalone IMDb badge.
        val reserveImdbInPrimary = !hasMdbListRatings && !preview.isSeries && !hasSecondaryBadge && hasImdbRatingForLayout
        val reserveImdbInPrimaryWithHighlight = reserveImdbInPrimary && secondaryHighlightText == null
        val reserveImdbInSecondary = !hasMdbListRatings && hasImdbRatingForLayout &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)
        val showImdbInPrimaryWithHighlight = showImdbRatings && reserveImdbInPrimaryWithHighlight
        val showImdbInSecondary = showImdbRatings && reserveImdbInSecondary

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = metaAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.genres, context) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                        add(com.nuvio.tv.ui.util.localizedGenreLabel(context, genre))
                    }
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                reserveImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!runtimeText.isNullOrBlank() && !yearText.isNullOrBlank()) {
                        HeroMetaDivider(metaScale)
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (reserveImdbInPrimaryWithHighlight) {
                        HeroImdbMeta(
                            imdbText = preview.imdbText.orEmpty(),
                            textStyle = labelMedium,
                            textColor = NuvioTheme.colors.TextSecondary,
                            logoSize = NuvioTheme.spacing.xl * metaScale,
                            spacing = imdbMetaSpacing,
                            visible = showImdbInPrimaryWithHighlight
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || reserveImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty() || hasMdbListRatings) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = metaAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                HeroAgeRatingStatusBadges(
                    ageRatingBadge = ageRatingBadge,
                    statusBadge = statusBadge,
                    textStyle = labelMedium,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
                if ((ageRatingBadge != null || statusBadge != null) && (reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                if (reserveImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        textStyle = labelMedium,
                        textColor = NuvioTheme.colors.TextSecondary,
                        logoSize = NuvioTheme.spacing.xl * metaScale,
                        spacing = imdbMetaSpacing,
                        visible = showImdbInSecondary
                    )
                }
                if (reserveImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary
                    )
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
                // MDBList ratings inline after other secondary meta.
                if (showImdbRatings && hasMdbListRatings) {
                    // Divider before MDBList ratings is already handled by the
                    // badge/IMDb/details divider logic above — only add one
                    // when IMDb or details were the last visible element.
                    if (showImdbInSecondary || secondaryDetails.isNotEmpty()) {
                        HeroMetaDivider(metaScale)
                    }
                    com.nuvio.tv.ui.components.MDBListRatingsRow(
                        ratings = preview.mdbListRatings!!,
                        maxItems = 3,
                        order = mdbListRatingOrder
                    )
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            AutoScrollingText(
                text = description,
                style = scaledDescriptionStyle.copy(textDirection = description.contentTextDirection()),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = descriptionMaxLines,
                autoScrollEnabled = descriptionAutoScrollEnabled,
                paused = trailerPlaying,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }
    }
}

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    visible: Boolean = true
) {
    Row(
        modifier = Modifier
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        ImdbRatingSourceLabel(
            logoModifier = Modifier.size(logoSize),
            textStyle = textStyle,
            textColor = textColor
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(
    scale: Float,
    visible: Boolean = true
) {
    Box(
        modifier = Modifier
            .size((NuvioTheme.spacing.xs * scale).coerceAtLeast(NuvioTheme.spacing.xxs))
            .clip(RoundedCornerShape(percent = 50))
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .background(NuvioTheme.colors.TextTertiary.copy(alpha = 0.78f))
    )
}

internal const val HOME_HERO_TRAILER_OWNER = "home_hero"
