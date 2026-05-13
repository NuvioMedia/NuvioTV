@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

private const val NuvioRepositoryUrl = "https://github.com/NuvioMedia/NuvioTV"
private const val TmdbUrl = "https://www.themoviedb.org"
private const val TraktUrl = "https://trakt.tv"
private const val MdbListUrl = "https://mdblist.com"
private const val IntroDbUrl = "https://introdb.app/"
private const val ImdbDatasetsUrl = "https://developer.imdb.com/non-commercial-datasets/"
private const val ApacheLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"
private const val LibMpvAndroidUrl = "https://github.com/jarnedemeulemeester/libmpv-android"

private sealed interface LicenseLogo {
    data class Drawable(@param:DrawableRes val resId: Int) : LicenseLogo
    data class Raw(@param:RawRes val resId: Int) : LicenseLogo
}

private data class LicenseAttributionItem(
    val title: String,
    val body: String,
    val url: String,
    val logo: LicenseLogo? = null
)

@Composable
fun LicensesAttributionsScreen(
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        LicensesAttributionsBrandPanel(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
        )

        LicensesAttributionsDetailsPanel(
            firstFocusRequester = firstFocusRequester,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun LicensesAttributionsBrandPanel(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.licenses_attributions_title),
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.licenses_attributions_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextSecondary
        )
    }
}

@Composable
private fun LicensesAttributionsDetailsPanel(
    firstFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .border(1.dp, NuvioColors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .background(NuvioColors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_app)
                ) {
                    AttributionDetailRow(
                        item = appLicenseItem(),
                        modifier = Modifier.focusRequester(firstFocusRequester)
                    )
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_data)
                ) {
                    dataAttributionItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }

                AttributionSection(
                    title = stringResource(R.string.licenses_attributions_section_playback)
                ) {
                    playbackLicenseItems().forEach { item ->
                        AttributionDetailRow(item = item)
                    }
                }
            }
            SettingsVerticalScrollIndicators(state = scrollState)
        }
    }
}

@Composable
private fun AttributionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioColors.TextSecondary,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun AttributionDetailRow(
    item: LicenseAttributionItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            item.logo?.let { logo ->
                AttributionLogo(logo = logo)
                Spacer(modifier = Modifier.width(14.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = NuvioColors.TextTertiary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun AttributionLogo(
    logo: LicenseLogo
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF24272E)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = when (logo) {
                is LicenseLogo.Drawable -> painterResource(id = logo.resId)
                is LicenseLogo.Raw -> rememberRawSvgPainter(rawIconRes = logo.resId)
            },
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes rawIconRes: Int): Painter {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { 48.dp.roundToPx() }
    val request = remember(rawIconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun appLicenseItem() = LicenseAttributionItem(
    title = stringResource(R.string.licenses_attributions_nuvio_title),
    body = stringResource(R.string.licenses_attributions_nuvio_body),
    url = NuvioRepositoryUrl
)

@Composable
private fun dataAttributionItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_tmdb_title),
        body = stringResource(R.string.licenses_attributions_tmdb_body),
        url = TmdbUrl,
        logo = LicenseLogo.Drawable(R.drawable.rating_tmdb)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_trakt_title),
        body = stringResource(R.string.licenses_attributions_trakt_body),
        url = TraktUrl,
        logo = LicenseLogo.Raw(R.raw.trakt_tv_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_mdblist_title),
        body = stringResource(R.string.licenses_attributions_mdblist_body),
        url = MdbListUrl,
        logo = LicenseLogo.Raw(R.raw.mdblist_logo)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_introdb_title),
        body = stringResource(R.string.licenses_attributions_introdb_body),
        url = IntroDbUrl,
        logo = LicenseLogo.Drawable(R.drawable.introdb_favicon)
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_imdb_title),
        body = stringResource(R.string.licenses_attributions_imdb_body),
        url = ImdbDatasetsUrl
    )
)

@Composable
private fun playbackLicenseItems() = listOf(
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_exoplayer_title),
        body = stringResource(R.string.licenses_attributions_exoplayer_body),
        url = ApacheLicenseUrl
    ),
    LicenseAttributionItem(
        title = stringResource(R.string.licenses_attributions_libmpv_title),
        body = stringResource(R.string.licenses_attributions_libmpv_body),
        url = LibMpvAndroidUrl
    )
)
