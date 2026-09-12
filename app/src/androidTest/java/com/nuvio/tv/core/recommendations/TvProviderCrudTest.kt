package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelManager
import com.nuvio.tv.core.sync.androidtv.TvChannelPreferences
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real provider read/write under the target app UID; never updates an existing user row. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class TvProviderCrudTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Before fun requireTvAndTargetUid() {
        assumeTrue("TV provider integration requires Android TV", context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        assertEquals("Instrumentation must use the target application's UID", context.applicationInfo.uid, Process.myUid())
    }

    private fun progress(id: String) = WatchProgress(
        contentId = id, contentType = "series", name = "Nuvio synthetic provider test",
        poster = null, backdrop = null, logo = null, videoId = "$id:2:3",
        season = 2, episode = 3, episodeTitle = "Synthetic episode",
        position = 2500L, duration = 10000L, lastWatched = System.currentTimeMillis(),
    )

    @Test fun watchNextCreateUpdateReadAndDelete() {
        val item = progress("codex-provider-${UUID.randomUUID()}")
        val builder = ProgramBuilder(context)
        val internalId = builder.watchNextId(item)
        val table = TvContractCompat.WatchNextPrograms.CONTENT_URI
        try {
            builder.upsertWatchNextProgram(builder.buildWatchNextProgram(item), internalId)
            val inserted = ownedUris(table, internalId).single()
            readRow(inserted) { cursor ->
                assertEquals(item.name, cursor.string(TvContract.Programs.COLUMN_TITLE))
                assertEquals("2", cursor.string(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER))
                assertEquals("3", cursor.string(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER))
                assertEquals(2500, cursor.integer(TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
                assertEquals(10000, cursor.integer(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS))
                assertTrue(cursor.string(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI)?.contains(item.contentId) == true)
            }
            val updated = item.copy(name = "Updated synthetic provider test", position = 5500L)
            builder.upsertWatchNextProgram(builder.buildWatchNextProgram(updated), internalId)
            assertEquals(listOf(inserted), ownedUris(table, internalId))
            readRow(inserted) { cursor ->
                assertEquals(updated.name, cursor.string(TvContract.Programs.COLUMN_TITLE))
                assertEquals(5500, cursor.integer(TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
            }
            builder.removeWatchNextProgram(internalId)
            assertTrue(ownedUris(table, internalId).isEmpty())
        } finally {
            // Exact UUID-owned rows only, including any duplicate if an assertion failed.
            ownedUris(table, internalId).forEach { resolver.delete(it, null, null) }
        }
    }

    @Test fun previewProgramCreateUpdateReadAndDelete() {
        val internalId = "codex-preview-${UUID.randomUUID()}"
        val item = progress(internalId)
        var channelUri: Uri? = null
        var programUri: Uri? = null
        try {
            channelUri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, ContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_TYPE, TvContractCompat.Channels.TYPE_PREVIEW)
                put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, "Nuvio synthetic provider test")
                put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, internalId)
                put(TvContractCompat.Channels.COLUMN_APP_LINK_INTENT_URI, "nuvio://detail/movie/$internalId")
            })
            assertNotNull("Preview channel was not inserted", channelUri)
            val channelId = ContentUris.parseId(channelUri!!)
            // Building values does not read or modify the user's stored channel preference.
            val manager = AndroidTvChannelManager(context, TvChannelPreferences(context))
            programUri = resolver.insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                manager.buildProgramValues(item, channelId, 0, internalId),
            )
            assertNotNull("Preview program was not inserted", programUri)
            readRow(programUri!!) { cursor ->
                assertEquals(item.name, cursor.string(TvContract.Programs.COLUMN_TITLE))
                assertEquals(channelId, cursor.getLong(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)))
                assertEquals(2500, cursor.integer(TvContractCompat.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
                assertEquals("Synthetic episode", cursor.string(TvContract.Programs.COLUMN_EPISODE_TITLE))
            }
            val updated = item.copy(name = "Updated preview test", duration = 0L, position = 0L)
            assertEquals(1, resolver.update(programUri!!, manager.buildProgramValues(updated, channelId, 1, internalId), null, null))
            readRow(programUri!!) { cursor ->
                assertEquals(updated.name, cursor.string(TvContract.Programs.COLUMN_TITLE))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_DURATION_MILLIS)))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(TvContract.Programs.COLUMN_POSTER_ART_URI)))
            }
            assertEquals(1, resolver.delete(programUri!!, null, null))
            assertTrue(ownedUris(TvContractCompat.PreviewPrograms.CONTENT_URI, internalId).isEmpty())
        } finally {
            try {
                programUri?.let { resolver.delete(it, null, null) }
            } finally {
                channelUri?.let { resolver.delete(it, null, null) }
            }
        }
    }

    private fun ownedUris(table: Uri, internalId: String): List<Uri> {
        val result = mutableListOf<Uri>()
        resolver.query(table, arrayOf("_id", "internal_provider_id"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string("internal_provider_id") == internalId) {
                    result += ContentUris.withAppendedId(table, cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                }
            }
        } ?: error("TV provider returned no cursor")
        return result
    }

    private fun readRow(uri: Uri, assertion: (Cursor) -> Unit) {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            assertTrue("Expected one synthetic row", cursor.moveToFirst())
            assertion(cursor)
            assertFalse("Unexpected duplicate row", cursor.moveToNext())
        } ?: error("TV provider returned no cursor")
    }

    private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))
    private fun Cursor.integer(column: String): Int = getInt(getColumnIndexOrThrow(column))
}
