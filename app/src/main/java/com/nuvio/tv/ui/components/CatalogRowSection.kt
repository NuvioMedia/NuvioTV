package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.R
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.formatAddonTypeLabel

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CatalogRowSection(
    catalogRow: CatalogRow,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: () -> Unit = {},
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showPosterLabels: Boolean = true,
    showAddonName: Boolean = true,
    showCatalogTypeSuffix: Boolean = true,
    focusedPosterBackdropExpandEnabled: Boolean = false,
    focusedPosterBackdropExpandDelaySeconds: Int = 3,
    focusedPosterBackdropTrailerEnabled: Boolean = false,
    focusedPosterBackdropTrailerMuted: Boolean = true,
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    onRequestTrailerPreview: (MetaPreview) -> Unit = {},
    onItemFocus: (MetaPreview) -> Unit = {},
    isItemWatched: (MetaPreview) -> Boolean = { false },
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    enableRowFocusRestorer: Boolean = true,
    initialScrollIndex: Int = 0,
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    rowFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
) {
    val seeAllCardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val currentOnItemFocused by rememberUpdatedState(onItemFocused)
    val currentOnItemFocus by rememberUpdatedState(onItemFocus)

    val internalRowFocusRequester = remember { FocusRequester() }
    val resolvedRowFocusRequester = rowFocusRequester ?: internalRowFocusRequester
    val itemFocusRequestersById = remember { mutableMapOf<String, FocusRequester>() }
    var lastRequestedFocusItemId by remember { mutableStateOf<String?>(null) }
    var lastFocusedItemIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(catalogRow.items) {
        val validIds = catalogRow.items.mapTo(mutableSetOf()) { it.id }
        itemFocusRequestersById.keys.retainAll(validIds)
        if (lastRequestedFocusItemId !in validIds) {
            lastRequestedFocusItemId = null
        }
    }

    LaunchedEffect(focusedItemIndex, catalogRow.items) {
        if (focusedItemIndex >= 0 && focusedItemIndex < catalogRow.items.size) {
            val targetItemId = catalogRow.items[focusedItemIndex].id
            if (lastRequestedFocusItemId == targetItemId) return@LaunchedEffect
            val requester = itemFocusRequestersById.getOrPut(targetItemId) { FocusRequester() }
            repeat(2) { withFrameNanos { } }
            val focused = runCatching { requester.requestFocus() }.isSuccess
            if (focused) {
                lastRequestedFocusItemId = targetItemId
            }
        } else {
            lastRequestedFocusItemId = null
        }
    }

    val directionalFocusModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }

    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType) {
        formatAddonTypeLabel(
            catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        )
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = catalogTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
                if (showAddonName) {
                    Text(
                        text = stringResource(R.string.catalog_from_addon, catalogRow.addonName),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextTertiary
                    )
                }
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(resolvedRowFocusRequester)
                .then(
                    if (enableRowFocusRestorer && focusedItemIndex < 0 && catalogRow.items.isNotEmpty()) {
                        Modifier.focusRestorer {
                            val fallbackIndex = listState.firstVisibleItemIndex
                                .coerceIn(0, (catalogRow.items.size - 1).coerceAtLeast(0))
                            val fallbackItemId = catalogRow.items.getOrNull(fallbackIndex)?.id
                            if (fallbackItemId != null) {
                                itemFocusRequestersById.getOrPut(fallbackItemId) { FocusRequester() }
                            } else {
                                resolvedRowFocusRequester
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            contentPadding = PaddingValues(start = 48.dp, end = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = catalogRow.items,
                key = { index, item ->
                    "${catalogRow.addonId}_${catalogRow.apiType}_${catalogRow.catalogId}_${item.id}_$index"
                },
                contentType = { _, _ -> "content_card" }
            ) { index, item ->
                ContentCard(
                    item = item,
                    posterCardStyle = posterCardStyle,
                    showLabels = showPosterLabels,
                    focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
                    focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
                    focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    trailerPreviewUrl = trailerPreviewUrls[item.id],
                    onRequestTrailerPreview = onRequestTrailerPreview,
                    isWatched = isItemWatched(item),
                    onFocus = { focusedItem ->
                        currentOnItemFocus(focusedItem)
                        if (lastFocusedItemIndex != index) {
                            lastFocusedItemIndex = index
                            currentOnItemFocused(index)
                        }
                    },
                    onClick = { onItemClick(item.id, item.apiType, catalogRow.addonBaseUrl) },
                    onLongPress = { onItemLongPress(item, catalogRow.addonBaseUrl) },
                    modifier = Modifier.then(directionalFocusModifier),
                    focusRequester = itemFocusRequestersById.getOrPut(item.id) { FocusRequester() }
                )
            }

            if (catalogRow.items.size >= 15) {
                item(key = "${catalogRow.type}_${catalogRow.catalogId}_see_all") {
                    Card(
                        onClick = onSeeAll,
                        modifier = Modifier
                            .width(posterCardStyle.width)
                            .height(posterCardStyle.height)
                            .then(directionalFocusModifier),
                        shape = CardDefaults.shape(shape = seeAllCardShape),
                        colors = CardDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.BackgroundCard
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                                shape = seeAllCardShape
                            )
                        ),
                        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(R.string.action_see_all),
                                    modifier = Modifier.size(32.dp),
                                    tint = NuvioColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.action_see_all),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NuvioColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
