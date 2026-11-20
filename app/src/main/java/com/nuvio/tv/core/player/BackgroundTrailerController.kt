package com.nuvio.tv.core.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class BackgroundTrailerRequest(
    val videoUrl: String,
    val audioUrl: String?,
    val isPlaying: Boolean = true,
    val isPaused: Boolean = false,
    val muted: Boolean = false,
    val cropToFill: Boolean = true,
    val autoCropLetterbox: Boolean = true,
    val seekRequestToken: Int = 0,
    val seekDeltaMs: Long = 0L,
    val bounds: Rect? = null,
    val overscanZoom: Float = 1f,
    val alpha: () -> Float = { 1f },
    val onEnded: () -> Unit = {},
    val onFirstFrameRendered: () -> Unit = {},
    val onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    val onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false }
) {
    fun hasSamePlayback(other: BackgroundTrailerRequest): Boolean =
        videoUrl == other.videoUrl &&
            audioUrl == other.audioUrl &&
            isPlaying == other.isPlaying &&
            isPaused == other.isPaused &&
            muted == other.muted &&
            cropToFill == other.cropToFill &&
            autoCropLetterbox == other.autoCropLetterbox &&
            seekRequestToken == other.seekRequestToken &&
            seekDeltaMs == other.seekDeltaMs &&
            bounds == other.bounds &&
            overscanZoom == other.overscanZoom
}

data class BackgroundTrailerTransfer(
    val itemId: String,
    val videoUrl: String,
    val audioUrl: String?,
    val youtubeKey: String?
)

@Singleton
class BackgroundTrailerController @Inject constructor() {
    private val requests = mutableStateMapOf<String, BackgroundTrailerRequest>()
    private val owners = mutableStateListOf<String>()
    private var renderedUrl by mutableStateOf<String?>(null)
    private var transfer: BackgroundTrailerTransfer? = null
    private var pinnedOwner: String? = null
    private var transferTimeoutJob: Job? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val activeRequest: BackgroundTrailerRequest?
        get() = owners.lastOrNull()?.let { requests[it] }

    fun update(owner: String, request: BackgroundTrailerRequest?) {
        if (owner == pinnedOwner) return
        if (request == null) {
            remove(owner)
            return
        }
        val current = requests[owner]
        if (current == null || !current.hasSamePlayback(request)) {
            requests[owner] = request
        }
        if (!owners.contains(owner)) {
            owners.add(owner)
            if (pinnedOwner != null) {
                transfer = null
                transferTimeoutJob?.cancel()
                transferTimeoutJob = null
            }
        }
    }

    fun release(owner: String) {
        if (owner == pinnedOwner) {
            pinnedOwner = null
            transfer = null
            transferTimeoutJob?.cancel()
            transferTimeoutJob = null
        }
        remove(owner)
    }

    fun isRendering(videoUrl: String?): Boolean =
        videoUrl != null && renderedUrl == videoUrl && activeRequest?.videoUrl == videoUrl

    fun isRenderingFor(owner: String?): Boolean {
        val mine = owner?.let { requests[it] } ?: return false
        val active = activeRequest ?: return false
        return mine.videoUrl == active.videoUrl && renderedUrl == active.videoUrl
    }

    fun markRendered(videoUrl: String) {
        renderedUrl = videoUrl
    }

    fun clearRendered() {
        renderedUrl = null
    }

    fun beginTransfer(itemId: String, videoUrl: String, audioUrl: String?, youtubeKey: String?): Boolean {
        val owner = owners.lastOrNull() ?: return false
        val active = requests[owner] ?: return false
        if (active.videoUrl != videoUrl || !active.isPlaying) return false
        transfer = BackgroundTrailerTransfer(itemId, videoUrl, audioUrl, youtubeKey)
        val frozenAlpha = active.alpha()
        requests[owner] = BackgroundTrailerRequest(
            videoUrl = active.videoUrl,
            audioUrl = active.audioUrl,
            isPlaying = true,
            isPaused = false,
            muted = active.muted,
            cropToFill = active.cropToFill,
            autoCropLetterbox = active.autoCropLetterbox,
            bounds = active.bounds,
            overscanZoom = active.overscanZoom,
            alpha = { frozenAlpha },
            onEnded = active.onEnded,
            onFirstFrameRendered = active.onFirstFrameRendered
        )
        pinnedOwner = owner
        transferTimeoutJob?.cancel()
        transferTimeoutJob = mainScope.launch {
            delay(TRANSFER_TIMEOUT_MS)
            if (transfer != null) cancelPinnedTransfer()
        }
        return true
    }

    fun pendingTransfer(itemId: String): BackgroundTrailerTransfer? = transfer?.takeIf { it.itemId == itemId }

    fun cancelTransfer(itemId: String) {
        if (transfer?.itemId == itemId) cancelPinnedTransfer()
    }

    private fun cancelPinnedTransfer() {
        val owner = pinnedOwner
        pinnedOwner = null
        transfer = null
        transferTimeoutJob?.cancel()
        transferTimeoutJob = null
        owner?.let(::remove)
    }

    private fun remove(owner: String) {
        requests.remove(owner)
        owners.remove(owner)
    }

    private companion object {
        const val TRANSFER_TIMEOUT_MS = 5_000L
    }
}

val LocalBackgroundTrailerController = staticCompositionLocalOf<BackgroundTrailerController?> { null }
