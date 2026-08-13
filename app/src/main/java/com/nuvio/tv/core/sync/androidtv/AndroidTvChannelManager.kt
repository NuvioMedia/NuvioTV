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
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
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

    suspend fun ensureChannel(): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null

        runCatching {
            val stored = prefs.getChannelId()

            if (stored != null) {
                val cursor = context.contentResolver.query(
                    TvContractCompat.buildChannelUri(stored),
                    arrayOf(TvContractCompat.Channels._ID),
                    null,
                    null,
                    null
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        return@runCatching stored
                    }
                }

                Log.d(TAG, "Stored channel $stored gone; recreating")
                prefs.clearChannelId()
            }

            val orphan = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null,
                null,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
                val providerIdx = c.getColumnIndex(
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                )

                if (idIdx < 0 || providerIdx < 0) return@use null

                while (c.moveToNext()) {
                    val providerId = c.getString(providerIdx)
                    if (
                        providerId != null &&
                        providerId.startsWith(context.packageName)
                    ) {
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

            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(R.string.tv_channel_continue_watching))
                .setAppLinkIntentUri(buildAppLinkUri())
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
        }.onFailure {
            Log.w(TAG, "ensureChannel failed", it)
        }.getOrNull()
    }

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
                            TvContractCompat.buildPreviewProgramUri(rowId),
                            null,
                            null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                }
            }

            items.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val values = buildProgramValues(
                    progress = progress,
                    channelId = channelId,
                    sortOrder = index,
                    key = key
                )

                val rowIds = existing[key]

                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId),
                        values,
                        null,
                        null
                    )

                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId),
                                null,
                                null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        values
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "reconcile failed", it)
        }
    }

    suspend fun ensureCatalogChannel(
        catalogKey: String,
        displayName: String
    ): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null

        runCatching {
            val stored = prefs.getCatalogChannelId(catalogKey)

            if (stored != null && channelExists(stored)) {
                updateChannelName(stored, displayName)
                return@runCatching stored
            }

            if (stored != null) {
                prefs.clearCatalogChannelId(catalogKey)
            }

            val providerId = catalogProviderId(catalogKey)
            val orphan = findChannelByProviderId(providerId)

            if (orphan != null) {
                prefs.setCatalogChannelId(catalogKey, orphan)
                updateChannelName(orphan, displayName)
                writeChannelLogo(orphan)
                return@runCatching orphan
            }

            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setInternalProviderId(providerId)
                .setAppLinkIntentUri(buildAppLinkUri())
                .build()

            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@runCatching null

            val channelId = ContentUris.parseId(inserted)
            prefs.setCatalogChannelId(catalogKey, channelId)
            writeChannelLogo(channelId)
            TvContractCompat.requestChannelBrowsable(context, channelId)

            Log.d(
                TAG,
                "Created catalog channel key=$catalogKey id=$channelId name=$displayName"
            )

            channelId
        }.onFailure {
            Log.w(TAG, "ensureCatalogChannel failed for $catalogKey", it)
        }.getOrNull()
    }

    suspend fun reconcileCatalog(
        catalogKey: String,
        displayName: String,
        items: List<MetaPreview>,
        maxItems: Int = 20
    ) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext

        runCatching {
            if (!prefs.isCatalogEnabled(catalogKey)) return@runCatching

            val channelId = ensureCatalogChannel(
                catalogKey = catalogKey,
                displayName = displayName
            ) ?: return@runCatching

            val catalogItems = items
                .distinctBy { catalogProgramKey(it) }
                .take(maxItems.coerceIn(1, 50))

            val existing = queryExistingPrograms(channelId)
            val desiredKeys = catalogItems.map { catalogProgramKey(it) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId),
                            null,
                            null
                        )
                    }
                }
            }

            catalogItems.forEachIndexed { index, item ->
                val key = catalogProgramKey(item)
                val values = buildCatalogProgramValues(
                    item = item,
                    channelId = channelId,
                    sortOrder = index,
                    key = key
                )

                val rowIds = existing[key]

                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId),
                        values,
                        null,
                        null
                    )

                    rowIds.drop(1).forEach { duplicateId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(duplicateId),
                            null,
                            null
                        )
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        values
                    )
                }
            }

            Log.d(TAG, "Catalog reconciled: $displayName (${catalogItems.size} items)")
        }.onFailure {
            Log.w(TAG, "reconcileCatalog failed for $catalogKey", it)
        }
    }

    suspend fun removeCatalogChannel(catalogKey: String) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext

        runCatching {
            val channelId = prefs.getCatalogChannelId(catalogKey)
                ?: findChannelByProviderId(catalogProviderId(catalogKey))

            if (channelId != null) {
                context.contentResolver.delete(
                    TvContractCompat.buildChannelUri(channelId),
                    null,
                    null
                )
                Log.d(TAG, "Deleted catalog channel key=$catalogKey id=$channelId")
            }

            prefs.clearCatalogChannelId(catalogKey)
        }.onFailure {
            Log.w(TAG, "removeCatalogChannel failed for $catalogKey", it)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext

        runCatching {
            val channelId = prefs.getChannelId() ?: return@runCatching
            val rows = queryExistingPrograms(channelId)
            var deletedCount = 0

            rows.values.flatten().forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId),
                    null,
                    null
                )
                deletedCount++
            }

            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure {
            Log.w(TAG, "clearAll failed", it)
        }
    }

    private fun channelExists(channelId: Long): Boolean =
        context.contentResolver.query(
            TvContractCompat.buildChannelUri(channelId),
            arrayOf(TvContractCompat.Channels._ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() } == true

    private fun findChannelByProviderId(providerId: String): Long? {
        return context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(
                TvContractCompat.Channels._ID,
                TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIndex = cursor.getColumnIndex(
                TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
            )

            if (idIndex < 0 || providerIndex < 0) return@use null

            while (cursor.moveToNext()) {
                val currentProviderId = cursor.getString(providerIndex)
                if (currentProviderId == providerId) {
                    return@use cursor.getLong(idIndex)
                }
            }

            null
        }
    }

    private fun updateChannelName(channelId: Long, displayName: String) {
        val values = ContentValues().apply {
            put(
                TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
                displayName
            )
        }

        context.contentResolver.update(
            TvContractCompat.buildChannelUri(channelId),
            values,
            null,
            null
        )
    }

    private fun buildAppLinkUri(): Uri =
        Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )

        val result = mutableMapOf<String, MutableList<Long>>()

        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(
                TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID
            )
            val keyIdx = c.getColumnIndexOrThrow(
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            )

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

        val type = if (progress.contentType.equals("movie", ignoreCase = true)) {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9

            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3

            else -> null to null
        }

        imageUri?.let {
            builder
                .setPosterArtUri(Uri.parse(it))
                .setPosterArtAspectRatio(aspectRatio!!)
        }

        progress.logo?.let {
            builder.setLogoUri(Uri.parse(it))
        }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())

            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (
                    progress.progressPercent
                        ?.let { it / 100f * progress.duration }
                        ?.toLong()
                        ?: 0L
                    ).toInt()
            }

            builder.setLastPlaybackPositionMillis(positionMs)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            it.put("last_engagement_time_utc_millis", progress.lastWatched)

            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }

            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }

            if (progress.duration <= 0) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun buildCatalogProgramValues(
        item: MetaPreview,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", item.id)
                putExtra("contentType", item.apiType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val programType = if (item.apiType.equals("series", ignoreCase = true)) {
            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
        } else {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(item.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        item.description
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.setDescription(it) }

        val (imageUri, aspectRatio) = when {
            !item.background.isNullOrBlank() ->
                item.background to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9

            !item.landscapePoster.isNullOrBlank() ->
                item.landscapePoster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9

            !item.poster.isNullOrBlank() ->
                item.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3

            else -> null to null
        }

        imageUri?.let {
            builder
                .setPosterArtUri(Uri.parse(it))
                .setPosterArtAspectRatio(aspectRatio!!)
        }

        item.logo
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.setLogoUri(Uri.parse(it)) }

        return builder.build().toContentValues().also { values ->
            values.put(
                "last_engagement_time_utc_millis",
                System.currentTimeMillis() - sortOrder
            )

            if (imageUri == null) {
                values.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }

            if (item.logo.isNullOrBlank()) {
                values.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
        }
    }

    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }

    private fun catalogProgramKey(item: MetaPreview): String =
        "${item.apiType}:${item.id}"

    private fun catalogProviderId(catalogKey: String): String =
        "nuvio_catalog:${sha256(catalogKey)}"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(
                context.resources,
                R.mipmap.ic_launcher
            ) ?: return

            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use {
                bitmap.compress(
                    android.graphics.Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            }
        }.onFailure {
            Log.w(TAG, "writeChannelLogo failed", it)
        }
    }
}
