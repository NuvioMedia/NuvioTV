package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.screens.home.CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS
import com.nuvio.tv.ui.screens.home.isNextUpEpisodeUnaired
import com.nuvio.tv.ui.util.parseEpisodeReleaseDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The first episode of the series that comes after the given run position and may be offered.
 *
 * A rewatch run on the detail screen works from the episode the run sits at rather than from the
 * canonical watch position, and the offer is the next step of the run: a run that reached S01E02
 * offers S01E03. When the run sits on the last episode this returns null and the canonical position
 * decides, exactly as it does without a run.
 *
 * A step that has not aired yet is skipped while [showUnairedNextUp] is off, and the first aired
 * episode after the run decides instead; an unaired step is offered again as soon as the user allows
 * unaired episodes. Airedness is the rule the Continue Watching Next Up row already applies, see
 * [isRunEpisodeOfferable], so the screen and the row cannot disagree about the same episode.
 *
 * @param today the day airedness is read against, so callers and tests decide the clock.
 */
internal fun nextEpisodeAfterRun(
    episodes: List<Video>,
    run: RewatchRunPosition,
    showUnairedNextUp: Boolean = true,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): Video? =
    episodes
        .filter { video -> video.season != null && video.episode != null }
        .sortedWith(compareBy<Video>({ video -> video.season ?: 0 }, { video -> video.episode ?: 0 }))
        .firstOrNull { video ->
            val season = video.season ?: return@firstOrNull false
            val episode = video.episode ?: return@firstOrNull false
            val afterRun = season > run.seasonNumber || (season == run.seasonNumber && episode > run.episodeNumber)
            afterRun && isRunEpisodeOfferable(video, run.seasonNumber, showUnairedNextUp, today)
        }

/**
 * Whether a run step may be offered, decided the way the Continue Watching Next Up row decides the
 * same question.
 *
 * An episode counts as aired once its release date is today or earlier, and an episode with no date
 * at all counts as unaired, which is how the row treats a missing date: it is no more watchable than
 * one dated ahead. An unaired step is skipped while the user hides unaired episodes, and the same
 * setting allows it again when the user shows them. A dated step into the next season also has to
 * fall inside the same seven day window the row uses, so a next season that is still months away is
 * not offered even with unaired episodes allowed. A next season step without a date has no day count
 * to measure against, so the setting alone decides it, because most catalogues ship no air dates at
 * all and refusing the step would end the run there.
 */
private fun isRunEpisodeOfferable(
    video: Video,
    runSeasonNumber: Int,
    showUnairedNextUp: Boolean,
    today: LocalDate,
): Boolean {
    val season = video.season ?: return false
    val releaseDate = parseEpisodeReleaseDate(video.released)
    if (!isNextUpEpisodeUnaired(releaseDate, today)) return true
    if (!showUnairedNextUp) return false
    if (season == runSeasonNumber) return true
    val daysUntilRelease = releaseDate?.let { date -> ChronoUnit.DAYS.between(today, date) } ?: return true
    return daysUntilRelease <= CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS
}
