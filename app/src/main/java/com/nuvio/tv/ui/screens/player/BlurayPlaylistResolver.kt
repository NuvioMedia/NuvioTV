package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object BlurayPlaylistResolver {
    private const val TAG = "BlurayResolver"
    private const val BDAV_PACKET_SIZE = 192
    private const val TS_SYNC_BYTE = 0x47
    private const val HTTP_PROBE_BYTES = 256 * 1024
    private const val HTTP_LISTING_BYTES = 512 * 1024
    private const val HTTP_MPLS_BYTES = 1024 * 1024
    private const val MAX_CLIP_REPEATS = 2
    private const val MAX_HTTP_PLAYLISTS = 1000
    private const val HTTP_PLAYLIST_THREADS = 8
    private const val MIN_TITLE_MS = 60_000L
    private const val MAX_TITLES = 30
    private val CLIP_PATH = Regex("(?i)/BDMV/(STREAM)/(\\d{5})\\.(m2ts)$")
    private val ENTRY_PATH = Regex("(?i)/(BDMV)/(?:(index\\.bdmv)|(movieobject\\.bdmv)|(PLAYLIST)/((\\d{5})\\.(mpls)))$")

    data class ResolvedBluraySource(
        val playlistFile: File,
        val segments: List<File>,
        val durationsMs: List<Long> = emptyList()
    )

    sealed interface RemoteLayout {
        data object BdavClip : RemoteLayout

        data class Playlist(
            val name: String,
            val segmentUrls: List<String>,
            val durationsMs: List<Long> = emptyList()
        ) : RemoteLayout
    }

    data class DiscProbe(
        val feature: RemoteLayout.Playlist?,
        val currentTitle: String?,
        val titles: List<RemoteLayout.Playlist>
    )

    data class ParsedMpls(
        val name: String,
        val clipIds: List<String>,
        val duration90kHz: Long,
        val itemTicks: List<Long> = emptyList()
    ) {
        fun itemDurationMs(index: Int): Long = (itemTicks.getOrNull(index) ?: 0L) / 45

        val durationMs: Long get() = duration90kHz / 45
    }

    private data class BlurayLocation(
        val bdmvDir: File,
        val explicitPlaylist: File?
    )

    fun isLikelyBdavM2tsUrl(url: String): Boolean {
        val path = extractPath(url).lowercase(Locale.US)
        return path.endsWith(".m2ts") || path.endsWith(".mts")
    }

    fun resolve(url: String): ResolvedBluraySource? {
        val input = toLocalFile(url) ?: return null
        val location = locateBluray(input)
        if (location == null) {
            if (looksLikeBlurayPath(url) || input.name.contains("bdmv", ignoreCase = true)) {
                Log.w(TAG, "resolve: path is not a supported BDMV entry point: ${input.absolutePath}")
            }
            return null
        }

        val playlists = selectPlaylists(location)
        if (playlists.isEmpty()) {
            Log.w(TAG, "resolve: no .mpls playlists found in ${location.bdmvDir.absolutePath}")
            return null
        }

        val parsed = playlists.mapNotNull { file ->
            val data = runCatching { file.readBytes() }.getOrElse { error ->
                Log.w(TAG, "resolve: failed to read ${file.absolutePath}: ${error.message}")
                return@mapNotNull null
            }
            parseMpls(file.name, data)?.let { file to it }
        }
        val selected = selectMainPlaylist(parsed.map { it.second }) ?: run {
            Log.w(TAG, "resolve: playlist parse failed for all .mpls files in ${location.bdmvDir.absolutePath}")
            return null
        }
        val selectedFile = parsed.first { it.second === selected }.first

        val streamDir = findChildDirectory(location.bdmvDir, "STREAM")
        if (streamDir == null) {
            Log.w(TAG, "resolve: BDMV/STREAM directory not found in ${location.bdmvDir.absolutePath}")
            return null
        }
        val streamLookup = streamDir.listFiles()
            ?.filter { it.isFile }
            ?.associateBy { it.name.lowercase(Locale.US) }
            .orEmpty()

        val missingClipIds = mutableListOf<String>()
        val found = selected.clipIds.withIndex().mapNotNull { (index, clipId) ->
            streamLookup["$clipId.m2ts".lowercase(Locale.US)]?.let { it to selected.itemDurationMs(index) } ?: run {
                missingClipIds += clipId
                null
            }
        }
        val segments = found.map { it.first }
        if (missingClipIds.isNotEmpty()) {
            val preview = missingClipIds.take(8).joinToString(",")
            val suffix = if (missingClipIds.size > 8) ", ..." else ""
            Log.w(TAG, "resolve: missing ${missingClipIds.size} referenced clip(s) in STREAM: $preview$suffix")
        }
        if (segments.isEmpty()) {
            Log.w(TAG, "resolve: selected playlist=${selected.name} resolved zero playable segments")
            return null
        }
        return ResolvedBluraySource(playlistFile = selectedFile, segments = segments, durationsMs = found.map { it.second })
    }

    fun probeHttp(url: String, headers: Map<String, String>, client: OkHttpClient): RemoteLayout? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val head = fetchBytes(client, httpUrl, headers, HTTP_PROBE_BYTES) ?: return null
        if (isBdavPacketPattern(head)) return RemoteLayout.BdavClip
        if (!looksLikeBlurayDirectoryHtml(String(head, Charsets.UTF_8))) return null

        val rootUrl = if (httpUrl.encodedPath.endsWith("/")) httpUrl else httpUrl.newBuilder().addPathSegment("").build()
        val playlistDirUrl = rootUrl.resolve("BDMV/PLAYLIST/") ?: return null
        val listing = fetchBytes(client, playlistDirUrl, headers, HTTP_LISTING_BYTES) ?: return null
        val playlistNames = extractPlaylistNames(String(listing, Charsets.UTF_8))
        if (playlistNames.isEmpty()) {
            Log.w(TAG, "HTTP Blu-ray folder has no .mpls at ${playlistDirUrl.encodedPath}")
            return null
        }
        val selected = selectMainPlaylist(fetchPlaylists(client, playlistDirUrl, headers, playlistNames)) ?: return null

        val streamDirUrl = rootUrl.resolve("BDMV/STREAM/") ?: return null
        val segments = selected.clipIds.withIndex().mapNotNull { (index, clipId) ->
            streamDirUrl.resolve("$clipId.m2ts")?.let { it.toString() to selected.itemDurationMs(index) }
        }
        return segments.takeIf { it.isNotEmpty() }
            ?.let { RemoteLayout.Playlist(selected.name, it.map { s -> s.first }, it.map { s -> s.second }) }
    }

    fun probeClipPlaylist(url: String, headers: Map<String, String>, client: OkHttpClient): RemoteLayout.Playlist? =
        probeDisc(url, headers, client)?.feature

    fun probeDisc(url: String, headers: Map<String, String>, client: OkHttpClient): DiscProbe? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val match = CLIP_PATH.find(httpUrl.encodedPath) ?: return null
        val (stream, clipId, extension) = match.destructured
        val playlistDir = if (stream == stream.lowercase(Locale.US)) "playlist" else "PLAYLIST"
        val playlistDirUrl = httpUrl.resolve("../$playlistDir/") ?: return null
        val listing = fetchBytes(client, playlistDirUrl, headers, HTTP_LISTING_BYTES) ?: return null
        val names = extractPlaylistNames(String(listing, Charsets.UTF_8)).take(MAX_HTTP_PLAYLISTS)
        if (names.isEmpty()) return null
        val playlists = fetchPlaylists(client, playlistDirUrl, headers, names)
        if (playlists.isEmpty()) return null
        fun remote(playlist: ParsedMpls): RemoteLayout.Playlist {
            val segments = playlist.clipIds.withIndex().mapNotNull { (index, id) ->
                httpUrl.resolve("$id.$extension")?.let { it.toString() to playlist.itemDurationMs(index) }
            }
            return RemoteLayout.Playlist(playlist.name, segments.map { it.first }, segments.map { it.second })
        }
        val main = selectMainPlaylist(playlists.filter { clipId in it.clipIds })
        val feature = main?.takeUnless { playlist -> playlist.clipIds.all { it == clipId } }?.let(::remote)
        if (feature != null) Log.i(TAG, "clip $clipId is part of playlist ${feature.name}: ${feature.segmentUrls.size} clips")
        return DiscProbe(feature = feature, currentTitle = main?.name, titles = selectTitles(playlists).map(::remote))
    }

    fun isLikelyDiscEntryUrl(url: String): Boolean =
        url.toHttpUrlOrNull()?.let { ENTRY_PATH.containsMatchIn(it.encodedPath) } == true

    fun probeDiscEntry(url: String, headers: Map<String, String>, client: OkHttpClient): DiscProbe? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val match = ENTRY_PATH.find(httpUrl.encodedPath) ?: return null
        val groups = match.groupValues
        val bdmv = groups[1]
        val explicitName = groups[5].takeIf { it.isNotEmpty() }
        val upperDirs = groups[4].ifEmpty { bdmv }.let { it == it.uppercase(Locale.US) }
        val entryName = groups[2].ifEmpty { groups[3] }.ifEmpty { groups[7] }
        val upperFiles = entryName == entryName.uppercase(Locale.US)
        val prefix = httpUrl.encodedPath.substring(0, match.range.first) + "/$bdmv"
        val playlistDir = "$prefix/${groups[4].ifEmpty { if (upperDirs) "PLAYLIST" else "playlist" }}/"
        val streamDir = "$prefix/${if (upperDirs) "STREAM" else "stream"}/"
        val clipExtension = if (upperFiles) "M2TS" else "m2ts"
        val playlistExtension = if (upperFiles) "MPLS" else "mpls"
        val movieObject = if (upperFiles) "MOVIEOBJECT.BDMV" else "MovieObject.bdmv"
        fun sibling(path: String): HttpUrl =
            httpUrl.newBuilder().encodedPath(path).encodedQuery(httpUrl.encodedQuery).build()

        val listed = fetchBytes(client, sibling(playlistDir), headers, HTTP_LISTING_BYTES)
            ?.let { extractPlaylistNames(String(it, Charsets.UTF_8)) }
            .orEmpty()
        val referenced = if (listed.isEmpty()) {
            fetchBytes(client, sibling("$prefix/$movieObject"), headers, HTTP_MPLS_BYTES)
                ?.let { parseMovieObjectPlaylists(it) }
                .orEmpty()
                .map { String.format(Locale.US, "%05d.%s", it, playlistExtension) }
        } else {
            emptyList()
        }
        val names = (listOfNotNull(explicitName) + listed + referenced)
            .distinctBy { it.lowercase(Locale.US) }
            .take(MAX_HTTP_PLAYLISTS)
        if (names.isEmpty()) return null
        val playlists = fetchPlaylistsWith(names) { name ->
            fetchBytes(client, sibling(playlistDir + name), headers, HTTP_MPLS_BYTES)
        }
        if (playlists.isEmpty()) return null
        val main = if (explicitName != null) {
            playlists.firstOrNull { it.name.equals(explicitName, ignoreCase = true) }
        } else {
            selectMainPlaylist(playlists)
        } ?: return null
        Log.i(TAG, "disc entry ${explicitName ?: entryName}: playlist ${main.name} of ${playlists.size}")
        return discProbe(main, playlists) { playlist ->
            val segments = playlist.clipIds.withIndex().map { (index, id) ->
                sibling("$streamDir$id.$clipExtension").toString() to playlist.itemDurationMs(index)
            }
            RemoteLayout.Playlist(playlist.name, segments.map { it.first }, segments.map { it.second })
                .takeIf { segments.isNotEmpty() }
        }
    }

    fun discProbe(
        main: ParsedMpls,
        playlists: List<ParsedMpls>,
        remote: (ParsedMpls) -> RemoteLayout.Playlist?
    ): DiscProbe? {
        val feature = remote(main) ?: return null
        val titles = selectTitles(playlists)
        val current = titles.firstOrNull { it.name == main.name }
            ?: titles.firstOrNull { it.clipIds == main.clipIds && it.durationMs / 1000 == main.durationMs / 1000 }
        val remoteTitles = titles.mapNotNull { title ->
            if (title === current) feature.copy(name = title.name) else remote(title)
        }
        return DiscProbe(
            feature = if (current != null) feature.copy(name = current.name) else feature,
            currentTitle = current?.name,
            titles = remoteTitles
        )
    }

    fun parseMovieObjectPlaylists(data: ByteArray): List<Int> {
        if (data.size < 50 || readAscii(data, 0, 4) != "MOBJ") return emptyList()
        val result = LinkedHashSet<Int>()
        val objects = readUInt16(data, 48)
        var position = 50
        for (index in 0 until objects) {
            if (position + 4 > data.size) break
            val commands = readUInt16(data, position + 2)
            position += 4
            for (command in 0 until commands) {
                if (position + 12 > data.size) return result.toList()
                val operation = data[position].toInt() and 0xFF
                val options = data[position + 1].toInt() and 0xFF
                val group = (operation shr 3) and 0x03
                val subGroup = operation and 0x07
                val immediate = options and 0x80 != 0
                val branch = options and 0x0F
                if (group == 0 && subGroup == 2 && immediate && branch <= 2) {
                    val playlist = readUInt32(data, position + 4)
                    if (playlist in 0..99_999) result += playlist.toInt()
                }
                position += 12
            }
        }
        return result.toList()
    }

    fun selectTitles(playlists: List<ParsedMpls>): List<ParsedMpls> = playlists
        .filter { !it.isLoop() && it.durationMs >= MIN_TITLE_MS }
        .sortedWith(compareByDescending<ParsedMpls> { it.duration90kHz }.thenBy { it.name })
        .distinctBy { it.clipIds to it.durationMs / 1000 }
        .take(MAX_TITLES)

    private fun ParsedMpls.isLoop() = clipIds.groupingBy { it }.eachCount().values.any { it > MAX_CLIP_REPEATS }

    fun titleLanguages(
        title: RemoteLayout.Playlist,
        headers: Map<String, String>,
        client: OkHttpClient
    ): Pair<List<String>, List<String>> {
        val clip = title.segmentUrls.firstOrNull()?.toHttpUrlOrNull() ?: return emptyList<String>() to emptyList()
        val infoPath = BlurayClipInfo.clipInfoPath(clip.encodedPath) ?: return emptyList<String>() to emptyList()
        val bytes = fetchBytes(client, clip.newBuilder().encodedPath(infoPath).build(), headers, HTTP_MPLS_BYTES)
            ?: return emptyList<String>() to emptyList()
        return clipLanguages(bytes)
    }

    fun clipLanguages(bytes: ByteArray): Pair<List<String>, List<String>> {
        val languages = BlurayClipInfo.parseLanguages(bytes)
        fun among(vararg pids: IntRange) =
            languages.filterKeys { pid -> pids.any { pid in it } }.toSortedMap().values.distinct()
        return among(0x1100..0x111F) to among(0x1200..0x12FF, 0x1800..0x181F)
    }

    fun selectMainPlaylist(playlists: List<ParsedMpls>): ParsedMpls? = playlists
        .filter { !it.isLoop() }
        .maxWithOrNull(compareBy<ParsedMpls> { it.duration90kHz }.thenBy { it.clipIds.size }.thenBy { it.name })

    private fun fetchPlaylistsWith(names: List<String>, fetch: (String) -> ByteArray?): List<ParsedMpls> {
        val pool = Executors.newFixedThreadPool(HTTP_PLAYLIST_THREADS)
        return try {
            names.map { name ->
                pool.submit<ParsedMpls?> { fetch(name)?.let { parseMpls(name, it) } }
            }.mapNotNull { future -> runCatching { future.get(30, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchPlaylists(client: OkHttpClient, dir: HttpUrl, headers: Map<String, String>, names: List<String>): List<ParsedMpls> {
        val pool = Executors.newFixedThreadPool(HTTP_PLAYLIST_THREADS)
        return try {
            names.map { name ->
                pool.submit<ParsedMpls?> {
                    dir.resolve(name)?.let { fetchBytes(client, it, headers, HTTP_MPLS_BYTES) }?.let { parseMpls(name, it) }
                }
            }.mapNotNull { future -> runCatching { future.get(30, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    fun isBdavPacketPattern(bytes: ByteArray): Boolean {
        if (bytes.size < 2 * BDAV_PACKET_SIZE + 5) return false
        return (0..2).all { packet -> (bytes[packet * BDAV_PACKET_SIZE + 4].toInt() and 0xFF) == TS_SYNC_BYTE }
    }

    fun parseMpls(name: String, data: ByteArray): ParsedMpls? {
        if (data.size < 32 || readAscii(data, 0, 4) != "MPLS") return null

        val playlistStart = readUInt32(data, 8).toInt()
        if (playlistStart <= 0 || playlistStart + 10 > data.size) return null

        val sectionStart = playlistStart + 4
        val playItemCount = readUInt16(data, sectionStart + 2)
        var cursor = sectionStart + 6

        val clipIds = ArrayList<String>(playItemCount)
        val itemTicks = ArrayList<Long>(playItemCount)
        var duration90kHz = 0L
        for (item in 0 until playItemCount) {
            if (cursor + 2 > data.size) break
            val itemLength = readUInt16(data, cursor)
            val itemStart = cursor + 2
            val itemEnd = itemStart + itemLength
            if (itemLength <= 0 || itemEnd > data.size || itemStart + 20 > data.size) break

            val clipId = readAscii(data, itemStart, 5)
            val codecId = readAscii(data, itemStart + 5, 4)
            val inTime = readUInt32(data, itemStart + 12)
            val outTime = readUInt32(data, itemStart + 16)
            if (clipId.length == 5 && clipId.all { it.isDigit() } && codecId.equals("M2TS", ignoreCase = true)) {
                clipIds += clipId
                itemTicks += if (outTime > inTime) outTime - inTime else 0L
            }
            if (outTime > inTime) duration90kHz += outTime - inTime
            cursor = itemEnd
        }
        return if (clipIds.isEmpty()) null else ParsedMpls(name, clipIds, duration90kHz, itemTicks)
    }

    fun looksLikeBlurayDirectoryHtml(content: String): Boolean {
        val lower = content.lowercase(Locale.US)
        return (lower.contains("<html") || lower.contains("<a ")) &&
            lower.contains("bdmv") &&
            (lower.contains("certificate") || lower.contains("playlist"))
    }

    fun extractPlaylistNames(content: String): List<String> =
        Regex("""(?i)(\d{5}\.mpls)""").findAll(content).map { it.groupValues[1] }.distinct().toList()

    private fun fetchBytes(client: OkHttpClient, url: HttpUrl, headers: Map<String, String>, maxBytes: Int): ByteArray? {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (key, value) -> if (!key.equals("Range", ignoreCase = true)) header(key, value) }
            header("Range", "bytes=0-${maxBytes - 1}")
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP probe failed: code=${response.code} path=${url.encodedPath}")
                    return null
                }
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(maxBytes)
                    var total = 0
                    while (total < maxBytes) {
                        val read = input.read(buffer, total, maxBytes - total)
                        if (read <= 0) break
                        total += read
                    }
                    buffer.copyOf(total)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "HTTP probe exception for path=${url.encodedPath}: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun toLocalFile(url: String): File? {
        if (url.isBlank()) return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val candidate = when {
            parsed == null || parsed.scheme.isNullOrEmpty() -> File(url)
            parsed.scheme.equals("file", ignoreCase = true) -> File(parsed.path ?: return null)
            else -> return null
        }
        if (candidate.exists()) return candidate

        val decodedPath = runCatching { Uri.decode(candidate.path) }.getOrNull().orEmpty()
        if (decodedPath.isNotBlank()) {
            val decoded = File(decodedPath)
            if (decoded.exists()) return decoded
        }
        return null
    }

    private fun locateBluray(input: File): BlurayLocation? {
        if (input.isDirectory) {
            if (input.name.equals("BDMV", ignoreCase = true)) {
                return BlurayLocation(bdmvDir = input, explicitPlaylist = null)
            }
            val bdmv = findChildDirectory(input, "BDMV") ?: return null
            return BlurayLocation(bdmvDir = bdmv, explicitPlaylist = null)
        }

        val parent = input.parentFile ?: return null
        return when {
            input.name.equals("index.bdmv", ignoreCase = true) ||
                input.name.equals("movieobject.bdmv", ignoreCase = true) -> {
                if (parent.name.equals("BDMV", ignoreCase = true)) BlurayLocation(parent, explicitPlaylist = null) else null
            }
            input.extension.equals("mpls", ignoreCase = true) &&
                parent.name.equals("PLAYLIST", ignoreCase = true) &&
                parent.parentFile?.name.equals("BDMV", ignoreCase = true) -> {
                BlurayLocation(parent.parentFile!!, explicitPlaylist = input)
            }
            else -> null
        }
    }

    private fun selectPlaylists(location: BlurayLocation): List<File> {
        location.explicitPlaylist?.let { return listOf(it) }
        val playlistDir = findChildDirectory(location.bdmvDir, "PLAYLIST") ?: return emptyList()
        return playlistDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mpls", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun readAscii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length <= 0 || offset + length > data.size) return ""
        return String(data, offset, length, StandardCharsets.US_ASCII).trim()
    }

    private fun findChildDirectory(parent: File, expectedName: String): File? =
        parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(expectedName, ignoreCase = true) }

    private fun extractPath(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfter("://").let { rest ->
            if ('/' in rest) rest.substring(rest.indexOf('/')) else rest
        }

    private fun looksLikeBlurayPath(url: String): Boolean {
        val path = extractPath(url).lowercase(Locale.US)
        return path.contains("bdmv") || path.endsWith(".mpls") || path.endsWith(".m2ts")
    }
}
