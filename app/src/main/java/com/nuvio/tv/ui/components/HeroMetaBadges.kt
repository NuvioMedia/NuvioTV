package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Normalizes a raw age rating (e.g. "PG-13") for display as a hero badge, or null when blank.
 */
internal fun heroAgeRatingBadgeText(ageRating: String?): String? =
    ageRating?.trim()?.takeIf { it.isNotBlank() }

/**
 * Maps a raw TMDB/addon status (e.g. "Returning Series") to the localized, upper-cased
 * hero badge label, or null when blank. Unknown statuses are shown as-is, upper-cased.
 */
@Composable
internal fun rememberHeroStatusBadgeText(status: String?, isSeries: Boolean): String? {
    val strEnded = stringResource(if (isSeries) R.string.series_status_ended else R.string.movie_status_ended)
    val strContinuing = stringResource(if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
    val strCurrent = stringResource(if (isSeries) R.string.series_status_current else R.string.movie_status_current)
    val strCancelled = stringResource(if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
    val strReleased = stringResource(if (isSeries) R.string.series_status_released else R.string.movie_status_released)
    val strPlanned = stringResource(if (isSeries) R.string.series_status_planned else R.string.movie_status_planned)
    val strRumored = stringResource(if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
    val strInProduction = stringResource(if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
    val strPostProduction = stringResource(if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
    return remember(status, isSeries, strEnded, strContinuing, strCurrent, strCancelled, strReleased, strPlanned, strRumored, strInProduction, strPostProduction) {
        when (status?.trim()?.lowercase()) {
            "ended" -> strEnded.uppercase()
            "continuing", "returning series" -> strContinuing.uppercase()
            "current" -> strCurrent.uppercase()
            "cancelled", "canceled" -> strCancelled.uppercase()
            "released" -> strReleased.uppercase()
            "planned" -> strPlanned.uppercase()
            "rumored" -> strRumored.uppercase()
            "in production" -> strInProduction.uppercase()
            "post production" -> strPostProduction.uppercase()
            else -> status?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
        }
    }
}

/**
 * Renders the age rating and status badges used in home hero sections. When both are present
 * they are merged into a single outlined badge separated by a thin divider.
 */
@Composable
internal fun HeroAgeRatingStatusBadges(
    ageRatingBadge: String?,
    statusBadge: String?,
    textStyle: TextStyle,
    contentColor: Color
) {
    if (ageRatingBadge != null && statusBadge != null) {
        HeroCombinedMetaBadge(
            leftText = ageRatingBadge,
            rightText = statusBadge,
            textStyle = textStyle,
            contentColor = contentColor
        )
    } else {
        ageRatingBadge?.let { HeroMetaBadge(text = it, textStyle = textStyle, contentColor = contentColor) }
        statusBadge?.let { HeroMetaBadge(text = it, textStyle = textStyle, contentColor = contentColor) }
    }
}

@Composable
internal fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(NuvioTheme.spacing.hairline, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(NuvioTheme.spacing.hairline)
                .height(NuvioTheme.spacing.md)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun HeroMetaBadge(
    text: String,
    textStyle: TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(NuvioTheme.spacing.hairline, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
