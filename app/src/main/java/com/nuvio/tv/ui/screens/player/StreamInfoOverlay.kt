package com.nuvio.tv.ui.screens.player

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioColors
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
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 36.dp)
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
        SectionLabel("SOURCE")
        Spacer(modifier = Modifier.height(4.dp))
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
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(12.dp))
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
                        text = data.streamName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!data.streamDescription.isNullOrBlank()) {
            Text(
                text = data.streamDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // FILE section
    val hasFileInfo = data.filename != null || data.fileSize != null
    if (hasFileInfo) {
        SectionLabel("FILE")
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = "Filename", value = data.filename)
            InfoItem(label = "Size", value = data.fileSize?.let { formatFileSize(it) })
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // VIDEO section
    val hasVideoInfo = data.videoCodec != null || data.videoWidth != null || data.videoFrameRate != null || data.videoBitrate != null
    if (hasVideoInfo) {
        SectionLabel("VIDEO")
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = "Codec", value = data.videoCodec)
            InfoItem(
                label = "Resolution",
                value = if (data.videoWidth != null && data.videoHeight != null) {
                    formatResolution(data.videoWidth, data.videoHeight)
                } else null
            )
            InfoItem(
                label = "Frame Rate",
                value = data.videoFrameRate?.let { "%.3f fps".format(it) }
            )
            InfoItem(
                label = "Bitrate",
                value = data.videoBitrate?.let { formatBitrate(it) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // AUDIO section
    val hasAudioInfo = data.audioCodec != null || data.audioChannels != null || data.audioLanguage != null || data.audioSampleRate != null
    if (hasAudioInfo) {
        SectionLabel("AUDIO")
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = "Codec", value = data.audioCodec)
            InfoItem(label = "Channels", value = data.audioChannels)
            InfoItem(
                label = "Sample Rate",
                value = data.audioSampleRate?.let { "${it / 1000} kHz" }
            )
            InfoItem(
                label = "Language",
                value = data.audioLanguage?.let { languageCodeToName(it) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // SUBTITLE section
    val hasSubtitleInfo = data.subtitleName != null || data.subtitleCodec != null || data.subtitleLanguage != null
    if (hasSubtitleInfo) {
        SectionLabel("SUBTITLE")
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            InfoItem(label = "Name", value = data.subtitleName)
            InfoItem(label = "Codec", value = data.subtitleCodec)
            InfoItem(
                label = "Language",
                value = data.subtitleLanguage?.let { languageCodeToName(it) }
            )
            InfoItem(label = "Source", value = data.subtitleSource)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioColors.TextTertiary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun InfoItem(label: String, value: String?) {
    if (value == null) return
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatBitrate(bps: Int): String {
    return when {
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f kbps".format(bps / 1_000.0)
        else -> "$bps bps"
    }
}

private fun formatResolution(width: Int, height: Int): String {
    val label = when {
        height >= 2160 || width >= 3840 -> "4K"
        height >= 1440 || width >= 2560 -> "1440p"
        height >= 1080 || width >= 1920 -> "1080p"
        height >= 720 || width >= 1280 -> "720p"
        height >= 480 || width >= 854 -> "480p"
        else -> "${height}p"
    }
    return "$width × $height ($label)"
}
