package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    
    @GET("find/{external_id}")
    suspend fun findByExternalId(
        @Path("external_id") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id"
    ): Response<TmdbFindResponse>
    
    @GET("movie/{movie_id}/external_ids")
    suspend fun getMovieExternalIds(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>
    
    @GET("tv/{tv_id}/external_ids")
    suspend fun getTvExternalIds(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): Response<TmdbVideosResponse>

    @GET("tv/{tv_id}/videos")
    suspend fun getTvVideos(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): Response<TmdbVideosResponse>

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    @GET("movie/{movie_id}/credits")
    suspend fun getMovieCredits(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCreditsResponse>

    @GET("tv/{tv_id}/credits")
    suspend fun getTvCredits(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCreditsResponse>

    @GET("movie/{movie_id}/images")
    suspend fun getMovieImages(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("tv/{tv_id}/images")
    suspend fun getTvImages(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("movie/{movie_id}/release_dates")
    suspend fun getMovieReleaseDates(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbMovieReleaseDatesResponse>

    @GET("tv/{tv_id}/content_ratings")
    suspend fun getTvContentRatings(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbTvContentRatingsResponse>

    @GET("movie/{movie_id}/recommendations")
    suspend fun getMovieRecommendations(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("tv/{tv_id}/recommendations")
    suspend fun getTvRecommendations(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeasonDetails(
        @Path("tv_id") tvId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbSeasonResponse>

    @GET("person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonResponse>

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCombinedCredits(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonCreditsResponse>
}

@JsonClass(generateAdapter = true)
data class TmdbFindResponse(
    @Json(name = "movie_results") val movieResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_results") val tvResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_episode_results") val tvEpisodeResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_season_results") val tvSeasonResults: List<TmdbFindResult>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbFindResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "media_type") val mediaType: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbExternalIdsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "tvdb_id") val tvdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbVideosResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "results") val results: List<TmdbVideoResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbVideoResult(
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "key") val key: String? = null,
    @Json(name = "site") val site: String? = null,
    @Json(name = "size") val size: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "official") val official: Boolean? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "id") val id: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDetailsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genres") val genres: List<TmdbGenre>? = null,
    @Json(name = "created_by") val createdBy: List<TmdbCreatedBy>? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "production_companies") val productionCompanies: List<TmdbCompany>? = null,
    @Json(name = "networks") val networks: List<TmdbNetwork>? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbCountry>? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCreatedBy(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbCompany(
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbNetwork(
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbCastMember>? = null,
    @Json(name = "crew") val crew: List<TmdbCrewMember>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCastMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCrewMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "job") val job: String? = null,
    @Json(name = "department") val department: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImagesResponse(
    @Json(name = "logos") val logos: List<TmdbImage>? = null,
    @Json(name = "backdrops") val backdrops: List<TmdbImage>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDatesResponse(
    @Json(name = "results") val results: List<TmdbMovieReleaseDateCountry>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDateCountry(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "release_dates") val releaseDates: List<TmdbMovieReleaseDateItem>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDateItem(
    @Json(name = "certification") val certification: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRatingsResponse(
    @Json(name = "results") val results: List<TmdbTvContentRatingItem>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRatingItem(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "rating") val rating: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImage(
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCountry(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonResponse(
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episodes") val episodes: List<TmdbEpisode>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendationsResponse(
    @Json(name = "results") val results: List<TmdbRecommendationResult>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendationResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null
)

// ── Person / Cast Detail DTOs ──

@JsonClass(generateAdapter = true)
data class TmdbPersonResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "biography") val biography: String? = null,
    @Json(name = "birthday") val birthday: String? = null,
    @Json(name = "deathday") val deathday: String? = null,
    @Json(name = "place_of_birth") val placeOfBirth: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "also_known_as") val alsoKnownAs: List<String>? = null,
    @Json(name = "imdb_id") val imdbId: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbPersonCreditCast>? = null,
    @Json(name = "crew") val crew: List<TmdbPersonCreditCrew>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditCast(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditCrew(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "job") val job: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)
