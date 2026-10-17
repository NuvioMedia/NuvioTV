package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.RottenTomatoesStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
fun MDBListRatingsRow(
    ratings: MDBListRatings,
    modifier: Modifier = Modifier,
    maxItems: Int = Int.MAX_VALUE,
    order: List<String> = MDBListSettings.DEFAULT_RATING_ORDER,
    iconSize: Dp = NuvioTheme.spacing.xl
) {
    val orderedProviders = remember(ratings, maxItems, order) {
        order.filter { provider -> getRatingValue(ratings, provider) != null }
            .take(maxItems)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        orderedProviders.forEach { provider ->
            val rating = getRatingValue(ratings, provider) ?: return@forEach
            RatingBadge(provider = provider, rating = rating, ratings = ratings, iconSize = iconSize)
        }
    }
}

@Composable
private fun RatingBadge(
    provider: String,
    rating: Double,
    ratings: MDBListRatings,
    iconSize: Dp = NuvioTheme.spacing.xl
) {
    val squareIconSize = iconSize * 0.75f

    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (provider) {
            "audience" -> {
                Image(
                    painter = painterResource(
                        id = when (ratings.audienceStatus) {
                            RottenTomatoesStatus.VERIFIED_HOT -> R.drawable.mdblist_audience_verified_hot
                            RottenTomatoesStatus.STALE -> R.drawable.mdblist_audience_stale
                            else -> R.drawable.mdblist_audience
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(squareIconSize)
                )
            }
            "metacritic" -> {
                Image(
                    painter = painterResource(id = R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(squareIconSize)
                )
            }
            else -> {
                val isImdb = provider == "imdb"
                val rawRes = when (provider) {
                    "trakt" -> R.raw.mdblist_trakt
                    "imdb" -> R.raw.imdb_logo_2016
                    "tmdb" -> R.raw.mdblist_tmdb
                    "letterboxd" -> R.raw.mdblist_letterboxd
                    "mal" -> R.raw.mdblist_mal
                    "tomatoes" -> when (ratings.tomatoesStatus) {
                        RottenTomatoesStatus.CERTIFIED_FRESH -> R.raw.mdblist_tomatoes_certified
                        RottenTomatoesStatus.ROTTEN -> R.raw.mdblist_tomatoes_rotten
                        else -> R.raw.mdblist_tomatoes
                    }
                    else -> return
                }
                val model = remember(context, rawRes) {
                    ImageRequest.Builder(context)
                        .data(rawRes)
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(if (isImdb) iconSize else squareIconSize),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Text(
            text = formatMDBListRating(provider, rating),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }
}

private fun getRatingValue(ratings: MDBListRatings, provider: String): Double? = when (provider) {
    "trakt" -> ratings.trakt
    "imdb" -> ratings.imdb
    "tmdb" -> ratings.tmdb
    "letterboxd" -> ratings.letterboxd
    "tomatoes" -> ratings.tomatoes
    "audience" -> ratings.audience
    "metacritic" -> ratings.metacritic
    "mal" -> ratings.mal
    else -> null
}

private fun formatMDBListRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}
