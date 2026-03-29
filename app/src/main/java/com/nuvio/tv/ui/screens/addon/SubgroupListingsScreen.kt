package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.screens.home.HeroTitleBlock
import com.nuvio.tv.ui.screens.home.toHeroPreview
import com.nuvio.tv.ui.screens.home.MODERN_HERO_TEXT_WIDTH_FRACTION
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.GroupPreferenceDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.core.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.delay
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import androidx.compose.foundation.layout.Arrangement

@HiltViewModel
class SubgroupListingsViewModel @Inject constructor(
    private val groupPreferenceDataStore: GroupPreferenceDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<List<CatalogRow>>(emptyList())
    val uiState: StateFlow<List<CatalogRow>> = _uiState.asStateFlow()
    
    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()
    
    val useLandscapePosters: StateFlow<Boolean> = layoutPreferenceDataStore.modernLandscapePostersEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)
        
    val posterCardWidthDp: StateFlow<Int> = layoutPreferenceDataStore.posterCardWidthDp
        .stateIn(viewModelScope, SharingStarted.Lazily, 126)

    fun loadSubgroup(subgroupId: String) {
        viewModelScope.launch {
            val groups = groupPreferenceDataStore.catalogGroups.first()
            val group = groups.find { it.id == subgroupId } ?: return@launch
            _groupName.value = group.name
            val addons = addonRepository.getInstalledAddons().first()
            
            val rows = mutableListOf<CatalogRow>()
            _uiState.value = rows
            
            group.catalogKeys.forEach { key ->
                val delimiter = if (key.contains(":::")) ":::" else "_"
                val parts = key.split(delimiter, limit = 3)
                if (parts.size != 3) return@forEach
                
                val addonId = parts[0]
                val apiType = parts[1]
                val catalogId = parts[2]
                
                val addon = addons.find { it.id == addonId } ?: return@forEach
                val catalog = addon.catalogs.find { it.id == catalogId && it.apiType == apiType } ?: return@forEach
                
                val supportsSkip = catalog.supportsExtra("skip")
                val skipStep = catalog.skipStep()
                
                launch {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = 0,
                        skipStep = skipStep,
                        supportsSkip = supportsSkip
                    ).collect { result ->
                        if (result is NetworkResult.Success) {
                            val newRow = result.data
                            _uiState.value = _uiState.value + newRow
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SubgroupListingsScreen(
    subgroupId: String,
    viewModel: SubgroupListingsViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val groupName by viewModel.groupName.collectAsState()
    val useLandscapePosters by viewModel.useLandscapePosters.collectAsState()
    val posterCardWidthDp by viewModel.posterCardWidthDp.collectAsState()
    
    androidx.compose.runtime.LaunchedEffect(subgroupId) {
        viewModel.loadSubgroup(subgroupId)
    }

    val computedWidthDp = if (useLandscapePosters) (posterCardWidthDp.dp * 1.35f) else posterCardWidthDp.dp
    val computedHeightDp = if (useLandscapePosters) (computedWidthDp / 1.77f) else (posterCardWidthDp.dp * 1.5f)

    val posterCardStyle = PosterCardStyle(
        width = computedWidthDp,
        height = computedHeightDp,
        cornerRadius = 8.dp,
        focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
        focusedScale = PosterCardDefaults.Style.focusedScale
    )

    var activeBackdropItem by remember { mutableStateOf<MetaPreview?>(null) }
    val firstItem = remember(uiState) { uiState.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull() }
    val heroItem = activeBackdropItem ?: firstItem

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val rowHorizontalPadding = 52.dp

        if (heroItem != null) {
            SubgroupHeroScene(
                preview = heroItem,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(maxHeight)
            )
            HeroTitleBlock(
                preview = heroItem.toHeroPreview(
                    useLandscapePosters = useLandscapePosters,
                    strTypeMovie = stringResource(id = R.string.type_movie),
                    strTypeSeries = stringResource(id = R.string.type_series)
                ),
                portraitMode = !useLandscapePosters,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = rowHorizontalPadding,
                        end = 48.dp,
                        bottom = rowsViewportHeight + 16.dp
                    )
                    .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
            )
        }

        LazyColumn(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(rowsViewportHeight),
            contentPadding = PaddingValues(top = 16.dp, bottom = 36.dp)
        ) {
            item {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(start = rowHorizontalPadding, bottom = 24.dp)
                )
            }
            
            items(uiState) { row ->
                if (row.items.isNotEmpty()) {
                    val finalRow = if (useLandscapePosters) {
                        row.copy(items = row.items.map {
                            it.copy(
                                posterShape = PosterShape.LANDSCAPE,
                                poster = it.backdropUrl ?: it.poster
                            )
                        })
                    } else {
                        row
                    }
                    CatalogRowSection(
                        catalogRow = finalRow,
                        posterCardStyle = posterCardStyle,
                        onItemClick = { itemId, itemType, baseUrl ->
                            onNavigateToDetail(itemId, itemType, baseUrl)
                        },
                        onSeeAll = {
                            onNavigateToCatalogSeeAll(row.catalogId, row.addonId, row.apiType)
                        },
                        onItemLongPress = { _, _ -> },
                        onItemFocus = { item ->
                            activeBackdropItem = item
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SubgroupHeroScene(
    preview: MetaPreview,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenConf = LocalConfiguration.current
    val screenWPx = remember(screenConf, density) { with(density) { (screenConf.screenWidthDp.dp * 1.5f).roundToPx() } }
    val screenHPx = remember(screenConf, density) { with(density) { (screenConf.screenHeightDp.dp * 1.5f).roundToPx() } }
    
    val rawBackdrop = preview.backdropUrl ?: preview.poster
    val highResBackdrop = remember(rawBackdrop) {
        rawBackdrop?.let {
            if (it.contains("image.tmdb.org")) {
                it.replace("/w500/", "/original/")
                  .replace("/w780/", "/original/")
                  .replace("/w1280/", "/original/")
                  .replace("/w342/", "/original/")
            } else it
        }
    }
        
    val imageModel = remember(context, highResBackdrop, screenWPx, screenHPx) {
        ImageRequest.Builder(context)
            .data(highResBackdrop)
            .crossfade(400)
            .size(width = screenWPx, height = screenHPx)
            .build()
    }

    Box(modifier = modifier) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd
        )
        
        val bgColor = NuvioColors.Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val horizontalFadeEndX = size.width * 0.65f
                    val horizontalGradient = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to bgColor,
                            0.22f to bgColor.copy(alpha = 0.90f),
                            0.46f to bgColor.copy(alpha = 0.80f),
                            0.76f to bgColor.copy(alpha = 0.42f),
                            1.0f to Color.Transparent
                        ),
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                    
                    val bottomStripStartY = size.height * 0.64f
                    val verticalGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        ),
                        startY = bottomStripStartY,
                        endY = size.height
                    )

                    onDrawBehind {
                        drawRect(
                            brush = horizontalGradient,
                            topLeft = Offset(0f, 0f),
                            size = Size(horizontalFadeEndX, size.height)
                        )
                        drawRect(
                            brush = verticalGradient,
                            topLeft = Offset(0f, bottomStripStartY),
                            size = Size(size.width, size.height - bottomStripStartY)
                        )
                    }
                }
        )
    }
}
