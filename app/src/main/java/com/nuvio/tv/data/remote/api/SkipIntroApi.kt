package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// --- IntroDB API ---

interface IntroDbApi {
    @GET("segments")
    suspend fun getSegments(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int,
        @Query("episode") episode: Int
    ): Response<IntroDbSegmentsResponse>
}

@JsonClass(generateAdapter = true)
data class IntroDbSegmentsResponse(
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "intro") val intro: IntroDbSegment? = null,
    @Json(name = "recap") val recap: IntroDbSegment? = null,
    @Json(name = "outro") val outro: IntroDbSegment? = null
)

@JsonClass(generateAdapter = true)
data class IntroDbSegment(
    @Json(name = "start_sec") val startSec: Double? = null,
    @Json(name = "end_sec") val endSec: Double? = null,
    @Json(name = "start_ms") val startMs: Long? = null,
    @Json(name = "end_ms") val endMs: Long? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    @Json(name = "submission_count") val submissionCount: Int? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

// --- AniSkip API ---

interface AniSkipApi {
    @GET("skip-times/{malId}/{episode}")
    suspend fun getSkipTimes(
        @Path("malId") malId: String,
        @Path("episode") episode: Int,
        @Query("types") types: List<String>,
        @Query("episodeLength") episodeLength: Int = 0
    ): Response<AniSkipResponse>
}

@JsonClass(generateAdapter = true)
data class AniSkipResponse(
    @Json(name = "found") val found: Boolean = false,
    @Json(name = "results") val results: List<AniSkipResult>? = null
)

@JsonClass(generateAdapter = true)
data class AniSkipResult(
    @Json(name = "interval") val interval: AniSkipInterval,
    @Json(name = "skipType") val skipType: String,
    @Json(name = "skipId") val skipId: String? = null
)

@JsonClass(generateAdapter = true)
data class AniSkipInterval(
    @Json(name = "startTime") val startTime: Double,
    @Json(name = "endTime") val endTime: Double
)

// --- ARM API (IMDB -> MAL ID resolution) ---

interface ArmApi {
    @GET("imdb")
    suspend fun resolveByImdb(
        @Query("id") imdbId: String,
        @Query("include") include: String = "myanimelist"
    ): Response<List<ArmEntry>>

    @GET("ids")
    suspend fun resolveByKitsu(
        @Query("source") source: String = "kitsu",
        @Query("id") kitsuId: String,
        @Query("include") include: String = "myanimelist"
    ): Response<ArmEntry>
}

@JsonClass(generateAdapter = true)
data class ArmEntry(
    @Json(name = "myanimelist") val myanimelist: Int? = null
)
