package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.calendar.CalendarEpisodeBuilder
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarRecommendation
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.repository.LibraryRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class CalendarContent(
    val days: List<CalendarDay>,
    val recommendations: List<CalendarRecommendation>
)

@Singleton
class CalendarRepository @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService
) {
    suspend fun load(today: LocalDate = LocalDate.now()): CalendarContent {
        val library = libraryRepository.libraryItems.first()
        val shows = library.filter { it.type.equals("series", true) || it.type.equals("tv", true) }
        val resolved = resolveShows(shows)
        val days = loadEpisodes(resolved, today)
        val recommendations = loadRecommendations(resolved, library)
        return CalendarContent(days, recommendations)
    }

    private suspend fun resolveShows(shows: List<LibraryEntry>): List<Pair<LibraryEntry, Int>> = coroutineScope {
        val semaphore = Semaphore(4)
        shows.map { show ->
            async {
                semaphore.withPermit {
                    val id = show.tmdbId ?: tmdbService.ensureTmdbId(show.imdbId ?: show.id, "series")?.toIntOrNull()
                    id?.let { show to it }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun loadEpisodes(
        shows: List<Pair<LibraryEntry, Int>>,
        today: LocalDate
    ): List<CalendarDay> = coroutineScope {
        val semaphore = Semaphore(4)
        val episodes = shows.map { (show, tmdbId) ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val details = tmdbApi.getTvDetails(tmdbId, BuildConfig.TMDB_API_KEY, "en-US").body()
                        val season = details?.nextEpisodeToAir?.seasonNumber ?: return@runCatching emptyList()
                        val seasonEpisodes = tmdbApi.getTvSeasonDetails(
                            tmdbId, season, BuildConfig.TMDB_API_KEY, "en-US"
                        ).body()?.episodes.orEmpty()
                        CalendarEpisodeBuilder.build(show, tmdbId, season, seasonEpisodes, today)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
        CalendarEpisodeBuilder.groupByDay(episodes)
    }

    private suspend fun loadRecommendations(
        shows: List<Pair<LibraryEntry, Int>>,
        library: List<LibraryEntry>
    ): List<CalendarRecommendation> = coroutineScope {
        val savedTmdbIds = library.mapNotNull { it.tmdbId }.toSet()
        val semaphore = Semaphore(3)
        val candidates = shows.take(8).map { (show, tmdbId) ->
            async {
                semaphore.withPermit {
                    runCatching {
                        tmdbMetadataService.fetchMoreLikeThis(tmdbId.toString(), ContentType.SERIES, maxItems = 10)
                            .map { it to show.name }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()

        candidates
            .filterNot { (item, _) -> item.id.removePrefix("tmdb:").toIntOrNull() in savedTmdbIds }
            .groupBy { it.first.id }
            .values
            .map { matches ->
                CalendarRecommendation(
                    item = matches.first().first,
                    recommendedBy = matches.map { it.second }.distinct().take(3),
                    score = matches.size
                )
            }
            .sortedWith(compareByDescending<CalendarRecommendation> { it.score }.thenBy { it.item.name })
            .take(20)
    }
}
