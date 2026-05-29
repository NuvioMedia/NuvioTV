package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeDirectDownloadFileSelector @Inject constructor() {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): PremiumizeDirectDownloadFileDto? {
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
        }

        return playable.maxByOrNull { it.size ?: 0L }
    }

    private fun PremiumizeDirectDownloadFileDto.isPlayableVideo(): Boolean {
        return !link.isNullOrBlank() && displayName().lowercase().hasDebridVideoExtension()
    }
}
