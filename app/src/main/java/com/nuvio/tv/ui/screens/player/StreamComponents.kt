@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun StreamItem(
    stream: Stream,
    focusRequester: FocusRequester,
    requestInitialFocus: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stream.getDisplayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )

                stream.getDisplayDescription()?.let { description ->
                    if (description != stream.getDisplayName()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (stream.isTorrent()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_torrent), color = NuvioColors.Secondary)
                    }
                    if (stream.isYouTube()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_youtube), color = Color(0xFFFF0000))
                    }
                    if (stream.isExternal()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_external), color = NuvioColors.Primary)
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                if (stream.addonLogo != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(stream.addonLogo)
                            .crossfade(true)
                            .build(),
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StreamTypeChip(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    selectedAddon: String?,
    onAddonSelected: (String?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        item {
            AddonChip(
                name = "All",
                isSelected = selectedAddon == null,
                onClick = { onAddonSelected(null) }
            )
        }

        items(addons) { addon ->
            AddonChip(
                name = addon,
                isSelected = selectedAddon == addon,
                onClick = { onAddonSelected(addon) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier,
        colors = FilterChipDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            selectedContainerColor = NuvioColors.Secondary,
            focusedSelectedContainerColor = NuvioColors.Secondary,
            contentColor = NuvioColors.TextSecondary,
            focusedContentColor = NuvioColors.OnSecondary,
            selectedContentColor = NuvioColors.OnSecondary,
            focusedSelectedContentColor = NuvioColors.OnSecondary
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            ),
            selectedBorder = Border(
                border = BorderStroke(1.dp, NuvioColors.Primary),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedSelectedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(20.dp))
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
