package com.nuvio.tv.core.livetv

import android.content.Context
import com.nuvio.tv.core.storage.MediaCacheCategory
import com.nuvio.tv.core.storage.TvMediaCache
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
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

data class CachedLiveTvGuide(
    val schema: Int = 1,
    val sourceId: String,
    val updatedEpochMillis: Long,
    val result: LiveTvImportResult,
)

/** Profile source IDs are hashed into app-private filenames; payloads share the central EPG quota. */
@Singleton
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

    suspend fun read(sourceId: String): CachedLiveTvGuide? = withContext(Dispatchers.IO) {
        val file = fileFor(sourceId)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_COMPRESSED_BYTES) return@withContext null
        try {
            DataInputStream(LimitedInputStream(GZIPInputStream(file.inputStream().buffered()), MAX_DECODED_BYTES).buffered()).use { input ->
                decode(input)
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
                    DataOutputStream(GZIPOutputStream(LimitedOutputStream(temporary.outputStream().buffered(), limit)).buffered()).use { output ->
                        encode(output, CachedLiveTvGuide(SCHEMA_VERSION, sourceId, updatedEpochMillis, result))
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

    private fun encode(output: DataOutputStream, guide: CachedLiveTvGuide) {
        output.writeInt(MAGIC)
        output.writeInt(guide.schema)
        output.writeString(guide.sourceId)
        output.writeLong(guide.updatedEpochMillis)
        output.writeInt(guide.result.channels.size)
        guide.result.channels.forEach { channel ->
            output.writeString(channel.id)
            output.writeString(channel.sourceId)
            output.writeString(channel.displayName)
            output.writeNullableString(channel.streamUrl)
            output.writeNullableString(channel.logoUrl)
            output.writeNullableString(channel.groupName)
            output.writeNullableString(channel.tvgId)
            output.writeNullableString(channel.tvgName)
        }
        output.writeInt(guide.result.programs.size)
        guide.result.programs.forEach { program ->
            output.writeString(program.sourceId)
            output.writeString(program.channelId)
            output.writeString(program.title)
            output.writeLong(program.startEpochMillis)
            output.writeLong(program.endEpochMillis)
            output.writeNullableString(program.description)
            output.writeNullableString(program.category)
            output.writeNullableString(program.iconUrl)
        }
        output.writeInt(guide.result.skippedEntries)
    }

    private fun decode(input: DataInputStream): CachedLiveTvGuide {
        require(input.readInt() == MAGIC) { "Invalid cached guide signature" }
        val schema = input.readInt()
        val sourceId = input.readString()
        val updatedAt = input.readLong()
        val channelCount = input.readInt().also { require(it in 0..MAX_CACHED_CHANNELS) }
        val channels = List(channelCount) {
            LiveTvChannel(
                id = input.readString(),
                sourceId = input.readString(),
                displayName = input.readString(),
                streamUrl = input.readNullableString(),
                logoUrl = input.readNullableString(),
                groupName = input.readNullableString(),
                tvgId = input.readNullableString(),
                tvgName = input.readNullableString(),
            )
        }
        val programCount = input.readInt().also { require(it in 0..XmlTvParser.MAX_PROGRAMS) }
        val programs = List(programCount) {
            LiveTvProgram(
                sourceId = input.readString(),
                channelId = input.readString(),
                title = input.readString(),
                startEpochMillis = input.readLong(),
                endEpochMillis = input.readLong(),
                description = input.readNullableString(),
                category = input.readNullableString(),
                iconUrl = input.readNullableString(),
            )
        }
        val skipped = input.readInt()
        require(input.read() == -1) { "Trailing cached guide data" }
        return CachedLiveTvGuide(schema, sourceId, updatedAt, LiveTvImportResult(channels, programs, skipped))
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt().also { require(it in 0..MAX_FIELD_BYTES) }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

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
        const val CACHE_EXTENSION = "bin.gz"
        const val SCHEMA_VERSION = 1
        const val MAGIC = 0x54455047
        const val MAX_FIELD_BYTES = 1024 * 1024
        const val MAX_CACHED_CHANNELS = M3uPlaylistParser.MAX_CHANNELS + XmlTvParser.MAX_CHANNELS
        const val MAX_COMPRESSED_BYTES = 64L * 1024L * 1024L
        const val MAX_DECODED_BYTES = 64L * 1024L * 1024L
    }
}
