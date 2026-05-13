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
import kotlinx.coroutines.delay
import java.io.IOException
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
        val profileId: Int,
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val maxRetries = 2
    private val retryDelayMs = 1_500L
    private val serverOverloadedRetryDelayMs = 5_000L

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "pause", item = item, progressPercent = progressPercent)
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        val activeProfileId = profileManager.activeProfileId.value
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return
        if (!traktAuthService.hasRequiredCredentials()) return

        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(activeProfileId, action, item.itemKey, clampedProgress)) return

        val requestBody = buildRequestBody(item, clampedProgress)

        var lastException: Exception? = null
        val attempts = if (action == "stop") maxRetries + 1 else 1

        for (attempt in 1..attempts) {
            val response = try {
                traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                    when (action) {
                        "start" -> traktApi.scrobbleStart(authHeader, requestBody)
                        else -> traktApi.scrobbleStop(authHeader, requestBody)
                    }
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < attempts) {
                    Log.w(TAG, "Scrobble $action attempt $attempt failed (IO), retrying", e)
                    delay(retryDelayMs * attempt)
                    continue
                }
                null
            }

            if (response == null) {
                if (attempt < attempts) {
                    Log.w(TAG, "Scrobble $action attempt $attempt returned null, retrying")
                    delay(retryDelayMs * attempt)
                    continue
                }
                Log.w(TAG, "Scrobble $action failed after $attempts attempts", lastException)
                return
            }

            if (response.isSuccessful || response.code() == 409) {
                lastScrobbleStamp = ScrobbleStamp(
                    profileId = activeProfileId,
                    action = action,
                    itemKey = item.itemKey,
                    progress = clampedProgress,
                    timestampMs = System.currentTimeMillis()
                )
                if (action == "stop") {
                    traktProgressService.refreshNow()
                }
                return
            }

            // Server error (5xx) — retry for stop actions
            if (response.code() in 500..599 && attempt < attempts) {
                val delayMs = if (response.code() in 502..504) {
                    serverOverloadedRetryDelayMs
                } else {
                    retryDelayMs * attempt
                }
                Log.w(TAG, "Scrobble $action attempt $attempt got ${response.code()}, retrying in ${delayMs}ms")
                delay(delayMs)
                continue
            }

            // Non-retryable error (4xx other than 409)
            Log.w(TAG, "Scrobble $action failed with code ${response.code()}")
            return
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

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameProfile = last.profileId == profileId
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow

        // Never skip a stop/pause if the last successful action was start —
        // Trakt needs to know playback ended regardless of timing.
        if ((action == "stop" || action == "pause") && last.action == "start" && isSameItem && isSameProfile) {
            return false
        }

        return isSameWindow && isSameProfile && isSameAction && isSameItem && isNearProgress
    }
}
