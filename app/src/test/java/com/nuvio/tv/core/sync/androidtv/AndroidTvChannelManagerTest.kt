package com.nuvio.tv.core.sync.androidtv

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.media.tv.TvContract
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import android.util.Log
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidTvChannelManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val prefs: TvChannelPreferences = mockk(relaxed = true)
    private lateinit var manager: AndroidTvChannelManager

    private val channelId = 42L

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } answers {
            println("Log.w: ${firstArg<String>()} - ${secondArg<String>()}")
            0
        }
        every { Log.w(any(), any<String>(), any()) } answers {
            println("Log.w: ${firstArg<String>()} - ${secondArg<String>()}")
            (args[2] as? Throwable)?.printStackTrace()
            0
        }
        every { Log.e(any(), any()) } returns 0

        val uriMocks = mutableMapOf<String, Uri>()
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val str = firstArg<String?>() ?: ""
            uriMocks.getOrPut(str) { mockk(relaxed = true) }
        }

        mockkStatic(TvContract::class)
        every { TvContract.buildChannelUri(any()) } answers {
            val id = firstArg<Long>()
            val str = "content://android.media.tv/channel/$id"
            uriMocks.getOrPut(str) { mockk(relaxed = true) }
        }
        every { TvContract.buildChannelLogoUri(any<Long>()) } answers {
            val id = firstArg<Long>()
            val str = "content://android.media.tv/channel/$id/logo"
            uriMocks.getOrPut(str) { mockk(relaxed = true) }
        }

        mockkStatic(ContentUris::class)
        every { ContentUris.withAppendedId(any(), any()) } answers {
            val baseUri = firstArg<Uri>()
            val id = secondArg<Long>()
            val str = "${baseUri.hashCode()}/$id"
            uriMocks.getOrPut(str) { mockk(relaxed = true) }
        }

        every { context.packageManager } returns packageManager
        every { context.contentResolver } returns contentResolver
        every { context.packageName } returns "com.nuvio.tv"
        every { context.getString(any<Int>()) } returns "Continue Watching"
        manager = AndroidTvChannelManager(context, prefs)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isSupported returns false on non-leanback device`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns false
        assert(!manager.isSupported())
    }

    @Test
    fun `isSupported returns true on leanback device`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
        assert(manager.isSupported())
    }

    @Test
    fun `reconcile is no-op on non-leanback device`() = runTest {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns false
        manager.reconcile(listOf(fakeProgress("tt001", "movie")))
        verify(exactly = 0) { contentResolver.insert(any(), any()) }
        verify(exactly = 0) { contentResolver.update(any(), any(), any(), any()) }
        verify(exactly = 0) { contentResolver.delete(any(), any(), any()) }
    }

    @Test
    fun `reconcile inserts program when channel exists and no existing programs`() = runTest {
        setupLeanback()
        setupChannelExists()
        stubProgramQuery(existingRows = emptyMap())

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 1) { contentResolver.insert(any(), any()) }
        verify(exactly = 0) { contentResolver.update(any(), any(), null, null) }
    }

    @Test
    fun `reconcile updates program that already exists in channel`() = runTest {
        setupLeanback()
        setupChannelExists()
        stubProgramQuery(existingRows = mapOf("tt001" to 99L))

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 0) { contentResolver.insert(any(), any()) }
        verify(exactly = 1) { contentResolver.update(any(), any(), null, null) }
    }

    @Test
    fun `reconcile deletes program that is no longer in the list`() = runTest {
        setupLeanback()
        setupChannelExists()
        // Channel has tt002 but we reconcile with only tt001
        stubProgramQuery(existingRows = mapOf("tt002" to 77L))

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 1) { contentResolver.delete(any(), null, null) }
        verify(exactly = 1) { contentResolver.insert(any(), any()) }
    }

    @Test
    fun `reconcile cleans up duplicate programs with the same key`() = runTest {
        setupLeanback()
        setupChannelExists()
        // Database has two rows with key "tt001" (row IDs 99L and 100L)
        val rows = listOf("tt001" to 99L, "tt001" to 100L)
        val cursor = mockk<Cursor>(relaxed = true) {
            var idx = -1
            every { moveToNext() } answers { idx++; idx < rows.size }
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID) } returns 0
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID) } returns 2
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID) } returns 1
            every { getLong(0) } answers { rows[idx].second }
            every { getLong(2) } returns channelId
            every { getString(1) } answers { rows[idx].first }
        }
        every {
            contentResolver.query(any(), match { it.size > 1 }, null, null, null)
        } returns cursor
        every { contentResolver.update(any(), any(), null, null) } returns 1
        every { contentResolver.delete(any(), null, null) } returns 1

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        // Verify the first row (99L) was updated, and the second row (100L) was deleted
        verify(exactly = 1) { ContentUris.withAppendedId(any(), 99L) }
        verify(exactly = 1) { ContentUris.withAppendedId(any(), 100L) }
        verify(exactly = 1) { contentResolver.update(any(), any(), null, null) }
        verify(exactly = 1) { contentResolver.delete(any(), null, null) }
    }

    // --- helpers ---

    private fun setupLeanback() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
    }

    private fun setupChannelExists() {
        coEvery { prefs.getChannelId() } returns channelId
        val channelCursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getLong(0) } returns channelId
        }
        every {
            contentResolver.query(any(), match { it.size == 1 }, null, null, null)
        } returns channelCursor
    }

    private fun stubProgramQuery(existingRows: Map<String, Long>) {
        val rows = existingRows.entries.toList()
        val cursor = mockk<Cursor>(relaxed = true) {
            var idx = -1
            every { moveToNext() } answers { idx++; idx < rows.size }
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID) } returns 0
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID) } returns 2
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID) } returns 1
            every { getLong(0) } answers { rows[idx].value }
            every { getLong(2) } returns channelId
            every { getString(1) } answers { rows[idx].key }
        }
        every {
            contentResolver.query(any(), match { it.size > 1 }, null, null, null)
        } returns cursor
    }

    private fun fakeProgress(contentId: String, contentType: String) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "Test Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 300_000L,
        duration = 5_400_000L,
        lastWatched = System.currentTimeMillis()
    )
}
