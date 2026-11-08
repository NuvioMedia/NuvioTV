package com.nuvio.tv.domain.model

import javax.inject.Inject
import javax.inject.Singleton

data class EpisodeShuffleSettings(
    val enabled: Boolean = false,
    val includeWatched: Boolean = false
)

enum class ShuffleSurface { DETAIL, HOME, PLAYBACK }

@Singleton
class EpisodeShuffle @Inject constructor() {
    private data class Key(
        val profileId: Int,
        val contentId: String,
        val surface: ShuffleSurface,
        val includeWatched: Boolean
    )

    private class Session {
        var picker: RandomEpisodePicker? = null
        var selected: Video? = null
        var current: Pair<Int, Int>? = null
        var visit: Long = 0
    }

    private val sessions = LinkedHashMap<Key, Session>(16, 0.75f, true)

    @Synchronized
    fun select(
        profileId: Int,
        contentId: String,
        videos: List<Video>,
        includeWatched: Boolean,
        watched: Set<Pair<Int, Int>> = emptySet(),
        progress: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
        surface: ShuffleSurface,
        current: Pair<Int, Int>? = null,
        visit: Long = 0,
        preferredVideoId: String? = null
    ): Video? {
        val key = Key(profileId, contentId, surface, includeWatched)
        val session = sessions.getOrPut(key, ::Session)
        while (sessions.size > 96) sessions.remove(sessions.keys.first())
        val picker = RandomEpisodePicker(contentId, videos, watched, progress)
        picker.inheritHistoryFrom(session.picker)
        if (current != null && (session.picker == null || session.current != current)) picker.recordPlayed(current)
        session.picker = picker
        val newerVisit = visit > session.visit
        if (session.current != current || newerVisit) session.selected = null
        session.current = current
        if (newerVisit) session.visit = visit
        session.selected = session.selected?.let { prev ->
            picker.find(prev.id, includeWatched, current)
                ?: prev.takeIf { surface == ShuffleSurface.HOME }
        } ?: preferredVideoId?.let { picker.find(it, includeWatched, current) }
                ?: picker.pick(includeWatched, current, consumeSelection = surface != ShuffleSurface.PLAYBACK || current == null)
        return session.selected
    }

    @Synchronized
    fun clearSelection(profileId: Int, contentId: String, surface: ShuffleSurface) {
        sessions.filterKeys {
            it.profileId == profileId && it.contentId == contentId && it.surface == surface
        }.values.forEach { it.selected = null }
    }
}
