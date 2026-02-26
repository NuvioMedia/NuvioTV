@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onPlaybackErrorBack: () -> Unit = onBackPress
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val containerFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val streamsFocusRequester = remember { FocusRequester() }
    val sourceStreamsFocusRequester = remember { FocusRequester() }
    val skipIntroFocusRequester = remember { FocusRequester() }
    val nextEpisodeFocusRequester = remember { FocusRequester() }

    val handleBackPress = {
        if (uiState.error != null) {
            onPlaybackErrorBack()
        } else if (uiState.showPauseOverlay) {
            viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
        } else if (uiState.showMoreDialog) {
            viewModel.onEvent(PlayerEvent.OnDismissMoreDialog)
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
        } else if (uiState.showControls) {
            // If controls are visible, hide them instead of going back
            viewModel.hideControls()
        } else {
            // If controls are hidden, go back
            onBackPress()
        }
    }

    BackHandler {
        handleBackPress()
    }

    LaunchedEffect(uiState.playbackEnded, uiState.error) {
        if (uiState.playbackEnded && uiState.error == null) {
            onBackPress()
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.exoPlayer?.pause()
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

    // Frame rate matching: switch display refresh rate to match video frame rate.
    // Track gets priority; probe is fallback.
    // Allow one correction if source/decision changes after first switch.
    val activity = LocalContext.current as? android.app.Activity
    val coroutineScope = rememberCoroutineScope()
    var afrAppliedSource by remember { mutableStateOf<FrameRateSource?>(null) }
    var afrAppliedRate by remember { mutableStateOf(0f) }
    var afrCorrectionUsed by remember { mutableStateOf(false) }
    LaunchedEffect(
        uiState.detectedFrameRate,
        uiState.detectedFrameRateRaw,
        uiState.detectedFrameRateSource,
        uiState.frameRateMatchingMode
    ) {
        if (uiState.frameRateMatchingMode == com.nuvio.tv.data.local.FrameRateMatchingMode.OFF) {
            afrAppliedSource = null
            afrAppliedRate = 0f
            afrCorrectionUsed = false
            return@LaunchedEffect
        }
        if (uiState.detectedFrameRate <= 0f) {
            afrAppliedSource = null
            afrAppliedRate = 0f
            afrCorrectionUsed = false
            return@LaunchedEffect
        }
        val source = uiState.detectedFrameRateSource ?: return@LaunchedEffect
        val allowFirstDecision = afrAppliedSource == null
        val allowSourceCorrection = afrAppliedSource != null &&
            !afrCorrectionUsed &&
            source != afrAppliedSource &&
            kotlin.math.abs(uiState.detectedFrameRate - afrAppliedRate) > 0.015f
        if (!allowFirstDecision && !allowSourceCorrection) return@LaunchedEffect

        if (activity != null) {
            val probeRaw = if (uiState.detectedFrameRateRaw > 0f) {
                uiState.detectedFrameRateRaw
            } else {
                uiState.detectedFrameRate
            }
            val prefer23976ProbeBias = source == FrameRateSource.PROBE &&
                probeRaw in 23.95f..24.12f
            val targetFrameRate = com.nuvio.tv.core.player.FrameRateUtils.refineFrameRateForDisplay(
                activity = activity,
                detectedFps = uiState.detectedFrameRate,
                prefer23976Near24 = prefer23976ProbeBias
            )
            val wasPlaying = uiState.isPlaying
            com.nuvio.tv.core.player.FrameRateUtils.matchFrameRate(
                activity,
                targetFrameRate,
                onBeforeSwitch = { if (wasPlaying) viewModel.exoPlayer?.pause() },
                onAfterSwitch = { result ->
                    if (wasPlaying) {
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(2000)
                            viewModel.exoPlayer?.play()
                        }
                    }
                    viewModel.onEvent(
                        PlayerEvent.OnShowDisplayModeInfo(
                            DisplayModeInfo(
                                width = result.appliedMode.physicalWidth,
                                height = result.appliedMode.physicalHeight,
                                refreshRate = result.appliedMode.refreshRate,
                                statusMessage = if (result.isFallback) "Fallback applied" else null
                            )
                        )
                    )
                }
            )
            if (allowSourceCorrection) {
                afrCorrectionUsed = true
            }
            afrAppliedSource = source
            afrAppliedRate = targetFrameRate
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
        uiState.showAudioDialog,
        uiState.showSubtitleDialog,
        uiState.showSpeedDialog,
    ) {
        if (uiState.showControls && !uiState.showEpisodesPanel && !uiState.showSourcesPanel &&
            !uiState.showAudioDialog && !uiState.showSubtitleDialog &&
            !uiState.showSubtitleStylePanel && !uiState.showSubtitleDelayOverlay &&
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
                    !uiState.showAudioDialog &&
                    !uiState.showSubtitleDialog &&
                    !uiState.showSubtitleStylePanel &&
                    !uiState.showSpeedDialog
                ) {
                    viewModel.onEvent(PlayerEvent.OnShowSubtitleDialog)
                }
                true
            }
            .onKeyEvent { keyEvent ->
                if (uiState.showSubtitleDelayOverlay) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS))
                                return@onKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                viewModel.onEvent(PlayerEvent.OnAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS))
                                return@onKeyEvent true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                viewModel.onEvent(PlayerEvent.OnHideSubtitleDelayOverlay)
                                return@onKeyEvent true
                            }
                        }
                    }
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        return@onKeyEvent true
                    }
                }

                // When a side panel or dialog is open, let it handle all keys
                val panelOrDialogOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
                        uiState.showAudioDialog || uiState.showSubtitleDialog ||
                        uiState.showSubtitleStylePanel || uiState.showSpeedDialog ||
                        uiState.showSubtitleDelayOverlay || uiState.showMoreDialog
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
                        viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay)
                        return@onKeyEvent true
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (!uiState.showControls) {
                                viewModel.onEvent(PlayerEvent.OnPlayPause)
                                true
                            } else {
                                // Let the focused button handle it
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!uiState.showControls) {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val deltaMs = when {
                                    repeatCount >= 8 -> 30_000L
                                    repeatCount >= 3 -> 20_000L
                                    else -> 10_000L
                                }
                                viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(deltaMs))
                                true
                            } else {
                                // Let focus system handle navigation when controls are visible
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!uiState.showControls) {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val deltaMs = when {
                                    repeatCount >= 8 -> -30_000L
                                    repeatCount >= 3 -> -20_000L
                                    else -> -10_000L
                                }
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
                                val skipVisible = uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed
                                if (skipVisible) {
                                    try {
                                        skipIntroFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        // Focus requester may not be ready yet
                                    }
                                } else if (uiState.showNextEpisodeCard && uiState.nextEpisode != null) {
                                    try {
                                        nextEpisodeFocusRequester.requestFocus()
                                    } catch (_: Exception) {
                                        // Focus requester may not be ready yet
                                    }
                                } else {
                                    viewModel.hideControls()
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
        viewModel.exoPlayer?.let { player ->
            val subtitleStyle = uiState.subtitleStyle
            val resizeMode = uiState.resizeMode
            
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                update = { playerView ->
                    Log.d("PlayerScreen", "Applying resizeMode: $resizeMode")
                    playerView.resizeMode = resizeMode
                    playerView.subtitleView?.apply {
                        // Calculate font size based on percentage (100% = 24sp base)
                        val baseFontSize = 24f
                        val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
                        setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
                        setApplyEmbeddedFontSizes(false)
                        
                        // Apply bold style via typeface
                        val typeface = if (subtitleStyle.bold) {
                            android.graphics.Typeface.DEFAULT_BOLD
                        } else {
                            android.graphics.Typeface.DEFAULT
                        }
                        
                        // Calculate edge type based on outline setting
                        val edgeType = if (subtitleStyle.outlineEnabled) {
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                        } else {
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
                        }
                        
                        setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                subtitleStyle.textColor,
                                subtitleStyle.backgroundColor,
                                android.graphics.Color.TRANSPARENT, // Window color
                                edgeType,
                                subtitleStyle.outlineColor,
                                typeface
                            )
                        )
                        
                        setApplyEmbeddedStyles(false)
                        
                        // Apply vertical offset (-20 = very bottom, 0 = default, 50 = middle)
                        // Convert percentage to fraction for bottom padding
                        val bottomPaddingFraction = (0.06f + (subtitleStyle.verticalOffset / 250f)).coerceIn(0f, 0.4f)
                        setBottomPaddingFraction(bottomPaddingFraction)

                        // Also apply explicit bottom padding based on view height for stronger offset effect
                        post {
                            val extraPadding = (height * (subtitleStyle.verticalOffset / 400f)).toInt().coerceAtLeast(0)
                            setPadding(paddingLeft, paddingTop, paddingRight, extraPadding)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        LoadingOverlay(
            visible = uiState.showLoadingOverlay && uiState.error == null,
            backdropUrl = uiState.backdrop,
            logoUrl = uiState.logo,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
        )

        PauseOverlay(
            visible = uiState.showPauseOverlay && uiState.error == null && !uiState.showLoadingOverlay,
            onClose = { viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay) },
            title = uiState.title,
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

        // Buffering indicator
        if (uiState.isBuffering && !uiState.showLoadingOverlay) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        // Error state
        if (uiState.error != null) {
            ErrorOverlay(
                message = uiState.error!!,
                onBack = onPlaybackErrorBack
            )
        }

        // Skip Intro button (bottom-left, independent of controls)
        SkipIntroButton(
            interval = uiState.activeSkipInterval,
            dismissed = uiState.skipIntervalDismissed,
            controlsVisible = uiState.showControls,
            onSkip = { viewModel.onEvent(PlayerEvent.OnSkipIntro) },
            onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissSkipIntro) },
            focusRequester = skipIntroFocusRequester,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = if (uiState.showControls) 120.dp else 32.dp)
        )

        NextEpisodeCardOverlay(
            nextEpisode = uiState.nextEpisode,
            visible = uiState.showNextEpisodeCard &&
                uiState.error == null &&
                !uiState.showLoadingOverlay &&
                !uiState.showPauseOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioDialog &&
                !uiState.showSubtitleDialog &&
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
            !uiState.showAudioDialog &&
            !uiState.showSubtitleDialog &&
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
            PlayerClockOverlay(
                currentPosition = uiState.currentPosition,
                duration = uiState.duration
            )
        }

        // Controls overlay
        AnimatedVisibility(
            visible = uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showSubtitleStylePanel &&
                !uiState.showSubtitleDelayOverlay &&
                !uiState.showEpisodesPanel &&
                !uiState.showSourcesPanel &&
                !uiState.showAudioDialog &&
                !uiState.showSubtitleDialog &&
                !uiState.showSpeedDialog,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            val context = LocalContext.current
            PlayerControlsOverlay(
                uiState = uiState,
                playPauseFocusRequester = playPauseFocusRequester,
                onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
                onSeekForward = { viewModel.onEvent(PlayerEvent.OnSeekForward) },
                onSeekBackward = { viewModel.onEvent(PlayerEvent.OnSeekBackward) },
                onSeekTo = { viewModel.onEvent(PlayerEvent.OnSeekTo(it)) },
                onShowEpisodesPanel = { viewModel.onEvent(PlayerEvent.OnShowEpisodesPanel) },
                onShowSourcesPanel = { viewModel.onEvent(PlayerEvent.OnShowSourcesPanel) },
                onShowAudioDialog = { viewModel.onEvent(PlayerEvent.OnShowAudioDialog) },
                onShowSubtitleDialog = { viewModel.onEvent(PlayerEvent.OnShowSubtitleDialog) },
                onShowSpeedDialog = { viewModel.onEvent(PlayerEvent.OnShowSpeedDialog) },
                onToggleAspectRatio = {
                    Log.d("PlayerScreen", "onToggleAspectRatio called - dispatching event")
                    viewModel.onEvent(PlayerEvent.OnToggleAspectRatio)
                },
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
                    onBackPress()
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = title,
                        headers = headers
                    )
                },
                onResetHideTimer = { viewModel.scheduleHideControls() },
                onBack = onBackPress
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
                !uiState.showAudioDialog &&
                !uiState.showSubtitleDialog &&
                !uiState.showSpeedDialog,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .zIndex(2.3f)
        ) {
            SubtitleDelayOverlay(
                subtitleDelayMs = uiState.subtitleDelayMs
            )
        }

        AnimatedVisibility(
            visible = uiState.showSeekOverlay && !uiState.showControls && uiState.error == null &&
                !uiState.showLoadingOverlay && !uiState.showPauseOverlay &&
                !uiState.showSubtitleDelayOverlay && !uiState.showMoreDialog,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SeekOverlay(uiState = uiState)
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
        if (uiState.showAudioDialog) {
            AudioSelectionDialog(
                tracks = uiState.audioTracks,
                selectedIndex = uiState.selectedAudioTrackIndex,
                onTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(it)) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissDialog) }
            )
        }

        // Subtitle track dialog
        if (uiState.showSubtitleDialog) {
            SubtitleSelectionDialog(
                internalTracks = uiState.subtitleTracks,
                selectedInternalIndex = uiState.selectedSubtitleTrackIndex,
                addonSubtitles = uiState.addonSubtitles,
                selectedAddonSubtitle = uiState.selectedAddonSubtitle,
                preferredLanguage = uiState.subtitleStyle.preferredLanguage,
                subtitleOrganizationMode = uiState.subtitleOrganizationMode,
                isLoadingAddons = uiState.isLoadingAddonSubtitles,
                onInternalTrackSelected = { viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(it)) },
                onAddonSubtitleSelected = { viewModel.onEvent(PlayerEvent.OnSelectAddonSubtitle(it)) },
                onDisableSubtitles = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
                onOpenStylePanel = { viewModel.onEvent(PlayerEvent.OnOpenSubtitleStylePanel) },
                onOpenDelayOverlay = { viewModel.onEvent(PlayerEvent.OnShowSubtitleDelayOverlay) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissDialog) }
            )
        }

        // Speed dialog
        if (uiState.showSpeedDialog) {
            SpeedSelectionDialog(
                currentSpeed = uiState.playbackSpeed,
                onSpeedSelected = { viewModel.onEvent(PlayerEvent.OnSetPlaybackSpeed(it)) },
                onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissDialog) }
            )
        }

    }
}

@Composable
private fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    playPauseFocusRequester: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowEpisodesPanel: () -> Unit,
    onShowSourcesPanel: () -> Unit,
    onShowAudioDialog: () -> Unit,
    onShowSubtitleDialog: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleMoreActions: () -> Unit,
    onOpenInExternalPlayer: () -> Unit,
    onResetHideTimer: () -> Unit,
    onBack: () -> Unit
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
            val skipIntroVisible = uiState.activeSkipInterval != null && !uiState.skipIntervalDismissed

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
                        val episodeInfo = buildString {
                            append("S${uiState.currentSeason}E${uiState.currentEpisode}")
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
                                    text = stringResource(R.string.player_via, uiState.currentStreamName ?: ""),
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

            // Progress bar
            ProgressBar(
                currentPosition = uiState.currentPosition,
                duration = uiState.duration,
                onSeekTo = onSeekTo
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons row
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

                    ControlButton(
                        icon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        iconPainter = if (uiState.isPlaying) customPausePainter else customPlayPainter,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        onClick = onPlayPause,
                        focusRequester = playPauseFocusRequester,
                        onFocused = onResetHideTimer
                    )

                    if (hasSubtitleControl) {
                        ControlButton(
                            icon = Icons.Default.ClosedCaption,
                            iconPainter = customSubtitlePainter,
                            contentDescription = "Subtitles",
                            onClick = onShowSubtitleDialog,
                            onFocused = onResetHideTimer
                        )
                    }

                    if (hasAudioControl) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            iconPainter = customAudioPainter,
                            contentDescription = "Audio tracks",
                            onClick = onShowAudioDialog,
                            onFocused = onResetHideTimer
                        )
                    }

                    ControlButton(
                        icon = Icons.Default.SwapHoriz,
                        iconPainter = customSourcePainter,
                        contentDescription = "Sources",
                        onClick = onShowSourcesPanel,
                        onFocused = onResetHideTimer
                    )

                    if (hasEpisodeContext) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Filled.List,
                            iconPainter = customEpisodesPainter,
                            contentDescription = "Episodes",
                            onClick = onShowEpisodesPanel,
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
                                contentDescription = "Playback speed",
                                onClick = {
                                    onShowSpeedDialog()
                                },
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = Icons.Default.AspectRatio,
                                iconPainter = customAspectPainter,
                                contentDescription = "Aspect ratio",
                                onClick = {
                                    onToggleAspectRatio()
                                },
                                onFocused = onResetHideTimer
                            )
                            ControlButton(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in external player",
                                onClick = {
                                    onOpenInExternalPlayer()
                                },
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
                        contentDescription = if (uiState.showMoreDialog) "Close more actions" else "More actions",
                        onClick = onToggleMoreActions,
                        onFocused = onResetHideTimer
                    )
                }

                // Right side - Time display only
                Text(
                    text = "${formatTime(uiState.currentPosition)} / ${formatTime(uiState.duration)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
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
    onSeekTo: (Long) -> Unit
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "progress"
    )

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
}

@Composable
private fun SeekOverlay(uiState: PlayerUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        ProgressBar(
            currentPosition = uiState.currentPosition,
            duration = uiState.duration,
            onSeekTo = {}
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(uiState.currentPosition)} / ${formatTime(uiState.duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun PlayerClockOverlay(
    currentPosition: Long,
    duration: Long
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val delayMs = (1_000L - (current % 1_000L)).coerceAtLeast(250L)
            delay(delayMs)
        }
    }

    val remainingMs = (duration - currentPosition).coerceAtLeast(0L)
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
private fun SubtitleDelayOverlay(subtitleDelayMs: Int) {
    val fraction = ((subtitleDelayMs - SUBTITLE_DELAY_MIN_MS).toFloat() /
        (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xCC1F3246),
                        Color(0xCC283655),
                        Color(0xCC2F2B55)
                    )
                )
            )
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
                            .background(Color.White.copy(alpha = 0.22f))
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
                    .background(Color.White.copy(alpha = 0.95f))
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
                    contentDescription = "Selected",
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
