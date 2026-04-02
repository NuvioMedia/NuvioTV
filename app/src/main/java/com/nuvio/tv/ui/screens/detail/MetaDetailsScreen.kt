package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.MetaDetailsSkeleton
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.rememberCoroutineScope

private enum class RestoreTarget {
    HERO,
    EPISODE,
    CAST_MEMBER,
    MORE_LIKE_THIS,
    COLLECTION,
    COMPANY_OR_NETWORK
}

private enum class PeopleSectionTab {
    CAST,
    RATINGS,
    MORE_LIKE_THIS,
    COLLECTION
}

private data class PeopleTabItem(
    val tab: PeopleSectionTab,
    val label: String,
    val focusRequester: FocusRequester
)

private data class DetailReturnEpisodeFocusRequest(
    val season: Int?,
    val episode: Int?
)

private fun resolveDetailReturnEpisodeFocusTarget(
    meta: Meta,
    request: DetailReturnEpisodeFocusRequest?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>
): Video? {
    val requestedSeason = request?.season ?: return null
    val requestedEpisode = request.episode ?: return null

    val orderedEpisodes = meta.videos
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    if (orderedEpisodes.isEmpty()) return null

    val matchedIndex = orderedEpisodes.indexOfFirst {
        it.season == requestedSeason && it.episode == requestedEpisode
    }
    if (matchedIndex < 0) return null

    return orderedEpisodes[matchedIndex]
}

private const val USER_INTERACTION_DISPATCH_DEBOUNCE_MS = 120L

private fun applyDither(bmp: android.graphics.Bitmap) {
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val rng = java.util.Random(0)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val noise = rng.nextInt(3) - 1
        pixels[i] = ((a shl 24) or
            ((r + noise).coerceIn(0, 255) shl 16) or
            ((g + noise).coerceIn(0, 255) shl 8) or
            (b + noise).coerceIn(0, 255))
    }
    bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
}

@Stable
private class TrailerSeekOverlayState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    returnFocusSeason: Int? = null,
    returnFocusEpisode: Int? = null,
    heroBackdropUrl: String? = null,
    onBackPress: () -> Unit,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String) -> Unit = { _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onPlayClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayManuallyClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val selectedComment = uiState.selectedComment
    var commentOverlayDirection by remember { mutableIntStateOf(0) }
    var restorePlayFocusAfterTrailerBackToken by rememberSaveable { mutableIntStateOf(0) }

    BackHandler {
        if (selectedComment != null) {
            commentOverlayDirection = 0
            viewModel.onEvent(MetaDetailsEvent.OnDismissCommentOverlay)
        } else if (uiState.isTrailerPlaying) {
            restorePlayFocusAfterTrailerBackToken += 1
            viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded)
        } else {
            onBackPress()
        }
    }

    val currentIsTrailerPlaying by rememberUpdatedState(uiState.isTrailerPlaying)
    val currentShowTrailerControls by rememberUpdatedState(uiState.showTrailerControls)
    var trailerSeekOverlayVisible by remember { mutableStateOf(false) }
    val trailerSeekOverlayState = remember { TrailerSeekOverlayState() }
    var trailerSeekToken by remember { mutableIntStateOf(0) }
    var trailerSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var lastUserInteractionDispatchMs by remember { mutableLongStateOf(0L) }
    val onTrailerProgressChanged = remember(trailerSeekOverlayState) {
        { position: Long, duration: Long ->
            trailerSeekOverlayState.positionMs = position
            trailerSeekOverlayState.durationMs = duration
        }
    }

    LaunchedEffect(uiState.userMessage) {
        if (uiState.userMessage != null) {
            delay(2500)
            viewModel.onEvent(MetaDetailsEvent.OnClearMessage)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onEvent(MetaDetailsEvent.OnLifecyclePause)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (currentIsTrailerPlaying) {
                    if (currentShowTrailerControls) {
                        if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                trailerSeekOverlayVisible = false
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> -12_000L
                                    repeatCount >= 6 -> -8_000L
                                    repeatCount >= 2 -> -5_000L
                                    else -> -3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> 12_000L
                                    repeatCount >= 6 -> 8_000L
                                    repeatCount >= 2 -> 5_000L
                                    else -> 3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            else -> false
                        }
                    }
                    // During auto trailer preview, consume all keys except back/ESC so content doesn't scroll.
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val shouldDispatch =
                        nativeEvent.repeatCount == 0 &&
                            (nativeEvent.eventTime - lastUserInteractionDispatchMs) >=
                            USER_INTERACTION_DISPATCH_DEBOUNCE_MS
                    if (shouldDispatch) {
                        lastUserInteractionDispatchMs = nativeEvent.eventTime
                        viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
                    }
                }
                false
            }
    ) {
        when {
            uiState.isLoading -> {
                // Show hero backdrop from ModernHome during loading to prevent visual gap
                if (!heroBackdropUrl.isNullOrBlank()) {
                    val localContext = LocalContext.current
                    val localDensity = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val loadingBackdropWidthPx = remember(configuration, localDensity) {
                        with(localDensity) { configuration.screenWidthDp.dp.roundToPx() }
                    }
                    val loadingBackdropHeightPx = remember(configuration, localDensity) {
                        with(localDensity) { configuration.screenHeightDp.dp.roundToPx() }
                    }
                    val loadingBackdropRequest = remember(localContext, heroBackdropUrl, loadingBackdropWidthPx, loadingBackdropHeightPx) {
                        ImageRequest.Builder(localContext)
                            .data(heroBackdropUrl)
                            .crossfade(false)
                            .size(width = loadingBackdropWidthPx, height = loadingBackdropHeightPx)
                            .build()
                    }
                    AsyncImage(
                        model = loadingBackdropRequest,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopEnd
                    )
                }
                MetaDetailsSkeleton(backdropAware = !heroBackdropUrl.isNullOrBlank())
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
                )
            }
            uiState.meta != null -> {
                val meta = uiState.meta!!
                val genresString = remember(meta.genres) {
                    meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                }
                val yearString = remember(meta.releaseInfo) {
                    meta.releaseInfo?.split("-")?.firstOrNull() ?: meta.releaseInfo
                }

                MetaDetailsContent(
                    heroBackdropUrl = heroBackdropUrl,
                    meta = meta,
                    detailReturnEpisodeFocusRequest = DetailReturnEpisodeFocusRequest(
                        season = returnFocusSeason,
                        episode = returnFocusEpisode
                    ),
                    seasons = uiState.seasons,
                    selectedSeason = uiState.selectedSeason,
                    episodesForSeason = uiState.episodesForSeason,
                    isInLibrary = uiState.isInLibrary,
                    librarySourceMode = uiState.librarySourceMode,
                    nextToWatch = uiState.nextToWatch,
                    episodeProgressMap = uiState.episodeProgressMap,
                    watchedEpisodes = uiState.watchedEpisodes,
                    episodeWatchedPendingKeys = uiState.episodeWatchedPendingKeys,
                    blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                    showFullReleaseDate = uiState.showFullReleaseDate,
                    isMovieWatched = uiState.isMovieWatched,
                    isMovieWatchedPending = uiState.isMovieWatchedPending,
                    moreLikeThis = uiState.moreLikeThis,
                    moreLikeThisSource = uiState.moreLikeThisSource,
                    collection = uiState.collection,
                    collectionName = uiState.collectionName,
                    episodeImdbRatings = uiState.episodeImdbRatings,
                    isEpisodeRatingsLoading = uiState.isEpisodeRatingsLoading,
                    episodeRatingsError = uiState.episodeRatingsError,
                    mdbListRatings = uiState.mdbListRatings,
                    showMdbListImdb = uiState.showMdbListImdb,
                    comments = uiState.comments,
                    commentsCurrentPage = uiState.commentsCurrentPage,
                    commentsPageCount = uiState.commentsPageCount,
                    isCommentsLoading = uiState.isCommentsLoading,
                    isCommentsLoadingMore = uiState.isCommentsLoadingMore,
                    commentsError = uiState.commentsError,
                    shouldShowCommentsSection = uiState.shouldShowCommentsSection,
                    selectedComment = uiState.selectedComment,
                    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                    onEpisodeClick = { video ->
                        onPlayClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime
                        )
                    },
                    onEpisodeManualPlayClick = { video ->
                        onPlayManuallyClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime
                        )
                    },
                    onPlayClick = { videoId ->
                        onPlayClick(
                            videoId,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null
                        )
                    },
                    onPlayManuallyClick = { videoId ->
                        onPlayManuallyClick(
                            videoId,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null
                        )
                    },
                    showManualPlayOption = effectiveAutoplayEnabled,
                    onPlayButtonFocused = { viewModel.onEvent(MetaDetailsEvent.OnPlayButtonFocused) },
                    onToggleLibrary = { viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary) },
                    onLibraryLongPress = { viewModel.onEvent(MetaDetailsEvent.OnLibraryLongPress) },
                    onToggleMovieWatched = { viewModel.onEvent(MetaDetailsEvent.OnToggleMovieWatched) },
                    onToggleEpisodeWatched = { video ->
                        viewModel.onEvent(MetaDetailsEvent.OnToggleEpisodeWatched(video))
                    },
                    onMarkSeasonWatched = { season ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(season))
                    },
                    onMarkSeasonUnwatched = { season ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonUnwatched(season))
                    },
                    onMarkPreviousEpisodesWatched = { video ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkPreviousEpisodesWatched(video))
                    },
                    isSeasonFullyWatched = { season ->
                        viewModel.isSeasonFullyWatched(season)
                    },
                    trailerUrl = uiState.trailerUrl,
                    trailerAudioUrl = uiState.trailerAudioUrl,
                    isTrailerPlaying = uiState.isTrailerPlaying,
                    showTrailerControls = uiState.showTrailerControls,
                    hideLogoDuringTrailer = uiState.hideLogoDuringTrailer,
                    trailerButtonEnabled = uiState.trailerButtonEnabled,
                    trailerSeekToken = trailerSeekToken,
                    trailerSeekDeltaMs = trailerSeekDeltaMs,
                    onTrailerControlKey = { keyCode, action, repeatCount ->
                        if (!uiState.showTrailerControls || !uiState.isTrailerPlaying) {
                            false
                        } else if (action != KeyEvent.ACTION_DOWN) {
                            false
                        } else {
                            val seekStepMs = when {
                                repeatCount >= 12 -> 12_000L
                                repeatCount >= 6 -> 8_000L
                                repeatCount >= 2 -> 5_000L
                                else -> 3_000L
                            }
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER,
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    trailerSeekOverlayVisible = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    trailerSeekDeltaMs = -seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    trailerSeekDeltaMs = seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                else -> false
                            }
                        }
                    },
                    onTrailerProgressChanged = onTrailerProgressChanged,
                    onTrailerEnded = { viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded) },
                    onTrailerButtonClick = { viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick) },
                    onRetryComments = { viewModel.onEvent(MetaDetailsEvent.OnRetryComments) },
                    onLoadMoreComments = { viewModel.onEvent(MetaDetailsEvent.OnLoadMoreComments) },
                    onCommentClick = {
                        commentOverlayDirection = 0
                        viewModel.onEvent(MetaDetailsEvent.OnCommentSelected(it))
                    },
                    onShowPreviousComment = {
                        commentOverlayDirection = -1
                        viewModel.onEvent(MetaDetailsEvent.OnAdvanceCommentOverlay(direction = -1))
                    },
                    onShowNextComment = {
                        commentOverlayDirection = 1
                        viewModel.onEvent(MetaDetailsEvent.OnAdvanceCommentOverlay(direction = 1))
                    },
                    onDismissCommentOverlay = {
                        commentOverlayDirection = 0
                        viewModel.onEvent(MetaDetailsEvent.OnDismissCommentOverlay)
                    },
                    commentOverlayDirection = commentOverlayDirection,
                    restorePlayFocusAfterTrailerBackToken = restorePlayFocusAfterTrailerBackToken,
                    onNavigateToCastDetail = onNavigateToCastDetail,
                    onNavigateToTmdbEntityBrowse = onNavigateToTmdbEntityBrowse,
                    onNavigateToDetail = onNavigateToDetail
                )
            }
        }

        if (uiState.showListPicker) {
            LibraryListPickerDialog(
                title = uiState.meta?.name ?: stringResource(R.string.detail_lists_fallback),
                tabs = uiState.libraryListTabs,
                membership = uiState.pickerMembership,
                isPending = uiState.pickerPending,
                error = uiState.pickerError,
                onToggle = { key ->
                    viewModel.onEvent(MetaDetailsEvent.OnPickerMembershipToggled(key))
                },
                onSave = { viewModel.onEvent(MetaDetailsEvent.OnPickerSave) },
                onDismiss = { viewModel.onEvent(MetaDetailsEvent.OnPickerDismiss) }
            )
        }

        val message = uiState.userMessage
        if (!message.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(
                        color = if (uiState.userMessageIsError) {
                            Color(0xFF5A1C1C)
                        } else {
                            NuvioColors.BackgroundElevated
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary
                )
            }
        }

        TrailerSeekOverlayHost(
            visible = uiState.isTrailerPlaying && uiState.showTrailerControls && trailerSeekOverlayVisible,
            overlayState = trailerSeekOverlayState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    LaunchedEffect(trailerSeekOverlayVisible, uiState.isTrailerPlaying, uiState.showTrailerControls, trailerSeekToken) {
        if (trailerSeekOverlayVisible && uiState.isTrailerPlaying && uiState.showTrailerControls) {
            delay(3000)
            trailerSeekOverlayVisible = false
        }
    }

    LaunchedEffect(uiState.isTrailerPlaying, uiState.showTrailerControls) {
        if (!uiState.isTrailerPlaying || !uiState.showTrailerControls) {
            trailerSeekOverlayVisible = false
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MetaDetailsContent(
    heroBackdropUrl: String? = null,
    meta: Meta,
    detailReturnEpisodeFocusRequest: DetailReturnEpisodeFocusRequest? = null,
    seasons: List<Int>,
    selectedSeason: Int,
    episodesForSeason: List<Video>,
    isInLibrary: Boolean,
    librarySourceMode: LibrarySourceMode,
    nextToWatch: NextToWatch?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeWatchedPendingKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    showFullReleaseDate: Boolean,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    moreLikeThis: List<MetaPreview>,
    moreLikeThisSource: MoreLikeThisSource?,
    collection: List<MetaPreview>,
    collectionName: String?,
    episodeImdbRatings: Map<Pair<Int, Int>, Double>,
    isEpisodeRatingsLoading: Boolean,
    episodeRatingsError: String?,
    mdbListRatings: MDBListRatings?,
    showMdbListImdb: Boolean,
    comments: List<TraktCommentReview>,
    commentsCurrentPage: Int,
    commentsPageCount: Int,
    isCommentsLoading: Boolean,
    isCommentsLoadingMore: Boolean,
    commentsError: String?,
    shouldShowCommentsSection: Boolean,
    selectedComment: TraktCommentReview?,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onEpisodeManualPlayClick: (Video) -> Unit,
    onPlayClick: (String) -> Unit,
    onPlayManuallyClick: (String) -> Unit,
    showManualPlayOption: Boolean,
    onPlayButtonFocused: () -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleMovieWatched: () -> Unit,
    onToggleEpisodeWatched: (Video) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onMarkSeasonUnwatched: (Int) -> Unit,
    onMarkPreviousEpisodesWatched: (Video) -> Unit,
    isSeasonFullyWatched: (Int) -> Boolean,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isTrailerPlaying: Boolean,
    showTrailerControls: Boolean,
    hideLogoDuringTrailer: Boolean,
    trailerButtonEnabled: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    onTrailerButtonClick: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentClick: (TraktCommentReview) -> Unit,
    onShowPreviousComment: () -> Unit,
    onShowNextComment: () -> Unit,
    onDismissCommentOverlay: () -> Unit,
    commentOverlayDirection: Int,
    restorePlayFocusAfterTrailerBackToken: Int,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String) -> Unit = { _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> }
) {
    val canLoadMoreComments = commentsCurrentPage in 1 until commentsPageCount
    val selectedCommentIndex = remember(comments, selectedComment?.id) {
        selectedComment?.let { review -> comments.indexOfFirst { it.id == review.id } } ?: -1
    }
    val isSeries = remember(meta.type, meta.videos) {
        meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
    }
    val defaultSeriesVideo = remember(meta.behaviorHints?.defaultVideoId, meta.videos) {
        val defaultVideoId = meta.behaviorHints?.defaultVideoId
        meta.videos.firstOrNull { it.id == defaultVideoId && it.available != false }
    }
    val nextEpisode = remember(episodesForSeason) { episodesForSeason.firstOrNull() }
    val heroVideo = remember(meta.videos, nextToWatch, nextEpisode, defaultSeriesVideo, isSeries) {
        if (!isSeries) return@remember null
        val byId = nextToWatch?.nextVideoId?.let { id ->
            meta.videos.firstOrNull { it.id == id }
        }
        val bySeasonEpisode = if (byId == null && nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
            meta.videos.firstOrNull { it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode }
        } else {
            null
        }
        byId ?: bySeasonEpisode ?: defaultSeriesVideo ?: nextEpisode
    }
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val listState = rememberLazyListState(prefetchStrategy = nestedPrefetchStrategy)
    // Suppress auto-scroll when hero buttons get focus
    val heroNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }
    // Suppress vertical scroll from LazyColumn when focus moves horizontally inside nested LazyRows,
    // but still pass the rect upward so focus traversal works correctly.
    val noVerticalScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }
    val selectedSeasonFocusRequester = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    val castTabFocusRequester = remember { FocusRequester() }
    val moreLikeTabFocusRequester = remember { FocusRequester() }
    val collectionTabFocusRequester = remember { FocusRequester() }
    val ratingsTabFocusRequester = remember { FocusRequester() }
    val ratingsContentFocusRequester = remember { FocusRequester() }
    val castSectionFocusRequester = remember { FocusRequester() }
    val moreLikeSectionFocusRequester = remember { FocusRequester() }
    val collectionSectionFocusRequester = remember { FocusRequester() }
    var pendingRestoreType by rememberSaveable { mutableStateOf<RestoreTarget?>(null) }
    var pendingRestoreEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCastPersonId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingRestoreMoreLikeItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCollectionItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCompanyId by rememberSaveable { mutableStateOf<Int?>(null) }
    var restoreFocusToken by rememberSaveable { mutableIntStateOf(0) }
    var initialHeroFocusRequested by rememberSaveable(meta.id) { mutableStateOf(false) }
    var showHeroPlayOptionsDialog by rememberSaveable(meta.id) { mutableStateOf(false) }
    var initialDetailReturnFocusHandled by remember(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode
    ) {
        mutableStateOf(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    fun clearPendingRestore() {
        pendingRestoreType = null
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
    }

    fun markHeroRestore() {
        pendingRestoreType = RestoreTarget.HERO
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
    }

    fun markEpisodeRestore(episodeId: String) {
        pendingRestoreType = RestoreTarget.EPISODE
        pendingRestoreEpisodeId = episodeId
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
    }

    fun markCastMemberRestore(personId: Int) {
        pendingRestoreType = RestoreTarget.CAST_MEMBER
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = personId
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
    }

    fun markMoreLikeThisRestore(itemId: String) {
        pendingRestoreType = RestoreTarget.MORE_LIKE_THIS
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = itemId
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
    }

    fun markCollectionRestore(itemId: String) {
        pendingRestoreType = RestoreTarget.COLLECTION
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = itemId
        pendingRestoreCompanyId = null
    }

    fun markCompanyRestore(companyId: Int) {
        pendingRestoreType = RestoreTarget.COMPANY_OR_NETWORK
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = companyId
    }

    DisposableEffect(
        lifecycleOwner,
        pendingRestoreType,
        pendingRestoreEpisodeId,
        pendingRestoreCastPersonId,
        pendingRestoreMoreLikeItemId,
        pendingRestoreCollectionItemId,
        pendingRestoreCompanyId
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreType != null) {
                restoreFocusToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode,
        nextToWatch,
        episodeProgressMap,
        watchedEpisodes
    ) {
        if (initialDetailReturnFocusHandled) return@LaunchedEffect
        if (!isSeries) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        val request = detailReturnEpisodeFocusRequest
        if (request?.season == null || request.episode == null) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        if (nextToWatch == null) return@LaunchedEffect

        val targetEpisode = resolveDetailReturnEpisodeFocusTarget(
            meta = meta,
            request = request,
            episodeProgressMap = episodeProgressMap,
            watchedEpisodes = watchedEpisodes
        )
        initialDetailReturnFocusHandled = true
        targetEpisode ?: return@LaunchedEffect

        val targetSeason = targetEpisode.season
        if (targetSeason != null && selectedSeason != targetSeason) {
            onSeasonSelected(targetSeason)
        }
        // Prevent the default hero autofocus from stealing focus after the episode restore completes.
        initialHeroFocusRequested = true
        markEpisodeRestore(targetEpisode.id)
        if (seasons.isNotEmpty()) {
            // Ensure the episodes row is composed before requesting focus on a card.
            listState.scrollToItem(2)
            delay(32)
        }
        restoreFocusToken += 1
    }

    // Track if scrolled past hero (first item)
    val isScrolledPastHero by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
            (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 200)
        }
    }

    // Pre-compute cast members to avoid recomputation in lazy scope
    val castMembersToShow = remember(meta.castMembers, meta.cast) {
        if (meta.castMembers.isNotEmpty()) {
            meta.castMembers
        } else {
            meta.cast.map { name -> MetaCastMember(name = name) }
        }
    }

    fun isLeadCreditRole(role: String?): Boolean {
        val r = role?.trim().orEmpty()
        return r.equals("Creator", ignoreCase = true) ||
            r.equals("Director", ignoreCase = true) ||
            r.equals("Writer", ignoreCase = true)
    }

    val directorWriterMembers = remember(castMembersToShow) {
        val creators = castMembersToShow.filter { it.character.equals("Creator", ignoreCase = true) }
        val directors = castMembersToShow.filter { it.character.equals("Director", ignoreCase = true) }
        val writers = castMembersToShow.filter { it.character.equals("Writer", ignoreCase = true) }
        when {
            creators.isNotEmpty() -> creators
            directors.isNotEmpty() -> directors
            else -> writers
        }
    }

    val normalCastMembers = remember(castMembersToShow, directorWriterMembers) {
        val leadingKeys = directorWriterMembers.map {
            listOf(
                it.tmdbId?.toString().orEmpty(),
                it.name.trim().lowercase(),
                it.character.orEmpty().trim().lowercase()
            ).joinToString("|")
        }.toSet()
        castMembersToShow.filterNot {
            isLeadCreditRole(it.character) && listOf(
                it.tmdbId?.toString().orEmpty(),
                it.name.trim().lowercase(),
                it.character.orEmpty().trim().lowercase()
            ).joinToString("|") in leadingKeys
        }
    }
    val isTvShow = remember(meta.type, meta.apiType) {
        meta.type == ContentType.SERIES ||
            meta.type == ContentType.TV ||
            meta.apiType in listOf("series", "tv")
    }
    val hasCastSection = directorWriterMembers.isNotEmpty() || normalCastMembers.isNotEmpty()
    val hasMoreLikeThisSection = moreLikeThis.isNotEmpty()
    val hasRatingsSection = isTvShow
    val strTabCast = stringResource(R.string.detail_tab_cast)
    val strTabRatings = stringResource(R.string.detail_tab_ratings)
    val strTabMoreLikeThis = stringResource(R.string.detail_tab_more_like_this)
    val strTabCollection = stringResource(R.string.tmdb_collections_title)
    val moreLikeThisSourceLabel = when (moreLikeThisSource) {
        MoreLikeThisSource.TMDB -> stringResource(R.string.detail_more_like_this_powered_by_tmdb)
        MoreLikeThisSource.TRAKT -> stringResource(R.string.detail_more_like_this_powered_by_trakt)
        null -> null
    }
    val peopleTabItems = remember(
        hasCastSection,
        hasMoreLikeThisSection,
        hasRatingsSection,
        collection,
        castTabFocusRequester,
        ratingsTabFocusRequester,
        moreLikeTabFocusRequester,
        collectionTabFocusRequester,
        collectionName
    ) {
        buildList {
            if (hasCastSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.CAST,
                        label = strTabCast,
                        focusRequester = castTabFocusRequester
                    )
                )
            }
            if (hasRatingsSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.RATINGS,
                        label = strTabRatings,
                        focusRequester = ratingsTabFocusRequester
                    )
                )
            }
            if (hasMoreLikeThisSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.MORE_LIKE_THIS,
                        label = strTabMoreLikeThis,
                        focusRequester = moreLikeTabFocusRequester
                    )
                )
            }
            if (collection.isNotEmpty()) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.COLLECTION,
                        label = collectionName ?: strTabCollection,
                        focusRequester = collectionTabFocusRequester
                    )
                )
            }
        }
    }
    val availablePeopleTabs = remember(peopleTabItems) { peopleTabItems.map { it.tab } }
    val hasPeopleSection = availablePeopleTabs.isNotEmpty()
    val hasPeopleTabs = availablePeopleTabs.size > 1
    val initialPeopleTab = when {
        availablePeopleTabs.contains(PeopleSectionTab.CAST) -> PeopleSectionTab.CAST
        availablePeopleTabs.isNotEmpty() -> availablePeopleTabs.first()
        else -> PeopleSectionTab.RATINGS
    }
    var activePeopleTab by rememberSaveable(meta.id) { mutableStateOf(initialPeopleTab) }
    var seasonOptionsDialogSeason by remember { mutableStateOf<Int?>(null) }
    val lastFocusedEpisodeIdBySeason = remember(meta.id) { mutableStateMapOf<Int, String>() }
    // Tracks whether the initial auto-scroll to the "next to play" episode has fired
    // for each season.  Until it fires we must keep passing scrollToEpisodeId even if
    // the user already focused an episode (which sets lastFocusedEpisodeIdBySeason).
    val nextToWatchScrolledSeasons = remember(meta.id) { mutableStateMapOf<Int, Boolean>() }
    val episodeFocusRequestersBySeason = remember(meta.id) { mutableMapOf<Int, MutableMap<String, FocusRequester>>() }
    val seasonEpisodeFocusRequesters = remember(selectedSeason, episodesForSeason) {
        val byEpisodeId = episodeFocusRequestersBySeason.getOrPut(selectedSeason) { mutableMapOf() }
        episodesForSeason.forEach { episode ->
            if (!byEpisodeId.containsKey(episode.id)) {
                byEpisodeId[episode.id] = FocusRequester()
            }
        }
        byEpisodeId.keys.retainAll(episodesForSeason.map { it.id }.toSet())
        byEpisodeId
    }
    val seasonDownFocusRequester = remember(selectedSeason, episodesForSeason, seasonEpisodeFocusRequesters, lastFocusedEpisodeIdBySeason[selectedSeason], nextToWatch, defaultSeriesVideo, pendingRestoreType, pendingRestoreEpisodeId) {
        val nextEpisodeId = if (pendingRestoreType == RestoreTarget.EPISODE) {
            null
        } else {
            nextToWatch?.nextVideoId
                ?: nextToWatch?.let { ntw -> episodesForSeason.firstOrNull { it.season == ntw.nextSeason && it.episode == ntw.nextEpisode }?.id }
                ?: defaultSeriesVideo?.id?.takeIf { defaultId -> episodesForSeason.any { it.id == defaultId } }
        }
        val preferredEpisodeId = lastFocusedEpisodeIdBySeason[selectedSeason]
            ?: nextEpisodeId?.takeIf { episodesForSeason.any { ep -> ep.id == it } }
        (preferredEpisodeId?.let { seasonEpisodeFocusRequesters[it] })
            ?: episodesForSeason.firstOrNull()?.id?.let { seasonEpisodeFocusRequesters[it] }
    }

    val activePeopleTabFocusRequester = peopleTabItems
        .firstOrNull { it.tab == activePeopleTab }
        ?.focusRequester
        ?: if (activePeopleTab == PeopleSectionTab.RATINGS && !hasPeopleTabs) {
            ratingsContentFocusRequester
        } else {
            castTabFocusRequester
        }
    val episodesDownFocusRequester = when {
        hasPeopleTabs -> activePeopleTabFocusRequester
        activePeopleTab == PeopleSectionTab.RATINGS -> ratingsContentFocusRequester
        else -> null
    }
    val commentsUpFocusRequester = when {
        hasPeopleSection -> when (activePeopleTab) {
            PeopleSectionTab.CAST -> castSectionFocusRequester
            PeopleSectionTab.MORE_LIKE_THIS -> moreLikeSectionFocusRequester
            PeopleSectionTab.COLLECTION -> collectionSectionFocusRequester
            PeopleSectionTab.RATINGS -> ratingsContentFocusRequester
        }
        isSeries -> seasonDownFocusRequester ?: heroPlayFocusRequester
        else -> heroPlayFocusRequester
    }

    LaunchedEffect(availablePeopleTabs) {
        if (availablePeopleTabs.isNotEmpty() && activePeopleTab !in availablePeopleTabs) {
            activePeopleTab = availablePeopleTabs.first()
        }
    }

    // Backdrop alpha for crossfade
    val backgroundColor = NuvioColors.Background

    // Pre-compute gradient brushes once

    // Stable hero play callback
    val heroPlayClick = remember(heroVideo, meta.id, onEpisodeClick, onPlayClick) {
        {
            markHeroRestore()
            if (heroVideo != null) {
                onEpisodeClick(heroVideo)
            } else {
                onPlayClick(meta.id)
            }
        }
    }
    val heroPlayManualClick = remember(heroVideo, meta.id, onEpisodeManualPlayClick, onPlayManuallyClick) {
        {
            markHeroRestore()
            if (heroVideo != null) {
                onEpisodeManualPlayClick(heroVideo)
            } else {
                onPlayManuallyClick(meta.id)
            }
        }
    }

    val episodeClick = remember(onEpisodeClick) {
        { video: Video ->
            markEpisodeRestore(video.id)
            onEpisodeClick(video)
        }
    }
    val episodeManualClick = remember(onEpisodeManualPlayClick) {
        { video: Video ->
            markEpisodeRestore(video.id)
            onEpisodeManualPlayClick(video)
        }
    }

    LaunchedEffect(
        pendingRestoreType,
        pendingRestoreEpisodeId,
        initialHeroFocusRequested,
        isTrailerPlaying
    ) {
        if (
            !initialHeroFocusRequested &&
            pendingRestoreType == null &&
            pendingRestoreEpisodeId == null &&
            !isTrailerPlaying
        ) {
            repeat(3) {
                if (initialHeroFocusRequested) return@repeat
                heroPlayFocusRequester.requestFocusAfterFrames()
                delay(80)
            }
        }
    }

    // Pre-compute screen dimensions to avoid BoxWithConstraints subcomposition overhead
    val configuration = LocalConfiguration.current
    val localContext = LocalContext.current
    val localDensity = LocalDensity.current
    val screenWidthDp = remember(configuration) { configuration.screenWidthDp.dp }
    val screenHeightDp = remember(configuration) { configuration.screenHeightDp.dp }
    val backdropWidthPx = remember(screenWidthDp, localDensity) {
        with(localDensity) { screenWidthDp.roundToPx() }
    }
    val backdropHeightPx = remember(screenHeightDp, localDensity) {
        with(localDensity) { screenHeightDp.roundToPx() }
    }
    val hasHeroBackdrop = !heroBackdropUrl.isNullOrBlank()
    val seedBackdropUrl = heroBackdropUrl?.takeIf { it.isNotBlank() }
    val backdropDataUrl = meta.backdropUrl ?: meta.poster
    val shouldReuseSeedBackdrop = seedBackdropUrl != null && seedBackdropUrl == backdropDataUrl
    val shouldShowSeedBackdropUnderlay = seedBackdropUrl != null && !shouldReuseSeedBackdrop
    val heroBackdropRequest = remember(
        localContext,
        seedBackdropUrl,
        backdropWidthPx,
        backdropHeightPx
    ) {
        seedBackdropUrl?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .crossfade(false)
                .size(width = backdropWidthPx, height = backdropHeightPx)
                .build()
        }
    }
    val backdropRequest = remember(
        localContext,
        backdropDataUrl,
        shouldReuseSeedBackdrop,
        hasHeroBackdrop,
        heroBackdropRequest,
        backdropWidthPx,
        backdropHeightPx
    ) {
        if (shouldReuseSeedBackdrop && heroBackdropRequest != null) {
            heroBackdropRequest
        } else {
            ImageRequest.Builder(localContext)
                .data(backdropDataUrl)
                .apply { if (shouldShowSeedBackdropUnderlay) crossfade(400) else if (hasHeroBackdrop) crossfade(false) else crossfade(400) }
                .size(width = backdropWidthPx, height = backdropHeightPx)
                .build()
        }
    }

    val leftGradientBitmap = remember(backgroundColor, backdropWidthPx, backdropHeightPx) {
        val w = backdropWidthPx.coerceAtLeast(1)
        val h = backdropHeightPx.coerceAtLeast(1)
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(w, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val shader = android.graphics.LinearGradient(
            0f, 0f, w * 0.78f, 0f,
            intArrayOf(
                backgroundColor.copy(alpha = 1f).toArgb(),
                backgroundColor.copy(alpha = 0.95f).toArgb(),
                backgroundColor.copy(alpha = 0.84f).toArgb(),
                backgroundColor.copy(alpha = 0.70f).toArgb(),
                backgroundColor.copy(alpha = 0.52f).toArgb(),
                backgroundColor.copy(alpha = 0.34f).toArgb(),
                backgroundColor.copy(alpha = 0.18f).toArgb(),
                backgroundColor.copy(alpha = 0.07f).toArgb(),
                transparent
            ),
            floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), 2f, android.graphics.Paint().apply {
            this.shader = shader
        })
        bmp.asImageBitmap()
    }
    val bottomGradientBitmap = remember(backgroundColor, backdropWidthPx, backdropHeightPx) {
        val w = backdropWidthPx.coerceAtLeast(1)
        val h = backdropHeightPx.coerceAtLeast(1)
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(2, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val startY = h * 0.38f
        val shader = android.graphics.LinearGradient(
            0f, startY, 0f, h.toFloat(),
            intArrayOf(
                transparent,
                backgroundColor.copy(alpha = 0.05f).toArgb(),
                backgroundColor.copy(alpha = 0.18f).toArgb(),
                backgroundColor.copy(alpha = 0.38f).toArgb(),
                backgroundColor.copy(alpha = 0.60f).toArgb(),
                backgroundColor.copy(alpha = 0.78f).toArgb(),
                backgroundColor.copy(alpha = 0.91f).toArgb(),
                backgroundColor.copy(alpha = 0.97f).toArgb(),
                backgroundColor.copy(alpha = 1f).toArgb()
            ),
            floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 2f, h.toFloat(), android.graphics.Paint().apply {
            this.shader = shader
        })
        bmp.asImageBitmap()
    }

    // Animated gradient alpha (moved outside subcomposition scope)

    // Always-composed bottom gradient alpha (avoids add/remove during scroll)

    Box(modifier = Modifier.fillMaxSize()) {
        // Sticky background — backdrop or trailer
        BackdropLayer(
            backdropRequest = backdropRequest,
            heroBackdropRequest = if (shouldShowSeedBackdropUnderlay) heroBackdropRequest else null,
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isTrailerPlaying = isTrailerPlaying,
            showTrailerControls = showTrailerControls,
            trailerSeekToken = trailerSeekToken,
            trailerSeekDeltaMs = trailerSeekDeltaMs,
            onTrailerControlKey = onTrailerControlKey,
            onTrailerProgressChanged = onTrailerProgressChanged,
            onTrailerEnded = onTrailerEnded,
            isScrolledPastHero = isScrolledPastHero,
            leftGradient = leftGradientBitmap,
            bottomGradient = bottomGradientBitmap,
        )

        // Single scrollable column with hero + content
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            // Hero as first item in the lazy column
            item(key = "hero", contentType = "hero") {
                Box(modifier = Modifier.bringIntoViewResponder(heroNoScrollResponder)) {
                    HeroContentSection(
                        meta = meta,
                        nextEpisode = nextEpisode,
                        nextToWatch = nextToWatch,
                        onPlayClick = heroPlayClick,
                        onPlayLongPress = if (showManualPlayOption) {
                            { showHeroPlayOptionsDialog = true }
                        } else {
                            null
                        },
                        isInLibrary = isInLibrary,
                        onToggleLibrary = onToggleLibrary,
                        onLibraryLongPress = {
                            if (librarySourceMode == LibrarySourceMode.TRAKT) {
                                onLibraryLongPress()
                            }
                        },
                        isMovieWatched = isMovieWatched,
                        isMovieWatchedPending = isMovieWatchedPending,
                        onToggleMovieWatched = onToggleMovieWatched,
                        mdbListRatings = mdbListRatings,
                        hideMetaInfoImdb = showMdbListImdb,
                        showFullReleaseDate = showFullReleaseDate,
                        trailerAvailable = trailerButtonEnabled && !trailerUrl.isNullOrBlank(),
                        onTrailerClick = onTrailerButtonClick,
                        hideLogoDuringTrailer = hideLogoDuringTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        playButtonFocusRequester = heroPlayFocusRequester,
                        onHeroActionFocused = {
                            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                            initialHeroFocusRequested = true
                            clearPendingRestore()
                        },
                        restorePlayFocusToken = (if (pendingRestoreType == RestoreTarget.HERO) restoreFocusToken else 0) +
                                restorePlayFocusAfterTrailerBackToken,
                        onPlayFocusRestored = {
                            onPlayButtonFocused()
                            initialHeroFocusRequested = true
                            clearPendingRestore()
                        }
                    )
                }
            }

            // Season tabs and episodes for series
            if (isSeries && seasons.isNotEmpty()) {
                item(key = "season_tabs", contentType = "season_tabs") {
                    Box(modifier = Modifier.bringIntoViewResponder(noVerticalScrollResponder)) {
                        SeasonTabs(
                            seasons = seasons,
                            selectedSeason = selectedSeason,
                            onSeasonSelected = onSeasonSelected,
                            onSeasonLongPress = { seasonOptionsDialogSeason = it },
                            selectedTabFocusRequester = selectedSeasonFocusRequester,
                            downFocusRequester = seasonDownFocusRequester
                        )
                    }
                }
                item(key = "episodes_$selectedSeason", contentType = "episodes") {
                    Box(modifier = Modifier.bringIntoViewResponder(noVerticalScrollResponder)) {
                        EpisodesRow(
                            episodes = episodesForSeason,
                            episodeProgressMap = episodeProgressMap,
                            episodeRatings = episodeImdbRatings,
                            watchedEpisodes = watchedEpisodes,
                            episodeWatchedPendingKeys = episodeWatchedPendingKeys,
                            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                            onEpisodeClick = episodeClick,
                            onEpisodeManualPlayClick = episodeManualClick,
                            showManualPlayOption = showManualPlayOption,
                            onToggleEpisodeWatched = onToggleEpisodeWatched,
                            onMarkSeasonWatched = onMarkSeasonWatched,
                            onMarkSeasonUnwatched = onMarkSeasonUnwatched,
                            isSeasonFullyWatched = isSeasonFullyWatched(selectedSeason),
                            selectedSeason = selectedSeason,
                            onMarkPreviousEpisodesWatched = onMarkPreviousEpisodesWatched,
                            upFocusRequester = selectedSeasonFocusRequester,
                            downFocusRequester = episodesDownFocusRequester,
                            episodeFocusRequesters = seasonEpisodeFocusRequesters,
                            restoreEpisodeId = if (pendingRestoreType == RestoreTarget.EPISODE) pendingRestoreEpisodeId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.EPISODE) restoreFocusToken else 0,
                            onRestoreFocusHandled = {
                                clearPendingRestore()
                            },
                            onEpisodeFocused = { episodeId ->
                                lastFocusedEpisodeIdBySeason[selectedSeason] = episodeId
                            },
                            scrollToEpisodeId = if (nextToWatchScrolledSeasons[selectedSeason] != true && pendingRestoreType != RestoreTarget.EPISODE) {
                                val ntwId = nextToWatch?.nextVideoId
                                    ?: nextToWatch?.let { ntw -> episodesForSeason.firstOrNull { it.season == ntw.nextSeason && it.episode == ntw.nextEpisode }?.id }
                                if (ntwId != null) {
                                    ntwId
                                } else if (nextToWatch != null) {
                                    // nextToWatch resolved but target is in a different season — mark done and fall through.
                                    nextToWatchScrolledSeasons[selectedSeason] = true
                                    defaultSeriesVideo?.id?.takeIf { defaultId -> episodesForSeason.any { it.id == defaultId } }
                                } else {
                                    // nextToWatch not yet calculated — emit null so LaunchedEffect waits.
                                    null
                                }
                            } else if (lastFocusedEpisodeIdBySeason[selectedSeason] == null && pendingRestoreType != RestoreTarget.EPISODE) {
                                // nextToWatch scroll already done; fall back to default only if user hasn't focused anything yet.
                                defaultSeriesVideo?.id?.takeIf { defaultId -> episodesForSeason.any { it.id == defaultId } }
                            } else null,
                            onScrollToEpisodeHandled = {
                                nextToWatchScrolledSeasons[selectedSeason] = true
                            }
                        )
                    }
            }
        }

        // Cast / More like this section
        if (hasPeopleSection) {
                if (hasPeopleTabs) {
                    item(key = "cast_more_like_tabs", contentType = "horizontal_row") {
                        PeopleSectionTabs(
                            activeTab = activePeopleTab,
                            tabs = peopleTabItems,
                            upFocusRequester = seasonDownFocusRequester,
                            ratingsDownFocusRequester = ratingsContentFocusRequester,
                            onTabFocused = { tab ->
                                activePeopleTab = tab
                            }
                        )
                    }
                }

                item(key = "cast_or_more_like", contentType = "horizontal_row") {
                    val visiblePeopleSection = if (hasPeopleTabs) {
                        activePeopleTab
                    } else {
                        availablePeopleTabs.first()
                    }
                    val hasItemsBelow = meta.networks.isNotEmpty() || meta.productionCompanies.isNotEmpty()
                    var castSectionHeightPx by remember { mutableIntStateOf(0) }
                    val castSectionHeight = with(LocalDensity.current) { castSectionHeightPx.toDp() }

                    Crossfade(
                        targetState = visiblePeopleSection,
                        animationSpec = tween(durationMillis = 160),
                        label = "peopleSectionSwitch"
                    ) { section ->
                        when (section) {
                            PeopleSectionTab.CAST -> {
                                CastSection(
                                    cast = normalCastMembers,
                                    title = if (hasPeopleTabs) "" else strTabCast,
                                    leadingCast = directorWriterMembers,
                                    upFocusRequester = if (hasPeopleTabs) castTabFocusRequester else seasonDownFocusRequester,
                                    sectionFocusRequester = castSectionFocusRequester,
                                    restorePersonId = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) pendingRestoreCastPersonId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onCastMemberClick = { member ->
                                        member.tmdbId?.let { id ->
                                            markCastMemberRestore(id)
                                            val preferCrew = member.character.equals("Creator", ignoreCase = true) ||
                                                member.character.equals("Director", ignoreCase = true) ||
                                                member.character.equals("Writer", ignoreCase = true)
                                            onNavigateToCastDetail(id, member.name, preferCrew)
                                        }
                                    },
                                    modifier = Modifier.onSizeChanged { castSectionHeightPx = it.height }
                                )
                            }

                            PeopleSectionTab.MORE_LIKE_THIS -> {
                                MoreLikeThisSection(
                                    items = moreLikeThis,
                                    sourceLabel = moreLikeThisSourceLabel,
                                    upFocusRequester = if (hasPeopleTabs) moreLikeTabFocusRequester else seasonDownFocusRequester,
                                    sectionFocusRequester = moreLikeSectionFocusRequester,
                                    restoreItemId = if (pendingRestoreType == RestoreTarget.MORE_LIKE_THIS) pendingRestoreMoreLikeItemId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.MORE_LIKE_THIS) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onItemClick = { item ->
                                        markMoreLikeThisRestore(item.id)
                                        onNavigateToDetail(item.id, item.apiType, null)
                                    }
                                )
                            }
                            
                            PeopleSectionTab.COLLECTION -> {
                                CollectionSection(
                                    items = collection,
                                    upFocusRequester = if (hasPeopleTabs) collectionTabFocusRequester else seasonDownFocusRequester,
                                    sectionFocusRequester = collectionSectionFocusRequester,
                                    restoreItemId = if (pendingRestoreType == RestoreTarget.COLLECTION) pendingRestoreCollectionItemId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.COLLECTION) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onItemClick = { item ->
                                        markCollectionRestore(item.id)
                                        onNavigateToDetail(item.id, item.apiType, null)
                                    }
                                )
                            }

                            PeopleSectionTab.RATINGS -> {
                                EpisodeRatingsSection(
                                    episodes = meta.videos,
                                    ratings = episodeImdbRatings,
                                    isLoading = isEpisodeRatingsLoading,
                                    error = episodeRatingsError,
                                    title = if (hasPeopleTabs) "" else strTabRatings,
                                    upFocusRequester = if (hasPeopleTabs) {
                                        ratingsTabFocusRequester
                                    } else {
                                        seasonDownFocusRequester ?: selectedSeasonFocusRequester
                                    },
                                    firstItemFocusRequester = ratingsContentFocusRequester,
                                    modifier = Modifier.heightIn(min = if (!hasItemsBelow) castSectionHeight else 0.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (shouldShowCommentsSection) {
                item(key = "trakt_comments", contentType = "horizontal_row") {
                    CommentsSection(
                        comments = comments,
                        isLoading = isCommentsLoading,
                        isLoadingMore = isCommentsLoadingMore,
                        canLoadMore = canLoadMoreComments,
                        error = commentsError,
                        upFocusRequester = commentsUpFocusRequester,
                        onRetry = onRetryComments,
                        onLoadMore = onLoadMoreComments,
                        onCommentClick = onCommentClick
                    )
                }
            }

            if (isTvShow) {
                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks,
                            restoreCompanyId = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) pendingRestoreCompanyId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) restoreFocusToken else 0,
                            onRestoreFocusHandled = { clearPendingRestore() },
                            onCompanyClick = { company ->
                                company.tmdbId?.let { entityId ->
                                    markCompanyRestore(entityId)
                                    onNavigateToTmdbEntityBrowse("network", entityId, company.name, meta.apiType)
                                }
                            }
                        )
                    }
                }

                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies,
                            restoreCompanyId = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) pendingRestoreCompanyId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) restoreFocusToken else 0,
                            onRestoreFocusHandled = { clearPendingRestore() },
                            onCompanyClick = { company ->
                                company.tmdbId?.let { entityId ->
                                    markCompanyRestore(entityId)
                                    onNavigateToTmdbEntityBrowse("company", entityId, company.name, meta.apiType)
                                }
                            }
                        )
                    }
                }
            } else {
                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies,
                            restoreCompanyId = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) pendingRestoreCompanyId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) restoreFocusToken else 0,
                            onRestoreFocusHandled = { clearPendingRestore() },
                            onCompanyClick = { company ->
                                company.tmdbId?.let { entityId ->
                                    markCompanyRestore(entityId)
                                    onNavigateToTmdbEntityBrowse("company", entityId, company.name, meta.apiType)
                                }
                            }
                        )
                    }
                }

                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks,
                            restoreCompanyId = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) pendingRestoreCompanyId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) restoreFocusToken else 0,
                            onRestoreFocusHandled = { clearPendingRestore() },
                            onCompanyClick = { company ->
                                company.tmdbId?.let { entityId ->
                                    markCompanyRestore(entityId)
                                    onNavigateToTmdbEntityBrowse("network", entityId, company.name, meta.apiType)
                                }
                            }
                        )
                    }
                }
            }
        }

        seasonOptionsDialogSeason?.let { season ->
            SeasonOptionsDialog(
                season = season,
                isFullyWatched = isSeasonFullyWatched(season),
                onDismiss = { seasonOptionsDialogSeason = null },
                onMarkSeasonWatched = {
                    onMarkSeasonWatched(season)
                    seasonOptionsDialogSeason = null
                },
                onMarkSeasonUnwatched = {
                    onMarkSeasonUnwatched(season)
                    seasonOptionsDialogSeason = null
                }
            )
        }

        if (showHeroPlayOptionsDialog) {
            PlayManualOverrideDialog(
                title = meta.name,
                subtitle = nextToWatch?.displayText ?: stringResource(R.string.hero_play),
                onDismiss = { showHeroPlayOptionsDialog = false },
                onPlayManually = {
                    showHeroPlayOptionsDialog = false
                    heroPlayManualClick()
                }
            )
        }

        selectedComment?.let { review ->
            CommentOverlay(
                review = review,
                canNavigatePrevious = selectedCommentIndex > 0,
                canNavigateNext = selectedCommentIndex >= 0 && (
                    selectedCommentIndex < comments.lastIndex || canLoadMoreComments || isCommentsLoadingMore
                ),
                isLoadingNext = isCommentsLoadingMore,
                transitionDirection = commentOverlayDirection,
                onPrevious = onShowPreviousComment,
                onNext = onShowNextComment,
                onDismiss = onDismissCommentOverlay
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayManualOverrideDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPlayManually: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle
    ) {
        Button(
            onClick = onPlayManually,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.play_manually))
        }
    }
}

@Composable
private fun BackdropLayer(
    backdropRequest: ImageRequest,
    heroBackdropRequest: ImageRequest? = null,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isTrailerPlaying: Boolean,
    showTrailerControls: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    isScrolledPastHero: Boolean,
    leftGradient: ImageBitmap,
    bottomGradient: ImageBitmap,
) {
    var showHeroBackdropUnderlay by remember(heroBackdropRequest, backdropRequest) {
        mutableStateOf(heroBackdropRequest != null)
    }
    val backdropAlphaState = animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else if (isScrolledPastHero) 0.15f else 1f,
        animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
        label = "backdropFade"
    )
    val gradientAlphaState = animateFloatAsState(
        targetValue = if (isTrailerPlaying || isScrolledPastHero) 0f else 1f,
        animationSpec = tween(durationMillis = if (isScrolledPastHero) 300 else 800),
        label = "gradientFade"
    )
    Box(modifier = Modifier.fillMaxSize()) {
        // Show hero backdrop from previous screen as persistent underlay
        // to prevent flash/re-render during navigation transition
        if (showHeroBackdropUnderlay && heroBackdropRequest != null) {
            AsyncImage(
                model = heroBackdropRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alpha = backdropAlphaState.value,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        AsyncImage(
            model = backdropRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            alpha = backdropAlphaState.value,
            onSuccess = { showHeroBackdropUnderlay = false },
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd
        )
        TrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = isTrailerPlaying,
            seekRequestToken = if (showTrailerControls) trailerSeekToken else 0,
            seekDeltaMs = if (showTrailerControls) trailerSeekDeltaMs else 0L,
            onRemoteKey = onTrailerControlKey,
            onProgressChanged = onTrailerProgressChanged,
            onEnded = onTrailerEnded,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {
                        if (gradientAlphaState.value > 0f) {
                            drawImage(
                                leftGradient,
                                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                                alpha = gradientAlphaState.value,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                            )
                        }
                    }
                }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PeopleSectionTabs(
    activeTab: PeopleSectionTab,
    tabs: List<PeopleTabItem>,
    upFocusRequester: FocusRequester? = null,
    ratingsDownFocusRequester: FocusRequester? = null,
    onTabFocused: (PeopleSectionTab) -> Unit
) {
    if (tabs.isEmpty()) return

    val defaultRequester = tabs.first().focusRequester
    val restorerRequester = tabs.firstOrNull { it.tab == activeTab }?.focusRequester ?: defaultRequester

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, start = 48.dp, end = 48.dp)
            .focusRestorer(restorerRequester),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    text = "|",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }

            PeopleSectionTabButton(
                label = item.label,
                selected = activeTab == item.tab,
                focusRequester = item.focusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = if (item.tab == PeopleSectionTab.RATINGS) ratingsDownFocusRequester else null,
                onFocused = { onTabFocused(item.tab) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PeopleSectionTabButton(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onFocused,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                if (upFocusRequester != null) {
                    up = upFocusRequester
                }
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            }
            .onFocusChanged { state ->
                val focusedNow = state.isFocused
                isFocused = focusedNow
                if (focusedNow) onFocused()
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = when {
                isFocused -> NuvioColors.TextPrimary
                selected -> NuvioColors.TextPrimary.copy(alpha = 0.92f)
                else -> NuvioColors.TextPrimary.copy(alpha = 0.55f)
            },
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TrailerSeekOverlayHost(
    visible: Boolean,
    overlayState: TrailerSeekOverlayState,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(150)),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        TrailerSeekOverlay(
            currentPosition = overlayState.positionMs,
            duration = overlayState.durationMs
        )
    }
}

@Composable
private fun TrailerSeekOverlay(
    currentPosition: Long,
    duration: Long
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "trailerSeekProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NuvioColors.Secondary)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatPlaybackTime(currentPosition)} / ${formatPlaybackTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "\u2713 ${tab.title}" else tab.title
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = NuvioColors.Border, thickness = 1.dp)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}
