package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

@Composable
fun WatchedMarker(
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    iconSize: Dp = 20.dp
) {
    val palette = NuvioTheme.palette

    Box(
        modifier = modifier
            .size(size)
            .shadow(10.dp, shape = CircleShape, spotColor = Color.Transparent)
            .background(palette.accentBrush(), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            tint = palette.onSecondary,
            contentDescription = stringResource(R.string.episodes_cd_watched),
            modifier = Modifier.size(iconSize)
        )
    }
}
