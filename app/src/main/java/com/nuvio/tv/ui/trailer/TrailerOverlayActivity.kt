package com.nuvio.tv.ui.trailer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.app.Activity

/**
 * Fullscreen Activity that hosts the YouTube IFrame player WebView.
 *
 * Runs in the `:trailer` process (declared in AndroidManifest.xml).
 * If Chrome's in-process GPU thread (Chrome_InProcGp) crashes due to
 * the PowerVR driver bug (PVRSRVFreeDeviceMemMIW SIGSEGV), only this
 * process dies — the main app process is completely unaffected.
 *
 * WebView is created asynchronously (posted to the main looper) to
 * ensure the window is drawn immediately and avoid ANR on slow SoCs.
 */
class TrailerOverlayActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_AUTO_PLAY = "auto_play"
        const val EXTRA_MUTED = "muted"

        // Broadcast actions sent TO the main process
        const val ACTION_TRAILER_EVENT = "com.nuvio.tv.TRAILER_EVENT"
        const val EXTRA_EVENT = "event"
        const val EXTRA_ERROR_CODE = "error_code"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"

        // Broadcast actions received FROM the main process
        const val ACTION_TRAILER_COMMAND = "com.nuvio.tv.TRAILER_COMMAND"
        const val EXTRA_COMMAND = "command"

        fun launch(context: Context, videoId: String, autoPlay: Boolean = true, muted: Boolean = false) {
            val intent = Intent(context, TrailerOverlayActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_AUTO_PLAY, autoPlay)
                putExtra(EXTRA_MUTED, muted)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun sendCommand(context: Context, command: String) {
            val intent = Intent(ACTION_TRAILER_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        fun dismiss(context: Context) {
            sendCommand(context, "stop")
        }
    }

    private var webView: WebView? = null
    private lateinit var container: FrameLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoId: String = ""
    private var autoPlay: Boolean = true
    private var muted: Boolean = false
    private var isDestroyed_ = false
    private var isFinishingGracefully = false
    private val exitRunnable = Runnable { finishAndRemoveTask() }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishingGracefully) return
            val wv = webView ?: return
            when (intent.getStringExtra(EXTRA_COMMAND)) {
                "play" -> wv.evaluateJavascript("playVideo();", null)
                "pause" -> wv.evaluateJavascript("pauseVideo();", null)
                "mute" -> wv.evaluateJavascript("mute();", null)
                "unmute" -> wv.evaluateJavascript("unMute();", null)
                "stop" -> prepareExitAndFinish()
            }
        }
    }

    private fun prepareExitAndFinish() {
        if (isFinishingGracefully) return
        isFinishingGracefully = true

        webView?.let { wv ->
            wv.evaluateJavascript(
                "try{if(player){player.stopVideo();player.destroy();player=null;}}catch(e){}", null
            )
            wv.stopLoading()
            wv.loadUrl("about:blank")
        }

        mainHandler.postDelayed(exitRunnable, 800)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: run {
            sendEvent("error", errorCode = 2)
            finish()
            return
        }
        autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
        muted = intent.getBooleanExtra(EXTRA_MUTED, false)

        // Show window IMMEDIATELY with a transparent container to prevent ANR and black flash.
        // WebView creation is deferred to the next frame.
        container = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)

        // Listen for commands from the main process
        val filter = IntentFilter(ACTION_TRAILER_COMMAND)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        // Create WebView on next frame — after the window is drawn.
        // This prevents ANR on slow SoCs (RTD6748).
        mainHandler.post { initWebView() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Cancel any pending graceful exit
        if (isFinishingGracefully) {
            isFinishingGracefully = false
            mainHandler.removeCallbacks(exitRunnable)
        }

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
        muted = intent.getBooleanExtra(EXTRA_MUTED, false)

        if (videoId.isNotBlank()) {
            webView?.let { wv ->
                val htmlContent = try {
                    assets.open("youtube_player.html").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    android.util.Log.e("TrailerOverlay", "Failed to load youtube_player.html", e)
                    sendEvent("error", errorCode = 2)
                    prepareExitAndFinish()
                    return
                }
                wv.alpha = 0f
                container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                wv.loadDataWithBaseURL(
                    "https://www.youtube-nocookie.com",
                    htmlContent,
                    "text/html",
                    "utf-8",
                    null
                )
            } ?: run {
                initWebView()
            }
        }
    }

    private fun initWebView() {
        if (isDestroyed_ || isFinishing) return

        val wv = createWebView()
        wv.alpha = 0f // hidden initially until first frame renders
        webView = wv
        container.addView(wv)

        // Load the YouTube player HTML
        val htmlContent = try {
            assets.open("youtube_player.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.e("TrailerOverlay", "Failed to load youtube_player.html", e)
            sendEvent("error", errorCode = 2)
            finish()
            return
        }

        wv.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            htmlContent,
            "text/html",
            "utf-8",
            null
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFinishingGracefully) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                sendEvent("ended")
                prepareExitAndFinish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                webView?.evaluateJavascript(
                    "(function(){if(player&&typeof player.getPlayerState==='function'){" +
                    "var s=player.getPlayerState();" +
                    "if(s===1)player.pauseVideo();else player.playVideo();" +
                    "}})()", null
                )
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                webView?.evaluateJavascript("pauseVideo();", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                webView?.evaluateJavascript(
                    "(function(){if(player&&typeof player.getCurrentTime==='function'){" +
                    "seekTo(player.getCurrentTime()-10);}})();", null
                )
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                webView?.evaluateJavascript(
                    "(function(){if(player&&typeof player.getCurrentTime==='function'){" +
                    "seekTo(player.getCurrentTime()+10);}})();", null
                )
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        isDestroyed_ = true
        mainHandler.removeCallbacks(exitRunnable)
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        webView?.let { wv ->
            if (!isFinishingGracefully) {
                wv.evaluateJavascript(
                    "try{if(player){player.stopVideo();player.destroy();player=null;}}catch(e){}", null
                )
                wv.stopLoading()
                wv.loadUrl("about:blank")
            }
            container.removeView(wv)
            wv.removeAllViews()
            try {
                wv.destroy()
            } catch (e: Exception) {
                android.util.Log.e("TrailerOverlay", "Error destroying WebView", e)
            }
        }
        webView = null
        super.onDestroy()
    }

    private fun sendEvent(event: String, errorCode: Int = 0, positionMs: Long = 0, durationMs: Long = 0) {
        val intent = Intent(ACTION_TRAILER_EVENT).apply {
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_ERROR_CODE, errorCode)
            putExtra(EXTRA_POSITION_MS, positionMs)
            putExtra(EXTRA_DURATION_MS, durationMs)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            addJavascriptInterface(createJsBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean = true

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    android.util.Log.e("TrailerOverlay", "WebView renderer process gone (didCrash=${detail?.didCrash()}). Recovering...")
                    return true
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        android.util.Log.d("TrailerOverlay_JS", "${it.message()} -- line ${it.lineNumber()}")
                    }
                    return true
                }
            }
        }
    }

    private fun showPlayerContent() {
        mainHandler.post {
            if (!isDestroyed_ && !isFinishing) {
                container.setBackgroundColor(android.graphics.Color.BLACK)
                webView?.animate()?.alpha(1f)?.setDuration(350)?.start()
            }
        }
    }

    private fun createJsBridge(): Any {
        return object {
            @JavascriptInterface
            fun onReady() {
                mainHandler.post {
                    webView?.evaluateJavascript(
                        "initPlayer('$videoId', $autoPlay, $muted);", null
                    )
                }
            }

            @JavascriptInterface
            fun onPlayerReady() {
                mainHandler.post {
                    showPlayerContent()
                    sendEvent("first_frame")
                }
            }

            @JavascriptInterface
            fun onStateChange(state: Int) {
                mainHandler.post {
                    if (state == 0) { // YT.PlayerState.ENDED
                        sendEvent("ended")
                        prepareExitAndFinish()
                    } else if (state == 1) { // YT.PlayerState.PLAYING
                        showPlayerContent()
                    }
                }
            }

            @JavascriptInterface
            fun onProgress(currentTime: Float, duration: Float) {
                mainHandler.post {
                    sendEvent(
                        "progress",
                        positionMs = (currentTime * 1000).toLong(),
                        durationMs = (duration * 1000).toLong()
                    )
                }
            }

            @JavascriptInterface
            fun onError(error: Int) {
                mainHandler.post {
                    android.util.Log.e("TrailerOverlay", "YouTube error: $error")
                    sendEvent("error", errorCode = error)
                    prepareExitAndFinish()
                }
            }
        }
    }
}
