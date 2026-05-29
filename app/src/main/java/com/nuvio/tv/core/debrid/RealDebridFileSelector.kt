package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): RealDebridTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
        if (playable.isEmpty()) return null

        val episodePatterns = buildDebridEpisodePatterns(
            season = season ?: resolve.season,
            episode = episode ?: resolve.episode
        )
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            playable.firstDebridNameMatch(names) { it.displayName() }?.let { return it }
        }

        if (episodePatterns.isNotEmpty()) {
            playable.firstOrNull { file ->
                val fileName = file.displayName().lowercase()
                episodePatterns.any { pattern -> fileName.contains(pattern) }
            }?.let { return it }
        }

        resolve.fileIdx?.let { fileIdx ->
            files.getOrNull(fileIdx)?.takeIf { it.isPlayableVideo() }?.let { return it }
            if (fileIdx > 0) {
                files.getOrNull(fileIdx - 1)?.takeIf { it.isPlayableVideo() }?.let { return it }
            }
            playable.firstOrNull { it.id == fileIdx }?.let { return it }
        }

        return playable.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean {
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
