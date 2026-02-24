package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GridContentCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showLabel: Boolean = true,
    imageCrossfade: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {}
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val density = LocalDensity.current
    val requestWidthPx = remember(density, posterCardStyle.width) { with(density) { posterCardStyle.width.roundToPx() } }
    val requestHeightPx = remember(density, posterCardStyle.height) { with(density) { posterCardStyle.height.roundToPx() } }

    Column(
        modifier = modifier.width(posterCardStyle.width)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .width(posterCardStyle.width)
                .height(posterCardStyle.height)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .then(
                    if (upFocusRequester != null) {
                        Modifier.focusProperties { up = upFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged { state ->
                    if (state.isFocused) onFocused()
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
            ) {
                val context = LocalContext.current
                val imageModel = remember(context, item.poster, requestWidthPx, requestHeightPx) {
                    ImageRequest.Builder(context)
                        .data(item.poster)
                        .crossfade(imageCrossfade)
                        .size(width = requestWidthPx, height = requestHeightPx)
                        .memoryCacheKey("${item.poster}_${requestWidthPx}x${requestHeightPx}")
                        .build()
                }
                if (item.poster.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        if (showLabel) {
            val textMeasurer = rememberTextMeasurer()
            val titleStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary)
            val itemName = item.name
            Box(
                modifier = Modifier
                    .width(posterCardStyle.width)
                    .padding(top = 8.dp, start = 2.dp, end = 2.dp)
                    .height(24.dp)
                    .drawWithCache {
                        val layout = textMeasurer.measure(
                            text = itemName,
                            style = titleStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = size.width.toInt())
                        )
                        onDrawBehind { drawText(layout) }
                    }
            )
        }
    }
}
