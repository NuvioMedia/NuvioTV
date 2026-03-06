package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.NextUpInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [PreviewProgram] and [WatchNextProgram] instances from domain models.
 */
@Singleton
class ProgramBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // ────────────────────────────────────────────────────────────────
    //  Continue Watching → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildContinueWatchingProgram(
        channelId: Long,
        progress: WatchProgress
    ): PreviewProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        }

        val description = if (!isMovie && progress.season != null && progress.episode != null) {
            buildString {
                append("S${progress.season}E${progress.episode}")
                progress.episodeTitle?.let { append(" · $it") }
            }
        } else {
            null
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(progress.name)
            .setInternalProviderId("cw_${progress.contentId}_${progress.videoId}")
            .setIntentUri(buildPlayUri(progress))
            .setLive(false)

        description?.let { builder.setDescription(it) }
        progress.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        progress.backdrop?.let { builder.setThumbnailUri(Uri.parse(it)) }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        // Progress bar on the card
        if (progress.duration > 0) {
            builder.setLastPlaybackPositionMillis(progress.position.toInt())
            builder.setDurationMillis(progress.duration.toInt())
        }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Next Up → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildNextUpProgram(
        channelId: Long,
        nextUp: NextUpInfo
    ): PreviewProgram {
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setTitle(nextUp.name)
            .setDescription("S${nextUp.season}E${nextUp.episode}" +
                    (nextUp.episodeTitle?.let { " · $it" } ?: ""))
            .setSeasonNumber(nextUp.season)
            .setEpisodeNumber(nextUp.episode)
            .setInternalProviderId("nu_${nextUp.contentId}_s${nextUp.season}e${nextUp.episode}")
            .setIntentUri(buildNextUpPlayUri(nextUp))
            .setLive(false)

        nextUp.episodeTitle?.let { builder.setEpisodeTitle(it) }
        nextUp.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        (nextUp.thumbnail ?: nextUp.backdrop)?.let { builder.setThumbnailUri(Uri.parse(it)) }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Trending → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildTrendingProgram(
        channelId: Long,
        item: MetaPreview
    ): PreviewProgram {
        val programType = when (item.type.toApiString()) {
            "series" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }

        val aspectRatio = when (item.posterShape) {
            PosterShape.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            PosterShape.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
            else -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(item.name)
            .setInternalProviderId("tr_${item.id}")
            .setIntentUri(buildDetailUri(item.id, item.type.toApiString()))
            .setPosterArtAspectRatio(aspectRatio)
            .setLive(false)

        item.description?.let { builder.setDescription(it) }
        item.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        item.background?.let { builder.setThumbnailUri(Uri.parse(it)) }
        item.releaseInfo?.let { builder.setReleaseDate(it) }
        item.genres.firstOrNull()?.let { builder.setGenre(it) }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Watch Next Row (system-managed row)
    // ────────────────────────────────────────────────────────────────

    fun buildWatchNextProgram(progress: WatchProgress): WatchNextProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        }

        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(progress.name)
            .setLastEngagementTimeUtcMillis(progress.lastWatched)
            .setInternalProviderId("wn_${progress.contentId}")
            .setIntentUri(buildPlayUri(progress))
        // Play Next row should natively present horizontal (16:9) backdrop cards.
        builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)

        // Prioritize backdrop (which is horizontal) over the vertical poster.
        val horizontalArt = progress.backdrop ?: progress.poster
        horizontalArt?.let {
            // Android TV caches Watch Next images heavily based on URI.
            // If the user was stuck on the vertical layout, we append a dummy query parameter
            // to trick the system launcher into fetching and rendering the new horizontal image.
            val uriWithCacheBuster = Uri.parse(it).buildUpon()
                .appendQueryParameter("v", "horizontal_fix")
                .build()
            builder.setPosterArtUri(uriWithCacheBuster)
        }

        if (progress.duration > 0) {
            builder.setLastPlaybackPositionMillis(progress.position.toInt())
            builder.setDurationMillis(progress.duration.toInt())
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Watch Next insert / update helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * Adds or updates a program in the system Watch Next row.
     */
    fun upsertWatchNextProgram(program: WatchNextProgram, internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId)
            if (existingId != null) {
                val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
                context.contentResolver.update(uri, program.toContentValues(), null, null)
            } else {
                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues()
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Removes a program from the Watch Next row by its internal provider id.
     */
    fun removeWatchNextProgram(internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId) ?: return
            val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Removes ALL Watch Next programs created by this app (identified by the "wn_" prefix).
     */
    fun clearAllWatchNextPrograms() {
        var cursor: android.database.Cursor? = null
        try {
            cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (idIdx >= 0) {
                        val providerId = it.getString(idIdx)
                        if (providerId?.startsWith("wn_") == true) {
                            val pkIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (pkIdx >= 0) {
                                val uri = TvContractCompat.buildWatchNextProgramUri(it.getLong(pkIdx))
                                context.contentResolver.delete(uri, null, null)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Deep-link URI builders
    // ────────────────────────────────────────────────────────────────

    private fun buildPlayUri(progress: WatchProgress): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_PLAY)
            .appendPath(progress.contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, progress.contentType)
            .appendQueryParameter(RecommendationConstants.PARAM_VIDEO_ID, progress.videoId)
            .appendQueryParameter(RecommendationConstants.PARAM_NAME, progress.name)
            .apply {
                progress.season?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_SEASON, it.toString())
                }
                progress.episode?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_EPISODE, it.toString())
                }
                appendQueryParameter(
                    RecommendationConstants.PARAM_RESUME_POSITION,
                    progress.position.toString()
                )
                progress.poster?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_POSTER, it)
                }
                progress.backdrop?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_BACKDROP, it)
                }
            }
            .build()

    private fun buildNextUpPlayUri(nextUp: NextUpInfo): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_PLAY)
            .appendPath(nextUp.contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, nextUp.contentType)
            .appendQueryParameter(RecommendationConstants.PARAM_VIDEO_ID, nextUp.videoId)
            .appendQueryParameter(RecommendationConstants.PARAM_NAME, nextUp.name)
            .appendQueryParameter(RecommendationConstants.PARAM_SEASON, nextUp.season.toString())
            .appendQueryParameter(RecommendationConstants.PARAM_EPISODE, nextUp.episode.toString())
            .apply {
                nextUp.poster?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_POSTER, it)
                }
                (nextUp.thumbnail ?: nextUp.backdrop)?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_BACKDROP, it)
                }
            }
            .build()

    private fun buildDetailUri(contentId: String, type: String): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_DETAIL)
            .appendPath(contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, type)
            .build()

    // ────────────────────────────────────────────────────────────────
    //  Watch Next query helper
    // ────────────────────────────────────────────────────────────────

    private fun findWatchNextByInternalId(internalId: String): Long? {
        return try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, 
                null, 
                null
            )
            var foundId: Long? = null
            cursor?.use {
                while (it.moveToNext()) {
                    val providerIdIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                    if (providerIdIdx >= 0) {
                        val currentProviderId = it.getString(providerIdIdx)
                        if (currentProviderId == internalId) {
                            val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (idIdx >= 0) {
                                foundId = it.getLong(idIdx)
                                break
                            }
                        }
                    }
                }
            }
            foundId
        } catch (_: Exception) {
            null
        }
    }
}
