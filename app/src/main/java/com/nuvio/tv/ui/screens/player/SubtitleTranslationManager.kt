package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SubtitleTranslation"

internal class SubtitleTranslationManager(
    private val service: SubtitleTranslationService,
    internal var targetLanguage: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val MOCK_MODE = false
    }

    var isEnabled: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    /** Called after each batch with success=true/false and optional error message. */
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /** Called when the lookahead window advances: coveredUpToMs = wall-clock ms, totalCount = total cue count in file. */
    var onLookaheadAdvanced: ((coveredUpToMs: Long, totalCount: Int) -> Unit)? = null

    val translatedCount: Int get() = cache.size

    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var pendingCount = 0
    private var hideTranslatingJob: Job? = null

    private data class PendingItem(val text: String, val deferred: CompletableDeferred<String>)
    private val queue = Channel<PendingItem>(Channel.UNLIMITED)

    init {
        if (!MOCK_MODE) {
            scope.launch { processBatches() }
        }
    }

    private suspend fun processBatches() {
        val batch = mutableListOf<PendingItem>()
        var lastSentMs = 0L
        while (true) {
            val first = queue.receive()
            batch.add(first)

            // Collect more items for up to 1500ms
            val deadline = System.currentTimeMillis() + 1500L
            while (batch.size < 40 && System.currentTimeMillis() < deadline) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }

            // Rate limit: at most 1 request per 3s to stay well under Groq's 30 RPM
            val sinceLastMs = System.currentTimeMillis() - lastSentMs
            if (sinceLastMs < 3_000L && lastSentMs > 0) delay(3_000L - sinceLastMs)

            val texts = batch.map { it.text }
            lastSentMs = System.currentTimeMillis()
            val result = service.translateBatch(texts, targetLanguage)
            if (!result.success) {
                Log.w(TAG, "processBatches: FAILED: ${result.errorMessage}")
                // Back off on error (likely rate limit) before next attempt
                delay(5_000L)
            }
            onBatchResult?.invoke(result.success, result.errorMessage)

            batch.forEachIndexed { i, item ->
                val translated = result.lines.getOrElse(i) { item.text }
                cache[item.text] = translated
                item.deferred.complete(translated)
            }
            batch.clear()
        }
    }

    fun getCached(text: String): String? = cache[text]

    suspend fun translate(text: String): String {
        cache[text]?.let { return it }

        val deferred = CompletableDeferred<String>()
        if (pendingCount++ == 0) onTranslatingChanged?.invoke(true)
        queue.send(PendingItem(text, deferred))
        return try {
            deferred.await()
        } finally {
            if (--pendingCount == 0) {
                // Debounce: keep the badge visible briefly so it doesn't blink per-cue
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount == 0) onTranslatingChanged?.invoke(false)
                }
            }
        }
    }

    fun reset() {
        cache.clear()
        pendingCount = 0
        onTranslatingChanged?.invoke(false)
    }

    /**
     * Pre-translates a batch of upcoming cues and populates the cache.
     * Does NOT affect the UI translating indicator or error badge — runs silently in the background.
     */
    suspend fun preTranslateWindow(texts: List<String>) {
        val uncached = texts.filter { !cache.containsKey(it) }
        if (uncached.isEmpty()) return
        uncached.chunked(40).forEach { chunk ->
            val result = service.translateBatch(chunk, targetLanguage)
            if (result.success) {
                onBatchResult?.invoke(true, null)
                chunk.forEachIndexed { i, text ->
                    cache[text] = result.lines.getOrElse(i) { text }
                }
            } else {
                Log.w(TAG, "preTranslateWindow: chunk FAILED: ${result.errorMessage}")
                delay(5_000L)  // back off on error before next chunk
                return          // abort remaining chunks — periodic lookahead will retry
            }
        }
    }
}
