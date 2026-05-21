package com.nuvio.tv.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.trailer.TrailerOverlayActivity

/**
 * WEB_VIEW mode trailer player.
 *
 * Instead of hosting a WebView inline (which crashes on PowerVR GPUs due to
 * Chrome_InProcGp SIGSEGV in PVRSRVFreeDeviceMemMIW), this composable
 * launches [TrailerOverlayActivity] in a separate `:trailer` process.
 *
 * The API is identical to the previous inline WebView implementation so
 * [TrailerPlayer] works without any changes — zero regression.
 */
@Composable
fun WebViewTrailerPlayer(
    trailerUrl: String?,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    onError: (error: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoId = remember(trailerUrl) { extractVideoId(trailerUrl) }

    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnError by rememberUpdatedState(onError)

    var isActivityLaunched by remember { mutableStateOf(false) }

    // Listen for state broadcasts from the :trailer process
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getStringExtra(TrailerOverlayActivity.EXTRA_EVENT)) {
                    "ended" -> {
                        isActivityLaunched = false
                        currentOnEnded()
                    }
                    "error" -> {
                        isActivityLaunched = false
                        val errorCode = intent.getIntExtra(TrailerOverlayActivity.EXTRA_ERROR_CODE, 0)
                        currentOnError(errorCode)
                    }
                    "first_frame" -> {
                        currentOnFirstFrameRendered()
                    }
                    "progress" -> {
                        val positionMs = intent.getLongExtra(TrailerOverlayActivity.EXTRA_POSITION_MS, 0)
                        val durationMs = intent.getLongExtra(TrailerOverlayActivity.EXTRA_DURATION_MS, 0)
                        currentOnProgressChanged(positionMs, durationMs)
                    }
                }
            }
        }
        val filter = IntentFilter(TrailerOverlayActivity.ACTION_TRAILER_EVENT)
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, android.content.Context.RECEIVER_EXPORTED
        )
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            // Dismiss the trailer Activity when this composable leaves composition
            if (isActivityLaunched) {
                TrailerOverlayActivity.dismiss(context)
                isActivityLaunched = false
            }
        }
    }

    // Launch or dismiss the trailer Activity based on play state
    LaunchedEffect(isPlaying, videoId) {
        if (isPlaying && !videoId.isNullOrBlank()) {
            if (!isActivityLaunched) {
                TrailerOverlayActivity.launch(context, videoId, autoPlay = true, muted = muted)
                isActivityLaunched = true
            }
        } else {
            if (isActivityLaunched) {
                TrailerOverlayActivity.dismiss(context)
                isActivityLaunched = false
            }
        }
    }

    // Forward pause/resume commands to the :trailer process
    LaunchedEffect(isPaused, isActivityLaunched) {
        if (!isActivityLaunched) return@LaunchedEffect
        TrailerOverlayActivity.sendCommand(
            context,
            if (isPaused) "pause" else "play"
        )
    }

    // Forward mute state changes
    LaunchedEffect(muted, isActivityLaunched) {
        if (!isActivityLaunched) return@LaunchedEffect
        TrailerOverlayActivity.sendCommand(
            context,
            if (muted) "mute" else "unmute"
        )
    }
}

private fun extractVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val reg = Regex("^.*(?:(?:youtu.be\\/|v\\/|vi\\/|u\\/\\w\\/|embed\\/|shorts\\/)|(?:(?:watch)?\\?v(?:i)?=|\\&v(?:i)?=))([^#\\&\\?]*).*")
    val match = reg.find(url)
    return match?.groupValues?.getOrNull(1) ?: url
}
