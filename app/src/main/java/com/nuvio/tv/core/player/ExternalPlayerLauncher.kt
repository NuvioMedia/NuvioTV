package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.R

object ExternalPlayerLauncher {

    /**
     * Fire-and-forget launch of an external player.
     * Used as a fallback when ActivityResultLauncher is not available (e.g. non-Activity context).
     */
    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")

                title?.let {
                    putExtra("title", it)
                    putExtra(Intent.EXTRA_TITLE, it)
                    putExtra("forcename", it) // Vimu Player
                }

                headers?.let { hdrs ->
                    if (hdrs.isNotEmpty()) {
                        val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                        putExtra("headers", headerArray)
                    }
                }

                if (resumePositionMs > 0L) {
                    putExtra("position", resumePositionMs.toInt())
                    putExtra("startfrom", resumePositionMs.toInt())
                    putExtra("from_start", false)
                }

                // Subtitle extras for external players
                if (!subtitles.isNullOrEmpty()) {
                    val subtitleUris = subtitles.map { Uri.parse(it.url) }.toTypedArray()
                    val subtitleNames = subtitles.map { it.name }.toTypedArray()
                    val subtitleFilenames = subtitles.map { "${it.lang}_${it.name}.srt" }.toTypedArray()

                    // MX Player / mpv-android / Nova
                    putExtra("subs", subtitleUris)
                    putExtra("subs.name", subtitleNames)
                    putExtra("subs.filename", subtitleFilenames)
                    putExtra("subs.enable", arrayOf(Uri.parse(subtitles.first().url)))

                    // Just Player
                    putExtra("subtitle_uri", subtitleUris)
                    putExtra("subtitle_name", subtitleNames)

                    // VLC (single subtitle — use first one)
                    putExtra("subtitles_location", Uri.parse(subtitles.first().url))

                    // Vimu Player
                    putExtra("forcedsrt", subtitles.first().url)
                }

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /**
     * Create an [ExternalPlayerInput] for use with [ExternalPlayerResultContract].
     * Prefer this over [launch] when you have an Activity context and want to receive
     * playback progress back from the external player.
     */
    fun createInput(
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L,
        subtitles: List<SubtitleInput>? = null
    ): ExternalPlayerInput = ExternalPlayerInput(
        url = url,
        title = title,
        headers = headers,
        resumePositionMs = resumePositionMs,
        subtitles = subtitles
    )
}
