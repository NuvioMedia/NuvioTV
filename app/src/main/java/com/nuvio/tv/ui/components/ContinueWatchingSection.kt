package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthSurface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.request.crossfade
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import com.nuvio.tv.ui.util.computeAirDateBadgeText
import com.nuvio.tv.ui.util.computeAirDateBadgeTextShort
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import com.nuvio.tv.domain.model.CardDepthStyle
import kotlinx.coroutines.delay

private val BadgeShape = RoundedCornerShape(NuvioTheme.radii.xs)
private val CwNewEpisodeBadgeColor = Color(0xFF1D4ED8)
private val CwNewSeasonBadgeColor = Color(0xFFB45309)

/** URLs that failed to load — skip them immediately on next recomposition. */
internal val brokenImageUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

// How long a focused card waits before its title starts scrolling, so briefly passing focus stays still.
private const val TITLE_MARQUEE_DELAY_MS = 3_000L

// Poster cards are narrower than landscape cards, so the title is scaled down to fit more of it.
private const val POSTER_TITLE_SCALE = 0.85f

// Line height multiplier for the poster title, kept near 1 so the row below the artwork stays compact.
private const val POSTER_TITLE_LINE_HEIGHT = 1.1f

// Fixed height of the poster title row, sized for the two lines the mobile card allows.
private val POSTER_TITLE_BLOCK_HEIGHT = 30.dp

// Width to height ratio of poster art, used to size the wide card's artwork strip.
private const val WIDE_POSTER_ASPECT = 2f / 3f

// The wide card fills its strip with poster art, so the strip width follows the card height and the poster is never cropped.
internal fun continueWatchingArtworkWidth(
    cardStyle: ContinueWatchingCardStyle,
    cardWidth: Dp,
    cardHeight: Dp
): Dp = if (cardStyle == ContinueWatchingCardStyle.WIDE) cardHeight * WIDE_POSTER_ASPECT else cardWidth

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualPlayOption: Boolean = false,
    onPlayManually: (ContinueWatchingItem) -> Unit = {},
    modifier: Modifier = Modifier,
    title: String? = null,
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true,
    downFocusRequester: FocusRequester? = null,
    entryFocusRequester: FocusRequester? = null,
    focusRequesters: MutableMap<Int, FocusRequester> = remember { mutableMapOf() },
    rowFocusRequester: FocusRequester = remember { FocusRequester() },
    listState: LazyListState = rememberLazyListState(),
    lastFocusedIndexState: MutableIntState = remember { mutableIntStateOf(-1) },
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    cardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    cornerRadius: Dp = NuvioTheme.radii.md,
    posterTitleOverride: TextStyle? = null
) {
    if (items.isEmpty()) return

    var lastFocusedIndex by lastFocusedIndexState
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }

    // Restore focus to specific item if requested
    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                val requester = focusRequesters[focusedItemIndex] ?: continue
                focused = runCatching { requester.requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = focusedItemIndex
            }
        } else {
            lastRequestedFocusIndex = -1
        }
    }

    Column(modifier = modifier.then(
        if (entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier
    )) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title ?: stringResource(R.string.continue_watching),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        val density = LocalDensity.current
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, isRtl) {
            val startPx = with(density) { NuvioTheme.spacing.xxxl.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    val childSize = kotlin.math.abs(size)
                    if (isRtl) {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = containerSize - startPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) {
                                childSize
                            } else {
                                initialTarget
                            }
                        return (offset + size) - targetForTrailingEdge
                    } else {
                        val target = startPx.toFloat()
                        val space = containerSize - target
                        val leading = if (childSize <= containerSize && space < childSize) containerSize - childSize else target
                        return offset - leading
                    }
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocusRequester)
                .focusRestorer {
                    val visibleIndices = listState.layoutInfo.visibleItemsInfo
                        .map { it.index }
                        .filter { it in items.indices }
                    val idx = lastFocusedIndex.takeIf { it in visibleIndices }
                        ?: visibleIndices.firstOrNull()
                    idx?.let { focusRequesters[it] }
                        ?: FocusRequester.Default
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            state = listState
        ) {
            itemsIndexed(
                items = items,
                key = { _, progress ->
                    when (progress) {
                        is ContinueWatchingItem.InProgress ->
                            "cw_${progress.progress.contentId}_${progress.progress.videoId}_${progress.progress.season ?: -1}_${progress.progress.episode ?: -1}"
                        is ContinueWatchingItem.NextUp ->
                            "nextup_${progress.info.contentId}_${progress.info.videoId}_${progress.info.season}_${progress.info.episode}"
                    }
                }
            ) { index, progress ->
                val requester = focusRequesters.getOrPut(index) { FocusRequester() }
                val focusModifier = Modifier.focusRequester(requester)
                val stableOnClick = remember(progress) { { onItemClick(progress) } }
                val stableOnLongPress = remember(progress) { { optionsItem = progress } }
                var isCardFocused by remember { mutableStateOf(false) }

                    ContinueWatchingCard(
                    item = progress,
                    onClick = stableOnClick,
                    onLongPress = stableOnLongPress,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    cardWidth = cardWidth,
                    imageHeight = imageHeight,
                    cardStyle = cardStyle,
                    cornerRadius = cornerRadius,
                    isFocused = isCardFocused,
                    posterTitleOverride = posterTitleOverride,
                    modifier = Modifier
                        .onFocusChanged { focusState ->
                            isCardFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                if (lastFocusedIndex != index) {
                                    lastFocusedIndex = index
                                }
                                onItemFocused(index)
                            }
                        }
                        .then(
                            if (downFocusRequester != null) {
                                Modifier.focusProperties { down = downFocusRequester }
                            } else Modifier
                        )
                        .then(focusModifier)
                )
            }
        }
        } // CompositionLocalProvider
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuItem)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(menuItem)
                optionsItem = null
            },
            onStartFromBeginning = {
                onStartFromBeginning(menuItem)
                optionsItem = null
            },
            showPlayManually = showManualPlayOption,
            onPlayManually = {
                onPlayManually(menuItem)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0) {
            val requester = focusRequesters[target]
            if (requester != null) {
                var focused = false
                for (attempt in 0 until 3) {
                    withFrameNanos { }
                    focused = runCatching { requester.requestFocus() }.isSuccess
                    if (focused) break
                }
                if (focused) {
                    lastRequestedFocusIndex = target
                }
            }
            pendingFocusIndex = null
        }
    }
}

// Poster cards always show poster art, so the episode thumbnail setting does not apply to them.
internal fun continueWatchingUsesEpisodeThumbnails(
    cardStyle: ContinueWatchingCardStyle,
    useEpisodeThumbnails: Boolean
): Boolean = useEpisodeThumbnails && cardStyle != ContinueWatchingCardStyle.POSTER

// Selects the primary Continue Watching image URL, shared by the card and the home-row prefetch so both request the same model.
internal fun continueWatchingImageModel(
    item: ContinueWatchingItem,
    useEpisodeThumbnails: Boolean,
    preferPosterArtwork: Boolean = false
): String? {
    // Poster art is already 2:3 so it wins here, and only an opted-in episode thumbnail outranks it.
    if (preferPosterArtwork) {
        val posterProgress = (item as? ContinueWatchingItem.InProgress)?.progress
        val posterNextUp = (item as? ContinueWatchingItem.NextUp)?.info
        val poster = posterNextUp?.poster ?: posterProgress?.poster
        val backdrop = posterNextUp?.backdrop ?: posterProgress?.backdrop
        val thumbnail = posterNextUp?.thumbnail
            ?: (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail
        fun firstUsable(vararg candidates: String?): String? =
            candidates.firstOrNull { !it.isNullOrBlank() && it !in brokenImageUrls }?.trim()
        // Movies carry no episode thumbnail, so they keep their poster without needing a content type check.
        // A thumbnail that is switched off leaves the chain entirely, because Trakt rows hydrate their artwork late and a demoted thumbnail would show until it lands.
        return if (useEpisodeThumbnails) {
            firstUsable(thumbnail, poster, backdrop)
        } else {
            firstUsable(poster, backdrop)
        }
    }
    val progress = (item as? ContinueWatchingItem.InProgress)?.progress
    val nextUp = (item as? ContinueWatchingItem.NextUp)?.info
    fun firstNonBroken(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() && it !in brokenImageUrls }?.trim()
    return when {
        nextUp != null && !nextUp.hasAired ->
            firstNonBroken(
                nextUp.backdrop,
                nextUp.poster,
                nextUp.thumbnail.takeIf { useEpisodeThumbnails }
            )
        nextUp != null && useEpisodeThumbnails ->
            firstNonBroken(nextUp.thumbnail, nextUp.backdrop, nextUp.poster)
        nextUp != null ->
            firstNonBroken(nextUp.backdrop, nextUp.poster)
        useEpisodeThumbnails -> firstNonBroken(
            (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail,
            progress?.backdrop,
            progress?.poster
        )
        else -> firstNonBroken(
            progress?.backdrop,
            progress?.poster
        )
    }
}

// Whether the unwatched-episode spoiler blur applies to this item.
internal fun continueWatchingShouldBlur(
    item: ContinueWatchingItem,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    preferPosterArtwork: Boolean = false
): Boolean {
    if (!blurUnwatchedEpisodes || !useEpisodeThumbnails) return false
    if (item is ContinueWatchingItem.InProgress && item.progress.isCompleted()) return false
    val thumbnail = when (item) {
        is ContinueWatchingItem.InProgress -> item.episodeThumbnail
        is ContinueWatchingItem.NextUp -> item.info.thumbnail
    }?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val resolved = continueWatchingImageModel(item, useEpisodeThumbnails, preferPosterArtwork)
    return resolved?.trim() == thumbnail
}

// Builds the Coil memory-cache key that the card and prefetch must share, including the blur suffix, so the prefetch is not wasted.
internal fun continueWatchingImageCacheKey(
    model: String?,
    widthPx: Int,
    heightPx: Int,
    shouldBlur: Boolean
): String = "${model}_${widthPx}x${heightPx}_blur${shouldBlur}"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true,
    cardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    isFocused: Boolean = false,
    cornerRadius: Dp = NuvioTheme.radii.md,
    posterTitleOverride: TextStyle? = null
) {
    val isPosterStyle = cardStyle == ContinueWatchingCardStyle.POSTER
    val isWideStyle = cardStyle == ContinueWatchingCardStyle.WIDE
    val effectiveEpisodeThumbnails =
        continueWatchingUsesEpisodeThumbnails(cardStyle, useEpisodeThumbnails)
    // The wide card shows its art in a poster shaped strip, so it resolves artwork the same way a poster card does.
    val usePosterArtwork = isPosterStyle || isWideStyle
    // Poster cards put their title under the artwork like the mobile app, the landscape card keeps it on top.
    val textBelowArtwork = isPosterStyle

    // Follow the user's poster corner radius so these cards match the catalog rows.
    val cwCardShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val cwClipShape = remember(cornerRadius, textBelowArtwork) {
        if (textBelowArtwork) {
            RoundedCornerShape(cornerRadius)
        } else {
            RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
        }
    }
    var longPressTriggered by remember { mutableStateOf(false) }
    val cardDepthStyle = LocalCardDepthStyle.current
    val longPressKeyTracker = rememberLongPressKeyTracker()

    val progress = remember(item) { (item as? ContinueWatchingItem.InProgress)?.progress }
    val nextUp = remember(item) { (item as? ContinueWatchingItem.NextUp)?.info }
    val cardContext = LocalContext.current
    val episodeStr = remember(progress, nextUp, cardContext) {
        val season = progress?.season ?: nextUp?.season
        val episode = progress?.episode ?: nextUp?.episode
        if (season != null && episode != null) {
            cardContext.getString(R.string.season_episode_format, season, episode)
        } else {
            null
        }
    }
    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strNextUp = stringResource(R.string.cw_next_up)
    val strNextUpShort = stringResource(R.string.cw_next_up_short)
    val strNewEpisode = stringResource(R.string.cw_new_episode)
    val strNewEpisodeShort = stringResource(R.string.cw_new_episode_short)
    val strNewSeason = stringResource(R.string.cw_new_season)
    val strResume = stringResource(R.string.cw_resume)
    val strPercentWatched = stringResource(R.string.cw_percent_watched)
    val strHoursMinLeft = stringResource(R.string.cw_hours_min_left)
    val strMinLeft = stringResource(R.string.cw_min_left)
    // In wide and poster styles, use the short label to save space.
    val useShortLabels = isPosterStyle || isWideStyle
    val effectiveNextUpLabel = if (useShortLabels) strNextUpShort else strNextUp
    val effectiveNewEpisodeLabel = if (useShortLabels) strNewEpisodeShort else strNewEpisode
    val nextUpBadgeText = nextUp?.let { info ->
        if (info.isReleaseAlert) {
            if (info.isNewSeasonRelease) strNewSeason else effectiveNewEpisodeLabel
        } else if (!info.hasAired) {
            val airDateText = if (useShortLabels) {
                computeAirDateBadgeTextShort(cardContext, info.released, info.airDateLabel)
            } else {
                computeAirDateBadgeText(cardContext, info.released, info.airDateLabel)
            }
            airDateText ?: strUpcoming
        } else {
            effectiveNextUpLabel
        }
    }
    val remainingText = progress?.let {
        remember(it.position, it.duration, it.progressPercent) {
            formatContinueWatchingProgressLabel(
                progress = it,
                resumeLabel = strResume,
                percentWatchedLabel = strPercentWatched,
                hoursMinLeftLabel = strHoursMinLeft,
                minLeftLabel = strMinLeft
            )
        }
    }
    val badgeText = remember(remainingText, nextUpBadgeText, effectiveNextUpLabel) {
        remainingText ?: nextUpBadgeText ?: effectiveNextUpLabel
    }
    val progressFraction = remember(progress) { progress?.progressPercentage ?: 0f }
    val imageModel = remember(item, effectiveEpisodeThumbnails, usePosterArtwork) {
        continueWatchingImageModel(item, effectiveEpisodeThumbnails, usePosterArtwork)
    }
    // A poster card keeps poster art first when falling back, so a load failure does not drop it to a cropped backdrop.
    val fallbackImageModel = remember(nextUp, progress, item, usePosterArtwork) {
        when {
            usePosterArtwork && nextUp != null -> firstNonBlank(nextUp.poster, nextUp.backdrop)
            usePosterArtwork -> firstNonBlank(progress?.poster, progress?.backdrop)
            nextUp != null -> firstNonBlank(nextUp.backdrop, nextUp.poster)
            else -> firstNonBlank(progress?.backdrop, progress?.poster)
        }
    }
    var usesFallbackImage by remember { mutableStateOf(false) }
    // Reset fallback state when the item changes
    LaunchedEffect(imageModel) { usesFallbackImage = false }

    val effectiveImageModel = if (usesFallbackImage) fallbackImageModel else imageModel
    val titleText = remember(progress, nextUp) { progress?.name ?: nextUp?.name.orEmpty() }
    val context = LocalContext.current
    val strAirsDateForEpisode = computeAirDateBadgeText(context, nextUp?.released, nextUp?.airDateLabel)
    val episodeTitle = remember(progress, nextUp, context, strAirsDateForEpisode) {
        when {
            progress != null -> progress.episodeTitle?.localizeEpisodeTitle(context)
            nextUp != null && !nextUp.hasAired -> nextUp.episodeTitle?.localizeEpisodeTitle(context) ?: strAirsDateForEpisode
            else -> nextUp?.episodeTitle?.localizeEpisodeTitle(context)
        }
    }
    val density = LocalDensity.current
    val artworkWidth = continueWatchingArtworkWidth(cardStyle, cardWidth, imageHeight)
    val requestWidthPx = remember(artworkWidth, density) {
        with(density) { artworkWidth.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(imageHeight, density) {
        with(density) { imageHeight.roundToPx() }.coerceAtLeast(1)
    }
    val shouldBlur =
        continueWatchingShouldBlur(item, blurUnwatchedEpisodes, effectiveEpisodeThumbnails, usePosterArtwork)

    val baseTitleStyle = MaterialTheme.typography.titleSmall
    val titleStyle = remember(baseTitleStyle, isPosterStyle, posterTitleOverride) {
        if (isPosterStyle && posterTitleOverride != null) {
            posterTitleOverride
        } else if (isPosterStyle) {
            // Tight line height keeps the title row short so the poster can stay catalog sized.
            val scaled = baseTitleStyle.fontSize * POSTER_TITLE_SCALE
            baseTitleStyle.copy(fontSize = scaled, lineHeight = scaled * POSTER_TITLE_LINE_HEIGHT)
        } else {
            baseTitleStyle
        }
    }

    // Hold the title still until focus has settled, then let it scroll if it overflows.
    var titleMarqueeActive by remember { mutableStateOf(false) }
    LaunchedEffect(isFocused, titleText) {
        titleMarqueeActive = false
        if (isFocused) {
            delay(TITLE_MARQUEE_DELAY_MS)
            titleMarqueeActive = true
        }
    }
    val imageRequest = remember(effectiveImageModel, requestWidthPx, requestHeightPx, shouldBlur) {
        ImageRequest.Builder(context)
            .data(effectiveImageModel)
            .crossfade(true)
            .memoryCacheKey(
                continueWatchingImageCacheKey(
                    effectiveImageModel, requestWidthPx, requestHeightPx, shouldBlur
                )
            )
            .size(width = requestWidthPx, height = requestHeightPx)
            .apply {
                if (shouldBlur) transformations(com.nuvio.tv.ui.util.BlurTransformation())
            }
            .build()
    }

    val bgColor = NuvioTheme.colors.Background
    val badgeBackground = remember(bgColor, nextUp) {
        when {
            nextUp?.isNewSeasonRelease == true -> CwNewSeasonBadgeColor
            nextUp?.isReleaseAlert == true -> CwNewEpisodeBadgeColor
            else -> bgColor.copy(alpha = 0.8f)
        }
    }
    
    val bgCardColor = NuvioTheme.colors.BackgroundCard
    val backgroundPainter = remember(bgCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgCardColor) }

    Card(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = modifier
            .width(cardWidth)
            .recompositionHighlighter()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (longPressKeyTracker.handle(native, ::isSelectKey) {
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
                    (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
        shape = CardDefaults.shape(shape = cwCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        // With text under the artwork the card border would ring the text too, so the artwork draws its own.
        border = if (textBelowArtwork) {
            CardDefaults.border(focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = cwCardShape
                )
            )
        },
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        if (isWideStyle) {
            WideCardContent(
                imageRequest = imageRequest,
                effectiveImageModel = effectiveImageModel,
                shouldBlur = shouldBlur,
                backgroundPainter = backgroundPainter,
                stripWidth = artworkWidth,
                stripHeight = imageHeight,
                cardShape = cwCardShape,
                cardDepthStyle = cardDepthStyle,
                titleText = titleText,
                titleStyle = titleStyle,
                titleMarqueeActive = titleMarqueeActive,
                episodeStr = episodeStr,
                episodeTitle = episodeTitle,
                badgeText = badgeText,
                badgeBackground = badgeBackground,
                showBadge = progress == null,
                progressFraction = progressFraction,
                hasProgress = progress != null,
                onImageError = {
                    if (!usesFallbackImage && effectiveImageModel != null) {
                        brokenImageUrls.add(effectiveImageModel)
                        if (fallbackImageModel != null && fallbackImageModel != effectiveImageModel) {
                            usesFallbackImage = true
                        }
                    }
                }
            )
            return@Card
        }
        Column(
            modifier = if (textBelowArtwork) {
                Modifier
            } else {
                Modifier.nuvioCardDepth(
                    shape = cwCardShape,
                    surface = CardDepthSurface.CONTINUE_WATCHING,
                    style = cardDepthStyle
                )
            }
        ) {
            // Thumbnail with progress overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .then(
                        if (textBelowArtwork) {
                            Modifier.nuvioCardDepth(
                                shape = cwClipShape,
                                surface = CardDepthSurface.CONTINUE_WATCHING,
                                style = cardDepthStyle
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(cwClipShape)
                    .then(
                        if (textBelowArtwork && isFocused) {
                            Modifier.border(
                                width = NuvioTheme.spacing.xxs,
                                color = NuvioTheme.colors.FocusRing,
                                shape = cwClipShape
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                // Background image with size hints for efficient decoding
                if (effectiveImageModel.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = titleText,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy =
                                    CompositingStrategy.Offscreen
                            }
                            .clip(cwClipShape)

                            // Gradient overlay for text legibility, only needed when text sits on the artwork.
                            .drawWithContent {
                                drawContent()
                                if (textBelowArtwork) return@drawWithContent

                                val startYPos = size.height * 0.45f
                                val gradient = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.6f to bgColor.copy(alpha = 0.7f),
                                        1.0f to bgColor.copy(alpha = 0.95f)
                                    ),
                                    startY = startYPos,
                                    endY = size.height
                                )

                                drawRect(
                                    brush = gradient,
                                    topLeft = Offset(-2f, startYPos),
                                    size = Size(size.width + 4f, (size.height - startYPos) + 4f)
                                )
                            },
                        placeholder = backgroundPainter,
                        error = backgroundPainter,
                        fallback = backgroundPainter,
                        contentScale = ContentScale.Crop,
                        onError = {
                            // Primary image failed (e.g. broken thumbnail URL) — remember and try fallback.
                            if (!usesFallbackImage && effectiveImageModel != null) {
                                brokenImageUrls.add(effectiveImageModel)
                                if (fallbackImageModel != null && fallbackImageModel != effectiveImageModel) {
                                    usesFallbackImage = true
                                }
                            }
                        }
                    )
                }

                // Content info at bottom
                if (!textBelowArtwork) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(NuvioTheme.spacing.md)
                    ) {
                        ContinueWatchingCardText(
                            episodeStr = episodeStr,
                            titleText = titleText,
                            titleStyle = titleStyle,
                            titleMarqueeActive = titleMarqueeActive,
                            episodeTitle = episodeTitle
                        )
                    }
                }

                // Remaining time badge - hide progress labels in poster style (only show next-up/new episode badges)
                val showBadgeInPoster = progress == null
                if (!isPosterStyle || showBadgeInPoster) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(NuvioTheme.spacing.sm)
                            .clip(BadgeShape)
                            .background(badgeBackground)
                            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }

                if (progress != null) {
                    // The poster card lifts its bar off the bottom edge and sits it on a pill, matching mobile.
                    val barInset =
                        if (isPosterStyle) NuvioTheme.spacing.sm else NuvioTheme.spacing.xs
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 10.dp, vertical = barInset)
                            .fillMaxWidth()
                            .then(
                                if (isPosterStyle) {
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(NuvioTheme.colors.Background.copy(alpha = 0.7f))
                                        .padding(NuvioTheme.spacing.xxs)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(1.5.dp))
                                .height(3.dp)
                                .background(Color.Black.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .height(3.dp)
                                    .background(NuvioTheme.colors.Primary)
                            )
                        }
                    }
                }
            }

            if (textBelowArtwork) {
                // The title wraps to two lines beside the episode code like the mobile poster card, and the
                // row height is fixed so a one line title does not make its card shorter than the rest.
                val titleBlockHeight = if (posterTitleOverride != null) 46.dp else POSTER_TITLE_BLOCK_HEIGHT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = NuvioTheme.spacing.xxs,
                            start = NuvioTheme.spacing.xs,
                            end = NuvioTheme.spacing.xs
                        )
                        .height(titleBlockHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = titleStyle,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (episodeStr != null) {
                        Text(
                            text = episodeStr,
                            modifier = Modifier.padding(start = NuvioTheme.spacing.xs),
                            style = if (posterTitleOverride != null) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelSmall
                            },
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// Wide card layout with the artwork as a strip on the left and the details stacked beside it.
@Composable
private fun WideCardContent(
    imageRequest: ImageRequest,
    effectiveImageModel: String?,
    shouldBlur: Boolean,
    backgroundPainter: Painter,
    stripWidth: Dp,
    stripHeight: Dp,
    cardShape: RoundedCornerShape,
    cardDepthStyle: CardDepthStyle,
    titleText: String,
    titleStyle: TextStyle,
    titleMarqueeActive: Boolean,
    episodeStr: String?,
    episodeTitle: String?,
    badgeText: String,
    badgeBackground: Color,
    showBadge: Boolean,
    progressFraction: Float,
    hasProgress: Boolean,
    onImageError: () -> Unit
) {
    // The card takes its height from this row, so pin it here or a taller text column would stretch the card.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .nuvioCardDepth(
                shape = cardShape,
                surface = CardDepthSurface.CONTINUE_WATCHING,
                style = cardDepthStyle
            )
            .clip(cardShape)
            .background(NuvioTheme.colors.BackgroundCard)
    ) {
        Box(
            modifier = Modifier
                .width(stripWidth)
                .fillMaxHeight()
        ) {
            if (effectiveImageModel.isNullOrBlank()) {
                MonochromePosterPlaceholder()
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = titleText,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (shouldBlur) Modifier.blur(12.dp) else Modifier),
                    placeholder = backgroundPainter,
                    error = backgroundPainter,
                    fallback = backgroundPainter,
                    contentScale = ContentScale.Crop,
                    onError = { onImageError() }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(
                    horizontal = NuvioTheme.spacing.md,
                    vertical = NuvioTheme.spacing.sm
                ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            // Text content takes available space; progress bar appears at bottom only when present.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        FocusMarqueeText(
                            text = titleText,
                            focused = titleMarqueeActive,
                            style = titleStyle,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                    if (showBadge) {
                        Box(
                            modifier = Modifier
                                .padding(start = NuvioTheme.spacing.xs)
                                .clip(BadgeShape)
                                .background(badgeBackground)
                                .padding(
                                    horizontal = NuvioTheme.spacing.sm,
                                    vertical = NuvioTheme.spacing.xxs
                                )
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextPrimary
                            )
                        }
                    }
                }
                // Both lines are always laid out so a movie with no episode data keeps the same shape as a show.
                Text(
                    text = episodeStr.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    minLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    FocusMarqueeText(
                        text = episodeTitle.orEmpty(),
                        focused = titleMarqueeActive,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }

            // The bar keeps its space even with no progress so every card lays out identically.
            Column(
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xxs)
                    .alpha(if (hasProgress) 1f else 0f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(1.5.dp))
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .clip(RoundedCornerShape(1.5.dp))
                            .height(3.dp)
                            .background(NuvioTheme.colors.Primary)
                    )
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Title block drawn on top of the artwork by the landscape card.
@Composable
private fun ContinueWatchingCardText(
    episodeStr: String?,
    titleText: String,
    titleStyle: TextStyle,
    titleMarqueeActive: Boolean,
    episodeTitle: String?
) {
    if (episodeStr != null) {
        Text(
            text = episodeStr,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextPrimary
        )
    }

    FocusMarqueeText(
        text = titleText,
        focused = titleMarqueeActive,
        style = titleStyle,
        color = NuvioTheme.colors.TextPrimary
    )

    episodeTitle?.let { title ->
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {}
) {
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }

    val detailsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        detailsFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(detailsFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        if (showPlayManually) {
            Button(
                onClick = onPlayManually,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.play_manually))
            }
        }

        if (item is ContinueWatchingItem.InProgress) {
            Button(
                onClick = onStartFromBeginning,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cw_action_start_from_beginning))
            }
        }

        Button(
            onClick = onRemove,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.cw_action_remove))
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun formatRemainingTime(
    remainingMs: Long,
    strHoursMinLeft: String,
    strMinLeft: String,
    strAlmostDone: String
): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> strHoursMinLeft.format(hours, minutes)
        minutes > 0 -> strMinLeft.format(minutes)
        else -> strAlmostDone
    }
}
