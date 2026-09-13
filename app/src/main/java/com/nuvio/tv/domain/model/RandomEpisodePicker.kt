package com.nuvio.tv.domain.model

import com.nuvio.tv.core.util.isEpisodeReleaseAired
import kotlin.random.Random

/** Shared, state-free episode eligibility and random-selection rules for shuffle playback. */
object RandomEpisodePicker {
    fun eligibleEpisodes(videos: List<Video>): List<Video> {
        val candidates = videos.filter {
            it.season != null && it.episode != null && (it.season ?: 0) > 0
        }
        val unavailableSeasons = candidates.groupBy { it.season }
            .filter { (_, episodes) ->
                val first = episodes.minByOrNull { it.episode ?: Int.MAX_VALUE }
                    ?: return@filter false
                first.available == false || isEpisodeReleaseAired(first.released) == false
            }
            .keys

        return candidates
            .filter { it.season !in unavailableSeasons }
            .filter { it.available != false && isEpisodeReleaseAired(it.released) != false }
    }

    fun pick(
        videos: List<Video>,
        excludedVideoIds: Set<String> = emptySet(),
        currentVideoId: String? = null,
        canPlay: (Video) -> Boolean = { true },
        random: Random = Random.Default
    ): Video? {
        val playable = eligibleEpisodes(videos).filter(canPlay)
        val unseen = playable.filter { it.id !in excludedVideoIds }
        val pool = if (unseen.isNotEmpty()) {
            unseen
        } else {
            playable.filter { it.id != currentVideoId }.ifEmpty { playable }
        }
        return pool.takeIf { it.isNotEmpty() }?.random(random)
    }
}

/** Process-local history: a shuffle cycle is intentionally not written to watch history. */
object ContinuousShuffleSession {
    private val playedByContentId = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun start(contentId: String, firstVideoId: String) {
        playedByContentId[contentId] = mutableSetOf(firstVideoId)
    }

    @Synchronized
    fun record(contentId: String, videoId: String) {
        playedByContentId.getOrPut(contentId) { mutableSetOf() }.add(videoId)
    }

    @Synchronized
    fun history(contentId: String): Set<String> =
        playedByContentId[contentId]?.toSet().orEmpty()

    @Synchronized
    fun restartCycle(contentId: String, currentVideoId: String?) {
        playedByContentId[contentId] = currentVideoId?.let { mutableSetOf(it) } ?: mutableSetOf()
    }
}
