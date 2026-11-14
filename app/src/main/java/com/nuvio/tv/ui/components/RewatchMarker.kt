package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

/**
 * The circular arrow badge that sits next to the watched marker on an episode the running rewatch
 * has covered.
 *
 * It uses the same circle, accent brush and on-accent colour as [WatchedMarker], so the two read as
 * one status pair. The refresh glyph is a vector, so it stays crisp at the 21 dp and 28 dp cards and
 * does not depend on the selected text font. The icon is drawn without its own semantics: the box
 * carries the rewatched description for screen readers.
 */
@Composable
fun RewatchMarker(
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    iconSize: Dp = size * 0.81f,
) {
    val palette = NuvioTheme.palette
    val description = stringResource(R.string.episodes_cd_rewatched)

    Box(
        modifier = modifier
            .size(size)
            .shadow(10.dp, shape = CircleShape, spotColor = Color.Transparent)
            .background(palette.accentBrush(), CircleShape)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            tint = palette.onSecondary,
            modifier = Modifier.size(iconSize)
        )
    }
}
