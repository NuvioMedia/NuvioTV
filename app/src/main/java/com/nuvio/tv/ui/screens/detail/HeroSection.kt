package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import coil.decode.SvgDecoder
import coil.request.ImageRequest

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    nextToWatch: NextToWatch?,
    onPlayClick: () -> Unit,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    onToggleMovieWatched: () -> Unit,
    trailerAvailable: Boolean = false,
    onTrailerClick: () -> Unit = {},
    hideLogoDuringTrailer: Boolean = false,
    mdbListRatings: MDBListRatings? = null,
    hideMetaInfoImdb: Boolean = false,
    isTrailerPlaying: Boolean = false,
    playButtonFocusRequester: FocusRequester? = null,
    restorePlayFocusToken: Int = 0,
    onHeroActionFocused: () -> Unit = {},
    onPlayFocusRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val isSeriesApi = remember(meta.apiType) {
        meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
    }
    val logoModel = remember(context, meta.logo) {
        meta.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .build()
        }
    }
    val libraryAddPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.library_add_plus
    )
    val trailerPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.trailer_play_button
    )
    val strCreator = stringResource(R.string.hero_creator)
    val strDirector = stringResource(R.string.hero_director)
    val strWriter = stringResource(R.string.hero_writer)
    val creditLine = remember(meta.director, meta.writer, isSeriesApi) {
        val directorLine = meta.director.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writerLine = meta.writer.takeIf { it.isNotEmpty() }?.joinToString(", ")
        when {
            !directorLine.isNullOrBlank() -> {
                if (isSeriesApi) strCreator.format(directorLine) else strDirector.format(directorLine)
            }
            !writerLine.isNullOrBlank() -> strWriter.format(writerLine)
            else -> null
        }
    }

    // Animate logo properties for trailer mode
    val logoHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) 60.dp else 100.dp,
        animationSpec = tween(600),
        label = "logoHeight"
    )
    val logoBottomPadding by animateDpAsState(
        targetValue = if (isTrailerPlaying) 24.dp else 16.dp,
        animationSpec = tween(600),
        label = "logoPadding"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0.25f else 0.4f,
        animationSpec = tween(600),
        label = "logoWidth"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(600))
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Logo/Title — always visible during trailer, animates size
            if (!meta.logo.isNullOrBlank() && !(isTrailerPlaying && hideLogoDuringTrailer)) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = meta.name,
                    modifier = Modifier
                        .height(logoHeight)
                        .fillMaxWidth(logoMaxWidth)
                        .padding(bottom = logoBottomPadding),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                // Text title hides entirely during trailer
                AnimatedVisibility(
                    visible = !isTrailerPlaying,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400))
                ) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.displayMedium,
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = isTrailerPlaying && !hideLogoDuringTrailer,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(300))
            ) {
                Text(
                    text = stringResource(R.string.hero_press_back_trailer),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = !isTrailerPlaying,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400))
            ) {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayButton(
                            text = nextToWatch?.displayText ?: when {
                                nextEpisode != null -> stringResource(R.string.hero_play_episode, nextEpisode.season ?: 0, nextEpisode.episode ?: 0)
                                else -> stringResource(R.string.hero_play)
                            },
                            onClick = onPlayClick,
                            focusRequester = playButtonFocusRequester,
                            restoreFocusToken = restorePlayFocusToken,
                            onFocusRestored = {
                                onHeroActionFocused()
                                onPlayFocusRestored()
                            }
                        )

                        ActionIconButton(
                            icon = if (isInLibrary) Icons.Default.Check else null,
                            painter = if (!isInLibrary) {
                                libraryAddPainter
                            } else {
                                null
                            },
                            contentDescription = if (isInLibrary) stringResource(R.string.hero_remove_from_library) else stringResource(R.string.hero_add_to_library),
                            onClick = onToggleLibrary,
                            onLongPress = onLibraryLongPress,
                            onFocused = onHeroActionFocused
                        )

                        if (meta.apiType == "movie") {
                            ActionIconButton(
                                icon = if (isMovieWatched) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (isMovieWatched) {
                                    stringResource(R.string.hero_mark_unwatched)
                                } else {
                                    stringResource(R.string.hero_mark_watched)
                                },
                                onClick = onToggleMovieWatched,
                                enabled = !isMovieWatchedPending,
                                selected = isMovieWatched,
                                selectedContainerColor = Color.White,
                                selectedContentColor = Color.Black,
                                onFocused = onHeroActionFocused
                            )
                        }

                        if (trailerAvailable) {
                            ActionIconButtonPainter(
                                painter = trailerPainter,
                                contentDescription = stringResource(R.string.hero_play_trailer),
                                onClick = onTrailerClick,
                                onFocused = onHeroActionFocused
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Director/Writer line above description
                    if (!creditLine.isNullOrBlank()) {
                        Text(
                            text = creditLine,
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (mdbListRatings?.isEmpty() == false) {
                        MDBListRatingsRow(ratings = mdbListRatings)
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Always show series/movie description, not episode description
                    if (meta.description != null) {
                        Text(
                            text = meta.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextPrimary,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .padding(bottom = 12.dp)
                        )
                    }

                    MetaInfoRow(meta = meta, hideImdbRating = hideMetaInfoImdb)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    restoreFocusToken: Int = 0,
    onFocusRestored: () -> Unit = {}
) {
    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken > 0 && focusRequester != null) {
            focusRequester.requestFocusAfterFrames()
        }
    }
    val context = LocalContext.current
    val playPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.ic_player_play
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocusRestored()
                }
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = ButtonDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.White,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            contentColor = androidx.compose.ui.graphics.Color.Black,
            focusedContentColor = androidx.compose.ui.graphics.Color.Black
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(32.dp)
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(32.dp)
            )
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = playPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButtonPainter(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectedContainerColor: Color = Color(0xFF7CFF9B),
    selectedContentColor: Color = Color.Black,
    onFocused: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }

                    val isSelectKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    if ((native.isLongPress || native.repeatCount > 0) && isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    val isSelectKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                    if (isSelectKey) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = if (selected) selectedContainerColor else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = if (selected) selectedContentColor else NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(
    meta: Meta,
    hideImdbRating: Boolean
) {
    val context = LocalContext.current
    val genresText = remember(meta.genres) { meta.genres.joinToString(" • ") }
    val runtimeText = remember(meta.runtime) { meta.runtime?.let { formatRuntime(it) } }
    val yearText = remember(meta.releaseInfo) {
        meta.releaseInfo?.split("-")?.firstOrNull() ?: meta.releaseInfo
    }
    val imdbRating = if (hideImdbRating) null else meta.imdbRating
    val shouldShowImdbRating = imdbRating != null
    val imdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val secondaryItems = remember(meta.ageRating, meta.country, meta.language) {
        buildList<String> {
            meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Primary row: Genres, Runtime, Release, Ratings
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show all genres
            if (meta.genres.isNotEmpty()) {
                Text(
                    text = genresText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                MetaInfoDivider()
            }

            // Runtime
            runtimeText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                MetaInfoDivider()
            }

            yearText?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (shouldShowImdbRating) {
                    MetaInfoDivider()
                }
            }

            imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AsyncImage(
                        model = imdbModel,
                        contentDescription = stringResource(R.string.cd_rating),
                        modifier = Modifier.size(30.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { String.format("%.1f", rating) }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }
        }

        // Secondary row: Age Rating, Country, Language
        if (secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                secondaryItems.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.extendedColors.textTertiary
                    )
                    if (index < secondaryItems.lastIndex) {
                        MetaInfoDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MDBListRatingsRow(ratings: MDBListRatings) {
    val context = LocalContext.current
    val items = remember(ratings) {
        listOf(
            Triple("trakt", com.nuvio.tv.R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", com.nuvio.tv.R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", com.nuvio.tv.R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", com.nuvio.tv.R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", com.nuvio.tv.R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
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
                        .decoderFactory(SvgDecoder.Factory())
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
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
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
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
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
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

private fun formatRuntime(runtime: String): String {
    val minutes = runtime.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun rememberRawSvgPainter(
    context: android.content.Context,
    @androidx.annotation.RawRes rawRes: Int
): Painter {
    val model = remember(rawRes, context) {
        ImageRequest.Builder(context)
            .data(rawRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    return coil.compose.rememberAsyncImagePainter(model = model)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.extendedColors.textTertiary
    )
}
