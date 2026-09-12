package com.nuvio.tv.domain.model

import kotlin.random.Random

internal class RandomEpisodePicker(
    meta: Meta,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeProgress: Map<Pair<Int, Int>, WatchProgress>,
    private val random: Random = Random.Default
) {
    private val contentId = meta.id
    private val episodes = meta.watchableEpisodes()
        .filter { it.id.isNotBlank() && (it.episode ?: 0) > 0 }
        .distinctBy { it.season to it.episode }
        .distinctBy { it.id }
    private val watchedKeys = watchedEpisodes + episodeProgress
        .filterValues { it.isCompleted() }
        .keys
    private val unwatchedEpisodes = episodes.filterNot { isWatched(it) }
    private val shownIds = mutableSetOf<String>()
    private var lastPickedId: String? = null

    fun count(includeWatched: Boolean): Int = candidates(includeWatched).size

    fun isWatched(episode: Video): Boolean = (episode.season to episode.episode) in watchedKeys

    fun find(videoId: String, includeWatched: Boolean): Video? =
        candidates(includeWatched).firstOrNull { it.id == videoId }

    fun inheritHistoryFrom(previous: RandomEpisodePicker?) {
        if (previous == null || previous.contentId != contentId) return
        shownIds.addAll(previous.shownIds)
        lastPickedId = previous.lastPickedId
    }

    fun pick(includeWatched: Boolean): Video? {
        val candidates = candidates(includeWatched)
        if (candidates.isEmpty()) return null
        val unseen = candidates.filterNot { it.id in shownIds }
        val pool = unseen.ifEmpty {
            shownIds.removeAll(candidates.map { it.id }.toSet())
            candidates.filterNot { it.id == lastPickedId }.ifEmpty { candidates }
        }
        return pool.random(random).also {
            shownIds.add(it.id)
            lastPickedId = it.id
        }
    }

    private fun candidates(includeWatched: Boolean): List<Video> =
        if (includeWatched) episodes else unwatchedEpisodes
}
