@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.annotation.RawRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.ExternalPlayerLauncher
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBackPress: (currentVideoId: String?, currentSeason: Int?, currentEpisode: Int?, autoPlayEnabled: Boolean) -> Unit,
    onPlaybackErrorBack: () -> Unit = { onBackPress(null, null, null, false) },
    onPlaybackEnded: ((nextVideoId: String?, nextSeason: Int?, nextEpisode: Int?) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val progressBarFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val streamsFocusRequester = remember { FocusRequester() }
    val sourceStreamsFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    var skipButtonActuallyVisible by remember { mutableStateOf(false) }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    var subtitleDelayAutoSyncFocused by remember { mutableStateOf(false) }
    var subtitleTimingConsumeNextConfirmKeyUp by remember { mutableStateOf(false) }
    val exitPlayer: () -> Unit = {
        viewModel.stopAndRelease()
        onBackPress(uiState.currentVideoId, uiState.currentSeason, uiState.currentEpisode, uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL)
    }
    val exitPlayerFromError: () -> Unit = {
        viewModel.stopAndRelease()
        onPlaybackErrorBack()
    }

    val handleBackPress = {
        if (uiState.error != null) {
            exitPlayerFromError()
        } else if (uiState.showAudioOverlay || uiState.showSubtitleOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay)
        } else if (uiState.showStreamInfoOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissStreamInfo)
        } else if (uiState.showPauseOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
        } else if (uiState.showMoreDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
        } else if (uiState.showSubtitleTimingDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog)
        } else if (uiState.showSubtitleDelayOverlay) {
            viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
        } else if (uiState.showSubtitleStylePanel) {
            viewModel.onEvent(PlayerEvent.OnDismissSubtitleStylePanel)
        } else if (uiState.showSourcesPanel) {
            viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
        } else if (uiState.showEpisodesPanel) {
            if (uiState.showEpisodeStreams) {
                viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams)
            } else {
                viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel)
            }
        } else if (uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed && !uiState.showControls) {
            viewModel.onEvent(PlayerEvent.OnDismissSkipIntro)
        } else if (uiState.showNextEpisodeCard && uiState.nextEpisode != null) {
            viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
        } else if (uiState.showControls) {
            // If controls are visible, hide them instead of going back
            viewModel.hideControls()
        } else {
            // If controls are hidden, go back
            exitPlayer()
        }
    }

    BackHandler {
        handleBackPress()
    }

    LaunchedEffect(uiState.playbackEnded, uiState.error) {
        if (uiState.playbackEnded && uiState.error == null &&
            !uiState.nextEpisodeAutoPlaySearching &&
            uiState.nextEpisodeAutoPlayCountdownSec == null
        ) {
            viewModel.stopAndRelease()
            val next = uiState.nextEpisode?.takeIf { it.hasAired }
            if (onPlaybackEnded != null) {
                onPlaybackEnded(next?.videoId, next?.season, next?.episode)
            } else {
                onBackPress(uiState.currentVideoId, uiState.currentSeason, uiState.currentEpisode, uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL)
            }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.pauseForLifecycle()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Don't auto-resume, let user control
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Frame rate matching lifecycle.
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(activity) {
        viewModel.attachHostActivity(activity)
        viewModel.startInitialPlaybackIfNeeded()
    }
    DisposableEffect(activity) {
        onDispose {
            viewModel.attachHostActivity(null)
        }
    }
    LaunchedEffect(uiState.frameRateMatchingMode) {
        if (activity != null &&
            uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF
        ) {
            com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
        }
    }
    // Restore original display mode when leaving the player
    DisposableEffect(activity, uiState.frameRateMatchingMode) {
        onDispose {
            if (activity != null) {
                if (uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.START_STOP) {
                    com.nuvio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
                } else {
                    com.nuvio.tv.core.player.FrameRateUtils.cleanupDisplayListener()
                    com.nuvio.tv.core.player.FrameRateUtils.clearOriginalDisplayMode()
                }
            }
        }
    }

    // Request focus for key events when controls visibility or panel state changes
    LaunchedEffect(
        uiState.showControls,
        uiState.showEpisodesPanel,
        uiState.showSourcesPanel,
        uiState.showSubtitleStylePanel,
        uiState.showSubtitleDelayOverlay,
        uiState.showSubtitleTimingDialog,
        uiState.showAudioOverlay,
        uiState.showSubtitleOverlay,
        uiState.showSpeedDialog,
    ) {
        if (uiState.showControls && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay && !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel && !uiState.showSubtitleDelayOverlay &&
            !uiState.showSubtitleTimingDialog &&
            !uiState.showSpeedDialog
        ) {
            // Wait for AnimatedVisibility animation to complete before focusing play/pause button
            kotlinx.coroutines.delay(250)
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus requester may not be ready yet
            }
        } else if (!uiState.showControls) {
            // When controls are hidden, let skip intro button take focus if visible
            val skipVisible = uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed
            val nextEpisodeVisible = uiState.showNextEpisodeCard && uiState.nextEpisode != null
            if (!skipVisible && !nextEpisodeVisible) {
                try {
                    containerFocusRequester.requestFocus()
                } catch (e: Exception) {
                    // Focus requester may not be ready yet
                }
            }
            // If skip or next episode card is visible, their own LaunchedEffect will request focus
        }
    }

    // Initial focus on container - the LaunchedEffect above will handle focusing controls
    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }
    LaunchedEffect(uiState.showSubtitleDelayOverlay) {
        subtitleDelayAutoSyncFocused = false
    }
    LaunchedEffect(uiState.showSubtitleTimingDialog) {
        if (!uiState.showSubtitleTimingDialog) {
            subtitleTimingConsumeNextConfirmKeyUp = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(containerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    return@onPreviewKeyEvent when (keyEvent.nativeKeyEvent.action) {
                        KeyEvent.ACTION_DOWN -> true
                        KeyEvent.ACTION_UP -> {
                            handleBackPress()
                            true
                        }
                        else -> true
                    }
                }

                if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_CAPTIONS) {
                    return@onPreviewKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent true
                }

                if (uiState.showSubtitleDelayOverlay) {
                    viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
                } else if (
                    !uiState.showEpisodesPanel &&
                    !uiState.showSourcesPanel &&
                    !uiState.showAudioOverlay &&
                    !uiState.showSubtitleOverlay &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showSubtitleTimingDialog &&
                    !uiState.showSpeedDialog
                ) {
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay)
                }
                true
            }
            .onKeyEvent { keyEvent ->
                if (subtitleTimingConsumeNextConfirmKeyUp &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    subtitleTimingConsumeNextConfirmKeyUp = false
                    return@onKeyEvent true
                }
                if (uiState.showSubtitleDelayOverlay) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (subtitleDelayAutoSyncFocused) {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER -> {
                                    subtitleDelayAutoSyncFocused = false
                                    subtitleTimingConsumeNextConfirmKeyUp = true
                                    viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    subtitleDelayAutoSyncFocused = false
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    return@onKeyEvent true
                                }
                            }
                        } else {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS))
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS))
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    subtitleDelayAutoSyncFocused = true
                                    return@onKeyEvent true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    return@onKeyEvent true
                                }
                            }
                        }
                    }
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        return@onKeyEvent true
                    }
                    if (keyEvent.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) {
                        // While open, consume all non-back keys to avoid accidental dismissal.
                        return@onKeyEvent true
                    }
                }

                // When a side panel or dialog is open, let it handle all keys
                val panelOrDialogOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
                        uiState.showAudioOverlay || uiState.showSubtitleOverlay ||
                        uiState.showSubtitleStylePanel || uiState.showSpeedDialog ||
                        uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
                        uiState.showMoreDialog
                if (panelOrDialogOpen) return@onKeyEvent false

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                                return@onKeyEvent true
                            }
                        }
                    }
                    return@onKeyEvent false
                }

                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (uiState.showPauseOverlay) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                // Resume directly from pause overlay in one click.
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            else -> {
                                viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
                            }
                        }
                        return@onKeyEvent true
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                                true
                            } else {
                                // Let the focused button handle it
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val stepMs = when {
                                    repeatCount >= 8 -> 30_000L
                                    repeatCount >= 3 -> 20_000L
                                    else -> 10_000L
                                }
                                val isLeft = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                val deltaMs = if (isLeft) -stepMs else stepMs
                                viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(deltaMs))
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!uiState.showControls) {
                                    viewModel.onEvent(PlayerEvent.OnToggleControls)
                                } else {
                                    try {
                                        progressBarFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        val skipVisible = skipButtonActuallyVisible
                                        if (skipVisible) {
                                            try {
                                                skipIntroFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else if (uiState.showNextEpisodeCard && uiState.nextEpisode != null) {
                                            try {
                                                nextEpisodeFocusRequester.requestFocus()
                                            } catch (_: Exception) {
                                            }
                                        } else {
                                            viewModel.hideControls()
                                        }
                                    }
                                }
                                true
                            }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnToggleControls)
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            viewModel.onEvent(PlayerEvent.OnPlayPause)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!uiState.isPlaying) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            viewModel.onEvent(PlayerEvent.OnSeekForward)
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            viewModel.onEvent(PlayerEvent.OnSeekBackward)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Video Player
        if (uiState.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
            MpvPlayerSurface(
                viewModel = viewModel,
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering,
                aspectMode = uiState.aspectMode,
                subtitleStyle = uiState.subtitleStyle,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            viewModel.exoPlayer?.let { player ->
                ExoPlayerSurface(
                    player = player,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    aspectMode = uiState.aspectMode,
                    useLibass = uiState.useLibass,
                    libassRenderType = uiState.libassRenderType,
                    subtitleStyle = uiState.subtitleStyle,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        LoadingOverlay(
            visible = uiState.showLoadingOverlay && uiState.error == null,
            backdropUrl = uiState.backdrop,
            logoUrl = uiState.logo,
            title = uiState.title,
            message = uiState.loadingMessage,
            progress = uiState.loadingProgress,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
        )

        PauseOverlay(
            visible = uiState.showPauseOverlay && uiState.error == null && !uiState.showLoadingOverlay,
            onClose = { viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay) },
            title = uiState.title,
            logo = uiState.logo,
            episodeTitle = uiState.currentEpisodeTitle,
            season = uiState.currentSeason,
            episode = uiState.currentEpisode,
            year = uiState.releaseYear,
            type = uiState.contentType,
            description = uiState.description,
            cast = uiState.castMembers,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.5f)
        )

        StreamInfoOverlay(
            visible = uiState.showStreamInfoOverlay && uiState.error == null && !uiState.showLoadingOverlay,
            onClose = { viewModel.onEvent(PlayerEvent.OnDismissStreamInfo) },
            data = uiState.streamInfoData,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        // Torrent stats overlay (top-right corner)
        TorrentOverlay(
            visible = uiState.isTorrentStream && uiState.showTorrentStats && !uiState.hideTorrentStats && uiState.error == null,
            downloadSpeed = uiState.torrentDownloadSpeed,
            uploadSpeed = uiState.torrentUploadSpeed,
            peers = uiState.torrentPeers,
            seeds = uiState.torrentSeeds,
            totalProgress = uiState.torrentTotalProgress,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .zIndex(2.7f)
        )

        // Buffering indicator
        if (uiState.isBuffering && !uiState.showLoadingOverlay) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isTorrentStream && uiState.torrentBufferingMessage != null) {
                    // Torrent rebuffer: spinner + download stats + progress bar
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LoadingIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = uiState.torrentBufferingMessage ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        if (uiState.torrentBufferingProgress > 0f) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(3.dp)
                                    .background(
                                        color = Color.White.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(uiState.torrentBufferingProgress.coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(
                                            color = Color.White.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    }
                } else {
                    LoadingIndicator()
                }
            }
        }

        // Error state
        if (uiState.error != null) {
            ErrorOverlay(
                message = uiState.error!!,
                onBack = exitPlayerFromError
            )
        }

        val skipButtonBottomPadding by animateDpAsState(
            targetValue = if (uiState.showControls) 122.dp else 30.dp,
            animationSpec = tween(durationMillis = 180),
            label = "skipButtonBottomPadding"
        )

        // Skip Intro button (bottom-left, lifted when controls are visible)
        SkipIntroButton(
            interval = if (uiState.showPauseOverlay || uiState.showLoadingOverlay) null else uiState.activeSkipInterval,
            dismissed = uiState.skipIntervalDismissed,
            controlsVisible = uiState.showControls,
            onSkip = { viewModel.onEvent(PlayerEvent.OnSkipIntro) },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSkipIntro) },
            onVisibilityChanged = { skipButtonActuallyVisible = it },
            onFocused = { viewModel.scheduleHideControls() },
            focusRequester = skipIntroFocusRequester,
            downFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
            upFocusRequester = null,
            onHideControls = {
                if (uiState.showControls) viewModel.hideControls()
                else viewModel.onEvent(PlayerEvent.OnToggleControls)
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = skipButtonBottomPadding)
                .zIndex(2.1f)
        )
        NextEpisodeCardOverlay(
            nextEpisode = uiState.nextEpisode,
            visible = uiState.showNextEpisodeCard &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showStreamInfoOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog,
            controlsVisible = uiState.showControls,
            isPlayable = uiState.nextEpisode?.hasAired == true,
            unairedMessage = uiState.nextEpisode?.unairedMessage,
            isAutoPlaySearching = uiState.nextEpisodeAutoPlaySearching,
            autoPlaySourceName = uiState.nextEpisodeAutoPlaySourceName,
            autoPlayCountdownSec = uiState.nextEpisodeAutoPlayCountdownSec,
            onPlayNext = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
            focusRequester = nextEpisodeFocusRequester,
            downFocusRequester = if (uiState.showControls) progressBarFocusRequester else null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 26.dp, bottom = if (uiState.showControls) 122.dp else 30.dp)
                .zIndex(2.1f)
        )

        // Parental guide overlay (shows when video first starts playing)
        ParentalGuideOverlay(
            warnings = uiState.parentalWarnings,
            isVisible = uiState.showParentalGuide,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnParentalGuideHide)
            },
            modifier = Modifier.align(Alignment.TopStart)
        )

        DisplayModeOverlay(
            info = uiState.displayModeInfo,
            isVisible = uiState.showDisplayModeInfo,
            onAnimationComplete = {
                viewModel.onEvent(PlayerEvent.OnHideDisplayModeInfo)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2.2f)
        )

        val showClockOverlay = uiState.showControls &&
            uiState.osdClockEnabled &&
            uiState.error == null &&
            !uiState.showLoadingOverlay &&
            !uiState.showPauseOverlay &&
            !uiState.showEpisodesPanel &&
            !uiState.showSourcesPanel &&
            !uiState.showAudioOverlay &&
            !uiState.showSubtitleOverlay &&
            !uiState.showSubtitleStylePanel &&
            !uiState.showSpeedDialog &&
            !uiState.showMoreDialog &&
            !uiState.showDisplayModeInfo

        AnimatedVisibility(
            visible = showClockOverlay,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 28.dp, top = 24.dp)
                .zIndex(2.15f)
        ) {
            PlayerClockOverlayHost(
                viewModel = viewModel,
                playbackSpeed = uiState.playbackSpeed
            )
        }

        // Controls overlay
        AnimatedVisibility(
            visible = uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showStreamInfoOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSpeedDialog,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            val context = LocalContext.current
            PlayerControlsOverlay(
                uiState = uiState,
                viewModel = viewModel,
                playPauseFocusRequester = playPauseFocusRequester,
                progressBarFocusRequester = progressBarFocusRequester,
                progressBarUpFocusRequester = when {
                    skipButtonActuallyVisible -> skipIntroFocusRequester
                    uiState.showNextEpisodeCard && uiState.nextEpisode != null -> nextEpisodeFocusRequester
                    else -> null
                },
                onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
                onPlayNextEpisode = { viewModel.onEvent(PlayerEvent.OnPlayNextEpisode) },
                onSeekForward = { viewModel.onEvent(PlayerEvent.OnSeekForward) },
                onSeekBackward = { viewModel.onEvent(PlayerEvent.OnSeekBackward) },
                onSeekTo = { viewModel.onEvent(PlayerEvent.OnSeekTo(it)) },
                onShowEpisodesPanel = { viewModel.onEvent(PlayerEvent.OnShowEpisodesPanel) },
                onShowSourcesPanel = { viewModel.onEvent(PlayerEvent.OnShowSourcesPanel) },
                onShowAudioDialog = { viewModel.onEvent(PlayerEvent.OnShowAudioOverlay) },
                onShowSubtitleDialog = { viewModel.onEvent(PlayerEvent.OnShowSubtitleOverlay) },
                onShowSpeedDialog = { viewModel.onEvent(PlayerEvent.OnShowSpeedDialog) },
                onToggleAspectRatio = {
                    Log.d("PlayerScreen", "onToggleAspectRatio called - dispatching event")
                    viewModel.onEvent(PlayerEvent.OnToggleAspectRatio)
                },
                onSwitchPlayerEngine = { viewModel.onEvent(PlayerEvent.OnSwitchInternalPlayerEngine) },
                onToggleMoreActions = {
                    if (uiState.showMoreDialog) {
                        viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
                    } else {
                        viewModel.onEvent(PlayerEvent.OnShowMoreDialog)
                    }
                },
                onOpenInExternalPlayer = {
                    val url = viewModel.getCurrentStreamUrl()
                    val title = uiState.title
                    val headers = viewModel.getCurrentHeaders()
                    viewModel.stopAndRelease()
                    onBackPress(uiState.currentVideoId, uiState.currentSeason, uiState.currentEpisode, uiState.streamAutoPlayMode != StreamAutoPlayMode.MANUAL)
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = title,
                        headers = headers
                    )
                },
                onShowStreamInfo = { viewModel.onEvent(PlayerEvent.OnShowStreamInfo) },
                onResetHideTimer = { viewModel.scheduleHideControls(); viewModel.onUserInteraction() },
                onHideControls = { viewModel.hideControls() },
                onBack = { exitPlayer() },
                skipButtonVisible = skipButtonActuallyVisible
            )
        }

        // Aspect ratio indicator (floating pill)
        AnimatedVisibility(
            visible = uiState.showAspectRatioIndicator,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            AspectRatioIndicator(text = uiState.aspectRatioIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showStreamSourceIndicator,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 128.dp)
        ) {
            StreamSourceIndicator(text = uiState.streamSourceIndicatorText)
        }

        AnimatedVisibility(
            visible = uiState.showPlayerEngineSwitchInfo && uiState.error == null,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.Center)
                .zIndex(2.35f)
        ) {
            PlayerEngineSwitchIndicator(
                title = stringResource(R.string.player_engine_switching_title),
                message = uiState.playerEngineSwitchInfoText
            )
        }

        // Seek-only overlay (progress bar + time) when controls are hidden
        AnimatedVisibility(
            visible = uiState.showSubtitleDelayOverlay &&
                !uiState.showControls &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleTimingDialog &&
                !uiState.showSpeedDialog,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .zIndex(2.3f)
        ) {
            SubtitleDelayOverlay(
                subtitleDelayMs = uiState.subtitleDelayMs,
                isAutoSyncButtonFocused = subtitleDelayAutoSyncFocused,
                isSliderFocused = !subtitleDelayAutoSyncFocused,
                onOpenSyncByLine = {
                    subtitleDelayAutoSyncFocused = false
                    subtitleTimingConsumeNextConfirmKeyUp = true
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleTimingDialog)
                }
            )
        }

        AnimatedVisibility(
            visible = uiState.showSeekOverlay && !uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showSubtitleDelayOverlay && !uiState.showSubtitleTimingDialog &&
                !uiState.showMoreDialog,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SeekOverlayHost(viewModel = viewModel)
        }

        // Episodes/streams side panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            // Scrim (fades in/out, no slide)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Panel itself (slides in from right)
        AnimatedVisibility(
            visible = uiState.showEpisodesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                EpisodesSidePanel(
                    uiState = uiState,
                    episodesFocusRequester = episodesFocusRequester,
                    streamsFocusRequester = streamsFocusRequester,
                    onClose = { viewModel.onEvent(PlayerEvent.OnDismissEpisodesPanel) },
                    onBackToEpisodes = { viewModel.onEvent(PlayerEvent.OnBackFromEpisodeStreams) },
                    onReloadEpisodeStreams = { viewModel.onEvent(PlayerEvent.OnReloadEpisodeStreams) },
                    onSeasonSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSeasonSelected(it)) },
                    onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeAddonFilterSelected(it)) },
                    onEpisodeSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeSelected(it)) },
                    onStreamSelected = { viewModel.onEvent(PlayerEvent.OnEpisodeStreamSelected(it)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Sources panel scrim
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // Sources panel (slides in from right)
        AnimatedVisibility(
            visible = uiState.showSourcesPanel && uiState.error == null,
            enter = slideInHorizontally(
                animationSpec = tween(220),
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(220),
                targetOffsetX = { it }
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                StreamSourcesSidePanel(
                    uiState = uiState,
                    streamsFocusRequester = sourceStreamsFocusRequester,
                    onClose = { viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel) },
                    onReload = { viewModel.onEvent(PlayerEvent.OnReloadSourceStreams) },
                    onAddonFilterSelected = { viewModel.onEvent(PlayerEvent.OnSourceAddonFilterSelected(it)) },
                    onStreamSelected = { viewModel.onEvent(PlayerEvent.OnSourceStreamSelected(it)) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Subtitle style panel scrim
        AnimatedVisibility(
            visible = uiState.showSubtitleStylePanel && uiState.error == null,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
        }

        // Subtitle style panel
        AnimatedVisibility(
            visible = uiState.showSubtitleStylePanel && uiState.error == null,
            enter = slideInVertically(
                animationSpec = tween(220),
                initialOffsetY = { -it }
            ),
            exit = slideOutVertically(
                animationSpec = tween(220),
                targetOffsetY = { -it }
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                SubtitleStyleSidePanel(
                    subtitleStyle = uiState.subtitleStyle,
                    onEvent = { viewModel.onEvent(it) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                )
            }
        }

        // Audio track dialog
        AudioSelectionOverlay(
            visible = uiState.showAudioOverlay,
            tracks = uiState.audioTracks,
            selectedIndex = uiState.selectedAudioTrackIndex,
            audioAmplificationDb = uiState.audioAmplificationDb,
            isAmplificationAvailable = uiState.isAudioAmplificationAvailable,
            persistAmplification = uiState.persistAudioAmplification,
            onTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(it)) },
            onAmplificationChange = { viewModel.onEvent(PlayerEvent.OnSetAudioAmplificationDb(it)) },
            onPersistAmplificationChange = {
                viewModel.onEvent(PlayerEvent.OnSetPersistAudioAmplification(it))
            },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        SubtitleSelectionOverlay(
            visible = uiState.showSubtitleOverlay,
            internalTracks = uiState.subtitleTracks,
            selectedInternalIndex = uiState.selectedSubtitleTrackIndex,
            addonSubtitles = uiState.addonSubtitles,
            selectedAddonSubtitle = uiState.selectedAddonSubtitle,
            subtitleStyle = uiState.subtitleStyle,
            subtitleDelayMs = uiState.subtitleDelayMs,
            installedSubtitleAddonOrder = uiState.installedSubtitleAddonOrder,
            isLoadingAddons = uiState.isLoadingAddonSubtitles,
            onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },
            onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },
            onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
            onEvent = { viewModel.onEvent(it) },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.6f)
        )

        PlayerOverlayScaffold(
            visible = uiState.showSubtitleTimingDialog &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioOverlay &&
                !uiState.showSubtitleOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showSpeedDialog &&
                !uiState.showMoreDialog,
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSubtitleTimingDialog) },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.35f),
            captureKeys = false,
            contentPadding = PaddingValues(top = 44.dp)
        ) {
            SubtitleTimingDialogHost(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.TopCenter),
                selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                cues = uiState.subtitleAutoSyncCues,
                capturedVideoMs = uiState.subtitleAutoSyncCapturedVideoMs,
                statusMessage = uiState.subtitleAutoSyncStatus,
                errorMessage = uiState.subtitleAutoSyncError,
                isLoadingCues = uiState.subtitleAutoSyncLoading,
                onCaptureNow = { viewModel.onEvent(PlayerEvent.OnCaptureSubtitleAutoSyncTime) },
                onCueSelected = { cue ->
                    viewModel.onEvent(PlayerEvent.OnApplySubtitleAutoSyncCue(cue.startTimeMs))
                }
            )
        }

        if (uiState.showSpeedDialog) {
            SpeedSelectionDialog(
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = { viewModel.onEvent(PlayerEvent.OnSetPlaybackSpeed(it)) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissTransientOverlay) }
            )
        }

    }
}

@Composable
private fun MpvPlayerSurface(
    viewModel: PlayerViewModel,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    subtitleStyle: SubtitleStyleSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val mpvView = remember(context) {
        NuvioMpvSurfaceView(context)
    }

    AndroidView(
        factory = { mpvView },
        modifier = modifier
    )

    DisposableEffect(viewModel, mpvView) {
        viewModel.attachMpvView(mpvView)
        onDispose {
            viewModel.attachMpvView(null)
        }
    }

    DisposableEffect(mpvView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            mpvView.applyAspectMode(latestAspectMode)
        }
        mpvView.addOnLayoutChangeListener(listener)
        onDispose {
            mpvView.removeOnLayoutChangeListener(listener)
        }
    }

    LaunchedEffect(mpvView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (mpvView.keepScreenOn != shouldKeepScreenOn) {
            mpvView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(mpvView, aspectMode) {
        mpvView.applyAspectMode(aspectMode)
    }

    LaunchedEffect(mpvView, subtitleStyle) {
        mpvView.applySubtitleStyle(subtitleStyle)
    }
}

@Composable
private fun ExoPlayerSurface(
    player: ExoPlayer,
    isPlaying: Boolean,
    isBuffering: Boolean,
    aspectMode: AspectMode,
    useLibass: Boolean,
    libassRenderType: LibassRenderType,
    subtitleStyle: SubtitleStyleSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestAspectMode by rememberUpdatedState(aspectMode)
    val playerView = remember(context, player) {
        PlayerView(context).apply {
            useController = false
            keepScreenOn = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            enableComposeSurfaceSyncWorkaroundIfAvailable()
            this.player = player
        }
    }

    AndroidView(
        factory = { playerView },
        modifier = modifier,
        update = {
            it.syncLibassOverlay(
                player = player,
                enabled = useLibass,
                renderType = libassRenderType
            )
        }
    )

    DisposableEffect(playerView, player) {
        if (playerView.player !== player) {
            playerView.player = player
        }
        onDispose {
            if (playerView.player === player) {
                playerView.player = null
            }
        }
    }

    DisposableEffect(player, playerView) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }

            override fun onRenderedFirstFrame() {
                playerView.post {
                    playerView.applyExoAspectMode(latestAspectMode)
                }
            }
        }
        player.addListener(listener)
        playerView.post {
            playerView.applyExoAspectMode(latestAspectMode)
        }
        onDispose {
            player.removeListener(listener)
        }
    }

    DisposableEffect(playerView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            playerView.applyExoAspectMode(latestAspectMode)
        }
        playerView.addOnLayoutChangeListener(listener)
        onDispose {
            playerView.removeOnLayoutChangeListener(listener)
        }
    }

    LaunchedEffect(playerView, isPlaying, isBuffering) {
        val shouldKeepScreenOn = isPlaying || isBuffering
        if (playerView.keepScreenOn != shouldKeepScreenOn) {
            playerView.keepScreenOn = shouldKeepScreenOn
        }
    }

    LaunchedEffect(playerView, aspectMode) {
        playerView.applyExoAspectMode(aspectMode)
    }

    LaunchedEffect(playerView, player, useLibass, libassRenderType) {
        playerView.syncLibassOverlay(
            player = player,
            enabled = useLibass,
            renderType = libassRenderType
        )
    }

    LaunchedEffect(playerView, subtitleStyle) {
        playerView.applySubtitleStyleIfNeeded(subtitleStyle)
    }
}

private fun PlayerView.enableComposeSurfaceSyncWorkaroundIfAvailable() {
    runCatching {
        javaClass
            .getMethod("setEnableComposeSurfaceSyncWorkaround", java.lang.Boolean.TYPE)
            .invoke(this, true)
    }
}

private fun PlayerView.applyExoAspectMode(mode: AspectMode) {
    setTag(R.id.player_view_aspect_mode_tag, mode)
    applyExoAspectMode(this, mode)
}

private fun PlayerView.applySubtitleStyleIfNeeded(subtitleStyle: SubtitleStyleSettings) {
    if (getTag(R.id.player_view_subtitle_style_tag) == subtitleStyle) {
        return
    }
    setTag(R.id.player_view_subtitle_style_tag, subtitleStyle)
    subtitleView?.apply {
        val baseFontSize = 24f
        val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
        setApplyEmbeddedFontSizes(false)

        val typeface = if (subtitleStyle.bold) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }

        val edgeType = if (subtitleStyle.outlineEnabled) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }

        setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                subtitleStyle.textColor,
                subtitleStyle.backgroundColor,
                android.graphics.Color.TRANSPARENT,
                edgeType,
                subtitleStyle.outlineColor,
                typeface
            )
        )

        setApplyEmbeddedStyles(false)

        val bottomPaddingFraction =
            (0.06f + (subtitleStyle.verticalOffset / 250f)).coerceIn(0f, 0.4f)
        setBottomPaddingFraction(bottomPaddingFraction)

        post {
            val extraPadding = (height * (subtitleStyle.verticalOffset / 400f)).toInt().coerceAtLeast(0)
            setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
        }
    }
}

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderType: LibassRenderType
) {
    val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
        R.id.libass_overlay_container_gl
    } else {
        R.id.libass_overlay_container
    }
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderType.usesOverlaySubtitleView()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
    setTag(R.id.libass_overlay_bound_player, player)
}

private fun LibassRenderType.usesOverlaySubtitleView(): Boolean {
    return this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL
}

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleView) {
            removeViewAt(index)
        }
    }
}

@Composable
private fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester,
    progressBarUpFocusRequester: FocusRequester? = null,
    onPlayPause: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onShowSourcesPanel: () -> Unit,
    onShowAudioDialog: () -> Unit,
    onShowSubtitleDialog: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onSwitchPlayerEngine: () -> Unit,
    onToggleMoreActions: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onShowStreamInfo: () -> Unit,
    onResetHideTimer: () -> Unit,
    onHideControls: () -> Unit,
    onBack: () -> Unit,
    skipButtonVisible: Boolean = false
) {
    val customPlayPainter = rememberRawSvgPainter(R.raw.ic_player_play)
    val customPausePainter = rememberRawSvgPainter(R.raw.ic_player_pause)
    val customSubtitlePainter = rememberRawSvgPainter(R.raw.ic_player_subtitles)
    val customAudioPainter = rememberRawSvgPainter(R.raw.ic_player_audio_filled)
    val customSourcePainter = rememberRawSvgPainter(R.raw.ic_player_source)
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)
    val customEpisodesPainter = rememberRawSvgPainter(R.raw.ic_player_episodes)

    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Bottom gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 24.dp)
        ) {
            val skipIntroVisible = uiState.activeSkipInterval != null

            AnimatedVisibility(
                visible = !skipIntroVisible,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(180))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val displayName = if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        uiState.contentName ?: uiState.title
                    } else {
                        uiState.title
                    }

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        val seasonEpisodeCode = stringResource(
                            R.string.season_episode_format,
                            uiState.currentSeason,
                            uiState.currentEpisode
                        )
                        val episodeInfo = buildString {
                            append(seasonEpisodeCode)
                            if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                                append(" • ${uiState.currentEpisodeTitle}")
                            }
                        }
                        Text(
                            text = episodeInfo,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val hasYear = !uiState.releaseYear.isNullOrBlank()
                    val showVia = !uiState.isPlaying && !uiState.currentStreamName.isNullOrBlank()
                    val yearText = uiState.releaseYear.orEmpty()

                    if (hasYear || showVia) {
                        Column {
                            if (hasYear) {
                                Text(
                                    text = yearText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.68f)
                                )
                            }

                            AnimatedVisibility(
                                visible = showVia,
                                enter = fadeIn(animationSpec = tween(durationMillis = 220)),
                                exit = fadeOut(animationSpec = tween(durationMillis = 180))
                            ) {
                                Text(
                                    text = stringResource(R.string.player_via, (uiState.currentStreamName ?: "").replace("\n", " · ")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.68f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar — always LTR regardless of locale
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PlayerControlsProgressBarHost(
                    viewModel = viewModel,
                    focusRequester = progressBarFocusRequester,
                    upFocusRequester = progressBarUpFocusRequester,
                    downFocusRequester = playPauseFocusRequester,
                    onUpKey = onHideControls,
                    onFocused = onResetHideTimer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons row — always LTR regardless of locale
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasEpisodeContext = uiState.currentSeason != null && uiState.currentEpisode != null
                    val hasSubtitleControl = uiState.subtitleTracks.isNotEmpty() || uiState.addonSubtitles.isNotEmpty()
                    val hasAudioControl = uiState.audioTracks.isNotEmpty()
                    val showNextEpisodeButton = uiState.nextEpisode?.hasAired == true &&
                        !uiState.nextEpisodeAutoPlaySearching &&
                        uiState.nextEpisodeAutoPlayCountdownSec == null

                    ControlButton(
                        icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        iconPainter = if (uiState.isPlaying) customPausePainter else customPlayPainter,
                        contentDescription = if (uiState.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                        onClick = onPlayPause,
                        focusRequester = playPauseFocusRequester,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )

                    if (showNextEpisodeButton) {
                        ControlButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.next_episode_label),
                            onClick = onPlayNextEpisode,
                            upFocusRequester = progressBarFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }

                    if (hasSubtitleControl) {
                        ControlButton(
                            icon = Icons.Default.ClosedCaption,
                            iconPainter = customSubtitlePainter,
                            contentDescription = stringResource(R.string.cd_subtitles),
                            onClick = onShowSubtitleDialog,
                            upFocusRequester = progressBarFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }

                    if (hasAudioControl) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            iconPainter = customAudioPainter,
                            contentDescription = stringResource(R.string.cd_audio_tracks),
                            onClick = onShowAudioDialog,
                            upFocusRequester = progressBarFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }

                    ControlButton(
                        icon = Icons.Default.SwapHoriz,
                        iconPainter = customSourcePainter,
                        contentDescription = stringResource(R.string.cd_sources),
                        onClick = onShowSourcesPanel,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )

                    ControlButton(
                        icon = Icons.Default.SwapHoriz,
                        contentDescription = stringResource(R.string.cd_switch_player_engine),
                        onClick = onSwitchPlayerEngine,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )

                    if (hasEpisodeContext) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.List,
                            iconPainter = customEpisodesPainter,
                            contentDescription = stringResource(R.string.cd_episodes),
                            onClick = onShowEpisodesPanel,
                            upFocusRequester = progressBarFocusRequester,
                            onDownKey = onHideControls,
                            onFocused = onResetHideTimer
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.showMoreDialog,
                        enter = slideInHorizontally(
                            animationSpec = tween(180),
                            initialOffsetX = { it / 2 }
                        ) + fadeIn(animationSpec = tween(180)),
                        exit = slideOutHorizontally(
                            animationSpec = tween(160),
                            targetOffsetX = { it / 2 }
                        ) + fadeOut(animationSpec = tween(160))
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ControlButton(
                                icon = Icons.Default.Speed,
                                contentDescription = stringResource(R.string.cd_playback_speed),
                                onClick = {
                                    onShowSpeedDialog()
                                },
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = Icons.Default.AspectRatio,
                                iconPainter = customAspectPainter,
                                contentDescription = stringResource(R.string.cd_aspect_ratio),
                                onClick = {
                                    onToggleAspectRatio()
                                },
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.cd_open_external_player),
                                onClick = {
                                    onOpenInExternalPlayer()
                                },
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = Icons.Default.Info,
                                contentDescription = stringResource(R.string.cd_stream_info),
                                onClick = {
                                    onShowStreamInfo()
                                },
                                upFocusRequester = progressBarFocusRequester,
                                onDownKey = onHideControls,
                                onFocused = onResetHideTimer
                            )
                        }
                    }

                    ControlButton(
                        icon = if (uiState.showMoreDialog) {
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = if (uiState.showMoreDialog) stringResource(R.string.cd_close_more_actions) else stringResource(R.string.cd_more_actions),
                        onClick = onToggleMoreActions,
                        upFocusRequester = progressBarFocusRequester,
                        onDownKey = onHideControls,
                        onFocused = onResetHideTimer
                    )
                }

                // Right side - Time display only
                PlayerControlsTimeTextHost(viewModel = viewModel)
            }
            }
        }
    }
}

@Composable
private fun PlayerControlsProgressBarHost(
    viewModel: PlayerViewModel,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    ProgressBar(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration,
        onSeekPreview = { delta ->
            viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(delta))
        },
        onSeekCommit = {
            viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
        },
        focusRequester = focusRequester,
        upFocusRequester = upFocusRequester,
        downFocusRequester = downFocusRequester,
        onUpKey = onUpKey,
        onFocused = onFocused
    )
}

@Composable
private fun PlayerControlsTimeTextHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    Text(
        text = "${formatTime(playbackTimeline.currentPosition)} / ${formatTime(playbackTimeline.duration)}",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.9f)
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    onDownKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (
                    upFocusRequester != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    try { upFocusRequester.requestFocus() } catch (_: Exception) {}
                    true
                } else if (
                    onDownKey != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onDownKey.invoke()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "progress"
    )
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isFocused) 10.dp else 6.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null || downFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekCommit()
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }

                // testing additional key handling for DPAD_LEFT and DPAD_RIGHT to allow seek in focus (check)
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (downFocusRequester != null) {
                                try {
                                    downFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (upFocusRequester != null) {
                                try {
                                    upFocusRequester.requestFocus()
                                } catch (_: Exception) {
                                }
                                true
                            } else if (onUpKey != null) {
                                onUpKey.invoke()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeekPreview(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeekPreview(10_000L)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (isFocused) Color.White.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.3f)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(3.dp))
                .background(NuvioColors.Secondary)
        )
    }
}

@Composable
private fun SeekOverlay(
    currentPosition: Long,
    duration: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ProgressBar(
                currentPosition = currentPosition,
                duration = duration,
                onSeekPreview = {},
                onSeekCommit = {}
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun SeekOverlayHost(viewModel: PlayerViewModel) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    SeekOverlay(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration
    )
}

@Composable
private fun PlayerClockOverlay(
    currentPosition: Long,
    duration: Long,
    playbackSpeed: Float
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val delayMs = (1_000L - (current % 1_000L)).coerceAtLeast(250L)
            delay(delayMs)
        }
    }

    val effectiveSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
    val remainingMediaMs = (duration - currentPosition).coerceAtLeast(0L)
    val remainingMs = kotlin.math.ceil(remainingMediaMs.toDouble() / effectiveSpeed.toDouble()).toLong()
    val endTimeText = if (duration > 0L) {
        timeFormatter.format(Date(nowMs + remainingMs))
    } else {
        "--:--"
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = timeFormatter.format(Date(nowMs)),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White.copy(alpha = 0.96f)
        )
        Text(
            text = stringResource(R.string.player_ends_at, endTimeText),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.78f)
        )
    }
}

@Composable
private fun PlayerClockOverlayHost(viewModel: PlayerViewModel, playbackSpeed: Float) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    PlayerClockOverlay(
        currentPosition = playbackTimeline.currentPosition,
        duration = playbackTimeline.duration,
        playbackSpeed = playbackSpeed
    )
}

@Composable
private fun SubtitleTimingDialogHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val playbackTimeline by viewModel.playbackTimeline.collectAsState()

    SubtitleTimingDialog(
        modifier = modifier,
        currentPositionMs = playbackTimeline.currentPosition,
        selectedAddonSubtitle = selectedAddonSubtitle,
        cues = cues,
        capturedVideoMs = capturedVideoMs,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        isLoadingCues = isLoadingCues,
        onCaptureNow = onCaptureNow,
        onCueSelected = onCueSelected
    )
}

@Composable
private fun AspectRatioIndicator(text: String) {
    val customAspectPainter = rememberRawSvgPainter(R.raw.ic_player_aspect_ratio)

    // Floating pill indicator for aspect ratio changes
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon background circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = Color(0xFF3B3B3B),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = customAspectPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Text
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color.White
        )
    }
}

@Composable
private fun StreamSourceIndicator(text: String) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerEngineSwitchIndicator(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SubtitleDelayOverlay(
    subtitleDelayMs: Int,
    isAutoSyncButtonFocused: Boolean,
    isSliderFocused: Boolean,
    onOpenSyncByLine: () -> Unit
) {
    val fraction = ((subtitleDelayMs - SUBTITLE_DELAY_MIN_MS).toFloat() /
        (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat()).coerceIn(0f, 1f)
    val sliderAccent = if (isSliderFocused) Color(0xFF4AA3FF) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC0F0F0F))
            .padding(horizontal = 26.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_subtitle_delay),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
            Text(
                text = formatSubtitleDelay(subtitleDelayMs),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.95f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            val thumbWidth = 22.dp
            val thumbOffset = (maxWidth - thumbWidth) * fraction

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.15f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(5) { index ->
                    val tickHeight = if (index == 2) 13.dp else 9.dp
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(tickHeight)
                            .background(sliderAccent.copy(alpha = if (isSliderFocused) 0.52f else 0.22f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .align(Alignment.CenterStart)
                    .width(thumbWidth)
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(sliderAccent.copy(alpha = 0.95f))
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            onClick = onOpenSyncByLine,
            colors = CardDefaults.colors(
                containerColor = if (isAutoSyncButtonFocused) {
                    Color.White.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.11f)
                },
                focusedContainerColor = Color.White.copy(alpha = 0.22f)
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(
                text = "Sync Line",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val request = remember(iconRes, context) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
private fun ErrorOverlay(
    message: String,
    onBack: () -> Unit
) {
    val exitFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        exitFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DialogButton(
                    text = stringResource(R.string.player_go_back),
                    onClick = onBack,
                    isPrimary = true,
                    modifier = Modifier
                        .focusRequester(exitFocusRequester)
                        .focusable()
                )
            }
        }
    }
}

@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_speed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp)
                ) {
                    items(PLAYBACK_SPEEDS) { speed ->
                        SpeedItem(
                            speed = speed,
                            isSelected = speed == currentSpeed,
                            onClick = { onSpeedSelected(speed) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionsDialog(
    onPlaybackSpeed: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_more_actions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                MoreActionItem(
                    text = stringResource(R.string.player_more_speed),
                    onClick = onPlaybackSpeed
                )
                MoreActionItem(
                    text = stringResource(R.string.player_more_aspect_ratio),
                    onClick = onToggleAspectRatio
                )
                MoreActionItem(
                    text = stringResource(R.string.player_more_open_external),
                    onClick = onOpenInExternalPlayer
                )
            }
        }
    }
}

@Composable
private fun MoreActionItem(
    text: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun SpeedItem(
    speed: Float,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.2f) else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (speed == 1f) stringResource(R.string.player_speed_normal) else "${speed}x",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun DialogButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isPrimary) NuvioColors.Secondary else NuvioColors.BackgroundCard,
            focusedContainerColor = if (isPrimary) NuvioColors.Secondary else NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (isPrimary) NuvioColors.OnSecondary else NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return String.format(Locale.US, "%+.3fs", delayMs / 1000f)
}
