package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pre-downloads and caches addon subtitle files to local storage so that
 * ExoPlayer media-source rebuilds can reference a local URI instead of a
 * remote URL. This eliminates network latency when switching subtitle
 * languages.
 *
 * Cache location: `context.cacheDir/subtitles/`
 * Max cache size: ~50 MB (enough for ~250+ subtitle files)
 */
class SubtitleCache(context: Context) {

    companion object {
        private const val TAG = "SubtitleCache"
        private const val CACHE_DIR_NAME = "subtitles"
        private const val MAX_CACHE_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB
        private const val DOWNLOAD_TIMEOUT_SECONDS = 10L
    }

    private val cacheDir: File = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var prefetchJob: Job? = null

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns a local cached [File] for the given subtitle.
     * If the file is not yet cached, downloads it first (suspending).
     * Returns `null` only if download fails.
     */
    suspend fun getOrDownload(subtitle: Subtitle): File? = withContext(Dispatchers.IO) {
        val cached = getCachedFile(subtitle)
        if (cached != null) {
            Log.d(TAG, "Cache HIT for ${subtitle.lang} id=${subtitle.id}")
            return@withContext cached
        }
        Log.d(TAG, "Cache MISS for ${subtitle.lang} id=${subtitle.id}, downloading…")
        download(subtitle)
    }

    /**
     * Returns the cached file if it already exists, `null` otherwise.
     * Does NOT trigger a download.
     */
    fun getCachedFile(subtitle: Subtitle): File? {
        val file = fileForSubtitle(subtitle)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Pre-fetches ALL subtitles in the background so that switching to any
     * language is instant.  Preferred-language subtitles are downloaded first.
     * Cancels any previous prefetch job.
     */
    fun prefetchAll(
        subtitles: List<Subtitle>,
        preferredLanguage: String?,
        scope: CoroutineScope
    ) {
        prefetchJob?.cancel()
        if (subtitles.isEmpty()) return

        // Sort: preferred language first, then rest
        val sorted = if (!preferredLanguage.isNullOrBlank() && preferredLanguage != "none") {
            val normalizedPref = PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)
            val (preferred, rest) = subtitles.partition { sub ->
                PlayerSubtitleUtils.matchesLanguageCode(sub.lang, normalizedPref)
            }
            preferred + rest
        } else {
            subtitles
        }

        // Deduplicate by URL to avoid downloading the same file twice
        val unique = sorted.distinctBy { it.url }
        Log.d(TAG, "Pre-fetching ALL ${unique.size} subtitles (total=${subtitles.size}, deduped=${unique.size})")

        prefetchJob = scope.launch(Dispatchers.IO) {
            // Download in batches of 5 to avoid overwhelming the network
            unique.chunked(5).forEach { batch ->
                batch.map { sub ->
                    launch {
                        if (getCachedFile(sub) == null) {
                            download(sub)
                        }
                    }
                }.forEach { it.join() }
            }
            Log.d(TAG, "Pre-fetch complete for ${unique.size} subtitles")
        }
    }

    /**
     * Deletes all cached subtitle files.
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Cancels any in-progress prefetch.
     */
    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun download(subtitle: Subtitle): File? {
        val file = fileForSubtitle(subtitle)
        return try {
            ensureCacheSpace()
            val request = Request.Builder()
                .url(subtitle.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download failed for ${subtitle.id}: HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                file.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            }

            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Downloaded ${subtitle.lang} id=${subtitle.id} (${file.length()} bytes)")
                file
            } else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download error for ${subtitle.id}: ${e.message}")
            file.delete()
            null
        }
    }

    private fun fileForSubtitle(subtitle: Subtitle): File {
        val hash = sha256("${subtitle.id}|${subtitle.url}")
        val extension = extensionFromUrl(subtitle.url)
        return File(cacheDir, "$hash.$extension")
    }

    private fun extensionFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> "vtt"
            path.endsWith(".ass") -> "ass"
            path.endsWith(".ssa") -> "ssa"
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> "ttml"
            else -> "srt"
        }
    }

    private fun ensureCacheSpace() {
        val files = cacheDir.listFiles() ?: return
        val totalSize = files.sumOf { it.length() }
        if (totalSize < MAX_CACHE_SIZE_BYTES) return

        // Evict oldest files first
        val sortedByAge = files.sortedBy { it.lastModified() }
        var freed = 0L
        val target = totalSize - (MAX_CACHE_SIZE_BYTES * 3 / 4) // free 25% of max
        for (file in sortedByAge) {
            if (freed >= target) break
            freed += file.length()
            file.delete()
        }
        Log.d(TAG, "Evicted ${freed / 1024}KB from cache")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24) // 24 hex chars is enough
    }
}
