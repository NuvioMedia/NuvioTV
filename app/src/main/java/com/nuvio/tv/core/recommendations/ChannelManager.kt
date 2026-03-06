package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of TV recommendation channels (create / query / delete)
 * and the preview programs within each channel.
 */
@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: RecommendationDataStore
) {

    // ────────────────────────────────────────────────────────────────
    //  Channel operations
    // ────────────────────────────────────────────────────────────────

    /**
     * Creates a channel on the TV launcher if it doesn't already exist.
     * Returns the channel ID (from ContentProvider) or `null` on failure.
     */
    suspend fun getOrCreateChannel(
        internalId: String,
        displayName: String
    ): Long? {
        // 1. Check cached ID first
        val cachedId = dataStore.getChannelId(internalId)
        if (cachedId != null && channelExists(cachedId)) {
            return cachedId
        }

        // 2. Search the provider for a channel we previously inserted
        val existingId = findChannelByInternalId(internalId)
        if (existingId != null) {
            dataStore.setChannelId(internalId, existingId)
            return existingId
        }

        // 3. Insert a brand-new channel
        return try {
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setAppLinkIntentUri(
                    Uri.parse("${RecommendationConstants.DEEP_LINK_SCHEME}://${RecommendationConstants.DEEP_LINK_HOST}/$internalId")
                )
                .setInternalProviderId(internalId)
                .build()

            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            )

            if (channelUri == null) {
                return null
            }

            val channelId = ContentUris.parseId(channelUri)
            dataStore.setChannelId(internalId, channelId)

            // Store a channel logo so the launcher can distinguish channels visually.
            storeChannelLogo(channelId)

            // Request the system to make this channel visible on the home screen.
            // On first call the user gets a prompt; subsequent calls are no-ops.
            try {
                val intent = android.content.Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                intent.putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
            }

            channelId
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes all programs inside a channel so we can insert a fresh set.
     */
    fun clearProgramsForChannel(channelId: Long) {
        try {
            val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Inserts a list of [PreviewProgram]s into a channel via bulk insert.
     */
    fun insertPrograms(programs: List<PreviewProgram>) {
        if (programs.isEmpty()) return
        try {
            val values = programs.map { it.toContentValues() }.toTypedArray()
            context.contentResolver.bulkInsert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                values
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Deletes the channel and removes its cached id.
     */
    suspend fun deleteChannel(internalId: String) {
        val channelId = dataStore.getChannelId(internalId) ?: return
        try {
            val uri = TvContractCompat.buildChannelUri(channelId)
            context.contentResolver.delete(uri, null, null)
            dataStore.clearChannelId(internalId)
        } catch (_: Exception) {
        }
    }

    /**
     * Resolves the real internal channel ID without creating it.
     */
    suspend fun getChannelId(internalId: String): Long? {
        val cachedId = dataStore.getChannelId(internalId)
        if (cachedId != null && channelExists(cachedId)) {
            return cachedId
        }
        val existingId = findChannelByInternalId(internalId)
        if (existingId != null) {
            dataStore.setChannelId(internalId, existingId)
            return existingId
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    private fun channelExists(channelId: Long): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels._ID),
                null, null, null
            )
            cursor != null && cursor.count > 0
        } catch (e: Exception) {
            false
        } finally {
            cursor?.close()
        }
    }

    /**
     * Stores the app launcher icon as the channel logo.
     * Each channel gets its own copy so the launcher treats them as distinct.
     */
    private fun storeChannelLogo(channelId: Long) {
        try {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher) ?: return
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
        } catch (_: Exception) {
        }
    }

    suspend fun cleanupLegacyChannels(validIds: List<String>) {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIndex = it.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    val channelIdIndex = it.getColumnIndex(TvContractCompat.Channels._ID)
                    if (idIndex >= 0 && channelIdIndex >= 0) {
                        val internalId = it.getString(idIndex)
                        if (internalId !in validIds) {
                            val channelId = it.getLong(channelIdIndex)
                            context.contentResolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
                            try { dataStore.clearChannelId(internalId) } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
    }

    private fun findChannelByInternalId(internalId: String): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIndex = it.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    if (idIndex >= 0 && it.getString(idIndex) == internalId) {
                        val channelIdIndex = it.getColumnIndex(TvContractCompat.Channels._ID)
                        if (channelIdIndex >= 0) return it.getLong(channelIdIndex)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
