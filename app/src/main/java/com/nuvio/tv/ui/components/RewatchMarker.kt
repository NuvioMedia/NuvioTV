package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

/**
 * The R badge that sits next to the watched marker on an episode the running rewatch has covered.
 *
 * It uses the same circle, accent brush and on-accent colour as [WatchedMarker], so the two read as
 * one status pair. The letter uses the app's selected font at a bold display weight; it is sized
 * from the badge so the 21 dp and 28 dp cards keep the same proportion.
 */
@Composable
fun RewatchMarker(
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
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
        Text(
            text = "R",
            color = palette.onSecondary,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.56f).sp,
                lineHeight = (size.value * 0.66f).sp,
                letterSpacing = (-0.4).sp,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}
