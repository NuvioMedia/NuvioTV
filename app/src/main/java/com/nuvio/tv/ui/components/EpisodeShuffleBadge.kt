package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R

@Composable
internal fun EpisodeShuffleBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Shuffle,
        contentDescription = stringResource(R.string.shuffle_badge),
        tint = Color.White,
        modifier = modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.8f), CircleShape)
            .padding(6.dp).size(18.dp)
    )
}
