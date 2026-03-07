package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int,
        currentEpisode: Int
    ): Video? {
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null

        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        val outroInterval = skipIntervals.firstOrNull { it.type == "outro" }
        return if (outroInterval != null) {
            positionMs / 1000.0 >= outroInterval.startTime
        } else {
            if (durationMs <= 0L) return false
            when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    if (thresholdPercent <= 0f) return false
                    val clampedPercent = thresholdPercent.coerceIn(0f, 99.5f)
                    (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    if (thresholdMinutesBeforeEnd <= 0f) return false
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    val remainingMs = durationMs - positionMs
                    remainingMs <= (clampedMinutes * 60_000f).toLong()
                }
            }
        }
    }

    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        val releasedDate = parseEpisodeReleaseDate(raw) ?: return true
        return !releasedDate.isAfter(LocalDate.now(clock))
    }
}
