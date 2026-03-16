package com.nuvio.tv.core.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class SubtitleFormatUtils @Inject constructor(private val okHttpClient: OkHttpClient) {

    fun resolveFormat(url: String, hintFormat: String? = null): String? =
        hintFormat ?: formatFromUrl(url) ?: probeFormatFromHead(url)

    private fun probeFormatFromHead(url: String): String? {
        return try {
            val request = Request.Builder().url(url).head().build()
            okHttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: return null
                when {
                    contentType.contains("x-ssa") || contentType.contains("ass") -> "ass"
                    contentType.contains("subrip") || contentType.contains("x-srt") -> "srt"
                    contentType.contains("vtt") || contentType.contains("webvtt") -> "vtt"
                    contentType.contains("ttml") -> "ttml"
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "HEAD probe failed for $url: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SubtitleFormatUtils"

        fun formatFromUrl(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
            return when {
                path.endsWith(".ass") || path.endsWith(".ssa") -> "ass"
                path.endsWith(".srt") -> "srt"
                path.endsWith(".vtt") || path.endsWith(".webvtt") -> "vtt"
                path.endsWith(".ttml") || path.endsWith(".dfxp") -> "ttml"
                else -> null
            }
        }
    }
}
