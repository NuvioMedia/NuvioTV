package com.nuvio.tv.data.wetrakr

interface WeTrakrSyncRemote {
    suspend fun activities(): WeTrakrActivities
    suspend fun watched(type: WeTrakrItemType, since: String?): List<WeTrakrWatchedRecord>
    suspend fun playback(type: WeTrakrItemType): List<WeTrakrPlayback>
    suspend fun watchlist(type: WeTrakrItemType): List<WeTrakrWatchlistItem>
}

class WeTrakrHttpSyncRemote(
    private val api: WeTrakrApiClient,
    private val scope: WeTrakrAuthScope
) : WeTrakrSyncRemote {
    override suspend fun activities(): WeTrakrActivities =
        decodeWeTrakrActivities(api.get("/sync/last_activities", scope = scope).body)

    override suspend fun watched(type: WeTrakrItemType, since: String?): List<WeTrakrWatchedRecord> {
        val path = "/sync/tracking/watched/${target(type)}"
        val initial = buildMap {
            put("compact", "true")
            put("limit", "5000")
            since?.let { put("from_date", it) }
        }
        val results = mutableListOf<WeTrakrWatchedRecord>()
        val visited = mutableSetOf<String>()
        var query = initial
        repeat(MAX_PAGES) {
            val response = api.get(path, query, scope)
            results += decodeWeTrakrWatched(response.body, type)
            val next = response.header("X-Pagination-Next")?.trim()?.takeIf(String::isNotBlank)
                ?: return results
            if (!visited.add(next)) throw WeTrakrDecodingException()
            query = initial + ("after" to next)
        }
        throw WeTrakrDecodingException()
    }

    override suspend fun playback(type: WeTrakrItemType): List<WeTrakrPlayback> =
        pages(
            "/sync/tracking/playing/${target(type)}",
            if (type == WeTrakrItemType.MOVIE) "movie_level_1" else "show_level_1,episode_level_1"
        ) { decodeWeTrakrPlayback(it, type) }

    override suspend fun watchlist(type: WeTrakrItemType): List<WeTrakrWatchlistItem> =
        pages(
            "/sync/tracking/planning/${target(type)}",
            if (type == WeTrakrItemType.MOVIE) "movie_level_1" else "show_level_1"
        ) { decodeWeTrakrWatchlist(it, type) }

    private suspend fun <T> pages(path: String, extended: String, decode: (String) -> List<T>): List<T> {
        val results = mutableListOf<T>()
        var page = 1
        repeat(MAX_PAGES) {
            val response = api.get(
                path,
                mapOf("page" to page.toString(), "limit" to "100", "extended" to extended),
                scope
            )
            results += decode(response.body)
            val pageCount = response.header("X-Pagination-Page-Count")?.trim()?.toIntOrNull() ?: return results
            if (page >= pageCount) return results
            page += 1
        }
        throw WeTrakrDecodingException()
    }

    private fun target(type: WeTrakrItemType): String = when (type) {
        WeTrakrItemType.MOVIE -> "movies"
        WeTrakrItemType.SHOW -> "shows"
        WeTrakrItemType.EPISODE -> "episodes"
    }

    private companion object {
        const val MAX_PAGES = 500
    }
}
