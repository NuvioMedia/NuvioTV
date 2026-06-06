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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.StreamBadge
import kotlin.math.round

@Composable
fun StreamBadgeChips(
    badges: List<StreamBadge>,
    fileSizeBytes: Long? = null,
    showFileSizeBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val imageBadges = remember(badges) { badges.filter { it.imageURL.isNotBlank() } }
    val sizeBytes = fileSizeBytes.takeIf { showFileSizeBadge }
    if (imageBadges.isEmpty() && sizeBytes == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (sizeBytes != null) {
            StreamFileSizeBadge(bytes = sizeBytes)
        }
        imageBadges.forEach { badge ->
            StreamImportedBadgeChip(badge = badge)
        }
    }
}

@Composable
private fun StreamFileSizeBadge(bytes: Long) {
    val label = remember(bytes) {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 1.0) {
            val roundedGiB = round(gib * 10.0) / 10.0
            "$roundedGiB GB"
        } else {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            "${round(mib).toInt()} MB"
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(shape)
            .background(Color(0xFF0A0C0C), shape)
            .border(1.dp, Color(0xFF0A0C0C), shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.streams_size, label),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@Composable
private fun StreamImportedBadgeChip(badge: StreamBadge) {
    val shape = RoundedCornerShape(6.dp)
    val context = LocalContext.current
    val density = LocalDensity.current
    val backgroundColor = remember(badge.tagColor, badge.tagStyle) {
        badge.tagColor.toBadgeColorOrNull()
            ?.takeIf { badge.tagStyle.equals("filled", ignoreCase = true) }
    }
    val outlineColor = remember(badge.borderColor) {
        badge.borderColor.toBadgeColorOrNull()
    }
    // Pre-upscale: decode at 2× target pixels so the hardware compositor
    // has enough pixel data for smooth edges inside Card RenderNodes.
    val decodeHeight = remember(density) {
        with(density) { 16.dp.roundToPx() } * 2
    }
    // Use a wide max-width to let Coil decode at aspect ratio constrained by height.
    val decodeWidth = remember(density) {
        with(density) { 92.dp.roundToPx() } * 2
    }
    val imageRequest = remember(context, badge.imageURL, decodeHeight) {
        ImageRequest.Builder(context)
            .data(badge.imageURL)
            .size(width = decodeWidth, height = decodeHeight)
            .memoryCacheKey("${badge.imageURL}_${decodeWidth}x${decodeHeight}")
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
