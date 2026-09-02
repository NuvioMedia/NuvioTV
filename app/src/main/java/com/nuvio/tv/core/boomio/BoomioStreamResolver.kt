package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Stream
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BoomioStreamResolver"

/**
 * Resolves playback streams through the boomio media plane (`bsf`).
 *
 * boomio exposes:
 *   GET /find/:imdbId                    — movie lookup
 *   GET /find/:imdbId/:season/:episode   — episode lookup
 *
 * and returns a ranked list of Stremio-shaped streams whose `url` is a signed
 * `bsc` byte-range proxy URL. Each entry is mapped onto the app's existing
 * [Stream] model via the same [toDomain] mapper the addon layer uses, so the
 * stream picker, autoplay selector and built-in player consume boomio streams
 * exactly like any addon stream.
 *
 * The seam is inert unless [BuildConfig.BOOMIO_BASE_URL] is set in
 * local.properties; the existing addon/debrid/Trakt/TMDB resolvers remain the
 * primary sources and are left untouched as fallback.
 */
@Singleton
class BoomioStreamResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val responseAdapter = moshi.adapter(StreamResponseDto::class.java)

    /** True when the boomio seam is configured (BOOMIO_BASE_URL is set). */
    fun isEnabled(): Boolean = BuildConfig.BOOMIO_BASE_URL.isNotBlank()

    /**
     * Calls boomio `/find` for [videoId] (the Stremio-style id, e.g. `tt1234567`
     * for a movie) with optional [season]/[episode], and returns the resolved
     * streams in boomio's ranked order.
     *
     * Returns an empty list when the seam is disabled, the id is blank, the
     * request fails, or boomio returns no streams.
     */
    suspend fun resolve(videoId: String?, season: Int?, episode: Int?): List<Stream> {
        if (!isEnabled()) return emptyList()
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            resolveUnsafe(id, season, episode)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "boomio /find failed for $id: ${error.message}")
            emptyList()
        }
    }

    private suspend fun resolveUnsafe(
        videoId: String,
        season: Int?,
        episode: Int?
    ): List<Stream> = withContext(Dispatchers.IO) {
        val url = buildFindUrl(videoId, season, episode) ?: return@withContext emptyList()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "boomio /find HTTP ${response.code} for $videoId")
                return@withContext emptyList()
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext emptyList()
            val dto = responseAdapter.fromJson(body)
            dto?.streams.orEmpty().mapNotNull { streamDto ->
                streamDto.toDomain(addonName = ADDON_NAME, addonLogo = null)
            }
        }
    }

    /**
     * Builds the `/find` URL from [BuildConfig.BOOMIO_BASE_URL]. Episodes use the
     * explicit `/find/:imdbId/:season/:episode` route (which requires an IMDB id);
     * everything else uses `/find/:stremioId`, which boomio normalizes (it accepts
     * `tt…` and `tmdb:…` ids and infers the type from the presence of a `:`).
     */
    private fun buildFindUrl(videoId: String, season: Int?, episode: Int?): String? {
        val base = BuildConfig.BOOMIO_BASE_URL.trim().trimEnd('/')
        val baseUrl = base.toHttpUrlOrNull() ?: run {
            Log.w(TAG, "boomio: invalid BOOMIO_BASE_URL")
            return null
        }
        val builder = baseUrl.newBuilder().addPathSegment("find")
        if (season != null && episode != null) {
            val imdbId = videoId.substringBefore(":")
            if (!imdbId.startsWith("tt")) return null
            builder.addPathSegment(imdbId)
                .addPathSegment(season.toString())
                .addPathSegment(episode.toString())
        } else {
            builder.addPathSegment(videoId)
        }
        // Install-level capability hint: when this build was compiled with a max
        // resolution (BOOMIO_MAX_RESOLUTION, e.g. "1080p"), ask bsf to cap the
        // streams it feeds back so higher resolutions never reach the picker.
        BuildConfig.BOOMIO_MAX_RESOLUTION.trim().takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("maxResolution", it)
        }
        return builder.build().toString()
    }

    companion object {
        /** Group name under which boomio streams appear in the picker/autoplay. */
        const val ADDON_NAME = "Boomio"
    }
}
