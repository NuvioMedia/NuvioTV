package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionRowSection(
    collection: Collection,
    onFolderClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    onFolderFocused: (collection: Collection, folder: CollectionFolder) -> Unit = { _, _ -> }
) {
    val currentOnItemFocused by rememberUpdatedState(onItemFocused)
    val currentOnFolderFocused by rememberUpdatedState(onFolderFocused)
    val rowFocusRequester = remember { FocusRequester() }
    var lastFocusedItemIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = collection.title,
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocusRequester)
                .focusRestorer(),
            contentPadding = PaddingValues(start = 48.dp, end = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = collection.folders,
                key = { _, folder -> "collection_${collection.id}_folder_${folder.id}" },
                contentType = { _, _ -> "collection_folder" }
            ) { index, folder ->
                FolderCard(
                    folder = folder,
                    collection = collection,
                    onClick = { onFolderClick(collection.id, folder.id) },
                    onFocused = {
                        if (lastFocusedItemIndex != index) {
                            lastFocusedItemIndex = index
                            currentOnItemFocused(index)
                        }
                        currentOnFolderFocused(collection, folder)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderCard(
    folder: CollectionFolder,
    collection: Collection,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileWidth: Dp
    val tileHeight: Dp
    var isFocused by remember { mutableStateOf(false) }
    when (folder.tileShape) {
        PosterShape.POSTER -> { tileWidth = 126.dp; tileHeight = 189.dp }
        PosterShape.LANDSCAPE -> { tileWidth = 224.dp; tileHeight = 126.dp }
        PosterShape.SQUARE -> { tileWidth = 150.dp; tileHeight = 150.dp }
    }

    val shape = RoundedCornerShape(12.dp)
    val cardGlow = rememberArtworkBackedCardGlow(
        imageUrl = folder.coverImageUrl,
        fallbackSeed = "${collection.title}:${folder.title}:${folder.coverEmoji.orEmpty()}",
        enabled = collection.focusGlowEnabled
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .width(tileWidth)
            .height(tileHeight)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = shape),
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
        scale = CardDefaults.scale(focusedScale = 1.05f),
        glow = cardGlow
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val activeImageUrl = collectionFolderCardImageUrl(folder, isFocused)
            if (!activeImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = activeImageUrl,
                    contentDescription = folder.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                    contentScale = ContentScale.FillBounds
                )
            } else if (!folder.coverEmoji.isNullOrBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.coverEmoji,
                        fontSize = 48.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            if (!folder.hideTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
