@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val IPTV_HALF_HOUR_MS = 30L * 60 * 1000
internal const val IPTV_HOUR_MS = 2 * IPTV_HALF_HOUR_MS

internal fun floorLocal(time: Long, step: Long): Long {
    val offset = TimeZone.getDefault().getOffset(time).toLong()
    return time - (time + offset).mod(step)
}

internal class IptvTimeFormatter(context: Context) {
    private val timeFormat = SimpleDateFormat(
        if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
        Locale.getDefault()
    )
    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    fun time(millis: Long): String = timeFormat.format(Date(millis))

    fun range(start: Long, end: Long): String = "${time(start)} – ${time(end)}"

    fun dayOffset(millis: Long, now: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = now }
        val b = Calendar.getInstance().apply { timeInMillis = millis }
        val dayA = a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR)
        val dayB = b.get(Calendar.YEAR) * 400 + b.get(Calendar.DAY_OF_YEAR)
        return dayB - dayA
    }

    fun day(millis: Long): String = dayFormat.format(Date(millis))
}

@Composable
internal fun IptvMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingText: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.SurfaceVariant else Color.Transparent,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(2.dp), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!trailingText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun IptvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.SurfaceVariant,
            contentColor = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(2.dp), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun IptvChannelLogo(
    logo: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = size * 1.5f, height = size)
            .clip(RoundedCornerShape(6.dp))
            .background(NuvioTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        } else {
            Text(
                text = name.trim().take(3).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun IptvChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.SurfaceVariant,
            contentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(2.dp), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
