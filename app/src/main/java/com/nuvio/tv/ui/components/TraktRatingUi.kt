package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star

@Composable
fun TraktRatingSelectorContent(
    rating: Int,
    modifier: Modifier = Modifier,
    starSize: Dp = 24.dp,
    activeTint: Color = Color.Unspecified,
    inactiveTint: Color = Color.Unspecified
) {
    val normalizedRating = rating.coerceIn(1, 10)
    val resolvedActiveTint = if (activeTint == Color.Unspecified) {
        NuvioColors.Secondary
    } else {
        activeTint
    }
    val resolvedInactiveTint = if (inactiveTint == Color.Unspecified) {
        NuvioColors.TextDisabled.copy(alpha = 0.55f)
    } else {
        inactiveTint
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = traktRatingLabel(normalizedRating),
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioColors.TextPrimary,
            textAlign = TextAlign.Center
        )
        TraktTenStarRow(
            rating = normalizedRating,
            starSize = starSize,
            activeTint = resolvedActiveTint,
            inactiveTint = resolvedInactiveTint
        )
        Text(
            text = stringResource(R.string.trakt_rating_scale_label, normalizedRating),
            style = MaterialTheme.typography.titleMedium,
            color = resolvedActiveTint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TraktTenStarRow(
    rating: Int,
    starSize: Dp,
    activeTint: Color = Color.Unspecified,
    inactiveTint: Color = Color.Unspecified
) {
    val normalizedRating = rating.coerceIn(1, 10)
    val resolvedActiveTint = if (activeTint == Color.Unspecified) {
        NuvioColors.Secondary
    } else {
        activeTint
    }
    val resolvedInactiveTint = if (inactiveTint == Color.Unspecified) {
        NuvioColors.TextDisabled.copy(alpha = 0.55f)
    } else {
        inactiveTint
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(10) { index ->
            val tint = if (index < normalizedRating) resolvedActiveTint else resolvedInactiveTint
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(starSize)
            )
        }
    }
}

@Composable
fun traktRatingLabel(rating: Int): String {
    return stringResource(
        when (rating.coerceIn(1, 10)) {
            1 -> R.string.trakt_rating_label_1
            2 -> R.string.trakt_rating_label_2
            3 -> R.string.trakt_rating_label_3
            4 -> R.string.trakt_rating_label_4
            5 -> R.string.trakt_rating_label_5
            6 -> R.string.trakt_rating_label_6
            7 -> R.string.trakt_rating_label_7
            8 -> R.string.trakt_rating_label_8
            9 -> R.string.trakt_rating_label_9
            else -> R.string.trakt_rating_label_10
        }
    )
}
