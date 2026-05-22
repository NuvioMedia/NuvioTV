package com.nuvio.tv.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nuvio.tv.ui.trailer.TrailerOverlayActivity

/**
 * WEB_VIEW mode trailer player.
 *
 * Direct inline WebView execution is supported for in-app / background Hero media
 * (isInline = true), styled with premium smooth fade-in transitions.
 *
 * Fullscreen playback (isInline = false) launches [TrailerOverlayActivity]
 * in a separate `:trailer` process to prevent PowerVR GPU driver crashes
 * from killing the main app process.
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
    isInline: Boolean = false,
    deferShowUntilPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoId = remember(trailerUrl) { extractVideoId(trailerUrl) }

    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnError by rememberUpdatedState(onError)

    // --- Inline WebView Mode ---
    if (isInline) {
        var hasRenderedFirstFrame by remember(videoId) { mutableStateOf(false) }
        val webViewAlphaState = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
            animationSpec = tween(durationMillis = 350),
            label = "webViewFirstFrameAlpha"
        )

        var isWebViewActive by remember { mutableStateOf(false) }
        var activeVideoId by remember { mutableStateOf<String?>(null) }
        var localWebView by remember { mutableStateOf<WebView?>(null) }

        LaunchedEffect(isPlaying, videoId) {
            if (isPlaying && !videoId.isNullOrBlank()) {
                activeVideoId = videoId
                isWebViewActive = true
                localWebView?.let { wv ->
                    val htmlContent = wv.context.assets.open("youtube_player.html").bufferedReader().use { it.readText() }
                    wv.loadDataWithBaseURL(
                        "https://www.youtube-nocookie.com",
                        htmlContent,
                        "text/html",
                        "utf-8",
                        null
                    )
                    CookieManager.getInstance().flush()
                }
            } else {
                localWebView?.let { wv ->
                    wv.evaluateJavascript(
                        "try{if(player){player.stopVideo();player.destroy();player=null;}}catch(e){}", null
                    )
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                }
                kotlinx.coroutines.delay(800)
                isWebViewActive = false
                activeVideoId = null
                hasRenderedFirstFrame = false
            }
        }

        if (isWebViewActive && !activeVideoId.isNullOrBlank()) {
            val mainHandler = remember { Handler(Looper.getMainLooper()) }

            // Handle updates to WebView state (play/pause)
            LaunchedEffect(isPaused, localWebView) {
                localWebView?.let { wv ->
                    wv.evaluateJavascript(if (isPaused) "pauseVideo();" else "playVideo();", null)
                }
            }

            // Handle mute/unmute
            LaunchedEffect(muted, localWebView) {
                localWebView?.let { wv ->
                    wv.evaluateJavascript(if (muted) "mute();" else "unMute();", null)
                }
            }

            // Handle remote seeking
            LaunchedEffect(seekRequestToken, seekDeltaMs, localWebView) {
                localWebView?.let { wv ->
                    if (seekRequestToken <= 0) return@LaunchedEffect
                    wv.evaluateJavascript(
                        "(function(){if(player&&typeof player.getCurrentTime==='function'){" +
                        "seekTo(player.getCurrentTime()+(${seekDeltaMs/1000f}));}})();", null
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    // Enable cookie persistence so YouTube guest cookies survive across sessions.
                    // Without this, every WebView launch appears as a fresh "bot" to YouTube.
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    // Pre-seed CONSENT cookie so YouTube doesn't show the consent/bot wall.
                    cookieManager.setCookie("https://www.youtube-nocookie.com", "CONSENT=YES+; Domain=.youtube.com; Path=/; Max-Age=31536000; SameSite=None; Secure")

                    WebView(ctx).apply {
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false

                        val jsBridge = object {
                            @JavascriptInterface
                            fun onReady() {
                                mainHandler.post {
                                    evaluateJavascript("initPlayer('$activeVideoId', true, $muted);", null)
                                }
                            }

                            @JavascriptInterface
                            fun onPlayerReady() {
                                mainHandler.post {
                                    if (!deferShowUntilPlaying) {
                                        hasRenderedFirstFrame = true
                                        currentOnFirstFrameRendered()
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onStateChange(state: Int) {
                                mainHandler.post {
                                    if (state == 0) { // ENDED
                                        currentOnEnded()
                                    } else if (state == 1) { // PLAYING
                                        if (deferShowUntilPlaying) {
                                            if (!hasRenderedFirstFrame) {
                                                hasRenderedFirstFrame = true
                                                currentOnFirstFrameRendered()
                                            }
                                        } else {
                                            hasRenderedFirstFrame = true
                                        }
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onProgress(currentTime: Float, duration: Float) {
                                mainHandler.post {
                                    currentOnProgressChanged(
                                        (currentTime * 1000).toLong(),
                                        (duration * 1000).toLong()
                                    )
                                }
                            }

                            @JavascriptInterface
                            fun onError(error: Int) {
                                mainHandler.post {
                                    currentOnError(error)
                                }
                            }
                        }

                        addJavascriptInterface(jsBridge, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                android.util.Log.e("WebViewTrailerPlayer", "WebView renderer process gone (didCrash=${detail?.didCrash()}). Recovering...")
                                return true
                            }
                        }

                        // Load initial HTML template upon creation
                        try {
                            val htmlContent = ctx.assets.open("youtube_player.html").bufferedReader().use { it.readText() }
                            loadDataWithBaseURL(
                                "https://www.youtube-nocookie.com",
                                htmlContent,
                                "text/html",
                                "utf-8",
                                null
                            )
                            cookieManager.flush()
                        } catch (e: Exception) {
                            android.util.Log.e("WebViewTrailerPlayer", "Error loading youtube_player.html", e)
                        }

                        localWebView = this
                    }
                },
                update = {},
                onRelease = { wv ->
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.removeAllViews()
                    try {
                        wv.destroy()
                    } catch (e: Exception) {
                        android.util.Log.e("WebViewTrailerPlayer", "Error destroying WebView", e)
                    }
                    if (localWebView === wv) {
                        localWebView = null
                    }
                },
                modifier = modifier
                    .graphicsLayer {
                        alpha = webViewAlphaState.value
                    }
            )
        }
        return
    }

    // --- Fullscreen Separate-Process Mode ---
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
