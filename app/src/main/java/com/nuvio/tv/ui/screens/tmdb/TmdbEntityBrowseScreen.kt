package com.nuvio.tv.ui.screens.tmdb

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun TmdbEntityBrowseScreen(
    viewModel: TmdbEntityBrowseViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Crossfade(
            targetState = uiState,
            label = "TmdbEntityBrowseState"
        ) { state ->
            when (state) {
                TmdbEntityBrowseUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }

                is TmdbEntityBrowseUiState.Error -> {
                    ErrorState(
                        message = state.message,
                        onRetry = { viewModel.retry() }
                    )
                }

                is TmdbEntityBrowseUiState.Success -> {
                    TmdbEntityBrowseContent(
                        data = state.data,
                        sourceType = viewModel.sourceType,
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
            }
        }
    }
}

@Composable
private fun TmdbEntityBrowseContent(
    data: TmdbEntityBrowseData,
    sourceType: String,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingRestoreItemId by rememberSaveable(data.header.id) { mutableStateOf<String?>(null) }
    var restoreFocusToken by rememberSaveable(data.header.id) { mutableIntStateOf(0) }

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

    val posterCardStyle = PosterCardDefaults.Style

    val backgroundRequest = rememberBackgroundRequest(
        data = data,
        sourceType = sourceType
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Background image layer
        if (backgroundRequest != null) {
            AsyncImage(
                model = backgroundRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.34f
            )
        }

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            NuvioColors.Background.copy(alpha = 0.35f),
                            NuvioColors.Background.copy(alpha = 0.75f),
                            NuvioColors.Background
                        )
                    )
                )
        )

        if (data.rails.isEmpty()) {
            EmptyScreenState(
                title = stringResource(R.string.tmdb_entity_empty_title),
                subtitle = stringResource(R.string.tmdb_entity_empty_subtitle),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val railsViewportFraction = 0.65f
            val railsViewportHeight = maxHeight * railsViewportFraction

            // Fixed hero in the upper area
            TmdbEntityHero(
                data = data,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 24.dp, bottom = 8.dp)
            )

            // Scrollable rails anchored to the bottom
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(railsViewportHeight),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(
                    items = data.rails,
                    key = { rail -> "${rail.mediaType.value}_${rail.railType.value}" }
                ) { rail ->
                    EntityRailRow(
                        rail = rail,
                        posterCardStyle = posterCardStyle,
                        restoreItemId = pendingRestoreItemId,
                        restoreFocusToken = restoreFocusToken,
                        onRestoreFocusHandled = { pendingRestoreItemId = null },
                        onItemClick = { item ->
                            pendingRestoreItemId = item.id
                            onNavigateToDetail(item.id, item.apiType, null)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberBackgroundRequest(
    data: TmdbEntityBrowseData,
    sourceType: String
) : ImageRequest? {
    val context = LocalContext.current
    return remember(context, data, sourceType) {
        val backgroundItem = data.rails.firstOrNull {
            it.mediaType == if (sourceType.trim().equals("movie", ignoreCase = true)) {
                TmdbEntityMediaType.MOVIE
            } else {
                TmdbEntityMediaType.TV
            }
        }?.items?.firstOrNull()?.background ?: data.rails.firstOrNull()?.items?.firstOrNull()?.background
        backgroundItem?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .build()
        }
    }
}

@Composable
private fun TmdbEntityHero(
    data: TmdbEntityBrowseData,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val context = LocalContext.current
        if (!data.header.logo.isNullOrBlank()) {
            val logoPainter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(data.header.logo)
                    .crossfade(true)
                    .allowHardware(false) // needed to read pixels
                    .build()
            )

            // Detect dark monochrome logo and tint white if needed
            var logoColorFilter by remember { mutableStateOf<ColorFilter?>(null) }
            val painterState = logoPainter.state
            LaunchedEffect(painterState) {
                if (painterState is AsyncImagePainter.State.Success) {
                    val bitmap = (painterState.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val isDarkMono = isLogoDarkAndMonochrome(bitmap)
                        logoColorFilter = if (isDarkMono) {
                            // Tint all opaque pixels white, preserving transparency
                            ColorFilter.tint(Color.White, BlendMode.SrcIn)
                        } else null
                    }
                }
            }

            androidx.compose.foundation.Image(
                painter = logoPainter,
                contentDescription = data.header.name,
                modifier = Modifier
                    .width(220.dp)
                    .height(90.dp),
                contentScale = ContentScale.Fit,
                colorFilter = logoColorFilter
            )
            Spacer(modifier = Modifier.width(24.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entityKindLabel(data.header.kind),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = data.header.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val metaLine = listOfNotNull(
                data.header.originCountry?.takeIf { it.isNotBlank() },
                data.header.secondaryLabel?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (metaLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
            data.header.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 20.sp
                    ),
                    color = NuvioColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.72f)
                )
            }
        }
    }
}

@Composable
private fun EntityRailRow(
    rail: TmdbEntityRail,
    posterCardStyle: PosterCardStyle,
    restoreItemId: String?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    onItemClick: (MetaPreview) -> Unit
) {
    val focusRequesters = remember(rail.items) {
        rail.items.associate { it.id to FocusRequester() }
    }

    LaunchedEffect(restoreItemId, restoreFocusToken) {
        if (restoreFocusToken <= 0 || restoreItemId == null) return@LaunchedEffect
        val requester = focusRequesters[restoreItemId] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        onRestoreFocusHandled()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = railTitle(rail),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = rail.items,
                key = { item -> item.id }
            ) { item ->
                GridContentCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    posterCardStyle = posterCardStyle,
                    showLabel = true,
                    focusRequester = focusRequesters[item.id]
                )
            }
        }
    }
}

@Composable
private fun entityKindLabel(kind: TmdbEntityKind): String = when (kind) {
    TmdbEntityKind.COMPANY -> stringResource(R.string.tmdb_entity_kind_company)
    TmdbEntityKind.NETWORK -> stringResource(R.string.tmdb_entity_kind_network)
}

@Composable
private fun railTitle(rail: TmdbEntityRail): String {
    val mediaLabel = when (rail.mediaType) {
        TmdbEntityMediaType.MOVIE -> stringResource(R.string.type_movie)
        TmdbEntityMediaType.TV -> stringResource(R.string.type_series)
    }
    val railLabel = when (rail.railType) {
        TmdbEntityRailType.POPULAR -> stringResource(R.string.tmdb_entity_rail_popular)
        TmdbEntityRailType.TOP_RATED -> stringResource(R.string.tmdb_entity_rail_top_rated)
        TmdbEntityRailType.RECENT -> stringResource(R.string.tmdb_entity_rail_recent)
    }
    return "$mediaLabel • $railLabel"
}

/**
 * Samples pixels from a [Bitmap] and returns true only if the non-transparent
 * pixels are both predominantly dark AND low-saturation (grayscale / monochrome).
 * This avoids treating colorful logos (red, blue, etc.) as "black".
 */
private fun isLogoDarkAndMonochrome(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val step = maxOf(1, minOf(width, height) / 20) // sample ~20×20 grid
    var totalLuminance = 0.0
    var totalSaturation = 0.0
    var count = 0
    val hsv = FloatArray(3)
    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha > 50) { // skip transparent / nearly-transparent pixels
                val r = ((pixel ushr 16) and 0xFF) / 255.0
                val g = ((pixel ushr 8) and 0xFF) / 255.0
                val b = (pixel and 0xFF) / 255.0
                totalLuminance += 0.2126 * r + 0.7152 * g + 0.0722 * b
                android.graphics.Color.colorToHSV(pixel, hsv)
                totalSaturation += hsv[1]
                count++
            }
            y += step
        }
        x += step
    }
    if (count == 0) return false
    val avgLuminance = totalLuminance / count
    val avgSaturation = totalSaturation / count
    // Dark (luminance < 0.3) AND grayscale (saturation < 0.2)
    return avgLuminance < 0.3 && avgSaturation < 0.2
}
