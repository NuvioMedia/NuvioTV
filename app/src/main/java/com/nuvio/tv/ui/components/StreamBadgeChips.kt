package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.StreamBadge

@Composable
fun StreamBadgeChips(
    badges: List<StreamBadge>,
    modifier: Modifier = Modifier
) {
    val imageBadges = remember(badges) { badges.filter { it.imageURL.isNotBlank() } }
    if (imageBadges.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        imageBadges.forEach { badge ->
            StreamImportedBadgeChip(badge = badge)
        }
    }
}

@Composable
private fun StreamImportedBadgeChip(badge: StreamBadge) {
    val shape = RoundedCornerShape(6.dp)
    val context = LocalContext.current
    val backgroundColor = remember(badge.tagColor, badge.tagStyle) {
        badge.tagColor.toBadgeColorOrNull()
            ?.takeIf { badge.tagStyle.equals("filled", ignoreCase = true) }
    }
    val outlineColor = remember(badge.borderColor) {
        badge.borderColor.toBadgeColorOrNull()
    }
    val imageRequest = remember(context, badge.imageURL) {
        ImageRequest.Builder(context)
            .data(badge.imageURL)
            .memoryCacheKey(badge.imageURL)
            .diskCacheKey(badge.imageURL)
            .crossfade(false)
            .build()
    }
    val chipModifier = Modifier
        .height(20.dp)
        .then(if (backgroundColor != null) Modifier.background(backgroundColor, shape) else Modifier)
        .then(if (outlineColor != null) Modifier.border(1.dp, outlineColor, shape) else Modifier)

    Box(
        modifier = chipModifier
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = badge.name,
            modifier = Modifier
                .height(16.dp)
                .widthIn(min = 34.dp, max = 92.dp)
                .clip(shape),
            contentScale = ContentScale.Fit
        )
    }
}

private fun String.toBadgeColorOrNull(): Color? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it) }
}
