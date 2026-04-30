package com.nuvio.tv.core.plugin.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.LocalScraperResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class RemotePluginExecutionException(message: String) : RuntimeException(message)

@Singleton
class RemotePluginRuntimeClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()

    private val pendingRequests =
        ConcurrentHashMap<String, CompletableDeferred<List<LocalScraperResult>>>()

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var isBound: Boolean = false

    private val bindMutex = Mutex()

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        val data = msg.data
        val requestId = data?.getString(PluginRuntimeIpc.KEY_REQUEST_ID)
        if (requestId == null) {
            true
        } else {
            val deferred = pendingRequests.remove(requestId)
            if (deferred != null) {
                when (msg.what) {
                    PluginRuntimeIpc.MSG_RESULT -> {
                        val json = data.getString(PluginRuntimeIpc.KEY_RESULTS_JSON) ?: "[]"
                        deferred.complete(parseResults(json))
                    }
                    PluginRuntimeIpc.MSG_ERROR -> {
                        val errorMsg = data.getString(PluginRuntimeIpc.KEY_ERROR_MESSAGE)
                            ?: "Unknown plugin error"
                        deferred.completeExceptionally(RemotePluginExecutionException(errorMsg))
                    }
                    else -> {
                        deferred.completeExceptionally(
                            IllegalStateException("Unknown message what=${msg.what}")
                        )
                    }
                }
            }
            true
        }
    }

    private val replyMessenger = Messenger(incomingHandler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Plugin service connected")
            serviceMessenger = service?.let { Messenger(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Plugin service disconnected (pending=${pendingRequests.size})")
            serviceMessenger = null
            // Bypass Android's exponential restart backoff (seen at ~200s after
            // an lmkd kill). Drop the binding so the next ensureBound() spins
            // up a fresh :plugin process on demand instead of waiting.
            isBound = false
            try { context.unbindService(this) } catch (_: Throwable) {}
            failAllPending("Plugin runtime process died")
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Log.w(TAG, "Plugin service binding died")
            serviceMessenger = null
            isBound = false
            try { context.unbindService(this) } catch (_: Throwable) {}
            failAllPending("Plugin runtime binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            super.onNullBinding(name)
            Log.w(TAG, "Plugin service returned null binding")
            serviceMessenger = null
        }
    }

    private fun failAllPending(reason: String) {
        val pending = pendingRequests.toMap()
        pendingRequests.clear()
        pending.values.forEach {
            it.completeExceptionally(RemotePluginExecutionException(reason))
        }
    }

    private suspend fun ensureBound(): Messenger {
        serviceMessenger?.let { return it }
        bindMutex.withLock {
            serviceMessenger?.let { return it }
            if (!isBound) {
                val intent = Intent(context, PluginRuntimeService::class.java)
                val bound = try {
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: Throwable) {
                    throw RemotePluginExecutionException("bindService threw: ${e.message}")
                }
                if (!bound) {
                    try { context.unbindService(connection) } catch (_: Throwable) {}
                    throw RemotePluginExecutionException("Failed to bind PluginRuntimeService")
                }
                isBound = true
            }
            val ready = withTimeoutOrNull(BIND_TIMEOUT_MS) {
                while (serviceMessenger == null) {
                    delay(20)
                }
                serviceMessenger
            }
            return ready ?: throw RemotePluginExecutionException(
                "Bind timeout (${BIND_TIMEOUT_MS}ms) to PluginRuntimeService"
            )
        }
    }

    suspend fun executePlugin(
        codePath: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap()
    ): List<LocalScraperResult> {
        val target = ensureBound()
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<List<LocalScraperResult>>()
        pendingRequests[requestId] = deferred

        val data = Bundle().apply {
            putString(PluginRuntimeIpc.KEY_REQUEST_ID, requestId)
            putString(PluginRuntimeIpc.KEY_CODE_PATH, codePath)
            putString(PluginRuntimeIpc.KEY_TMDB_ID, tmdbId)
            putString(PluginRuntimeIpc.KEY_MEDIA_TYPE, mediaType)
            if (season != null) putInt(PluginRuntimeIpc.KEY_SEASON, season)
            if (episode != null) putInt(PluginRuntimeIpc.KEY_EPISODE, episode)
            putString(PluginRuntimeIpc.KEY_SCRAPER_ID, scraperId)
            putString(PluginRuntimeIpc.KEY_SETTINGS_JSON, gson.toJson(scraperSettings))
        }
        val msg = Message.obtain(null, PluginRuntimeIpc.MSG_EXECUTE).apply {
            this.data = data
            this.replyTo = replyMessenger
        }

        try {
            target.send(msg)
        } catch (re: RemoteException) {
            pendingRequests.remove(requestId)
            throw RemotePluginExecutionException("send failed: ${re.message}")
        }

        return try {
            deferred.await()
        } catch (t: Throwable) {
            pendingRequests.remove(requestId)
            sendCancel(requestId)
            throw t
        }
    }

    /**
     * Tear down the binding if no scraper is currently in flight. Called from
     * PluginManager when a scraper batch completes so the :plugin process can
     * exit and release its native arenas / OkHttp pools back to the OS.
     * No-op while requests are still pending (concurrent batches are safe).
     */
    suspend fun releaseIfIdle() {
        bindMutex.withLock {
            if (pendingRequests.isNotEmpty()) return
            if (!isBound && serviceMessenger == null) return
            Log.d(TAG, "Releasing plugin runtime (idle)")
            serviceMessenger = null
            isBound = false
            try { context.unbindService(connection) } catch (_: Throwable) {}
        }
    }

    private fun sendCancel(requestId: String) {
        val target = serviceMessenger ?: return
        try {
            val data = Bundle().apply { putString(PluginRuntimeIpc.KEY_REQUEST_ID, requestId) }
            val msg = Message.obtain(null, PluginRuntimeIpc.MSG_CANCEL).apply { this.data = data }
            target.send(msg)
        } catch (_: Throwable) { }
    }

    private fun parseResults(json: String): List<LocalScraperResult> {
        return try {
            val type = object : TypeToken<List<LocalScraperResult>>() {}.type
            gson.fromJson<List<LocalScraperResult>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote results: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "RemotePluginRuntime"
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}
