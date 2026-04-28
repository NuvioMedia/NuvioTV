package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewProgram
import coil3.imageLoader
import coil3.request.allowHardware
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.formatContinueWatchingProgressLabel
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

    suspend fun buildContinueWatchingProgram(
        channelId: Long,
        progress: WatchProgress
    ): PreviewProgram = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        }

        var description = if (!isMovie && progress.season != null && progress.episode != null) {
            buildString {
                append("S${progress.season}E${progress.episode}")
                progress.episodeTitle?.let { append(" · $it") }
            }
        } else {
            ""
        }

        // Android TV Launcher explicitly hides the visual red progress bar for PreviewPrograms 
        // (it's restricted to WatchNextPrograms). We inject a textual progress indicator here instead.
        if (progress.duration > 0) {
            val percent = (progress.position.toFloat() / progress.duration * 100).toInt().coerceIn(0, 100)
            val remainingMs = progress.duration - progress.position
            val remainingMin = (remainingMs / 60000).coerceAtLeast(1)
            
            val progressInfo = if (remainingMin > 0 && percent < 95) {
                "▶ %$percent (${remainingMin}m)"
            } else {
                "▶ %$percent"
            }
            description = if (description.isEmpty()) progressInfo else "$description  •  $progressInfo"
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(progress.name)
            .setInternalProviderId("cw_${progress.contentId}_${progress.videoId}")
            .setIntentUri(buildPlayUri(progress))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setLive(false)

        if (description.isNotEmpty()) {
            builder.setDescription(description)
        }

        // Play Next row natively presents horizontal (16:9) backdrop cards.
        val horizontalArt = progress.backdrop ?: progress.poster
        var finalArtUri: Uri? = null
        if (horizontalArt != null) {
            val badgeText = formatContinueWatchingProgressLabel(
                progress = progress,
                resumeLabel = context.getString(com.nuvio.tv.R.string.cw_resume),
                percentWatchedLabel = context.getString(com.nuvio.tv.R.string.cw_percent_watched),
                hoursMinLeftLabel = context.getString(com.nuvio.tv.R.string.cw_hours_min_left),
                minLeftLabel = context.getString(com.nuvio.tv.R.string.cw_min_left)
            )
            val fraction = progress.progressPercentage.takeIf { it > 0f }
            val file = createCwOverlayImage(
                url = horizontalArt,
                badgeText = badgeText,
                badgeBgColor = CW_BADGE_DEFAULT_BG,
                progressFraction = fraction,
                logoUrl = progress.logo,
                cachePrefix = "progress_${progress.contentId}_${progress.videoId}"
            )
            if (file != null) {
                finalArtUri = Uri.parse("content://${context.packageName}.tvimages/${file.name}")
            }
        }
        
        if (finalArtUri == null && horizontalArt != null) {
            finalArtUri = Uri.parse(horizontalArt)
        }
        finalArtUri?.let {
            builder.setPosterArtUri(it)
            builder.setThumbnailUri(it)
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        // We do not set position/duration here because otherwise 
        // third-party Android TV launchers (or even Google TV) will render their 
        // own native red progress bars, which conflict with our canvas-drawn ones.

        return@withContext builder.build()
    }

    private suspend fun createCwOverlayImage(
        url: String,
        badgeText: String,
        badgeBgColor: Int,
        progressFraction: Float?,
        logoUrl: String?,
        cachePrefix: String
    ): java.io.File? {
        try {
            val request = coil3.request.ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()

            val result = context.imageLoader.execute(request)
            if (result is coil3.request.SuccessResult) {
                val original = (result.image as? coil3.BitmapImage)?.bitmap ?: return null
                val bitmap = original.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(bitmap)
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()

                val gradientBgParams = intArrayOf(
                    0x000D0D0D,
                    0xB30D0D0D.toInt(),
                    0xF20D0D0D.toInt()
                )
                val gradientPositions = floatArrayOf(0f, 0.6f, 1f)
                val shadowStartY = h * 0.45f
                val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, shadowStartY, 0f, h,
                        gradientBgParams, gradientPositions,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, shadowStartY, w, h, shadowPaint)

                // Treat the bitmap as if it were the 288×162 dp Continue Watching card,
                // so launcher-side downscaling preserves the same legibility as in-app.
                val dpScale = w / 288f

                if (!logoUrl.isNullOrBlank()) {
                    val logoRequest = coil3.request.ImageRequest.Builder(context)
                        .data(logoUrl)
                        .allowHardware(false)
                        .build()
                    val logoResult = context.imageLoader.execute(logoRequest)
                    if (logoResult is coil3.request.SuccessResult) {
                        val logoBitmap = (logoResult.image as? coil3.BitmapImage)?.bitmap
                        if (logoBitmap != null) {
                            val logoBoxWidth = 140f * dpScale
                            val logoBoxHeight = 48f * dpScale
                            val logoAspect = logoBitmap.width.toFloat() / logoBitmap.height.toFloat()
                            val logoWidth: Float
                            val logoHeight: Float
                            if (logoAspect > logoBoxWidth / logoBoxHeight) {
                                logoWidth = logoBoxWidth
                                logoHeight = logoBoxWidth / logoAspect
                            } else {
                                logoHeight = logoBoxHeight
                                logoWidth = logoBoxHeight * logoAspect
                            }
                            val logoLeft = 12f * dpScale
                            val barReservedSpace = if (progressFraction != null) 16f * dpScale else 12f * dpScale
                            val logoBottom = h - barReservedSpace
                            val logoTop = logoBottom - logoHeight
                            val dst = android.graphics.RectF(logoLeft, logoTop, logoLeft + logoWidth, logoBottom)
                            canvas.drawBitmap(logoBitmap, null, dst, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                isFilterBitmap = true
                            })
                        }
                    }
                }

                if (progressFraction != null) {
                    val pct = progressFraction.coerceIn(0f, 1f)
                    val marginX = 10f * dpScale
                    val marginBottom = 4f * dpScale
                    val barHeight = 3f * dpScale
                    val left = marginX
                    val right = w - marginX
                    val bottom = h - marginBottom
                    val top = bottom - barHeight
                    val radius = barHeight / 2f

                    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x4D000000
                        style = android.graphics.Paint.Style.FILL
                    }
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

                    val fgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF9E9E9E.toInt()
                        style = android.graphics.Paint.Style.FILL
                    }
                    val progressRight = left + (right - left) * pct
                    if (progressRight > left + radius) {
                        canvas.drawRoundRect(left, top, progressRight, bottom, radius, radius, fgPaint)
                    }
                }

                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 14f * dpScale
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }

                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(badgeText, 0, badgeText.length, textBounds)

                val badgePadX = 10f * dpScale
                val badgePadY = 6f * dpScale
                val badgeMargin = 10f * dpScale
                val badgeRight = w - badgeMargin
                val badgeTop = badgeMargin

                val badgeWidth = textBounds.width() + badgePadX * 2
                val badgeHeight = textBounds.height() + badgePadY * 2
                val badgeLeft = badgeRight - badgeWidth
                val badgeBottom = badgeTop + badgeHeight

                val badgeBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = badgeBgColor
                    style = android.graphics.Paint.Style.FILL
                }
                val badgeRadius = 6f * dpScale
                canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, badgeRadius, badgeRadius, badgeBgPaint)

                val textX = badgeLeft + badgePadX
                val textY = badgeBottom - badgePadY - textBounds.bottom
                canvas.drawText(badgeText, textX, textY, textPaint)

                val cacheDir = java.io.File(context.cacheDir, "tv_progress")
                cacheDir.mkdirs()
                cacheDir.listFiles { _, name -> name.startsWith(cachePrefix) }?.forEach { it.delete() }

                val finalName = "${cachePrefix}_${System.currentTimeMillis()}.jpg"
                val outFile = java.io.File(cacheDir, finalName)
                java.io.FileOutputStream(outFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                return outFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  Next Up → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    suspend fun buildNextUpProgram(
        channelId: Long,
        nextUp: NextUpInfo
    ): PreviewProgram = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setLive(false)

        nextUp.episodeTitle?.let { builder.setEpisodeTitle(it) }

        val badgeText = when {
            nextUp.isReleaseAlert && nextUp.isNewSeasonRelease ->
                context.getString(com.nuvio.tv.R.string.cw_new_season)
            nextUp.isReleaseAlert ->
                context.getString(com.nuvio.tv.R.string.cw_new_episode)
            !nextUp.hasAired -> nextUp.airDateLabel
                ?.let { context.getString(com.nuvio.tv.R.string.cw_airs_date, it) }
                ?: context.getString(com.nuvio.tv.R.string.cw_upcoming)
            else -> context.getString(com.nuvio.tv.R.string.cw_next_up)
        }
        val badgeBg = when {
            nextUp.isNewSeasonRelease -> CW_BADGE_NEW_SEASON_BG
            nextUp.isReleaseAlert -> CW_BADGE_NEW_EPISODE_BG
            else -> CW_BADGE_DEFAULT_BG
        }

        val horizontalArt = nextUp.backdrop ?: nextUp.thumbnail ?: nextUp.poster
        var finalArtUri: Uri? = null
        if (horizontalArt != null) {
            val file = createCwOverlayImage(
                url = horizontalArt,
                badgeText = badgeText,
                badgeBgColor = badgeBg,
                progressFraction = null,
                logoUrl = nextUp.logo,
                cachePrefix = "nextup_${nextUp.contentId}_s${nextUp.season}e${nextUp.episode}"
            )
            if (file != null) {
                finalArtUri = Uri.parse("content://${context.packageName}.tvimages/${file.name}")
            }
        }
        if (finalArtUri == null && horizontalArt != null) {
            finalArtUri = Uri.parse(horizontalArt)
        }
        finalArtUri?.let {
            builder.setPosterArtUri(it)
            builder.setThumbnailUri(it)
        }

        return@withContext builder.build()
    }

    private companion object {
        const val CW_BADGE_DEFAULT_BG = 0xCC0D0D0D.toInt()
        const val CW_BADGE_NEW_EPISODE_BG = 0xFF1D4ED8.toInt()
        const val CW_BADGE_NEW_SEASON_BG = 0xFFB45309.toInt()
    }

    // ────────────────────────────────────────────────────────────────
    //  Catalog Item → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildTrendingProgram(
        channelId: Long,
        item: MetaPreview,
        useWidePoster: Boolean
    ): PreviewProgram {
        val programType = when (item.type.toApiString()) {
            "series" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }

        val aspectRatio = if (useWidePoster) {
            TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
        } else {
            when (item.posterShape) {
                PosterShape.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                PosterShape.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
                else -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            }
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

        if (useWidePoster) {
            val horizontalArt = item.background ?: item.poster
            horizontalArt?.let { builder.setPosterArtUri(Uri.parse(it)) }
        } else {
            item.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
            item.background?.let { builder.setThumbnailUri(Uri.parse(it)) }
        }

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
