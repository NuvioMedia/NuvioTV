package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object BlurayIso {
    const val SCHEME = "bdiso"
    private const val TAG = "BlurayIso"
    private const val SECTOR = 2048L
    private const val ANCHOR_SECTOR = 256L
    private const val MAX_VDS_SECTORS = 64
    private const val MAX_DIRECTORY_BYTES = 8 shl 20
    private const val MAX_SMALL_FILE_BYTES = 1 shl 20
    private const val MAX_PLAYLISTS = 1000
    private const val PLAYLIST_THREADS = 8
    private const val MAX_IMAGES = 4
    private const val MAX_AD_CHAIN = 64

    fun interface RangeReader {
        fun read(offset: Long, length: Int): ByteArray
    }

    data class Extent(val offset: Long, val length: Long)

    class Image internal constructor(
        val id: String,
        val sourceUrl: String,
        private val files: Map<String, List<Extent>>,
        private val names: Map<String, String>,
        internal val reader: RangeReader
    ) {
        fun extents(path: String): List<Extent>? = files[normalize(path)]

        internal fun paths(directory: String, extension: String): List<String> {
            val prefix = normalize(directory).trimEnd('/') + "/"
            val suffix = ".${extension.uppercase(Locale.US)}"
            return files.keys.filter { it.startsWith(prefix) && it.endsWith(suffix) && it.indexOf('/', prefix.length) < 0 }.sorted()
        }

        internal fun displayName(path: String): String = names[normalize(path)] ?: path.substringAfterLast('/')

        internal fun readFile(path: String, maxBytes: Int = MAX_SMALL_FILE_BYTES): ByteArray? {
            val extents = extents(path) ?: return null
            val total = extents.sumOf { it.length }.coerceAtMost(maxBytes.toLong()).toInt()
            val out = ByteArray(total)
            var written = 0
            for (extent in extents) {
                if (written >= total) break
                val count = minOf(extent.length, (total - written).toLong()).toInt()
                val bytes = reader.read(extent.offset, count)
                if (bytes.size < count) return null
                System.arraycopy(bytes, 0, out, written, count)
                written += count
            }
            return out
        }
    }

    private val images = object : LinkedHashMap<String, Image>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > MAX_IMAGES
    }

    fun image(id: String?): Image? = id?.let { synchronized(images) { images[it] } }

    fun isIsoUri(uri: Uri?): Boolean = uri?.scheme.equals(SCHEME, ignoreCase = true)

    fun isIsoUri(uri: String?): Boolean = uri?.startsWith("$SCHEME://", ignoreCase = true) == true

    fun clipUri(image: Image, path: String): String = "$SCHEME://${image.id}/${path.trimStart('/')}"

    fun isLikelyIsoUrl(url: String, filename: String?): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        if (path.endsWith(".iso")) return true
        val decoded = runCatching { Uri.decode(path) }.getOrNull().orEmpty().lowercase(Locale.US)
        if (decoded.endsWith(".iso")) return true
        val name = filename?.substringBefore('?')?.substringBefore('#')?.trim()?.lowercase(Locale.US) ?: return false
        return name.endsWith(".iso")
    }

    fun open(url: String, headers: Map<String, String>): Image? {
        val id = idFor(url)
        image(id)?.let { return it }
        val (sourceUrl, reader) = createReader(url, headers) ?: return null
        val cached = CachedRangeReader(reader)
        val udf = Udf(cached)
        val loaded = try {
            udf.load()
        } catch (error: Exception) {
            Log.w(TAG, "ISO read failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        } ?: return null
        var image = Image(id, sourceUrl, loaded.files(physicalFileData = false), loaded.names, cached)
        if (loaded.hasMetadataPartition && !hasReadablePlaylist(image)) {
            image = Image(id, sourceUrl, loaded.files(physicalFileData = true), loaded.names, cached)
        }
        if (image.paths("BDMV/STREAM", "m2ts").isEmpty()) {
            Log.w(TAG, "ISO has no BDMV/STREAM clips")
            return null
        }
        synchronized(images) { images[id] = image }
        return image
    }

    fun probeDisc(url: String, headers: Map<String, String>): BlurayPlaylistResolver.DiscProbe? {
        val image = open(url, headers) ?: return null
        val playlists = readPlaylists(image)
        if (playlists.isEmpty()) {
            Log.w(TAG, "ISO has no readable playlists")
            return null
        }
        val main = BlurayPlaylistResolver.selectMainPlaylist(playlists) ?: return null
        val disc = BlurayPlaylistResolver.discProbe(main, playlists) { remote(image, it) } ?: return null
        Log.i(TAG, "ISO feature ${main.name}: ${disc.feature?.segmentUrls?.size} clips, ${disc.titles.size} titles")
        return disc
    }

    fun titleLanguages(title: BlurayPlaylistResolver.RemoteLayout.Playlist): Pair<List<String>, List<String>> {
        val empty = emptyList<String>() to emptyList<String>()
        val clip = title.segmentUrls.firstOrNull()?.let(Uri::parse) ?: return empty
        val image = image(clip.host) ?: return empty
        val infoPath = BlurayClipInfo.clipInfoPath(clip.path.orEmpty()) ?: return empty
        val bytes = runCatching { image.readFile(infoPath) }.getOrNull() ?: return empty
        return BlurayPlaylistResolver.clipLanguages(bytes)
    }

    private fun remote(image: Image, playlist: BlurayPlaylistResolver.ParsedMpls): BlurayPlaylistResolver.RemoteLayout.Playlist? {
        val segments = playlist.clipIds.withIndex().mapNotNull { (index, clipId) ->
            val path = "BDMV/STREAM/$clipId.m2ts"
            if (image.extents(path) == null) null else clipUri(image, path) to playlist.itemDurationMs(index)
        }
        if (segments.isEmpty()) return null
        return BlurayPlaylistResolver.RemoteLayout.Playlist(playlist.name, segments.map { it.first }, segments.map { it.second })
    }

    private fun hasReadablePlaylist(image: Image): Boolean =
        image.paths("BDMV/PLAYLIST", "mpls").take(4).any { path ->
            runCatching { image.readFile(path, 4) }.getOrNull()?.let { String(it, StandardCharsets.US_ASCII) == "MPLS" } == true
        }

    private fun readPlaylists(image: Image): List<BlurayPlaylistResolver.ParsedMpls> {
        val paths = image.paths("BDMV/PLAYLIST", "mpls").take(MAX_PLAYLISTS)
        if (paths.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(PLAYLIST_THREADS)
        return try {
            paths.map { path ->
                pool.submit<BlurayPlaylistResolver.ParsedMpls?> {
                    image.readFile(path)?.let { BlurayPlaylistResolver.parseMpls(image.displayName(path), it) }
                }
            }.mapNotNull { future -> runCatching { future.get(30, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun createReader(url: String, headers: Map<String, String>): Pair<String, RangeReader>? {
        url.toHttpUrlOrNull()?.let { httpUrl ->
            val client = PlayerPlaybackNetworking.createHttpClient(
                headers,
                useLongReadTimeout = PlayerMediaSourceFactory.isLoopbackUrl(url)
            )
            return url to HttpRangeReader(httpUrl.toString(), client)
        }
        val file = toLocalFile(url) ?: return null
        return Uri.fromFile(file).toString() to FileRangeReader(file)
    }

    private fun toLocalFile(url: String): File? {
        if (url.isBlank()) return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val candidate = when {
            parsed == null || parsed.scheme.isNullOrEmpty() -> File(url)
            parsed.scheme.equals("file", ignoreCase = true) -> File(parsed.path ?: return null)
            else -> return null
        }
        if (candidate.isFile) return candidate
        val decoded = runCatching { Uri.decode(candidate.path) }.getOrNull()?.let(::File)
        return decoded?.takeIf { it.isFile }
    }

    private fun idFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(StandardCharsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    internal fun normalize(path: String): String = path.trim().trimStart('/').uppercase(Locale.US)

    private class HttpRangeReader(private val url: String, private val client: OkHttpClient) : RangeReader {
        override fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .build()
            client.newCall(request).execute().use { response ->
                val partial = response.code == 206
                if (!partial && !(response.code == 200 && offset == 0L)) {
                    throw IOException("range request failed: code=${response.code}")
                }
                if (partial) {
                    val range = response.header("Content-Range")
                    if (range != null && !range.trim().startsWith("bytes $offset-")) {
                        throw IOException("unexpected range: $range")
                    }
                }
                val input = response.body?.byteStream() ?: throw IOException("empty body")
                val buffer = ByteArray(length)
                var total = 0
                while (total < length) {
                    val read = input.read(buffer, total, length - total)
                    if (read <= 0) break
                    total += read
                }
                return if (total == length) buffer else buffer.copyOf(total)
            }
        }
    }

    private class FileRangeReader(private val file: File) : RangeReader {
        override fun read(offset: Long, length: Int): ByteArray {
            RandomAccessFile(file, "r").use { raf ->
                if (offset >= raf.length()) return ByteArray(0)
                val count = minOf(length.toLong(), raf.length() - offset).toInt()
                val buffer = ByteArray(count)
                raf.seek(offset)
                raf.readFully(buffer)
                return buffer
            }
        }
    }

    internal class CachedRangeReader(
        private val upstream: RangeReader,
        private val chunkSize: Int = 256 * 1024,
        private val maxChunks: Int = 64
    ) : RangeReader {
        private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxChunks
        }

        override fun read(offset: Long, length: Int): ByteArray {
            if (length > chunkSize * 4) return upstream.read(offset, length)
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                val position = offset + done
                val index = position / chunkSize
                val chunk = chunk(index)
                val inChunk = (position - index * chunkSize).toInt()
                if (inChunk >= chunk.size) break
                val count = minOf(chunk.size - inChunk, length - done)
                System.arraycopy(chunk, inChunk, out, done, count)
                done += count
                if (chunk.size < chunkSize) break
            }
            return if (done == length) out else out.copyOf(done)
        }

        private fun chunk(index: Long): ByteArray {
            synchronized(chunks) { chunks[index]?.let { return it } }
            val bytes = upstream.read(index * chunkSize, chunkSize)
            synchronized(chunks) { chunks[index] = bytes }
            return bytes
        }
    }

    internal class LoadedVolume(
        private val entries: Map<String, Udf.FileEntry>,
        val names: Map<String, String>,
        val hasMetadataPartition: Boolean,
        private val udf: Udf
    ) {
        fun files(physicalFileData: Boolean): Map<String, List<Extent>> =
            entries.mapNotNull { (path, entry) -> udf.fileExtents(entry, physicalFileData)?.let { path to it } }.toMap()
    }

    internal class Udf(private val reader: RangeReader) {
        private sealed interface PartitionMap {
            val number: Int

            data class Physical(override val number: Int) : PartitionMap
            data class Metadata(override val number: Int, val fileLocation: Long, val mirrorLocation: Long) : PartitionMap
            data class Unsupported(override val number: Int) : PartitionMap
        }

        data class Ad(val partition: Int, val block: Long, val length: Long, val type: Int)

        data class FileEntry(
            val fileType: Int,
            val length: Long,
            val ads: List<Ad>,
            val embeddedOffset: Long?,
            val embedded: ByteArray?
        )

        private data class Fid(val name: String, val directory: Boolean, val partition: Int, val block: Long)

        private var blockSize = SECTOR
        private val partitionStarts = HashMap<Int, Long>()
        private val maps = ArrayList<PartitionMap>()
        private var metadataExtents: List<Extent> = emptyList()

        fun load(): LoadedVolume? {
            val anchor = reader.read(ANCHOR_SECTOR * SECTOR, SECTOR.toInt())
            if (anchor.size < 24 || u16(anchor, 0) != 2) return null
            val vdsLength = u32(anchor, 16)
            val vdsLocation = u32(anchor, 20)
            val sectors = (vdsLength / SECTOR).toInt().coerceIn(1, MAX_VDS_SECTORS)
            val vds = reader.read(vdsLocation * SECTOR, (sectors * SECTOR).toInt())
            var fsdBlock = -1L
            var fsdPartition = 0
            for (sector in 0 until vds.size / SECTOR.toInt()) {
                val base = sector * SECTOR.toInt()
                when (u16(vds, base)) {
                    5 -> partitionStarts[u16(vds, base + 22)] = u32(vds, base + 188)
                    6 -> {
                        blockSize = u32(vds, base + 212).takeIf { it > 0 } ?: SECTOR
                        fsdBlock = u32(vds, base + 252)
                        fsdPartition = u16(vds, base + 256)
                        maps.clear()
                        parsePartitionMaps(vds, base + 440, u32(vds, base + 268).toInt())
                    }
                    8 -> break
                }
            }
            if (fsdBlock < 0 || maps.isEmpty() || partitionStarts.isEmpty()) return null
            val metadata = maps.filterIsInstance<PartitionMap.Metadata>().firstOrNull()
            if (metadata != null) {
                metadataExtents = loadMetadataExtents(metadata) ?: return null
            }
            val fsd = readBlocks(fsdPartition, fsdBlock, blockSize.toInt()) ?: return null
            if (u16(fsd, 0) != 256) return null
            val rootBlock = u32(fsd, 404)
            val rootPartition = u16(fsd, 408)
            val root = readEntry(rootPartition, rootBlock) ?: return null
            val bdmv = list(root, rootPartition).firstOrNull { it.directory && it.name.equals("BDMV", ignoreCase = true) } ?: return null
            val bdmvEntry = readEntry(bdmv.partition, bdmv.block) ?: return null
            val entries = LinkedHashMap<String, FileEntry>()
            val names = HashMap<String, String>()
            for (child in list(bdmvEntry, bdmv.partition)) {
                if (!child.directory) continue
                val dirName = child.name.uppercase(Locale.US)
                if (dirName != "PLAYLIST" && dirName != "STREAM" && dirName != "CLIPINF") continue
                val dirEntry = readEntry(child.partition, child.block) ?: continue
                for (file in list(dirEntry, child.partition)) {
                    if (file.directory) continue
                    val entry = readEntry(file.partition, file.block) ?: continue
                    val key = normalize("BDMV/$dirName/${file.name}")
                    entries[key] = entry
                    names[key] = file.name
                }
            }
            return LoadedVolume(entries, names, metadata != null, this)
        }

        fun fileExtents(entry: FileEntry, physicalFileData: Boolean): List<Extent>? {
            if (entry.embedded != null) {
                val offset = entry.embeddedOffset ?: return null
                return listOf(Extent(offset, minOf(entry.length, entry.embedded.size.toLong())))
            }
            val raw = ArrayList<Extent>()
            for (ad in entry.ads) {
                if (ad.type != 0) return null
                val map = maps.getOrNull(ad.partition) ?: return null
                when {
                    map is PartitionMap.Physical || (map is PartitionMap.Metadata && physicalFileData) -> {
                        val start = partitionStarts[map.number] ?: return null
                        raw += Extent((start + ad.block) * blockSize, ad.length)
                    }
                    map is PartitionMap.Metadata -> raw += metadataRuns(ad.block * blockSize, ad.length) ?: return null
                    else -> return null
                }
            }
            return trim(merge(raw), entry.length)
        }

        private fun parsePartitionMaps(data: ByteArray, start: Int, count: Int) {
            var position = start
            repeat(count) {
                if (position + 2 > data.size) return
                val type = u8(data, position)
                val length = u8(data, position + 1)
                if (length <= 0) return
                when (type) {
                    1 -> maps += PartitionMap.Physical(u16(data, position + 4))
                    2 -> {
                        val ident = ascii(data, position + 5, 23)
                        val number = u16(data, position + 38)
                        maps += when {
                            ident.startsWith("*UDF Metadata Partition") ->
                                PartitionMap.Metadata(number, u32(data, position + 40), u32(data, position + 44))
                            ident.startsWith("*UDF Sparable Partition") -> PartitionMap.Physical(number)
                            else -> PartitionMap.Unsupported(number)
                        }
                    }
                    else -> maps += PartitionMap.Unsupported(-1)
                }
                position += length
            }
        }

        private fun loadMetadataExtents(map: PartitionMap.Metadata): List<Extent>? {
            val start = partitionStarts[map.number] ?: return null
            for (location in listOf(map.fileLocation, map.mirrorLocation)) {
                val address = (start + location) * blockSize
                val block = runCatching { reader.read(address, blockSize.toInt()) }.getOrNull() ?: continue
                val entry = parseEntry(block, address, partition = -1) ?: continue
                if (entry.embedded != null) continue
                val extents = entry.ads.filter { it.type == 0 }.map { Extent((start + it.block) * blockSize, it.length) }
                if (extents.isNotEmpty()) return merge(extents)
            }
            return null
        }

        private fun metadataRuns(offset: Long, length: Long): List<Extent>? {
            val runs = ArrayList<Extent>()
            var logical = 0L
            var position = offset
            var remaining = length
            for (extent in metadataExtents) {
                if (remaining <= 0) break
                val end = logical + extent.length
                if (position < end) {
                    val inExtent = position - logical
                    val count = minOf(extent.length - inExtent, remaining)
                    runs += Extent(extent.offset + inExtent, count)
                    position += count
                    remaining -= count
                }
                logical = end
            }
            return if (remaining > 0) null else runs
        }

        private fun address(partition: Int, block: Long, length: Long): List<Extent>? {
            val map = maps.getOrNull(partition) ?: return null
            return when (map) {
                is PartitionMap.Physical -> partitionStarts[map.number]?.let { listOf(Extent((it + block) * blockSize, length)) }
                is PartitionMap.Metadata -> metadataRuns(block * blockSize, length)
                is PartitionMap.Unsupported -> null
            }
        }

        private fun readBlocks(partition: Int, block: Long, length: Int): ByteArray? {
            val runs = address(partition, block, length.toLong()) ?: return null
            return readRuns(runs, length)
        }

        private fun readRuns(runs: List<Extent>, length: Int): ByteArray? {
            val out = ByteArray(length)
            var written = 0
            for (run in runs) {
                if (written >= length) break
                val count = minOf(run.length, (length - written).toLong()).toInt()
                val bytes = reader.read(run.offset, count)
                if (bytes.size < count) return null
                System.arraycopy(bytes, 0, out, written, count)
                written += count
            }
            return if (written == length) out else null
        }

        private fun readEntry(partition: Int, block: Long): FileEntry? {
            val runs = address(partition, block, blockSize) ?: return null
            val data = readRuns(runs, blockSize.toInt()) ?: return null
            return parseEntry(data, runs.first().offset, partition)
        }

        private fun parseEntry(data: ByteArray, address: Long, partition: Int): FileEntry? {
            val (eaOffset, dataStart) = when (u16(data, 0)) {
                261 -> 168 to 176
                266 -> 208 to 216
                else -> return null
            }
            val fileType = u8(data, 27)
            val adType = u16(data, 34) and 7
            val length = u64(data, 56)
            val eaLength = u32(data, eaOffset).toInt()
            val adLength = u32(data, eaOffset + 4).toInt()
            val adStart = dataStart + eaLength
            if (eaLength < 0 || adLength < 0 || adStart + adLength > data.size) return null
            if (adType == 3) {
                return FileEntry(fileType, length, emptyList(), address + adStart, data.copyOfRange(adStart, adStart + adLength))
            }
            val ads = ArrayList<Ad>()
            if (!parseAds(data, adStart, adLength, adType, partition, ads, 0)) return null
            return FileEntry(fileType, length, ads, null, null)
        }

        private fun parseAds(data: ByteArray, start: Int, length: Int, adType: Int, partition: Int, out: MutableList<Ad>, depth: Int): Boolean {
            val size = when (adType) {
                0 -> 8
                1 -> 16
                2 -> 20
                else -> return false
            }
            var position = start
            while (position + size <= start + length) {
                val raw = u32(data, position)
                val extentLength = raw and 0x3FFFFFFF
                val type = (raw ushr 30).toInt()
                if (extentLength == 0L) break
                val (block, adPartition) = when (adType) {
                    0 -> u32(data, position + 4) to partition
                    1 -> u32(data, position + 4) to u16(data, position + 8)
                    else -> u32(data, position + 12) to u16(data, position + 16)
                }
                if (type == 3) {
                    if (depth >= MAX_AD_CHAIN) return false
                    val next = readBlocks(adPartition, block, blockSize.toInt()) ?: return false
                    if (u16(next, 0) != 258) return false
                    val nextLength = u32(next, 20).toInt().coerceAtMost(next.size - 24)
                    return parseAds(next, 24, nextLength, adType, adPartition, out, depth + 1)
                }
                out += Ad(adPartition, block, extentLength, type)
                position += size
            }
            return true
        }

        private fun list(directory: FileEntry, partition: Int): List<Fid> {
            val data = directoryData(directory, partition) ?: return emptyList()
            val result = ArrayList<Fid>()
            var position = 0
            while (position + 38 <= data.size) {
                if (u16(data, position) != 257) break
                val characteristics = u8(data, position + 18)
                val nameLength = u8(data, position + 19)
                val block = u32(data, position + 24)
                val fidPartition = u16(data, position + 28)
                val implementationLength = u16(data, position + 36)
                val nameStart = position + 38 + implementationLength
                if (nameStart + nameLength > data.size) break
                val parent = characteristics and 0x08 != 0
                val deleted = characteristics and 0x04 != 0
                if (!parent && !deleted && nameLength > 0) {
                    val name = decodeName(data, nameStart, nameLength)
                    if (name.isNotEmpty()) result += Fid(name, characteristics and 0x02 != 0, fidPartition, block)
                }
                position += (38 + implementationLength + nameLength + 3) and 3.inv()
            }
            return result
        }

        private fun directoryData(directory: FileEntry, partition: Int): ByteArray? {
            directory.embedded?.let { return it.copyOf(minOf(it.size.toLong(), directory.length).toInt()) }
            val total = directory.length.coerceAtMost(MAX_DIRECTORY_BYTES.toLong()).toInt()
            val runs = ArrayList<Extent>()
            for (ad in directory.ads) {
                if (ad.type != 0) return null
                runs += address(ad.partition, ad.block, ad.length) ?: return null
            }
            if (runs.isEmpty()) return null
            return readRuns(trim(merge(runs), total.toLong()), total)
        }

        private fun decodeName(data: ByteArray, start: Int, length: Int): String {
            if (length <= 1) return ""
            return when (u8(data, start)) {
                8 -> String(data, start + 1, length - 1, StandardCharsets.ISO_8859_1)
                16 -> String(data, start + 1, (length - 1) and 1.inv(), StandardCharsets.UTF_16BE)
                else -> ""
            }.trimEnd('\u0000')
        }

        private fun merge(extents: List<Extent>): List<Extent> {
            val merged = ArrayList<Extent>(extents.size)
            for (extent in extents) {
                val last = merged.lastOrNull()
                if (last != null && last.offset + last.length == extent.offset) {
                    merged[merged.size - 1] = Extent(last.offset, last.length + extent.length)
                } else {
                    merged += extent
                }
            }
            return merged
        }

        private fun trim(extents: List<Extent>, length: Long): List<Extent> {
            val result = ArrayList<Extent>(extents.size)
            var remaining = length
            for (extent in extents) {
                if (remaining <= 0) break
                val count = minOf(extent.length, remaining)
                result += Extent(extent.offset, count)
                remaining -= count
            }
            return result
        }

        private fun u8(data: ByteArray, offset: Int): Int =
            if (offset in data.indices) data[offset].toInt() and 0xFF else 0

        private fun u16(data: ByteArray, offset: Int): Int = u8(data, offset) or (u8(data, offset + 1) shl 8)

        private fun u32(data: ByteArray, offset: Int): Long =
            (u16(data, offset).toLong()) or (u16(data, offset + 2).toLong() shl 16)

        private fun u64(data: ByteArray, offset: Int): Long = u32(data, offset) or (u32(data, offset + 4) shl 32)

        private fun ascii(data: ByteArray, offset: Int, length: Int): String {
            if (offset < 0 || offset + length > data.size) return ""
            return String(data, offset, length, StandardCharsets.US_ASCII).trimEnd('\u0000', ' ')
        }
    }
}
