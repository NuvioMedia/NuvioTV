package com.omnio.phone.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.omnio.tv.core.player.PlayerEvent
import com.omnio.tv.core.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PhonePlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var controlsVisible by remember { mutableStateOf(true) }
    var seekPreviewDeltaMs by remember { mutableStateOf<Long?>(null) }
    var seekPreviewTargetMs by remember { mutableStateOf<Long?>(null) }
    var brightnessLevel by remember { mutableStateOf<Float?>(null) }
    var volumeLevel by remember { mutableStateOf<Float?>(null) }
    var inPictureInPicture by remember { mutableStateOf(false) }
    var backgroundPlaybackEnabled by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }

    // Auto-hide controls after a few seconds of no interaction.
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(4000)
            controlsVisible = false
        }
    }

    // Clear transient HUDs.
    LaunchedEffect(seekPreviewDeltaMs) {
        if (seekPreviewDeltaMs != null) {
            delay(900)
            seekPreviewDeltaMs = null
            seekPreviewTargetMs = null
        }
    }
    LaunchedEffect(brightnessLevel) {
        if (brightnessLevel != null) {
            delay(900)
            brightnessLevel = null
        }
    }
    LaunchedEffect(volumeLevel) {
        if (volumeLevel != null) {
            delay(900)
            volumeLevel = null
        }
    }

    // Lock to landscape while the player is mounted.
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    // Keep the screen on while playing.
    DisposableEffect(activity, uiState.isPlaying) {
        if (uiState.isPlaying) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Attach activity + start playback when the screen appears.
    LaunchedEffect(activity) {
        viewModel.attachHostActivity(activity)
        viewModel.startInitialPlaybackIfNeeded()
    }
    DisposableEffect(activity) {
        onDispose { viewModel.attachHostActivity(null) }
    }

    // Lifecycle: pause on backgrounding (unless background-playback opted in).
    DisposableEffect(lifecycleOwner, backgroundPlaybackEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!backgroundPlaybackEnabled && !inPictureInPicture) {
                        viewModel.pauseForLifecycle()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // PiP wiring: register on enter, unregister on exit.
    DisposableEffect(activity) {
        val handler: (Activity) -> Unit = { act ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && uiState.isPlaying) {
                runCatching { act.enterPictureInPictureMode(buildPipParams(uiState.currentPosition, uiState.duration)) }
            }
        }
        PhonePlayerPipController.register(handler)
        onDispose { PhonePlayerPipController.unregister(handler) }
    }

    // Detect PiP mode changes via the activity's configuration callbacks.
    DisposableEffect(activity) {
        val componentActivity = activity as? androidx.activity.ComponentActivity
        val listener = androidx.core.util.Consumer<android.content.res.Configuration> {
            inPictureInPicture = componentActivity?.isInPictureInPictureMode == true
            if (inPictureInPicture) {
                controlsVisible = false
                sheet = null
            }
        }
        componentActivity?.addOnConfigurationChangedListener(listener)
        onDispose { componentActivity?.removeOnConfigurationChangedListener(listener) }
    }

    // MediaSession lifecycle: bind to current ExoPlayer.
    val exoPlayer = viewModel.exoPlayer
    DisposableEffect(exoPlayer, activity) {
        val player = exoPlayer
        val session = if (player != null && activity != null) {
            MediaSession.Builder(activity, player).build()
        } else null
        onDispose { session?.release() }
    }

    // Back press: dismiss sheet → close.
    BackHandler(enabled = sheet != null) { sheet = null }
    BackHandler(enabled = sheet == null) {
        viewModel.stopAndRelease()
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video surface.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = uiState.resizeMode
            }
        )

        // Gesture layer (below overlay so taps reach overlay first when visible).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .phonePlayerGestures(
                    enabled = !inPictureInPicture && sheet == null,
                    onSingleTap = { controlsVisible = !controlsVisible },
                    onSeekDelta = { deltaPx, totalWidthPx ->
                        val durationMs = uiState.duration.coerceAtLeast(1L)
                        // 60% of screen width = full duration sweep, capped at +/-90s for UX.
                        val rawDeltaMs = (deltaPx / totalWidthPx) * durationMs
                        val clampedMs = rawDeltaMs.toLong().coerceIn(-180_000, 180_000)
                        seekPreviewDeltaMs = clampedMs
                        seekPreviewTargetMs = (uiState.currentPosition + clampedMs)
                            .coerceIn(0, durationMs)
                    },
                    onSeekCommit = {
                        val target = seekPreviewTargetMs ?: return@phonePlayerGestures
                        viewModel.onEvent(PlayerEvent.OnSeekTo(target))
                        controlsVisible = true
                    },
                    onShowBrightness = { level -> brightnessLevel = level },
                    onShowVolume = { level -> volumeLevel = level },
                    onPinch = { viewModel.onEvent(PlayerEvent.OnToggleAspectRatio) }
                )
        )

        // Overlay (top bar + center controls + bottom controls).
        PhonePlayerOverlay(
            uiState = uiState,
            visible = controlsVisible && !inPictureInPicture,
            onBack = {
                viewModel.stopAndRelease()
                onBack()
            },
            onPlayPause = { viewModel.onEvent(PlayerEvent.OnPlayPause) },
            onSeekBackward = { viewModel.onEvent(PlayerEvent.OnSeekBackward) },
            onSeekForward = { viewModel.onEvent(PlayerEvent.OnSeekForward) },
            onSeekTo = { pos -> viewModel.onEvent(PlayerEvent.OnSeekTo(pos)) },
            onShowAudioSheet = { sheet = PlayerSheet.Audio },
            onShowSubtitleSheet = { sheet = PlayerSheet.Subtitle },
            onShowSourcesSheet = {
                viewModel.onEvent(PlayerEvent.OnShowSourcesPanel)
                sheet = PlayerSheet.Sources
            },
            onToggleAspect = { viewModel.onEvent(PlayerEvent.OnToggleAspectRatio) },
            onTogglePip = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                    runCatching {
                        activity.enterPictureInPictureMode(
                            buildPipParams(uiState.currentPosition, uiState.duration)
                        )
                    }
                }
            }
        )

        // Transient HUDs.
        PhonePlayerSeekHud(
            visibleSeekDeltaMs = seekPreviewDeltaMs,
            targetPositionMs = seekPreviewTargetMs,
            durationMs = uiState.duration
        )
        PhonePlayerLevelHud(
            level01 = brightnessLevel,
            icon = IconBrightness,
            label = "Brightness"
        )
        PhonePlayerLevelHud(
            level01 = volumeLevel,
            icon = IconVolume,
            label = "Volume"
        )

        SnackbarHost(snackbarHostState)
    }

    // Bottom sheets.
    when (sheet) {
        PlayerSheet.Audio -> PhonePlayerAudioSheet(
            uiState = uiState,
            onSelect = { idx -> viewModel.onEvent(PlayerEvent.OnSelectAudioTrack(idx)) },
            onDismiss = { sheet = null }
        )
        PlayerSheet.Subtitle -> PhonePlayerSubtitleSheet(
            uiState = uiState,
            onSelect = { idx -> viewModel.onEvent(PlayerEvent.OnSelectSubtitleTrack(idx)) },
            onDisable = { viewModel.onEvent(PlayerEvent.OnDisableSubtitles) },
            onDismiss = { sheet = null }
        )
        PlayerSheet.Sources -> PhonePlayerSourcesSheet(
            uiState = uiState,
            onSelectStream = { stream -> viewModel.onEvent(PlayerEvent.OnSourceStreamSelected(stream)) },
            onDismiss = {
                viewModel.onEvent(PlayerEvent.OnDismissSourcesPanel)
                sheet = null
            }
        )
        PlayerSheet.Settings -> PhonePlayerSettingsSheet(
            backgroundPlaybackEnabled = backgroundPlaybackEnabled,
            onBackgroundPlaybackChange = { enabled ->
                backgroundPlaybackEnabled = enabled
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        if (enabled) "Background playback enabled for this session." else "Background playback disabled."
                    )
                }
            },
            onDismiss = { sheet = null }
        )
        null -> {}
    }
}

private enum class PlayerSheet { Audio, Subtitle, Sources, Settings }

@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private fun buildPipParams(positionMs: Long, durationMs: Long): PictureInPictureParams {
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
}
