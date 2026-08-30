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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.components.StreamBadgeChips
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch as coroutineLaunch
import com.nuvio.tv.ui.components.RefreshFilterChip
import com.nuvio.tv.R

@Composable
internal fun StreamItem(
    stream: Stream,
    focusRequester: FocusRequester? = null,
    requestInitialFocus: Boolean = true,
    isCurrentStream: Boolean = false,
    showFileSizeBadges: Boolean = true,
    showAddonLogo: Boolean = true,
    badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    onClick: () -> Unit,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onUpKey: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val unknownStreamLabel = stringResource(R.string.stream_unknown)
    val streamName = remember(stream, unknownStreamLabel) { stream.getDisplayNameOrNull() ?: unknownStreamLabel }
    val streamDescription = remember(stream) { stream.getDisplayDescription() }
    val hasBadges = stream.badges.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints?.videoSize != null)
    // Pre-upscale: decode at 2× target pixels so the hardware compositor
    // has enough pixel data for smooth edges inside Card RenderNodes.
    val logoDecodeSize = remember(density) {
        with(density) { NuvioTheme.spacing.xxl.roundToPx() } * 2
    }
    val addonLogoModel = remember(context, stream.addonLogo, logoDecodeSize) {
        stream.addonLogo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .size(width = logoDecodeSize, height = logoDecodeSize)
                .memoryCacheKey("${logo}_${logoDecodeSize}x${logoDecodeSize}")
                .crossfade(true)
                .build()
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null && requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                onFocusChanged?.invoke(it.isFocused)
            }
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(
                    NuvioTheme.spacing.hairline,
                    if (isCurrentStream) NuvioTheme.colors.Primary.copy(alpha = 0.65f) else Color.Transparent
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                    StreamBadgeChips(
                        badges = stream.badges,
                        fileSizeBytes = stream.behaviorHints?.videoSize,
                        showFileSizeBadge = showFileSizeBadges
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    Text(
                        text = streamName,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )

                    if (isCurrentStream) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(NuvioTheme.colors.Primary.copy(alpha = 0.2f))
                                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                        ) {
                            Text(
                                text = stringResource(R.string.sources_playing),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.Primary
                            )
                        }
                    }
                }

                streamDescription?.let { description ->
                    if (description != streamName) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary
                        )
                    }
                }

                if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                    StreamBadgeChips(
                        badges = stream.badges,
                        fileSizeBytes = stream.behaviorHints?.videoSize,
                        showFileSizeBadge = showFileSizeBadges,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)
                    )
                }
            }

            if (showAddonLogo) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    if (addonLogoModel != null) {
                        AsyncImage(
                            model = addonLogoModel,
                            contentDescription = stream.addonName,
                            modifier = Modifier
                                .size(NuvioTheme.spacing.xxl)
                                .clip(RoundedCornerShape(NuvioTheme.radii.xs)),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem> = emptyList(),
    selectedAddon: String?,
    isStillFetching: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onAddonSelected: (String?) -> Unit,
    externalFocusRequesters: List<FocusRequester>? = null,
    externalOrderedNames: List<String>? = null,
    onUpKey: (() -> Unit)? = null,
    debugTag: String = "AddonFilterChips"
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val chipMap = sourceChips.associateBy { it.name }
    val orderedNames = externalOrderedNames ?: remember(addons, sourceChips) {
        buildList {
            addAll(addons)
            sourceChips.forEach { chip -> if (chip.name !in this) add(chip.name) }
        }
    }
    val hasRefresh = onRefresh != null
    val refreshFocusRequester = remember { FocusRequester() }
    val allFocusRequester = remember { FocusRequester() }
    val addonFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val focusRequesters = externalFocusRequesters ?: remember(orderedNames) {
        buildList {
            if (hasRefresh) add(refreshFocusRequester)
            add(allFocusRequester)
            orderedNames.forEach { addon ->
                add(addonFocusRequesters.getOrPut(addon) { FocusRequester() })
            }
        }
    }

    var chipRowHasFocus by remember { mutableStateOf(false) }
    var refreshHasFocus by remember { mutableStateOf(false) }
    var focusedChipIndex by remember { mutableStateOf(
        if (hasRefresh) {
            if (selectedAddon == null) 1 else (orderedNames.indexOf(selectedAddon) + 2).coerceAtLeast(1)
        } else {
            if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        }
    ) }
    LaunchedEffect(selectedAddon, orderedNames) {
        if (refreshHasFocus || focusedChipIndex == 0) return@LaunchedEffect
        val maxIndex = if (hasRefresh) orderedNames.size + 1 else orderedNames.size
        if (focusedChipIndex > maxIndex) {
            focusedChipIndex = maxIndex.coerceAtLeast(if (hasRefresh) 1 else 0)
        }
        val currentAddonAtFocus = if (hasRefresh) {
            if (focusedChipIndex == 1) null else orderedNames.getOrNull(focusedChipIndex - 2)
        } else {
            if (focusedChipIndex == 0) null else orderedNames.getOrNull(focusedChipIndex - 1)
        }
        if (currentAddonAtFocus == selectedAddon) return@LaunchedEffect
        val idx = if (hasRefresh) {
            if (selectedAddon == null) 1 else (orderedNames.indexOf(selectedAddon) + 2).coerceAtLeast(1)
        } else {
            if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        }
        focusedChipIndex = idx.coerceIn(0, maxIndex)
        // When orderedNames changed (new addon arrived) and chip row has focus,
        // move actual focus to the correct chip so highlight doesn't stick on the wrong one.
        if (chipRowHasFocus) {
            withFrameNanos {}
            if (idx in focusRequesters.indices) {
                runCatching { focusRequesters[idx].requestFocus() }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    fun moveFocusTo(targetIndex: Int) {
        focusedChipIndex = targetIndex
        if (hasRefresh && targetIndex == 0) {
            refreshHasFocus = true
            scope.coroutineLaunch {
                withFrameNanos {}
                try { focusRequesters[0].requestFocus() } catch (_: Exception) {}
            }
            return
        }

        refreshHasFocus = false
        val selectedFilter = if (hasRefresh) {
            if (targetIndex == 1) null else orderedNames.getOrNull(targetIndex - 2)
        } else {
            if (targetIndex == 0) null else orderedNames.getOrNull(targetIndex - 1)
        }
        onAddonSelected(selectedFilter)
        scope.coroutineLaunch {
            withFrameNanos {}
            if (targetIndex in focusRequesters.indices) {
                try { focusRequesters[targetIndex].requestFocus() } catch (_: Exception) {}
            }
        }
    }

    val chipListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // When the selected addon is removed, switch filter to the last available addon
    LaunchedEffect(selectedAddon, orderedNames) {
        if (selectedAddon != null && selectedAddon !in orderedNames) {
            val lastAddon = orderedNames.lastOrNull()
            onAddonSelected(lastAddon)
        }
    }

    LazyRow(
        state = chipListState,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        modifier = Modifier
            .onFocusChanged { focusState ->
                val hasFocus = focusState.hasFocus
                if (hasFocus && !chipRowHasFocus && isRtl) {
                    scope.coroutineLaunch {
                        withFrameNanos {}
                        focusRequesters.getOrNull(focusedChipIndex)?.requestFocus()
                    }
                }
                chipRowHasFocus = hasFocus
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }

                if (event.key == androidx.compose.ui.input.key.Key.DirectionUp && onUpKey != null) {
                    onUpKey()
                    return@onKeyEvent true
                }

                val lastIndex = if (hasRefresh) orderedNames.size + 1 else orderedNames.size
                val currentIdx = focusedChipIndex.coerceIn(0, lastIndex)
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        if (isRtl) {
                            if (currentIdx < lastIndex) { moveFocusTo(currentIdx + 1); true } else true
                        } else {
                            if (currentIdx > 0) { moveFocusTo(currentIdx - 1); true } else true
                        }
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        if (isRtl) {
                            if (currentIdx > 0) { moveFocusTo(currentIdx - 1); true } else true
                        } else {
                            if (currentIdx < lastIndex) { moveFocusTo(currentIdx + 1); true } else true
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (onRefresh != null) {
            item {
                RefreshFilterChip(
                    onClick = onRefresh,
                    isLoading = isStillFetching,
                    onFocusChanged = { isFocused ->
                        refreshHasFocus = isFocused
                        if (isFocused) focusedChipIndex = 0
                    },
                    modifier = Modifier
                        .focusRequester(focusRequesters[0])
                        .focusProperties { canFocus = focusedChipIndex == 0 }
                )
            }
        }

        item {
            val isAllSelected = selectedAddon == null && !refreshHasFocus
            val allChipIndex = if (hasRefresh) 1 else 0
            SourceStatusFilterChip(
                name = stringResource(R.string.stream_filter_all),
                isSelected = isAllSelected,
                status = SourceChipStatus.SUCCESS,
                isSelectable = true,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[allChipIndex])
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedChipIndex = allChipIndex
                            refreshHasFocus = false
                        }
                    }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            val requesterIdx = if (hasRefresh) i + 2 else i + 1
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { if (isSelectable) onAddonSelected(addon) },
                modifier = Modifier
                    .focusRequester(focusRequesters[requesterIdx])
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedChipIndex = requesterIdx
                            refreshHasFocus = false
                        }
                    }
            )
        }
    }
}
