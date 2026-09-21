package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle

/**
 * One-shot sidecar subtitles for the stream that is about to play.
 * Keyed by playback URL so a leftover list cannot attach to a different stream.
 */
internal object StreamSidecarSubtitles {
    @Volatile
    private var playbackUrl: String? = null

    @Volatile
    private var subtitles: List<Subtitle> = emptyList()

    fun set(url: String?, subtitles: List<Subtitle>) {
        val playbackUrl = url?.takeIf { it.isNotBlank() }
        this.playbackUrl = playbackUrl
        this.subtitles = if (playbackUrl == null) emptyList() else subtitles
    }

    fun forUrl(url: String): List<Subtitle> {
        val storedUrl = playbackUrl ?: return emptyList()
        return if (storedUrl == url) subtitles else emptyList()
    }
}
