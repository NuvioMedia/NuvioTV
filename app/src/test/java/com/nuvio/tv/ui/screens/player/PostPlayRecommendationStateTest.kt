package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayRecommendationStateTest {
    @Test
    fun `prefetches in final ten minutes`() {
        assertTrue(
            shouldPrefetchPostPlayRecommendation(
                positionMs = 6_600_000L,
                durationMs = 7_200_000L
            )
        )
    }

    @Test
    fun `does not prefetch early in playback`() {
        assertFalse(
            shouldPrefetchPostPlayRecommendation(
                positionMs = 1_800_000L,
                durationMs = 7_200_000L
            )
        )
    }

    @Test
    fun `countdown follows final playback seconds`() {
        assertEquals(5, postPlayRecommendationCountdownSeconds(95_200L, 100_000L))
        assertEquals(2, postPlayRecommendationCountdownSeconds(98_400L, 100_000L))
        assertEquals(1, postPlayRecommendationCountdownSeconds(100_000L, 100_000L))
    }

    @Test
    fun `countdown stays hidden before final five seconds`() {
        assertNull(postPlayRecommendationCountdownSeconds(94_900L, 100_000L))
    }

    @Test
    fun `loaded recommendation holds natural completion until overlay evaluation`() {
        val recommendation = PostPlayRecommendation(
            id = "tmdb:1",
            contentType = "movie",
            title = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            description = null,
            releaseInfo = null,
            rating = null,
            genres = emptyList(),
            runtime = null
        )

        assertTrue(PostPlayRecommendationUiState(recommendation = recommendation).blocksNaturalCompletion)
    }

    @Test
    fun `active seek preview blocks the recommendation until release`() {
        val seeking = PlayerUiState(pendingPreviewSeekPosition = 95_000L)

        assertTrue(seeking.blocksPostPlayRecommendation())
        assertFalse(
            seeking.copy(pendingPreviewSeekPosition = null).blocksPostPlayRecommendation()
        )
    }

    @Test
    fun `post play returns to player only while its window is available`() {
        val state = PostPlayRecommendationUiState(isVisible = true)
        val returned = state.returnToPlayer()

        assertTrue(state.canReturnToPlayer)
        assertFalse(state.copy(isTrailerPlaying = true).canReturnToPlayer)
        assertFalse(state.copy(hasAutoPlayedTrailer = true).canReturnToPlayer)
        assertFalse(state.copy(isVisible = false).canReturnToPlayer)
        assertFalse(returned.isVisible)
        assertTrue(returned.hasReturnedToPlayer)
        assertTrue(returned.blocksNaturalCompletion)
    }

    @Test
    fun `recommendation navigation follows pipeline bounds`() {
        val first = PostPlayRecommendationUiState(
            recommendationIndex = 0,
            recommendationCount = 3
        )
        val middle = first.copy(recommendationIndex = 1)
        val last = first.copy(recommendationIndex = 2)
        val changing = middle.copy(isChangingRecommendation = true)

        assertFalse(first.canNavigatePrevious)
        assertTrue(first.canNavigateNext)
        assertTrue(middle.canNavigatePrevious)
        assertTrue(middle.canNavigateNext)
        assertTrue(last.canNavigatePrevious)
        assertFalse(last.canNavigateNext)
        assertFalse(changing.canNavigatePrevious)
        assertFalse(changing.canNavigateNext)
    }

    @Test
    fun `trailer action requires in app trailer playback`() {
        val recommendation = recommendation(trailerVideoUrl = "https://video/trailer.m3u8")

        assertTrue(
            shouldShowPostPlayTrailerAction(
                recommendation = recommendation,
                isTrailerPlaying = false,
                inAppTrailerPlaybackEnabled = true
            )
        )
        assertFalse(
            shouldShowPostPlayTrailerAction(
                recommendation = recommendation,
                isTrailerPlaying = false,
                inAppTrailerPlaybackEnabled = false
            )
        )
    }

    @Test
    fun `trailer action stays hidden without an idle trailer`() {
        assertFalse(
            shouldShowPostPlayTrailerAction(
                recommendation = recommendation(),
                isTrailerPlaying = false,
                inAppTrailerPlaybackEnabled = true
            )
        )
        assertFalse(
            shouldShowPostPlayTrailerAction(
                recommendation = recommendation(trailerVideoUrl = "https://video/trailer.m3u8"),
                isTrailerPlaying = true,
                inAppTrailerPlaybackEnabled = true
            )
        )
    }

    @Test
    fun `resolved recommendation uses detail enrichment artwork and title`() {
        val recommendation = resolvePostPlayRecommendation(
            candidate = preview(
                background = "https://image/candidate-backdrop.jpg",
                logo = null
            ),
            meta = null,
            enrichment = enrichment(
                title = "Localized title",
                backdrop = "https://image/detail-backdrop.jpg",
                logo = "https://image/title-logo.png"
            ),
            settings = TmdbSettings(
                enabled = true,
                useArtwork = true,
                useBasicInfo = true,
                useDetails = true,
                useReleaseDates = true
            ),
            tmdbId = "42"
        )

        assertEquals("Localized title", recommendation.title)
        assertEquals("Localized description", recommendation.description)
        assertEquals("https://image/detail-backdrop.jpg", recommendation.backdrop)
        assertEquals("https://image/title-logo.png", recommendation.logo)
        assertEquals("42", recommendation.tmdbId)
        assertEquals("en", recommendation.contentLanguage)
    }

    @Test
    fun `resolved recommendation respects disabled artwork enrichment`() {
        val recommendation = resolvePostPlayRecommendation(
            candidate = preview(
                background = "https://image/addon-backdrop.jpg",
                logo = "https://image/addon-logo.png"
            ),
            meta = null,
            enrichment = enrichment(
                title = "Localized title",
                backdrop = "https://image/tmdb-backdrop.jpg",
                logo = "https://image/tmdb-logo.png"
            ),
            settings = TmdbSettings(enabled = true, useArtwork = false),
            tmdbId = "42"
        )

        assertEquals("https://image/addon-backdrop.jpg", recommendation.backdrop)
        assertEquals("https://image/addon-logo.png", recommendation.logo)
    }

    @Test
    fun `movies always use recommendation post play`() {
        assertTrue(
            shouldUsePostPlayRecommendation(
                contentType = "movie",
                isNextEpisodeMetadataResolved = false,
                nextEpisodeHasAired = null
            )
        )
    }

    @Test
    fun `post play recommendations default on and respect the setting`() {
        assertTrue(PlayerSettings().postPlayRecommendationsEnabled)
        assertFalse(
            shouldUsePostPlayRecommendation(
                contentType = "movie",
                isNextEpisodeMetadataResolved = false,
                nextEpisodeHasAired = null,
                enabled = false
            )
        )
    }

    @Test
    fun `series use recommendations after an unaired next episode is resolved`() {
        assertTrue(
            shouldUsePostPlayRecommendation(
                contentType = "series",
                isNextEpisodeMetadataResolved = true,
                nextEpisodeHasAired = false
            )
        )
    }

    @Test
    fun `ended series use recommendations after metadata resolves without a next episode`() {
        assertTrue(
            shouldUsePostPlayRecommendation(
                contentType = "tv",
                isNextEpisodeMetadataResolved = true,
                nextEpisodeHasAired = null
            )
        )
    }

    @Test
    fun `series keep the episode post play flow when the next episode has aired`() {
        assertFalse(
            shouldUsePostPlayRecommendation(
                contentType = "series",
                isNextEpisodeMetadataResolved = true,
                nextEpisodeHasAired = true
            )
        )
    }

    @Test
    fun `series wait for episode metadata before using recommendations`() {
        assertFalse(
            shouldUsePostPlayRecommendation(
                contentType = "series",
                isNextEpisodeMetadataResolved = false,
                nextEpisodeHasAired = null
            )
        )
    }

    @Test
    fun `series api types resolve to series metadata requests`() {
        assertEquals(ContentType.SERIES, resolvePostPlayContentType("series"))
        assertEquals(ContentType.SERIES, resolvePostPlayContentType("tv"))
        assertEquals(ContentType.SERIES, resolvePostPlayContentType("show"))
    }

    @Test
    fun `post play candidates use watched status for their content type`() {
        val movie = preview(id = "tmdb:42", type = ContentType.MOVIE)
        val series = preview(id = "tmdb:84", type = ContentType.SERIES)

        assertTrue(
            isPostPlayCandidateWatched(
                candidate = movie,
                watchedMovieIds = setOf(movie.id),
                watchedSeriesIds = emptySet()
            )
        )
        assertTrue(
            isPostPlayCandidateWatched(
                candidate = series,
                watchedMovieIds = emptySet(),
                watchedSeriesIds = setOf(series.id)
            )
        )
        assertFalse(
            isPostPlayCandidateWatched(
                candidate = series,
                watchedMovieIds = setOf(series.id),
                watchedSeriesIds = emptySet()
            )
        )
    }

    @Test
    fun `post play candidates recognize watched imdb aliases`() {
        val movie = preview(
            id = "tmdb:42",
            type = ContentType.MOVIE,
            imdbId = "tt0000042"
        )

        assertTrue(
            isPostPlayCandidateWatched(
                candidate = movie,
                watchedMovieIds = setOf("tt0000042"),
                watchedSeriesIds = emptySet()
            )
        )
    }

    private fun recommendation(trailerVideoUrl: String? = null): PostPlayRecommendation {
        return PostPlayRecommendation(
            id = "tmdb:1",
            contentType = "movie",
            title = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            description = null,
            releaseInfo = null,
            rating = null,
            genres = emptyList(),
            runtime = null,
            trailerVideoUrl = trailerVideoUrl
        )
    }

    private fun preview(
        background: String = "https://image/candidate-backdrop.jpg",
        logo: String? = null,
        id: String = "tmdb:42",
        type: ContentType = ContentType.MOVIE,
        imdbId: String? = null
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = "Candidate title",
            poster = "https://image/poster.jpg",
            posterShape = PosterShape.LANDSCAPE,
            background = background,
            logo = logo,
            description = "Candidate description",
            releaseInfo = "2025",
            imdbRating = 7.5f,
            genres = listOf("Drama"),
            runtime = "120",
            imdbId = imdbId
        )
    }

    private fun enrichment(
        title: String,
        backdrop: String,
        logo: String
    ): TmdbEnrichment {
        return TmdbEnrichment(
            localizedTitle = title,
            description = "Localized description",
            genres = listOf("Thriller"),
            backdrop = backdrop,
            logo = logo,
            poster = "https://image/tmdb-poster.jpg",
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = "2026",
            rating = 8.2,
            runtimeMinutes = 118,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = "PG-13",
            status = "Released",
            countries = listOf("US"),
            language = "en",
            collectionId = null,
            collectionName = null
        )
    }
}
