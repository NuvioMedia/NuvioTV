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
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
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
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.MetaDetailsSkeleton
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog

private enum class RestoreTarget {
    HERO,
    EPISODE,
    CAST_MEMBER,
    MORE_LIKE_THIS
}

private enum class PeopleSectionTab {
    CAST,
    MORE_LIKE_THIS,
    RATINGS
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

    val isCompleted = episodeProgressMap[requestedSeason to requestedEpisode]?.isCompleted() == true ||
        watchedEpisodes.contains(requestedSeason to requestedEpisode)

    return if (isCompleted) {
        orderedEpisodes.getOrNull(matchedIndex + 1) ?: orderedEpisodes[matchedIndex]
    } else {
        orderedEpisodes[matchedIndex]
    }
}

private const val USER_INTERACTION_DISPATCH_DEBOUNCE_MS = 120L

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
    onBackPress: () -> Unit,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = { _, _, _ -> },
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
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var restorePlayFocusAfterTrailerBackToken by rememberSaveable { mutableIntStateOf(0) }

    BackHandler {
        if (uiState.isTrailerPlaying) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
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
                MetaDetailsSkeleton()
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
                    isMovieWatched = uiState.isMovieWatched,
                    isMovieWatchedPending = uiState.isMovieWatchedPending,
                    moreLikeThis = uiState.moreLikeThis,
                    episodeImdbRatings = uiState.episodeImdbRatings,
                    isEpisodeRatingsLoading = uiState.isEpisodeRatingsLoading,
                    episodeRatingsError = uiState.episodeRatingsError,
                    mdbListRatings = uiState.mdbListRatings,
                    showMdbListImdb = uiState.showMdbListImdb,
                    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                    onEpisodeClick = { video ->
                        onPlayClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.background,
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
                            meta.background,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null
                        )
                    },
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
                    restorePlayFocusAfterTrailerBackToken = restorePlayFocusAfterTrailerBackToken,
                    onNavigateToCastDetail = onNavigateToCastDetail,
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
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    moreLikeThis: List<MetaPreview>,
    episodeImdbRatings: Map<Pair<Int, Int>, Double>,
    isEpisodeRatingsLoading: Boolean,
    episodeRatingsError: String?,
    mdbListRatings: MDBListRatings?,
    showMdbListImdb: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onPlayClick: (String) -> Unit,
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
    restorePlayFocusAfterTrailerBackToken: Int,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean) -> Unit = { _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> }
) {
    val isSeries = remember(meta.type, meta.videos) {
        meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
    }
    val nextEpisode = remember(episodesForSeason) { episodesForSeason.firstOrNull() }
    val heroVideo = remember(meta.videos, nextToWatch, nextEpisode, isSeries) {
        if (!isSeries) return@remember null
        val byId = nextToWatch?.nextVideoId?.let { id ->
            meta.videos.firstOrNull { it.id == id }
        }
        val bySeasonEpisode = if (byId == null && nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
            meta.videos.firstOrNull { it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode }
        } else {
            null
        }
        byId ?: bySeasonEpisode ?: nextEpisode
    }
    val listState = rememberLazyListState()
    // Suppress auto-scroll when hero buttons get focus
    val heroNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }
    val selectedSeasonFocusRequester = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    val castTabFocusRequester = remember { FocusRequester() }
    val moreLikeTabFocusRequester = remember { FocusRequester() }
    val ratingsTabFocusRequester = remember { FocusRequester() }
    val ratingsContentFocusRequester = remember { FocusRequester() }
    var pendingRestoreType by rememberSaveable { mutableStateOf<RestoreTarget?>(null) }
    var pendingRestoreEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCastPersonId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingRestoreMoreLikeItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocusToken by rememberSaveable { mutableIntStateOf(0) }
    var initialHeroFocusRequested by rememberSaveable(meta.id) { mutableStateOf(false) }
    var initialDetailReturnFocusHandled by rememberSaveable(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode
    ) {
        mutableStateOf(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun clearPendingRestore() {
        pendingRestoreType = null
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
    }

    fun markHeroRestore() {
        pendingRestoreType = RestoreTarget.HERO
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
    }

    fun markEpisodeRestore(episodeId: String) {
        pendingRestoreType = RestoreTarget.EPISODE
        pendingRestoreEpisodeId = episodeId
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
    }

    fun markCastMemberRestore(personId: Int) {
        pendingRestoreType = RestoreTarget.CAST_MEMBER
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = personId
        pendingRestoreMoreLikeItemId = null
    }

    fun markMoreLikeThisRestore(itemId: String) {
        pendingRestoreType = RestoreTarget.MORE_LIKE_THIS
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = itemId
    }

    androidx.compose.runtime.DisposableEffect(
        lifecycleOwner,
        pendingRestoreType,
        pendingRestoreEpisodeId,
        pendingRestoreCastPersonId,
        pendingRestoreMoreLikeItemId
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
        val creators = castMembersToShow.filter { it.tmdbId != null && it.character.equals("Creator", ignoreCase = true) }
        val directors = castMembersToShow.filter { it.tmdbId != null && it.character.equals("Director", ignoreCase = true) }
        val writers = castMembersToShow.filter { it.tmdbId != null && it.character.equals("Writer", ignoreCase = true) }
        when {
            creators.isNotEmpty() -> creators
            directors.isNotEmpty() -> directors
            else -> writers
        }
    }

    val normalCastMembers = remember(castMembersToShow, directorWriterMembers) {
        val leadingIds = directorWriterMembers.mapNotNull { it.tmdbId }.toSet()
        castMembersToShow.filterNot {
            val id = it.tmdbId
            id != null && id in leadingIds && isLeadCreditRole(it.character)
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
    val peopleTabItems = remember(
        hasCastSection,
        hasMoreLikeThisSection,
        hasRatingsSection,
        castTabFocusRequester,
        ratingsTabFocusRequester,
        moreLikeTabFocusRequester
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

    LaunchedEffect(availablePeopleTabs) {
        if (availablePeopleTabs.isNotEmpty() && activePeopleTab !in availablePeopleTabs) {
            activePeopleTab = availablePeopleTabs.first()
        }
    }

    // Backdrop alpha for crossfade
    val backdropAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "backdropFade"
    )

    val backgroundColor = NuvioColors.Background

    // Pre-compute gradient brushes once
    val leftGradient = remember(backgroundColor) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to backgroundColor,
                0.20f to backgroundColor.copy(alpha = 0.95f),
                0.35f to backgroundColor.copy(alpha = 0.8f),
                0.45f to backgroundColor.copy(alpha = 0.6f),
                0.55f to backgroundColor.copy(alpha = 0.4f),
                0.65f to backgroundColor.copy(alpha = 0.2f),
                0.75f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
    }
    val bottomGradient = remember(backgroundColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.38f to Color.Transparent,
                0.56f to backgroundColor.copy(alpha = 0.38f),
                0.72f to backgroundColor.copy(alpha = 0.74f),
                0.86f to backgroundColor.copy(alpha = 0.94f),
                1.0f to backgroundColor.copy(alpha = 1.0f)
            )
        )
    }
    val dimColor = remember(backgroundColor) { backgroundColor.copy(alpha = 0.08f) }

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

    val episodeClick = remember(onEpisodeClick) {
        { video: Video ->
            markEpisodeRestore(video.id)
            onEpisodeClick(video)
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
    val backdropRequest = remember(
        localContext,
        meta.background,
        meta.poster,
        backdropWidthPx,
        backdropHeightPx
    ) {
        ImageRequest.Builder(localContext)
            .data(meta.background ?: meta.poster)
            .crossfade(false)
            .size(width = backdropWidthPx, height = backdropHeightPx)
            .build()
    }

    // Animated gradient alpha (moved outside subcomposition scope)
    val gradientAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "gradientFade"
    )

    // Always-composed bottom gradient alpha (avoids add/remove during scroll)
    val bottomGradientAlpha by animateFloatAsState(
        targetValue = if (isScrolledPastHero) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "bottomGradientFade"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Sticky background — backdrop or trailer
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop image (fades out when trailer plays)
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha },
                contentScale = ContentScale.Crop
            )

            // Trailer video (fades in when trailer plays)
            TrailerPlayer(
                trailerUrl = trailerUrl,
                isPlaying = isTrailerPlaying,
                seekRequestToken = if (showTrailerControls) trailerSeekToken else 0,
                seekDeltaMs = if (showTrailerControls) trailerSeekDeltaMs else 0L,
                onRemoteKey = onTrailerControlKey,
                onProgressChanged = onTrailerProgressChanged,
                onEnded = onTrailerEnded,
                modifier = Modifier.fillMaxSize()
            )

            // Light global dim so text remains readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dimColor)
            )

            // Left side gradient fade for text readability (fades out during trailer)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = gradientAlpha }
                    .background(leftGradient)
            )

            // Bottom gradient — always composed, alpha-controlled to avoid layout churn
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = bottomGradientAlpha }
                    .background(bottomGradient)
            )
        }

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
                        trailerAvailable = trailerButtonEnabled && !trailerUrl.isNullOrBlank(),
                        onTrailerClick = onTrailerButtonClick,
                        hideLogoDuringTrailer = hideLogoDuringTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        playButtonFocusRequester = heroPlayFocusRequester,
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
                    SeasonTabs(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        onSeasonSelected = onSeasonSelected,
                        onSeasonLongPress = { seasonOptionsDialogSeason = it },
                        selectedTabFocusRequester = selectedSeasonFocusRequester
                    )
                }
                item(key = "episodes_$selectedSeason", contentType = "episodes") {
                    EpisodesRow(
                        episodes = episodesForSeason,
                        episodeProgressMap = episodeProgressMap,
                        watchedEpisodes = watchedEpisodes,
                        episodeWatchedPendingKeys = episodeWatchedPendingKeys,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        onEpisodeClick = episodeClick,
                        onToggleEpisodeWatched = onToggleEpisodeWatched,
                        onMarkSeasonWatched = onMarkSeasonWatched,
                        onMarkSeasonUnwatched = onMarkSeasonUnwatched,
                        isSeasonFullyWatched = isSeasonFullyWatched(selectedSeason),
                        selectedSeason = selectedSeason,
                        onMarkPreviousEpisodesWatched = onMarkPreviousEpisodesWatched,
                        upFocusRequester = selectedSeasonFocusRequester,
                        downFocusRequester = episodesDownFocusRequester,
                        restoreEpisodeId = if (pendingRestoreType == RestoreTarget.EPISODE) pendingRestoreEpisodeId else null,
                        restoreFocusToken = if (pendingRestoreType == RestoreTarget.EPISODE) restoreFocusToken else 0,
                        onRestoreFocusHandled = {
                            clearPendingRestore()
                        }
                    )
                }
            }

            // Cast / More like this section
            if (hasPeopleSection) {
                if (hasPeopleTabs) {
                    item(key = "cast_more_like_tabs", contentType = "horizontal_row") {
                        PeopleSectionTabs(
                            activeTab = activePeopleTab,
                            tabs = peopleTabItems,
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
                                    upFocusRequester = castTabFocusRequester.takeIf { hasPeopleTabs },
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
                                    }
                                )
                            }

                            PeopleSectionTab.MORE_LIKE_THIS -> {
                                MoreLikeThisSection(
                                    items = moreLikeThis,
                                    upFocusRequester = moreLikeTabFocusRequester.takeIf { hasPeopleTabs },
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
                                        selectedSeasonFocusRequester
                                    },
                                    firstItemFocusRequester = ratingsContentFocusRequester
                                )
                            }
                        }
                    }
                }
            }

            if (isTvShow) {
                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks
                        )
                    }
                }

                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies
                        )
                    }
                }
            } else {
                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies
                        )
                    }
                }

                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks
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
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PeopleSectionTabs(
    activeTab: PeopleSectionTab,
    tabs: List<PeopleTabItem>,
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
            .focusRestorer {
                restorerRequester
            },
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
    downFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onFocused,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
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

        Divider(color = NuvioColors.Border, thickness = 1.dp)

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
