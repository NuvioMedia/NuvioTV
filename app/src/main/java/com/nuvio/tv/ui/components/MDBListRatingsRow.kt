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
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.unit.dp

@Composable
fun MDBListRatingsRow(
    ratings: MDBListRatings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items = remember(ratings) {
        listOf(
            Triple("trakt", R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("mal", R.raw.mdblist_mal, ratings.mal),
            Triple("tomatoes", R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    ImageRequest.Builder(context)
                        .data(logoRes)
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTheme.spacing.xl),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatMDBListRating(provider, resolvedRating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTheme.spacing.xl)
                )
                Text(
                    text = formatMDBListRating("audience", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(NuvioTheme.spacing.xl)
                )
                Text(
                    text = formatMDBListRating("metacritic", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}

private fun formatMDBListRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}
