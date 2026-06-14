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
        val outroSegments = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false
            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end — fire at earliest outro start.
                positionMs / 1_000.0 >= outroSegments.minOf { it.startTime }
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        if (durationMs <= 0L) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
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

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L
}