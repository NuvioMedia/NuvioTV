package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.languageCodeToName

@Composable
fun StreamInfoOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    data: StreamInfoData?,
    modifier: Modifier = Modifier
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        dismissOnCenter = true,
        contentPadding = PaddingValues(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = 36.dp, bottom = 36.dp)
    ) {
        if (data != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart),
                verticalArrangement = Arrangement.Bottom
            ) {
                StreamInfoContent(data = data)
            }
        }
    }
}

@Composable
private fun StreamInfoContent(data: StreamInfoData) {
    // SOURCE section
    val hasSourceInfo = data.addonName != null || data.streamName != null
    if (hasSourceInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_source))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!data.addonLogo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(data.addonLogo)
                        .crossfade(true)
                        .build(),
                    contentDescription = data.addonName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column {
                if (data.addonName != null) {
                    Text(
                        text = data.addonName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (data.streamName != null && data.streamName != data.addonName) {
                    Text(
                        text = data.streamName.replace("\n", " · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!data.streamDescription.isNullOrBlank()) {
            Text(
                text = data.streamDescription.replace("\n", " · "),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }
        if (data.playerEngine != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            InfoItem(label = stringResource(R.string.stream_info_player_engine), value = data.playerEngine)
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // FILE section
    val hasFileInfo = data.filename != null || data.fileSize != null
    if (hasFileInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_file))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_filename), value = data.filename, modifier = Modifier.weight(1f))
            InfoItem(label = stringResource(R.string.stream_info_size), value = data.fileSize?.let { formatFileSize(it) })
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // VIDEO section
    val hasVideoInfo = data.videoCodec != null || data.videoWidth != null || data.videoFrameRate != null || data.videoBitrate != null
    if (hasVideoInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_video))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.videoCodec)
            InfoItem(
                label = stringResource(R.string.stream_info_resolution),
                value = if (data.videoWidth != null && data.videoHeight != null) {
                    formatResolution(data.videoWidth, data.videoHeight)
                } else null
            )
            InfoItem(
                label = stringResource(R.string.stream_info_frame_rate),
                value = data.videoFrameRate?.let { "%.3f fps".format(it) }
            )
            InfoItem(
                label = stringResource(R.string.stream_info_bitrate),
                value = data.videoBitrate?.let { formatBitrate(it) }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // AUDIO section
    val hasAudioInfo = data.audioCodec != null || data.audioChannels != null || data.audioLanguage != null || data.audioSampleRate != null
    if (hasAudioInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_audio))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.audioCodec)
            InfoItem(label = stringResource(R.string.stream_info_channels), value = data.audioChannels)
            InfoItem(
                label = stringResource(R.string.stream_info_sample_rate),
                value = data.audioSampleRate?.let { "${it / 1000} kHz" }
            )
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.audioLanguage?.let { languageCodeToName(it) }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }

    // SUBTITLE section
    val hasSubtitleInfo = data.subtitleName != null || data.subtitleCodec != null || data.subtitleLanguage != null
    if (hasSubtitleInfo) {
        SectionLabel(stringResource(R.string.stream_info_section_subtitle))
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = stringResource(R.string.stream_info_name), value = data.subtitleName)
            InfoItem(label = stringResource(R.string.stream_info_codec), value = data.subtitleCodec)
            InfoItem(
                label = stringResource(R.string.stream_info_language),
                value = data.subtitleLanguage?.let { languageCodeToName(it) }
            )
            InfoItem(label = stringResource(R.string.stream_info_source), value = data.subtitleSource)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun InfoItem(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> stringResource(R.string.unit_size_gb, "%.1f".format(bytes / 1_073_741_824.0))
        bytes >= 1_048_576L -> stringResource(R.string.unit_size_mb, "%.1f".format(bytes / 1_048_576.0))
        bytes >= 1024L -> stringResource(R.string.unit_size_kb, "%.1f".format(bytes / 1024.0))
        else -> stringResource(R.string.unit_size_b, bytes)
    }
}

private fun formatBitrate(bps: Int): String {
    return when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f kbps".format(bps / 1_000.0)
        else -> "$bps bps"
    }
}

internal fun formatResolution(width: Int, height: Int): String {
    val maxDim = maxOf(width, height)
    val label = when {
        maxDim >= 3600 -> "4K"
        maxDim >= 2400 -> "1440p"
        maxDim >= 1800 -> "1080p"
        maxDim >= 1200 -> "720p"
        maxDim >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}
