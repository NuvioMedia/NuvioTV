package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    internal var targetLanguage: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val MOCK_MODE = false
        // Groq free tier: 30 RPM. Subtitles change every 2-4s naturally (~15-20 RPM).
        // No artificial rate limit needed — just don't fire concurrent requests.
        // If we get a 429, we back off 5s.
        private const val BATCH_WINDOW_MS = 150L  // wait up to 150ms for more items to batch
    }

    var isEnabled: Boolean = false
    var removeHearingImpaired: Boolean = true
    @Volatile var removeSubtitleHearingImpaired: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /**
     * Fired when translation is active but the cues carry no extractable text — i.e. the chosen
     * AI source is an image track (PGS/VOBSUB). Metadata alone can't be trusted to catch this
     * (containers report null/generic MIME), so this is the runtime backstop that stops AI from
     * silently rendering the untranslated source language.
     */
    var onUntranslatableSource: (() -> Unit)? = null

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()
    // Tracks texts currently queued but not yet translated, to avoid double-queuing the same text
    // from both the real-time path and preTranslateWindow racing on the same cue.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingCount = AtomicInteger(0)
    private var hideTranslatingJob: Job? = null

    private data class PendingItem(val text: String, val deferred: CompletableDeferred<String>)
    private val queue = Channel<PendingItem>(Channel.UNLIMITED)

    init {
        if (!MOCK_MODE) {
            scope.launch { processBatches() }
        }
    }

    fun updateService(apiKey: String, model: SubtitleAiModel) {
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model }
        )
    }

    private suspend fun processBatches() {
        val batch = mutableListOf<PendingItem>()
        while (true) {
            // Block until the first item is available
            val first = queue.receive()
            batch.add(first)

            // Collect any additional items that arrive within BATCH_WINDOW_MS.
            // This handles burst cache misses (e.g., right after a seek) efficiently.
            val deadline = System.currentTimeMillis() + BATCH_WINDOW_MS
            while (batch.size < 40) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                val next = withTimeoutOrNull(remaining) { queue.receive() } ?: break
                batch.add(next)
            }

            val texts = batch.map { it.text }
            val result = try {
                service.translateBatch(texts, targetLanguage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult(
                    lines = emptyList(),
                    success = false,
                    errorMessage = e.message ?: "Translation error"
                )
            }
            if (!result.success) {
                onBatchResult?.invoke(false, result.errorMessage)
                // On error: complete deferreds with original text so the caller doesn't hang,
                // but do NOT cache — the next render will retry rather than permanently show English.
                batch.forEachIndexed { i, item ->
                    inFlight.remove(item.text)
                    item.deferred.complete(item.text)
                }
                batch.clear()
                delay(5_000L)
                continue
            }
            onBatchResult?.invoke(true, null)
            batch.forEachIndexed { i, item ->
                val translated = result.lines.getOrElse(i) { item.text }
                cache[item.text] = translated
                inFlight.remove(item.text)
                item.deferred.complete(translated)
            }
            batch.clear()
        }
    }

    fun getCached(text: String): String? = cache[text]

    fun isInFlight(text: String): Boolean = inFlight.containsKey(text)

    suspend fun translate(text: String): String {
        cache[text]?.let { return it }
        // If the same text is already queued (e.g. preTranslateWindow raced us), join it.
        inFlight[text]?.let { return it.await() }

        val deferred = CompletableDeferred<String>()
        inFlight[text] = deferred
        val depth = pendingCount.getAndIncrement()
        if (depth == 0) {
            isTranslating = true
            onTranslatingChanged?.invoke(true)
        }
        queue.send(PendingItem(text, deferred))
        return try {
            deferred.await()
        } finally {
            if (pendingCount.decrementAndGet() == 0) {
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount.get() == 0) {
                        isTranslating = false
                        onTranslatingChanged?.invoke(false)
                    }
                }
            }
        }
    }

    fun reset() {
        cache.clear()
        inFlight.clear()
        if (pendingCount.get() == 0) {
            isTranslating = false
            onTranslatingChanged?.invoke(false)
        }
    }

    suspend fun preTranslateWindow(texts: List<String>) {
        // The lookahead triggers fire whenever a text track renders cues — including tracks
        // selected under the hood by "find best match" with AI translation off. Never spend
        // API requests unless translation is actually active.
        if (!isEnabled) return
        val uncached = texts.filter { !cache.containsKey(it) && !inFlight.containsKey(it) }
        if (uncached.isEmpty()) return
        uncached.chunked(40).forEach { chunk ->
            val result = service.translateBatch(chunk, targetLanguage)
            if (result.success) {
                onBatchResult?.invoke(true, null)
                chunk.forEachIndexed { i, text ->
                    cache[text] = result.lines.getOrElse(i) { text }
                }
            } else {
                onBatchResult?.invoke(false, result.errorMessage)
                delay(5_000L)
                return
            }
        }
    }
}
