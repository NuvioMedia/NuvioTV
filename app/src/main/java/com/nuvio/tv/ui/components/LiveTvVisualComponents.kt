package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.LocalUiMotion
import com.nuvio.tv.ui.theme.NuvioTheme

/** Shared Live TV primitives. Screens supply source data and connect the actions to real flows. */
internal data class LiveTvProgramPresentation(
    val key: String,
    val title: String,
    val timeLabel: String,
    val isCurrent: Boolean = false,
    val gapBeforeMinutes: Int = 0,
    val durationMinutes: Int = 60,
)

@Composable
internal fun LiveTvChannelTile(name: String, number: String?, logoUrl: String?, isSelected: Boolean,
    onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    LiveTvAction(isSelected, onClick, modifier.width(NuvioTheme.components.liveTv.channelWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            if (!logoUrl.isNullOrBlank()) AsyncImage(model = logoUrl, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                number?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LiveTvNowNext(nowTitle: String, nextTitle: String?, nextLabel: String, progress: Float?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Text(nowTitle, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        progress?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)?.let { fraction ->
            Box(Modifier.fillMaxWidth().height(NuvioTheme.components.liveTv.progressHeight)
                .clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.Surface)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(NuvioTheme.focusRing.solidColor))
            }
        }
        nextTitle?.takeIf(String::isNotBlank)?.let {
            Text("$nextLabel: $it", style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LiveTvProgramCell(program: LiveTvProgramPresentation, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val width = (NuvioTheme.components.liveTv.programWidth * (program.durationMinutes.coerceIn(15, 180) / 60f))
        .coerceAtLeast(72.dp)
    LiveTvAction(program.isCurrent, onClick, modifier.width(width)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Text(program.timeLabel, style = MaterialTheme.typography.labelSmall)
            Text(program.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Grid row primitive; guide data and playback actions are provided by the Live TV screen. */
@Composable
internal fun LiveTvGuideRow(channelName: String, channelNumber: String?, logoUrl: String?, selectedChannel: Boolean,
    programs: List<LiveTvProgramPresentation>, onChannelClick: (() -> Unit)?, onProgramClick: (String) -> Unit,
    modifier: Modifier = Modifier, scrollState: LazyListState = rememberLazyListState()) {
    val tokens = NuvioTheme.components.liveTv
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing), verticalAlignment = Alignment.CenterVertically) {
        LiveTvChannelTile(channelName, channelNumber, logoUrl, selectedChannel, onChannelClick)
        LazyRow(Modifier.weight(1f), state = scrollState, contentPadding = PaddingValues(tokens.focusedBorderWidth)) {
            items(programs, key = { it.key }) { program ->
                if (program.gapBeforeMinutes > 0) Spacer(Modifier.width(tokens.programWidth * (program.gapBeforeMinutes / 60f)))
                LiveTvProgramCell(program, onProgramClick.takeIf { onChannelClick != null }?.let { click -> { click(program.key) } })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveTvAction(isSelected: Boolean, onClick: (() -> Unit)?, modifier: Modifier, content: @Composable () -> Unit) {
    val tokens = NuvioTheme.components.liveTv
    val shape = RoundedCornerShape(tokens.cornerRadius)
    val motion = LocalUiMotion.current
    val actionModifier = modifier.heightIn(min = tokens.rowHeight).semantics { selected = isSelected }
    if (onClick == null) {
        androidx.compose.material3.Surface(modifier = actionModifier, shape = shape,
            color = NuvioTheme.colors.Surface) {
            Box(Modifier.padding(PaddingValues(tokens.contentPadding))) { content() }
        }
        return
    }
    Button(onClick = onClick, modifier = actionModifier,
        contentPadding = PaddingValues(tokens.contentPadding),
        colors = ButtonDefaults.colors(containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.Surface,
            contentColor = if (isSelected) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(focusedBorder = Border(NuvioTheme.focusRing.border(tokens.focusedBorderWidth), shape = shape)),
        scale = ButtonDefaults.scale(focusedScale = motion.scale(1.03f), pressedScale = motion.scale(0.98f))) { content() }
}
