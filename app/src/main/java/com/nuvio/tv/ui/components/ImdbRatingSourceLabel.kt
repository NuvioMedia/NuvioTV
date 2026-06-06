package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy

@Composable
fun ImdbRatingSourceLabel(
    logoModifier: Modifier,
    textStyle: TextStyle,
    textColor: Color,
    textModifier: Modifier = Modifier
) {
    val label = stringResource(R.string.cd_imdb)
    if (AppFeaturePolicy.imdbRatingLogoEnabled) {
        val context = LocalContext.current
        val model = remember(context) {
            ImageRequest.Builder(context)
                .data(R.raw.imdb_logo_2016)
                .build()
        }
        AsyncImage(
            model = model,
            contentDescription = label,
            modifier = logoModifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = label,
            modifier = textModifier,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
