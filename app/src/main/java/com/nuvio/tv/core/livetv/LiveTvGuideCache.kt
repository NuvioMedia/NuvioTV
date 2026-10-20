package com.nuvio.tv.core.livetv

import android.content.Context
import com.nuvio.tv.core.storage.MediaCacheCategory
import com.nuvio.tv.core.storage.TvMediaCache
import com.nuvio.tv.domain.model.LiveTvImportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromStream
import kotlinx.serialization.encodeToStream
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CachedLiveTvGuide(
    val schema: Int = 1,
    val sourceId: String,
    val updatedEpochMillis: Long,
    val result: LiveTvImportResult,
)

/** Profile source IDs are hashed into app-private filenames; payloads share the central EPG quota. */
@Singleton
@OptIn(ExperimentalSerializationApi::class)
class LiveTvGuideCache private constructor(
    private val directory: File,
    private val quotaBytes: () -> Long,
    private val writesEnabled: () -> Boolean,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        mediaCache: TvMediaCache,
    ) : this(
        File(context.cacheDir, DIRECTORY_NAME),
        { mediaCache.coilQuota(MediaCacheCategory.EPG) },
        { mediaCache.writesEnabled },
    )

    internal constructor(directory: File, quotaBytes: Long, writesEnabled: Boolean) : this(
        directory,
        { quotaBytes },
        { writesEnabled },
    )

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    suspend fun read(sourceId: String): CachedLiveTvGuide? = withContext(Dispatchers.IO) {
        val file = fileFor(sourceId)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_COMPRESSED_BYTES) return@withContext null
        try {
            GZIPInputStream(file.inputStream().buffered()).use { gzip ->
                LimitedInputStream(gzip, MAX_DECODED_BYTES).use { bounded ->
                    json.decodeFromStream(CachedLiveTvGuide.serializer(), bounded)
                }
            }.takeIf { entry ->
                entry.schema == SCHEMA_VERSION && entry.sourceId == sourceId &&
                    entry.result.channels.size <= MAX_CACHED_CHANNELS &&
                    entry.result.programs.size <= XmlTvParser.MAX_PROGRAMS
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    /** Writes atomically only when the central cache policy allows it and space remains in EPG's quota. */
    suspend fun write(sourceId: String, result: LiveTvImportResult, updatedEpochMillis: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            if (!writesEnabled() || result.channels.size > MAX_CACHED_CHANNELS ||
                result.programs.size > XmlTvParser.MAX_PROGRAMS
            ) return@withContext false
            val limit = quotaBytes().coerceAtLeast(0).coerceAtMost(MAX_COMPRESSED_BYTES)
            if (limit == 0L || !directory.exists() && !directory.mkdirs()) return@withContext false
            directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".tmp") }.forEach(File::delete)
            val target = fileFor(sourceId)
            val temporary = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                try {
                    GZIPOutputStream(LimitedOutputStream(temporary.outputStream().buffered(), limit)).use { gzip ->
                        json.encodeToStream(
                            CachedLiveTvGuide.serializer(),
                            CachedLiveTvGuide(SCHEMA_VERSION, sourceId, updatedEpochMillis, result),
                            gzip,
                        )
                    }
                } catch (_: CacheQuotaExceededException) {
                    return@withContext false
                }
                val incomingBytes = temporary.length()
                if (incomingBytes <= 0 || incomingBytes > limit) return@withContext false

                val otherFiles = directory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.endsWith(".$CACHE_EXTENSION") && it != target }
                    .sortedBy(File::lastModified)
                var otherBytes = otherFiles.sumOf { it.length().coerceAtLeast(0) }
                val iterator = otherFiles.iterator()
                while (otherBytes + incomingBytes > limit && iterator.hasNext()) {
                    val old = iterator.next()
                    val bytes = old.length().coerceAtLeast(0)
                    if (old.delete()) otherBytes = (otherBytes - bytes).coerceAtLeast(0)
                }
                if (otherBytes + incomingBytes > limit) return@withContext false

                if (!temporary.renameTo(target)) throw java.io.IOException("Could not atomically replace the cached guide")
                target.setLastModified(System.currentTimeMillis())
                true
            } finally {
                temporary.delete()
            }
        }

    suspend fun remove(sourceId: String) = withContext(Dispatchers.IO) { fileFor(sourceId).delete() }

    private fun fileFor(sourceId: String): File {
        require(sourceId.isNotBlank() && sourceId.length <= 120 && sourceId.none(Char::isISOControl))
        val hash = MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.$CACHE_EXTENSION")
    }

    private class LimitedInputStream(input: InputStream, private val maximumBytes: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++count > maximumBytes) throw java.io.IOException("Cached EPG exceeds its decode limit")
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                if (count > maximumBytes) throw java.io.IOException("Cached EPG exceeds its decode limit")
            }
            return read
        }
    }

    private class LimitedOutputStream(output: OutputStream, private val maximumBytes: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            ensureCapacity(1)
            out.write(value)
            count++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            out.write(buffer, offset, length)
            count += length
        }
        private fun ensureCapacity(length: Int) {
            if (length < 0 || count > maximumBytes - length) throw CacheQuotaExceededException()
        }
    }

    private class CacheQuotaExceededException : java.io.IOException()

    private companion object {
        const val DIRECTORY_NAME = "live_tv_guide"
        const val CACHE_EXTENSION = "json.gz"
        const val SCHEMA_VERSION = 1
        const val MAX_CACHED_CHANNELS = M3uPlaylistParser.MAX_CHANNELS + XmlTvParser.MAX_CHANNELS
        const val MAX_COMPRESSED_BYTES = 64L * 1024L * 1024L
        const val MAX_DECODED_BYTES = 64L * 1024L * 1024L
    }
}
