package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
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
        if (horizontalArt != null && progress.duration > 0) {
            val file = createProgressImage(horizontalArt, progress)
            if (file != null) {
                finalArtUri = Uri.parse("content://${context.packageName}.tvimages/${file.name}")
            }
        }
        
        if (finalArtUri == null && horizontalArt != null) {
            finalArtUri = Uri.parse(horizontalArt)
        }
        finalArtUri?.let { builder.setPosterArtUri(it) }
        progress.poster?.let { builder.setThumbnailUri(Uri.parse(it)) }

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

    private suspend fun createProgressImage(url: String, progress: WatchProgress): java.io.File? {
        try {
            val loader = coil.ImageLoader(context)
            val request = coil.request.ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
                
            val result = loader.execute(request)
            if (result is coil.request.SuccessResult) {
                val dr = result.drawable
                val original = if (dr is android.graphics.drawable.BitmapDrawable) {
                    dr.bitmap 
                } else {
                    val fallback = android.graphics.Bitmap.createBitmap(dr.intrinsicWidth.coerceAtLeast(1), dr.intrinsicHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                    val canvasFallback = android.graphics.Canvas(fallback)
                    dr.setBounds(0, 0, canvasFallback.width, canvasFallback.height)
                    dr.draw(canvasFallback)
                    fallback
                }
                val bitmap = original.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(bitmap)
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                
                val pct = (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
                val marginX = w * 0.04f // slightly larger horizontal padding
                val marginBottom = h * 0.04f // pushed up slightly more from the bottom
                val barHeight = h * 0.035f // noticeably thicker height
                val left = marginX
                val right = w - marginX
                val bottom = h - marginBottom
                val top = bottom - barHeight
                val radius = barHeight / 2f
                
                // --------- Draw bottom gradient overlay for readability ---------
                val gradientBgParams = intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.parseColor("#B3000000"), // 70% black
                    android.graphics.Color.parseColor("#F2000000")  // 95% black
                )
                val gradientPositions = floatArrayOf(0f, 0.5f, 1f)
                
                val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, h * 0.6f, 
                        0f, h, 
                        gradientBgParams, gradientPositions, 
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, h * 0.6f, w, h, shadowPaint)
                
                val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#4D000000") // Black 30% alpha
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)
                
                val fgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = (0xFF9E9E9E).toInt() // NuvioColors.Primary
                    style = android.graphics.Paint.Style.FILL
                }
                
                // Draw clipped right edge for progress rect
                val progressRight = left + (right - left) * pct
                // Ensure a minimum width so radius can be drawn without visual bugs
                if (progressRight > left + radius) {
                    canvas.drawRoundRect(left, top, progressRight, bottom, radius, radius, fgPaint)
                }

                // --------- Draw "41m left" badge in top right ---------
                val remainingMs = progress.duration - progress.position
                val totalMinutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                
                val badgeText = when {
                    hours > 0 -> context.getString(com.nuvio.tv.R.string.cw_hours_min_left, hours, minutes)
                    minutes > 0 -> context.getString(com.nuvio.tv.R.string.cw_min_left, minutes)
                    else -> context.getString(com.nuvio.tv.R.string.cw_almost_done)
                }
                
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = (0xFFEAEAEA).toInt() // NuvioColors.TextPrimary approx
                    textSize = h * 0.057f // Small label text size 
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                }
                
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(badgeText, 0, badgeText.length, textBounds)
                
                // Match Compose padding: `padding(horizontal = 8.dp, vertical = 4.dp)`
                val badgePadX = w * 0.02f // Approx 8dp
                val badgePadY = w * 0.01f // Approx 4dp
                
                // Match Compose margin: `padding(8.dp)` outside
                val badgeMargin = w * 0.02f
                val badgeRight = w - badgeMargin
                val badgeTop = badgeMargin
                
                val badgeWidth = textBounds.width() + badgePadX * 2
                val badgeHeight = textBounds.height() + badgePadY * 2
                val badgeLeft = badgeRight - badgeWidth
                val badgeBottom = badgeTop + badgeHeight
                
                val badgeBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#CC141414") // NuvioColors.Background approx with 80% opacity
                    style = android.graphics.Paint.Style.FILL
                }
                
                // Match Compose generic badge radius shape: RoundedCornerShape(4.dp)
                val badgeRadius = w * 0.01f 
                canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, badgeRadius, badgeRadius, badgeBgPaint)
                
                val textX = badgeLeft + badgePadX
                val textY = badgeBottom - badgePadY - textBounds.bottom
                canvas.drawText(badgeText, textX, textY, textPaint)
                
                val pctInt = (pct * 100).toInt()
                val cacheDir = java.io.File(context.cacheDir, "tv_progress")
                cacheDir.mkdirs()
                
                // Cleanup old files for this specific content to avoid bloating storage
                cacheDir.listFiles { _, name -> 
                    name.startsWith("progress_${progress.contentId}_${progress.videoId}") 
                }?.forEach { it.delete() }
                
                // Add timestamp / pct to filename so Android TV Launcher invalidates its image cache
                val finalName = "progress_${progress.contentId}_${progress.videoId}_${pctInt}_${System.currentTimeMillis()}.jpg"
                val outFile = java.io.File(cacheDir, finalName)
                java.io.FileOutputStream(outFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                return outFile
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
        return null
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
