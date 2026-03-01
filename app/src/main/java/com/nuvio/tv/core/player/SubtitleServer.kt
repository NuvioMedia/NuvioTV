package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.domain.model.Subtitle
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream

/**
 * A local proxy server that intercepts ExoPlayer subtitle requests
 * and serves them directly from the local SubtitleCache.
 * This integrates ExoPlayer's on-demand track selection with our async pre-fetch system.
 */
class SubtitleServer(
    private val subtitleCache: SubtitleCache,
    private val getSubtitleById: (String) -> Subtitle?
) : NanoHTTPD("127.0.0.1", 8155) {

    companion object {
        private const val TAG = "SubtitleServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/subtitle") {
            val id = session.parameters["id"]?.firstOrNull()
            if (id == null) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")
            
            val subtitle = getSubtitleById(id)
            if (subtitle == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Subtitle not found")
            
            Log.d(TAG, "ExoPlayer requested subtitle: ${subtitle.lang} (id=${id})")
            
            // Blocking wait until the file is pre-fetched or downloaded
            val file = runBlocking { subtitleCache.getOrDownload(subtitle) }
            
            if (file != null && file.exists() && file.length() > 0) {
                val mime = when {
                    file.name.endsWith(".vtt") -> "text/vtt"
                    file.name.endsWith(".srt") -> "application/x-subrip"
                    file.name.endsWith(".ssa") || file.name.endsWith(".ass") -> "text/x-ssa"
                    else -> "text/plain"
                }
                Log.d(TAG, "Serving ${file.name} to ExoPlayer (${file.length()} bytes)")
                return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
            }
            Log.w(TAG, "Failed to serve ${subtitle.id}, file missing")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download failed")
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }
}
