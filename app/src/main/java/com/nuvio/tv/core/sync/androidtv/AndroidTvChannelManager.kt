package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
) {
    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /**
     * Returns the channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        runCatching {
            val stored = prefs.getChannelId()
            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null, null, null
                )
                cursor?.use { if (it.moveToFirst()) return@runCatching stored }
                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId()
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || providerIdx < 0) return@use null
                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (providerId != null && providerId.startsWith(context.packageName)) {
                        return@use c.getLong(idIdx)
                    }
                }
                null
            }
            if (orphan != null) {
                Log.d(TAG, "Reusing orphaned channel $orphan")
                prefs.setChannelId(orphan)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val appLinkUri = Uri.parse(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.toUri(Intent.URI_INTENT_SCHEME)
            )
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(R.string.tv_channel_continue_watching))
                .setAppLinkIntentUri(appLinkUri)
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val id = ContentUris.parseId(inserted)
            prefs.setChannelId(id)
            writeChannelLogo(id)
            TvContractCompat.requestChannelBrowsable(context, id)
            Log.d(TAG, "Created channel id=$id")
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(items: List<WatchProgress>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureChannel() ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { progressKey(it) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                }
            }

            items.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val values = buildProgramValues(progress, channelId, index, key)
                val rowIds = existing[key]
                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    // UPDATE the existing program row in place — keeping its row ID stable.
                    // This mirrors the canonical androidx PreviewChannelHelper.updatePreviewProgram
                    // approach (which Stremio uses and which launchers like Projectivy repaint
                    // on). The previous delete+re-insert of every program on every reconcile
                    // churned the provider and left launchers showing a stale cached view until
                    // the channel's visibility was toggled. buildProgramValues() explicitly nulls
                    // absent columns, so updates don't leave stale artwork/position behind.
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId), values, null, null
                    )
                    // If there are duplicate rows for the same key, delete them to clean up the database.
                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getChannelId() ?: return@runCatching
            val rows = queryExistingPrograms(channelId)
            var deletedCount = 0
            rows.values.flatten().forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId), null, null
                )
                deletedCount++
            }
            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    // ---

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )
        val result = mutableMapOf<String, MutableList<Long>>()
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(c.getLong(idIdx))
            }
        }
        return result
    }

    private fun buildProgramValues(
        progress: WatchProgress,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        // Backdrop/poster fills the tile via posterArt; logo goes to the dedicated logo column
        // so the launcher renders it as a small badge overlay on focus.
        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                // Trakt items may have duration (from runtime hydration) but position=0 with only progressPercent.
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            progress.contentId
}
