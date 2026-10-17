package com.nuvio.tv.core.torrent

import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentEngineConfigTest {
    @Test
    fun `app owned routes disable automatic inactivity expiry`() {
        val stateDirectory = File("state")
        val cacheDirectory = File("cache")

        val uploading = buildTorrentEngineConfig(
            stateDirectory = stateDirectory,
            cacheDirectory = cacheDirectory,
            uploadEnabled = true,
            torrentProfile = TorrentProfile.FAST,
            diskCacheCapacityBytes = TorrentCacheSize.GB_5.bytes
        )
        val downloadOnly = buildTorrentEngineConfig(
            stateDirectory = stateDirectory,
            cacheDirectory = cacheDirectory,
            uploadEnabled = false,
            torrentProfile = TorrentProfile.SOFT,
            diskCacheCapacityBytes = TorrentCacheSize.NONE.bytes
        )

        assertEquals(0, uploading.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Unlimited, uploading.uploadMode)
        assertEquals(NuvioTorrentProfile.Fast, uploading.torrentProfile)
        assertEquals(TorrentCacheSize.GB_5.bytes, uploading.diskCacheCapacityBytes)
        assertEquals(0, downloadOnly.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Disabled, downloadOnly.uploadMode)
        assertEquals(NuvioTorrentProfile.Soft, downloadOnly.torrentProfile)
        assertEquals(0L, downloadOnly.diskCacheCapacityBytes)
    }

    @Test
    fun `matching unsolicited stop becomes terminal error`() {
        assertEquals(
            TorrentState.Error("stream expired after inactivity"),
            unexpectedStreamStopError(
                requestId = 0L,
                eventStreamId = "stream",
                currentStreamId = "stream",
                message = "stream expired after inactivity",
                fallbackMessage = "unknown"
            )
        )
    }

    @Test
    fun `explicit and stale stops are ignored`() {
        assertNull(unexpectedStreamStopError(7L, "stream", "stream", "stopped", "unknown"))
        assertNull(unexpectedStreamStopError(0L, "old-stream", "stream", "stopped", "unknown"))
    }

    @Test
    fun `blank stop message uses fallback`() {
        assertEquals(
            TorrentState.Error("unknown"),
            unexpectedStreamStopError(0L, "stream", "stream", "  ", "unknown")
        )
    }

    @Test
    fun `global cache pressure does not become terminal error`() {
        assertNull(
            unexpectedTorrentError(
                requestId = 0L,
                eventTorrentId = null,
                currentTorrentId = "torrent",
                message = "disk cache budget is exceeded by protected torrent data",
                fallbackMessage = "unknown"
            )
        )
    }

    @Test
    fun `matching unsolicited torrent failure becomes terminal error`() {
        assertEquals(
            TorrentState.Error("file write failed"),
            unexpectedTorrentError(0L, "torrent", "torrent", "file write failed", "unknown")
        )
    }

    @Test
    fun `command and stale torrent failures are ignored`() {
        assertNull(unexpectedTorrentError(9L, "torrent", "torrent", "failed", "unknown"))
        assertNull(unexpectedTorrentError(0L, "old-torrent", "torrent", "failed", "unknown"))
    }

    @Test
    fun `initial loading progress blends engine bytes and player buffer`() {
        assertEquals(0f, torrentInitialLoadingProgress(0L, 0L, 0L), 0f)
        val engineOnly = torrentInitialLoadingProgress(
            bufferedAheadMs = 0L,
            downloadedBytes = TorrentInitialByteProgressMidpoint,
            deliveredBytes = TorrentInitialByteProgressMidpoint
        )
        assertEquals(0.375f, engineOnly, 0.0001f)
        assertEquals(0.95f, torrentInitialLoadingProgress(60_000L, 0L, 0L), 0.0001f)
    }

    @Test
    fun `nested payload moves to the engine payload directory`() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("cached")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("cached", File(cacheDirectory, "payload/$TORRENT_ID/video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `nested payload never replaces current payload`() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val current = File(cacheDirectory, "payload/$TORRENT_ID").apply { mkdirs() }
            File(current, "video.mkv").writeText("current")
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("stale")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("current", File(current, "video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `missing nested payload leaves cache untouched`() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            migrateNestedPayloadDirectory(cacheDirectory)

            assertTrue(cacheDirectory.listFiles().isNullOrEmpty())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val TORRENT_ID = "0123456789abcdef0123456789abcdef01234567"
    }
}
