package com.nuvio.tv.ui.screens.cast

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CastDetailScreen(
    viewModel: CastDetailViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    var isBiographyExpanded by rememberSaveable(viewModel.personId) { mutableStateOf(false) }

    BackHandler {
        if (isBiographyExpanded && uiState is CastDetailUiState.Success) {
            isBiographyExpanded = false
        } else {
            onBackPress()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Crossfade(
            targetState = uiState,
            label = "CastDetailStateCrossfade"
        ) { state ->
            when (state) {
                is CastDetailUiState.Loading -> {
                    CastDetailSkeleton(personName = viewModel.personName)
                }
                is CastDetailUiState.Error -> {
                    CastDetailError(
                        message = state.message,
                        onRetry = { viewModel.retry() }
                    )
                }
                is CastDetailUiState.Success -> {
                    CastDetailContent(
                        person = state.personDetail,
                        isBiographyExpanded = isBiographyExpanded,
                        onBiographyExpandedChange = { isBiographyExpanded = it },
                        onNavigateToDetail = onNavigateToDetail,
                        posterOptions = viewModel.posterOptions,
                        posterCardCornerRadiusDp = viewModel.posterCardCornerRadiusDp.collectAsState().value,
                        isItemWatched = { item ->
                            if (item.apiType == "movie") item.id in watchedMovieIds
                            else item.id in watchedSeriesIds
                        }
                    )
                }
            }
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailContent(
    person: PersonDetail,
    isBiographyExpanded: Boolean,
    onBiographyExpandedChange: (Boolean) -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    posterCardCornerRadiusDp: Int = 12,
    isItemWatched: (MetaPreview) -> Boolean = { false }
) {
    val backgroundColor = NuvioTheme.colors.Background
    val accentColor = NuvioTheme.colors.Secondary

    val allCredits = remember(person.movieCredits, person.tvCredits) {
        (person.movieCredits + person.tvCredits)
            .distinctBy { it.id }
            .sortedByDescending { releaseYearSortKey(it.releaseInfo) }
    }

    val filmographyPosterStyle = remember(posterCardCornerRadiusDp) {
        PosterCardStyle(
            width = 112.dp,
            height = 168.dp,
            cornerRadius = posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val firstPosterFocusRequester = remember { FocusRequester() }
    var isBiographyTruncated by rememberSaveable(person.tmdbId) { mutableStateOf(false) }

    // Focus restoration state for filmography row
    var pendingRestoreItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocusToken by rememberSaveable { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, pendingRestoreItemId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreItemId != null) {
                restoreFocusToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Left accent gradient overlay
        val accentGradient = remember(accentColor, backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.26f),
                    0.12f to accentColor.copy(alpha = 0.18f),
                    0.28f to accentColor.copy(alpha = 0.10f),
                    0.45f to accentColor.copy(alpha = 0.04f),
                    0.60f to Color.Transparent
                )
            )
        }
        // Accent goes on top of the plain background to provide the Cast theme coloring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(accentGradient)
        )

        // Main content
        AnimatedVisibility(
            visible = true,
            enter = fadeIn()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeroSection(
                    person = person,
                    isBiographyExpanded = isBiographyExpanded,
                    isBiographyTruncated = isBiographyTruncated,
                    onBiographyTruncationChanged = { isBiographyTruncated = it },
                    onPortraitClick = {
                        if (isBiographyExpanded || isBiographyTruncated) {
                            onBiographyExpandedChange(!isBiographyExpanded)
                        }
                    },
                    modifier = if (isBiographyExpanded) Modifier.weight(1f) else Modifier
                )

                AnimatedVisibility(
                    visible = allCredits.isNotEmpty() && !isBiographyExpanded,
                    enter = fadeIn() +
                        expandVertically(expandFrom = Alignment.Top) +
                        slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() +
                        shrinkVertically(shrinkTowards = Alignment.Top) +
                        slideOutVertically(targetOffsetY = { it })
                ) {
                    Column {
                        SectionHeader(
                            title = stringResource(R.string.cast_detail_filmography),
                            count = allCredits.size
                        )
                        FilmographyRow(
                            credits = allCredits,
                            posterCardStyle = filmographyPosterStyle,
                            firstItemFocusRequester = firstPosterFocusRequester,
                            restoreItemId = pendingRestoreItemId,
                            restoreFocusToken = restoreFocusToken,
                            onRestoreFocusHandled = { pendingRestoreItemId = null },
                            onItemClick = { item ->
                                pendingRestoreItemId = item.id
                                onNavigateToDetail(item.id, item.apiType, null)
                            },
                            onItemLongPress = { item ->
                                posterOptions.show(item, null)
                            },
                            isItemWatched = isItemWatched
                        )
                    }
                }
            }
        }
    }
}

private fun releaseYearSortKey(releaseInfo: String?): Int {
    return releaseInfo
        ?.trim()
        ?.take(4)
        ?.toIntOrNull()
        ?: 0
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroSection(
    person: PersonDetail,
    isBiographyExpanded: Boolean,
    isBiographyTruncated: Boolean,
    onBiographyTruncationChanged: (Boolean) -> Unit,
    onPortraitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val portraitFocusRequester = remember { FocusRequester() }
    val biographyScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isBiographyExpanded) {
        if (isBiographyExpanded) {
            biographyScrollState.scrollTo(0)
            portraitFocusRequester.requestFocusAfterFrames()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xxl, bottom = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar / Profile Photo
        Card(
            onClick = onPortraitClick,
            modifier = Modifier
                .width(160.dp)
                .height(240.dp)
                .focusRequester(portraitFocusRequester)
                .focusProperties {
                    canFocus = isBiographyExpanded || isBiographyTruncated
                }
                .onPreviewKeyEvent { event ->
                    if (!isBiographyExpanded || event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (biographyScrollState.value < biographyScrollState.maxValue) {
                                    coroutineScope.launch { biographyScrollState.animateScrollBy(160f) }
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.DirectionUp -> {
                                if (biographyScrollState.value > 0) {
                                    coroutineScope.launch { biographyScrollState.animateScrollBy(-160f) }
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                },
            shape = CardDefaults.shape(
                shape = RoundedCornerShape(NuvioTheme.radii.xl)
            ),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                )
            )
        ) {
            val bgCardColor = NuvioTheme.colors.SurfaceVariant
            val bgPainter = remember(bgCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgCardColor) }
            val photo = person.profilePhoto
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                    .then(if (photo.isNullOrBlank()) Modifier.background(bgCardColor) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (!photo.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photo)
                            .crossfade(true)
                            .size(
                                width = with(LocalDensity.current) { 160.dp.roundToPx() },
                                height = with(LocalDensity.current) { 240.dp.roundToPx() }
                            )
                            .build(),
                        contentDescription = person.name,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = person.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.displayLarge,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xl))

        // Bio Column
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (isBiographyExpanded) Modifier.fillMaxHeight() else Modifier)
                .padding(top = NuvioTheme.spacing.xs)
        ) {
            // Name
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            Spacer(modifier = Modifier.height(10.dp))

            // Personal Info Row
            val strBorn = stringResource(R.string.cast_detail_born)
            val strBornDied = stringResource(R.string.cast_detail_born_died)
            val strAge = stringResource(R.string.cast_detail_age)
            val infoItems = buildList {
                person.birthday?.let { bday ->
                    val age = calculateAge(bday, person.deathday)
                    val ageStr = if (age != null) " (${strAge.format(age)})" else ""
                    val bdayDisplay = formatDateForDisplay(bday) ?: bday
                    val deathDisplay = person.deathday?.let { formatDateForDisplay(it) ?: it }
                    val line = if (deathDisplay != null) {
                        strBornDied.format(bdayDisplay, deathDisplay) + ageStr
                    } else {
                        strBorn.format(bdayDisplay) + ageStr
                    }
                    add(line)
                }
                person.placeOfBirth?.let { add(it) }
            }
            if (infoItems.isNotEmpty()) {
                infoItems.forEach { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            }

            // Biography
            person.biography?.let { bio ->
                if (isBiographyExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(biographyScrollState)
                    ) {
                        Text(
                            text = bio,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 20.sp
                            ),
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(end = NuvioTheme.spacing.md)
                        )
                    }
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.hero_synopsis_dismiss_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                } else {
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 20.sp
                        ),
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow != isBiographyTruncated) {
                                onBiographyTruncationChanged(result.hasVisualOverflow)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary,
            modifier = Modifier
                .background(
                    color = NuvioTheme.colors.SurfaceVariant,
                    shape = RoundedCornerShape(NuvioTheme.radii.xs)
                )
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FilmographyRow(
    credits: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    firstItemFocusRequester: FocusRequester,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit = {},
    isItemWatched: (MetaPreview) -> Boolean = { false }
) {
    val hasRequestedInitialFocus = remember(credits) { mutableStateOf(false) }
    val restoreFocusRequester = remember { FocusRequester() }
    var restorePending by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken <= 0 || restoreItemId == null) {
            restorePending = false
            return@LaunchedEffect
        }
        val targetIndex = credits.indexOfFirst { it.id == restoreItemId }
        if (targetIndex < 0) {
            restorePending = false
            return@LaunchedEffect
        }
        restorePending = true
        restoreFocusRequester.requestFocusAfterFrames()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer { if (restorePending) restoreFocusRequester else firstItemFocusRequester },
        state = lazyListState,
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        itemsIndexed(
            items = credits,
            key = { _, item -> item.id + item.name }
        ) { index, item ->
            val isRestoreTarget = item.id == restoreItemId
            val isFirstItem = index == 0
            val itemFocusRequester = when {
                isRestoreTarget -> restoreFocusRequester
                isFirstItem -> firstItemFocusRequester
                else -> null
            }

            GridContentCard(
                item = item,
                onClick = { onItemClick(item) },
                onLongPress = { onItemLongPress(item) },
                isWatched = isItemWatched(item),
                modifier = if (isFirstItem) {
                    Modifier.onGloballyPositioned {
                        if (!hasRequestedInitialFocus.value) {
                            hasRequestedInitialFocus.value = true
                            runCatching { firstItemFocusRequester.requestFocus() }
                        }
                    }
                } else {
                    Modifier
                },
                posterCardStyle = posterCardStyle,
                showLabel = true,
                focusRequester = itemFocusRequester,
                onFocused = {
                    if (isRestoreTarget && restoreFocusToken > 0) {
                        onRestoreFocusHandled()
                        restorePending = false
                    }
                }
            )
        }
    }
}

// ─── Loading / Error States ───

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailSkeleton(personName: String) {
    val backgroundColor = NuvioTheme.colors.Background
    val accentColor = NuvioTheme.colors.Secondary
    val shimmerBrush = rememberShimmerBrush()

    Box(modifier = Modifier.fillMaxSize()) {
        val accentGradient = remember(accentColor, backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.26f),
                    0.12f to accentColor.copy(alpha = 0.18f),
                    0.28f to accentColor.copy(alpha = 0.10f),
                    0.45f to accentColor.copy(alpha = 0.04f),
                    0.60f to Color.Transparent
                )
            )
        }
        // Accent gradient provides skeleton color depth
        Box(modifier = Modifier.fillMaxSize().background(accentGradient))

        Column(modifier = Modifier.fillMaxSize()) {
            // Hero skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xxl, bottom = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(240.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                        .background(shimmerBrush)
                )

                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xl))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = NuvioTheme.spacing.xs)
                ) {
                    Text(
                        text = personName,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (it == 0) 0.60f else if (it == 1) 0.48f else 0.72f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(shimmerBrush)
                    )
                }
            }

            // Filmography header skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(shimmerBrush)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(shimmerBrush)
                )
            }

            // Filmography row skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                repeat(7) {
                    Column(modifier = Modifier.width(112.dp)) {
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .height(168.dp)
                                .clip(RoundedCornerShape(PosterCardDefaults.Style.cornerRadius))
                                .background(shimmerBrush)
                        )
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NuvioTheme.spacing.lg)
                                .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailError(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.cast_detail_error),
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                )
            ) {
                Text(stringResource(R.string.cast_detail_retry))
            }
        }
    }
}

// ─── Utility ───

private fun calculateAge(birthday: String, deathday: String?): Int? {
    val birth = parseDateFlexible(birthday) ?: return null
    val end = deathday?.let { parseDateFlexible(it) } ?: Date()

    val birthCal = Calendar.getInstance().apply { time = birth }
    val endCal = Calendar.getInstance().apply { time = end }

    var age = endCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
    val endMonth = endCal.get(Calendar.MONTH)
    val birthMonth = birthCal.get(Calendar.MONTH)
    val endDay = endCal.get(Calendar.DAY_OF_MONTH)
    val birthDay = birthCal.get(Calendar.DAY_OF_MONTH)

    if (endMonth < birthMonth || (endMonth == birthMonth && endDay < birthDay)) {
        age--
    }
    return age.takeIf { it >= 0 }
}

private fun parseDateFlexible(date: String?): Date? {
    val raw = date?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val patterns = arrayOf("yyyy-MM-dd", "dd-MM-yyyy")
    for (pattern in patterns) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            return sdf.parse(raw)
        } catch (_: Exception) {
            // try next
        }
    }
    return null
}

private fun formatDateForDisplay(date: String?): String? {
    val parsed = parseDateFlexible(date) ?: return null
    return try {
        val locale = Locale.getDefault()
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale).format(parsed)
    } catch (_: Exception) {
        null
    }
}
