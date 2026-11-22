package com.nuvio.tv.ui.screens.player

/**
 * The sources the stream screen already tried before the auto-selected stream that is about to play.
 *
 * Falling back to the next matching source starts on the stream screen, when an auto-selected
 * stream cannot be resolved, and goes on in the player, when the stream that did resolve fails to
 * start. The player picks up the tried sources and the count from here, so it skips the ones that
 * already failed and the configured number of sources covers both. One-shot and keyed by playback
 * URL, like [StreamSidecarSubtitles], so a leftover entry cannot attach to another playback.
 */
internal object SourceFallbackHandoff {
    data class TriedSources(val keys: Set<String>, val attempts: Int) {
        companion object {
            /** A stream auto selection picked first, with nothing tried before it. */
            val None = TriedSources(keys = emptySet(), attempts = 0)
        }
    }

    @Volatile
    private var playbackUrl: String? = null

    @Volatile
    private var triedSources: TriedSources? = null

    fun set(url: String?, triedSources: TriedSources) {
        val playbackUrl = url?.takeIf { it.isNotBlank() }
        this.playbackUrl = playbackUrl
        this.triedSources = triedSources.takeIf { playbackUrl != null }
    }

    /**
     * The sources tried before the playback of any of [urls] (the URL as navigated and as the player
     * normalized it), once: the entry is dropped whether or not it matches.
     */
    fun take(vararg urls: String): TriedSources? {
        val storedUrl = playbackUrl
        val stored = triedSources
        playbackUrl = null
        triedSources = null
        return stored?.takeIf { storedUrl != null && storedUrl in urls }
    }
}
