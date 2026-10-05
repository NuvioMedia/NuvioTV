package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeTrakrScrobbleService @Inject constructor(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val configuration: WeTrakrConfiguration
) {
    suspend fun scrobble(scope: WeTrakrAuthScope, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!event.progressPercent.isFinite()) return
        val target = event.media.weTrakrTarget()?.takeIf { it.type != WeTrakrItemType.SHOW } ?: return
        val progress = event.progressPercent.coerceIn(0.0, 100.0)
        sync.write(scope, setOf(WeTrakrSyncBucket.PLAYBACK, watchedBucket(target))) { snapshot ->
            val response = api.post(
                "/scrobble/${action.wireValue}",
                target.scrobbleBody(progress, configuration.appVersion).toString(),
                scope,
                acceptedStatuses = setOf(404)
            )
            if (response.status == 404) return@write snapshot to Unit
            val body = weTrakrElement(response.body).weTrakrObject()
            val confirmed = body.decimal("progress")?.toFloat()?.coerceIn(0f, 100f) ?: progress.toFloat()
            val now = Instant.now().toString()
            val previous = snapshot.playback.firstOrNull(target::matches)
            val remaining = snapshot.playback.filterNot(target::matches)
            val next = if (action == TrackingScrobbleAction.STOP && body.text("action") == "scrobble") {
                snapshot.copy(
                    playback = remaining,
                    watched = snapshot.watched.filterNot(target::matches) + target.watched(now)
                )
            } else {
                snapshot.copy(playback = remaining + target.playback(confirmed, now, previous))
            }
            next to Unit
        }
    }

    suspend fun clear(scope: WeTrakrAuthScope, contentId: String, season: Int?, episode: Int?) {
        sync.ensureLoaded()
        val sessions = sync.currentSnapshot()?.playback.orEmpty().filter { session ->
            contentId in session.media.ids.aliases() &&
                (season == null || episode == null || session.season == season && session.episode == episode)
        }
        for (session in sessions) {
            sync.write(scope, setOf(WeTrakrSyncBucket.PLAYBACK)) { snapshot ->
                val target = WeTrakrTarget(session.type, session.media, session.season, session.episode)
                api.delete("/scrobble/playing", target.scrobbleBody().toString(), scope, acceptedStatuses = setOf(404))
                snapshot.copy(playback = snapshot.playback.filterNot(target::matches)) to Unit
            }
        }
    }

    private fun watchedBucket(target: WeTrakrTarget): WeTrakrSyncBucket =
        if (target.type == WeTrakrItemType.MOVIE) WeTrakrSyncBucket.WATCHED_MOVIES else WeTrakrSyncBucket.WATCHED_EPISODES
}
