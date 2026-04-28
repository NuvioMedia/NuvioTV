package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors

private data class TrailerListItem(
    val trailer: MetaTrailer,
    val preview: MetaPreview
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrailerSection(
    trailers: List<MetaTrailer>,
    upFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restoreTrailerId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onTrailerFocused: (MetaTrailer) -> Unit = {},
    onTrailerClick: (MetaTrailer) -> Unit
) {
    if (trailers.isEmpty()) return

    val trailerItems = remember(trailers) {
        trailers.mapNotNull { trailer ->
            val ytId = trailer.ytId?.trim().orEmpty()
            if (ytId.isBlank()) return@mapNotNull null
            val title = trailer.name?.takeIf { it.isNotBlank() }
                ?: trailer.type?.takeIf { it.isNotBlank() }
                ?: "Trailer"
            val subtitle = buildList {
                trailer.type?.takeIf { it.isNotBlank() }?.let(::add)
                trailer.lang?.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
            }.joinToString(" • ")

            TrailerListItem(
                trailer = trailer,
                preview = MetaPreview(
                    id = ytId,
                    type = ContentType.MOVIE,
                    name = title,
                    poster = "https://img.youtube.com/vi/$ytId/hqdefault.jpg",
                    posterShape = PosterShape.LANDSCAPE,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = subtitle.ifBlank { null },
                    imdbRating = null,
                    genres = emptyList(),
                    trailerYtIds = listOf(ytId),
                    trailers = listOf(trailer)
                )
            )
        }
    }

    if (trailerItems.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(trailerItems) {
        val validIds = trailerItems.mapTo(mutableSetOf()) { it.preview.id }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    LaunchedEffect(restoreFocusToken, restoreTrailerId, trailerItems) {
        if (restoreFocusToken <= 0 || restoreTrailerId.isNullOrBlank()) return@LaunchedEffect
        if (trailerItems.none { it.preview.id == restoreTrailerId }) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames()
    }

    val landscapeStyle = remember {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer { firstItemFocusRequester },
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = trailerItems,
                key = { index, item -> item.preview.id + "|" + item.preview.name + "|" + index }
            ) { index, item ->
                val isRestoreTarget = item.preview.id == restoreTrailerId
                val isFirstItem = index == 0
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstItem -> firstItemFocusRequester
                    else -> remember(item.preview.id) {
                        itemFocusRequesters.getOrPut(item.preview.id) { FocusRequester() }
                    }
                }

                Column {
                    GridContentCard(
                        item = item.preview,
                        onClick = { onTrailerClick(item.trailer) },
                        posterCardStyle = landscapeStyle,
                        showLabel = true,
                        imageCrossfade = true,
                        focusRequester = focusRequester,
                        upFocusRequester = upFocusRequester,
                        onFocused = {
                            onTrailerFocused(item.trailer)
                            if (isRestoreTarget && restoreFocusToken > 0) {
                                onRestoreFocusHandled()
                            }
                        }
                    )

                    val subtitle = item.preview.releaseInfo
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = 2.dp, end = 2.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
