package com.omnio.tv.domain.tmdb

interface TmdbService {

    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int?

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String?

    suspend fun ensureTmdbId(videoId: String, mediaType: String): String?

    fun clearCache()

    fun preCacheMapping(imdbId: String, tmdbId: Int)

    fun apiKey(): String
}
