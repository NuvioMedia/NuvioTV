package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MDBListRatings
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactMdbListRatingsRow(
    ratings: MDBListRatings,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White.copy(alpha = 0.82f),
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    logoSize: Dp = 22.dp,
    itemSpacing: Dp = 12.dp,
    wrap: Boolean = false,
    lineSpacing: Dp = 6.dp
) {
    val context = LocalContext.current
    val rawItems = remember(ratings) {
        listOf(
            Triple("trakt", R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("mal", R.raw.mdblist_mal, ratings.mal),
            Triple("tomatoes", R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    val content: @Composable () -> Unit = {
        rawItems.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            RatingItem(
                provider = provider,
                rating = resolvedRating,
                logo = {
                    val model = remember(context, logoRes, logoSize) {
                        ImageRequest.Builder(context)
                            .data(logoRes)
                            .build()
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.size(logoSize),
                        contentScale = ContentScale.Fit
                    )
                },
                textColor = textColor,
                textStyle = textStyle
            )
        }

        ratings.audience?.let { rating ->
            RatingItem(
                provider = "audience",
                rating = rating,
                logo = {
                    Image(
                        painter = painterResource(id = R.drawable.mdblist_audience),
                        contentDescription = null,
                        modifier = Modifier.size(logoSize)
                    )
                },
                textColor = textColor,
                textStyle = textStyle
            )
        }

        ratings.metacritic?.let { rating ->
            RatingItem(
                provider = "metacritic",
                rating = rating,
                logo = {
                    Image(
                        painter = painterResource(id = R.drawable.mdblist_metacritic),
                        contentDescription = null,
                        modifier = Modifier.size(logoSize)
                    )
                },
                textColor = textColor,
                textStyle = textStyle
            )
        }
    }

    if (wrap) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalArrangement = Arrangement.spacedBy(lineSpacing)
        ) {
            content()
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun RatingItem(
    provider: String,
    rating: Double,
    logo: @Composable () -> Unit,
    textColor: Color,
    textStyle: TextStyle
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        logo()
        Text(
            text = formatRating(provider, rating),
            style = textStyle,
            color = textColor,
            maxLines = 1
        )
    }
}

private fun formatRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format(Locale.US, "%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) {
                rating.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", rating)
            }
        }
    }
}
