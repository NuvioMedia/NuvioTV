package com.nuvio.tv.core.streams

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.domain.model.Stream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes streams that only carry a YouTube id ([Stream.ytId]) playable.
 *
 * With in-app playback the video is resolved on the device to a URL the player can open.
 * Without it, the stream becomes an external link to the video's watch page, the same way
 * trailers open in that case.
 */
@Singleton
class YouTubeStreamResolver internal constructor(
    private val inAppPlaybackEnabled: Boolean,
    private val extractSingleUrl: suspend (String) -> String?
) {
    @Inject
    constructor(extractor: InAppYouTubeExtractor) : this(
        inAppPlaybackEnabled = AppFeaturePolicy.inAppTrailerPlaybackEnabled,
        extractSingleUrl = extractor::extractSingleUrl
    )

    /**
     * Returns [stream] unchanged when it doesn't need resolving, a copy that can be played
     * when it does, or null when the video couldn't be resolved.
     */
    suspend fun resolve(stream: Stream): Stream? {
        val videoId = stream.youTubeIdToResolve() ?: return stream
        val watchUrl = watchUrl(videoId)
        if (!inAppPlaybackEnabled) return stream.copy(externalUrl = watchUrl)
        val url = extractSingleUrl(watchUrl)?.takeIf { it.isNotBlank() } ?: return null
        return stream.copy(url = url)
    }

    companion object {
        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
    }
}
