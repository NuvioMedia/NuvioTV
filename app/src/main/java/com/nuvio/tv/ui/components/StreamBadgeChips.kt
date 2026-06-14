package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

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
import androidx.compose.ui.draw.alpha
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

/**
 * Shared badge ImageRequest cache. Badges with the same URL and target decode
 * dimensions share a single [ImageRequest] instance across all composables in
 * the stream list. This avoids per-item allocations of builder objects and
 * intermediate strings that pressure the GC on low-memory TV devices.
 *
 * The cache is keyed by the Coil memory-cache key (url + dimensions) and is
 * bounded by the number of unique badge images (typically 10-30 in practice).
 * Entries are never evicted — they live for the process lifetime which is fine
 * because they hold no bitmaps, only request metadata.
 */
private val badgeImageRequestCache = HashMap<String, ImageRequest>(32)

/** Shared shape instance — all badge chips use the same corner radius. */
private val BadgeChipShape = RoundedCornerShape(6.dp)

@Composable
fun StreamBadgeChips(
    badges: List<StreamBadge>,
    fileSizeBytes: Long? = null,
    showFileSizeBadge: Boolean = false,
    animate: Boolean = false,
    modifier: Modifier = Modifier
) {
    val imageBadges = remember(badges) { badges.filter { it.imageURL.isNotBlank() } }
    val sizeBytes = fileSizeBytes.takeIf { showFileSizeBadge }
    if (imageBadges.isEmpty() && sizeBytes == null) return

    val chipAlpha = if (animate) {
        val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            alpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(200))
        }
        alpha.value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(if (chipAlpha < 1f) Modifier.alpha(chipAlpha) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        imageBadges.forEach { badge ->
            StreamImportedBadgeChip(badge = badge)
        }
        if (sizeBytes != null) {
            StreamFileSizeBadge(bytes = sizeBytes)
        }
    }
}

@Composable
private fun StreamFileSizeBadge(bytes: Long) {
    val gbTemplate = stringResource(R.string.unit_size_gb)
    val mbTemplate = stringResource(R.string.unit_size_mb)
    val label = remember(bytes, gbTemplate, mbTemplate) {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 1.0) {
            val roundedGiB = round(gib * 10.0) / 10.0
            gbTemplate.format(roundedGiB.toString())
        } else {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            mbTemplate.format(round(mib).toInt().toString())
        }
    }
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(BadgeChipShape)
            .background(Color(0xFF0A0C0C), BadgeChipShape)
            .border(NuvioTheme.spacing.hairline, Color(0xFF0A0C0C), BadgeChipShape)
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
private fun StreamImportedBadgeChip(badge: StreamBadge, crossfade: Boolean = false) {
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
        with(density) { NuvioTheme.spacing.lg.roundToPx() } * 2
    }
    // Use a wide max-width to let Coil decode at aspect ratio constrained by height.
    val decodeWidth = remember(density) {
        with(density) { 92.dp.roundToPx() } * 2
    }
    // Reuse ImageRequest instances across composables via a shared HashMap.
    // This avoids creating a new Builder + intermediate strings per badge per
    // recomposition and ensures Coil's internal equality checks hit the same
    // object reference — preventing redundant decode dispatches.
    val cacheKey = "${badge.imageURL}_${decodeWidth}x${decodeHeight}"
    val imageRequest = remember(cacheKey) {
        badgeImageRequestCache.getOrPut(cacheKey) {
            ImageRequest.Builder(context)
                .data(badge.imageURL)
                .size(width = decodeWidth, height = decodeHeight)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(badge.imageURL)
                .crossfade(false)
                .build()
        }
    }
    val chipModifier = Modifier
        .height(20.dp)
        .then(if (backgroundColor != null) Modifier.background(backgroundColor, BadgeChipShape) else Modifier)
        .then(if (outlineColor != null) Modifier.border(NuvioTheme.spacing.hairline, outlineColor, BadgeChipShape) else Modifier)

    Box(
        modifier = chipModifier
            .padding(horizontal = 3.dp, vertical = NuvioTheme.spacing.xxs),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = badge.name,
            modifier = Modifier
                .height(NuvioTheme.spacing.lg)
                .widthIn(min = 34.dp, max = 92.dp)
                .clip(BadgeChipShape),
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
