package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktTrackingScrobbler @Inject constructor(
    private val service: TraktScrobbleService,
    private val episodeMappingService: TraktEpisodeMappingService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.TRAKT
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART

    /*
     * Trakt records a play on every stop at or above the bar, and a pause is sent as a stop. One
     * playback can therefore report a completion more than once when it is interrupted and resumed;
     * the player's run id lets the second one be dropped without touching a later, separate viewing.
     */
    @Volatile
    private var lastCompletedPlaybackId: Long = 0L

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        /*
         * A playback that resumes above the completion bar is not a new viewing here. The player
         * marks that start sessionless, so the coordinator already leaves this scrobbler out; the
         * guard keeps the rule for a caller that reaches the scrobbler directly, because Trakt would
         * otherwise record the same watch twice, once through the start and once through the stop.
         */
        if (action == TrackingScrobbleAction.START &&
            event.progressPercent >= TRACKING_COMPLETION_BAR_PERCENT
        ) {
            return
        }
        val completesPlayback = event.progressPercent >= TRACKING_COMPLETION_BAR_PERCENT &&
            (action == TrackingScrobbleAction.PAUSE || action == TrackingScrobbleAction.STOP)
        if (completesPlayback &&
            event.playbackId != 0L &&
            event.playbackId == lastCompletedPlaybackId
        ) {
            return
        }
        val item = event.media.toTraktScrobbleItem() ?: return
        val written = when (action) {
            TrackingScrobbleAction.START -> service.scrobbleStart(item, event.progressPercent.toFloat())
            TrackingScrobbleAction.PAUSE -> service.scrobbleStop(item, event.progressPercent.toFloat())
            TrackingScrobbleAction.STOP -> service.scrobbleStop(item, event.progressPercent.toFloat())
        }
        // The id is latched only after the tracker accepted the write, so a failed stop cannot
        // suppress the later stop that really ends the playback.
        if (completesPlayback && written && event.playbackId != 0L) {
            lastCompletedPlaybackId = event.playbackId
        }
    }

    private suspend fun TrackingMediaReference.toTraktScrobbleItem(): TraktScrobbleItem? {
        val traktIds = TraktIdsDto(
            trakt = ids.trakt.toIntExactOrNull(),
            imdb = ids.imdb?.takeIf(String::isNotBlank),
            tmdb = ids.tmdb.toIntExactOrNull(),
            tvdb = ids.tvdb?.toIntOrNull()
        )
        if (!traktIds.hasAnyId()) return null
        if (kind == TrackingMediaKind.MOVIE) {
            return TraktScrobbleItem.Movie(title = title, year = year, ids = traktIds)
        }

        val episodeReference = episode ?: return null
        val season = episodeReference.season ?: return null
        val contentId = catalog?.contentId ?: ids.imdb ?: ids.tmdb?.let { "tmdb:$it" }
        val mapped = episodeMappingService.resolveEpisodeMapping(
            contentId = contentId,
            contentType = catalog?.contentType ?: "series",
            videoId = catalog?.videoId,
            season = season,
            episode = episodeReference.number,
            episodeTitle = episodeReference.title
        )
        return TraktScrobbleItem.Episode(
            showTitle = title,
            showYear = year,
            showIds = traktIds,
            season = mapped?.season ?: season,
            number = mapped?.episode ?: episodeReference.number,
            episodeTitle = episodeReference.title
        )
    }
}

private fun Long?.toIntExactOrNull(): Int? = this?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
