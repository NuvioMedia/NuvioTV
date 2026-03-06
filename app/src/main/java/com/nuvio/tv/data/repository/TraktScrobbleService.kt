package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

sealed interface TraktScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val year: Int?,
        val ids: TraktIdsDto
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "movie:${ids.imdb ?: ids.tmdb ?: ids.trakt ?: title.orEmpty()}:${year ?: 0}"
    }

    data class Episode(
        val showTitle: String?,
        val showYear: Int?,
        val showIds: TraktIdsDto,
        val season: Int,
        val number: Int,
        val episodeTitle: String?
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "episode:${showIds.imdb ?: showIds.tmdb ?: showIds.trakt ?: showTitle.orEmpty()}:$season:$number"
    }
}

@Singleton
class TraktScrobbleService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktProgressService: TraktProgressService,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "TraktScrobbleSvc"
    }

    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "pause", item = item, progressPercent = progressPercent)
    }

    fun scrobbleStartAsync(item: TraktScrobbleItem, progressPercent: Float) {
        serviceScope.launch {
            scrobbleStart(item = item, progressPercent = progressPercent)
        }
    }

    fun scrobbleStopAsync(item: TraktScrobbleItem, progressPercent: Float) {
        serviceScope.launch {
            scrobbleStop(item = item, progressPercent = progressPercent)
        }
    }

    fun scrobblePauseAsync(item: TraktScrobbleItem, progressPercent: Float) {
        serviceScope.launch {
            scrobblePause(item = item, progressPercent = progressPercent)
        }
    }

    fun scrobbleStartThenPauseAsync(item: TraktScrobbleItem, progressPercent: Float) {
        serviceScope.launch {
            scrobbleStart(item = item, progressPercent = progressPercent)
            scrobblePause(item = item, progressPercent = progressPercent)
        }
    }

    fun scrobbleStartThenStopAsync(item: TraktScrobbleItem, progressPercent: Float) {
        serviceScope.launch {
            scrobbleStart(item = item, progressPercent = progressPercent)
            scrobbleStop(item = item, progressPercent = progressPercent)
        }
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        if (profileManager.activeProfileId.value != 1) return
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return
        if (!traktAuthService.hasRequiredCredentials()) return

        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(action, item.itemKey, clampedProgress)) return

        val requestBody = buildRequestBody(item, clampedProgress)

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            when (action) {
                "start" -> traktApi.scrobbleStart(authHeader, requestBody)
                "pause" -> traktApi.scrobblePause(authHeader, requestBody)
                else -> traktApi.scrobbleStop(authHeader, requestBody)
            }
        } ?: run {
            Log.w(TAG, "Trakt scrobble $action failed: no response")
            return
        }

        if (response.isSuccessful || response.code() == 409) {
            Log.d(
                TAG,
                "Trakt scrobble $action accepted (HTTP ${response.code()}) progress=$clampedProgress item=${item.itemKey}"
            )
            lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey,
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            if (action == "pause" || action == "stop") {
                traktProgressService.refreshNow()
            }
        } else {
            Log.w(TAG, "Trakt scrobble $action failed with HTTP ${response.code()} for ${item.itemKey}")
        }
    }

    internal fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float
    ): TraktScrobbleRequestDto {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktScrobbleRequestDto(
                movie = TraktMovieDto(
                    title = item.title,
                    year = item.year,
                    ids = item.ids
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )

            is TraktScrobbleItem.Episode -> TraktScrobbleRequestDto(
                show = TraktShowDto(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = item.showIds
                ),
                episode = TraktEpisodeDto(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        return isSameWindow && isSameAction && isSameItem && isNearProgress
    }
}
