package com.nuvio.tv.ui.screens.player.audiosync.asr

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One loaded speech recogniser shared by every player, kept for a while after the last one closes
 * so the next episode or film has it at once instead of loading the model again.
 */
internal object SharedRecognizer {
    /** Covers moving to the next episode or picking another film. */
    private const val KEEP_AFTER_LAST_USE_MS = 3 * 60_000L

    private val closer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NuvioAsrRelease").apply { isDaemon = true }
    }
    private var instance: SherpaSpeechToText? = null
    private var users = 0
    private var pendingClose: ScheduledFuture<*>? = null

    /** True when a recogniser is loaded and can be acquired without waiting. */
    @get:Synchronized
    val isLoaded: Boolean
        get() = instance != null

    /** Returns the loaded recogniser, loading it first when needed (blocking: call off the main thread). */
    @Synchronized
    fun acquire(modelDir: File, threads: Int): SherpaSpeechToText {
        pendingClose?.cancel(false)
        pendingClose = null
        val recognizer = instance ?: SherpaSpeechToText(modelDir, threads).also { instance = it }
        users++
        return recognizer
    }

    /** Gives back an acquired recogniser; it is closed once nobody has used it for a while. */
    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || instance == null) return
        pendingClose?.cancel(false)
        pendingClose = closer.schedule({ closeIfUnused() }, KEEP_AFTER_LAST_USE_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun closeIfUnused() {
        if (users > 0) return
        instance?.let { runCatching { it.close() } }
        instance = null
        pendingClose = null
    }
}
