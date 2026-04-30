package com.nuvio.tv.core.plugin.ipc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.domain.model.LocalScraperResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PluginRuntimeService : Service() {

    private val gson: Gson = Gson()

    private val runtime: PluginRuntime by lazy { PluginRuntime() }

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(MAX_CONCURRENT_SCRAPERS) { runnable ->
            Thread(runnable, "plugin-svc-worker").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pluginDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + pluginDispatcher)

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val incomingMessenger: Messenger by lazy { Messenger(IncomingHandler()) }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onDestroy() {
        super.onDestroy()
        try { scope.cancel() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            val data = msg.data ?: return
            when (msg.what) {
                PluginRuntimeIpc.MSG_EXECUTE -> handleExecute(data, replyTo)
                PluginRuntimeIpc.MSG_CANCEL -> handleCancel(data)
                else -> Log.w(TAG, "Unknown message what=${msg.what}")
            }
        }
    }

    private fun handleExecute(data: Bundle, replyTo: Messenger?) {
        val requestId = data.getString(PluginRuntimeIpc.KEY_REQUEST_ID) ?: return
        val codePath = data.getString(PluginRuntimeIpc.KEY_CODE_PATH).orEmpty()
        val tmdbId = data.getString(PluginRuntimeIpc.KEY_TMDB_ID).orEmpty()
        val mediaType = data.getString(PluginRuntimeIpc.KEY_MEDIA_TYPE).orEmpty()
        val season = if (data.containsKey(PluginRuntimeIpc.KEY_SEASON))
            data.getInt(PluginRuntimeIpc.KEY_SEASON) else null
        val episode = if (data.containsKey(PluginRuntimeIpc.KEY_EPISODE))
            data.getInt(PluginRuntimeIpc.KEY_EPISODE) else null
        val scraperId = data.getString(PluginRuntimeIpc.KEY_SCRAPER_ID).orEmpty()
        val settingsJson = data.getString(PluginRuntimeIpc.KEY_SETTINGS_JSON) ?: "{}"

        val job = scope.launch {
            try {
                val codeFile = File(codePath)
                if (!codeFile.exists() || codeFile.length() == 0L) {
                    sendError(replyTo, requestId, "Scraper code file missing: $codePath")
                    return@launch
                }
                val code = try {
                    codeFile.readText()
                } catch (e: Exception) {
                    sendError(replyTo, requestId, "Failed to read scraper code: ${e.message}")
                    return@launch
                }

                val settings: Map<String, Any> = try {
                    @Suppress("UNCHECKED_CAST")
                    (gson.fromJson(settingsJson, Map::class.java) as? Map<String, Any>) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

                val results = runtime.executePlugin(
                    code = code,
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    season = season,
                    episode = episode,
                    scraperId = scraperId,
                    scraperSettings = settings
                )
                sendResult(replyTo, requestId, results)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "executePlugin failed for $scraperId: ${t.message}", t)
                sendError(replyTo, requestId, t.message ?: t.javaClass.simpleName)
            } finally {
                activeJobs.remove(requestId)
            }
        }
        activeJobs[requestId] = job
    }

    private fun handleCancel(data: Bundle) {
        val requestId = data.getString(PluginRuntimeIpc.KEY_REQUEST_ID) ?: return
        activeJobs.remove(requestId)?.cancel()
    }

    private fun sendResult(replyTo: Messenger?, requestId: String, results: List<LocalScraperResult>) {
        replyTo ?: return
        val json = try { gson.toJson(results) } catch (_: Exception) { "[]" }
        val out = Bundle().apply {
            putString(PluginRuntimeIpc.KEY_REQUEST_ID, requestId)
            putString(PluginRuntimeIpc.KEY_RESULTS_JSON, json)
        }
        val reply = Message.obtain(null, PluginRuntimeIpc.MSG_RESULT).apply { this.data = out }
        try { replyTo.send(reply) } catch (_: RemoteException) { }
    }

    private fun sendError(replyTo: Messenger?, requestId: String, message: String) {
        replyTo ?: return
        val out = Bundle().apply {
            putString(PluginRuntimeIpc.KEY_REQUEST_ID, requestId)
            putString(PluginRuntimeIpc.KEY_ERROR_MESSAGE, message)
        }
        val reply = Message.obtain(null, PluginRuntimeIpc.MSG_ERROR).apply { this.data = out }
        try { replyTo.send(reply) } catch (_: RemoteException) { }
    }

    companion object {
        private const val TAG = "PluginRuntimeService"
        private const val MAX_CONCURRENT_SCRAPERS = 10
    }
}
