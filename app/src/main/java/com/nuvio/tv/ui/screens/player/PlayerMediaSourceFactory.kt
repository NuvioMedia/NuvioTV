package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class PlayerMediaSourceFactory(
    private val context: Context
) {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val playbackHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    private data class RemoteBlurayResolution(
        val playlistName: String,
        val segmentUris: List<Uri>
    )

    private data class ParsedMplsPlaylist(
        val name: String,
        val clipIds: List<String>,
        val duration90kHz: Long
    )

    fun createMediaSource(
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        mimeTypeOverride: String? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
            setDefaultRequestProperties(sanitizedHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }
        val baseDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val extractorsFactory = customExtractorsFactory ?: createDefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(baseDataSourceFactory, extractorsFactory).apply {
            customSubtitleParserFactory?.let { parserFactory ->
                setSubtitleParserFactory(parserFactory)
            }
        }
        val forceDefaultFactory = customExtractorsFactory != null || customSubtitleParserFactory != null

        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(url = url, filename = null)
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)

        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }

        val mediaItem = mediaItemBuilder.build()
        val blurayLocalSource = BlurayPlaylistResolver.resolve(url)
        if (blurayLocalSource != null) {
            return createBlurayMediaSource(
                source = blurayLocalSource,
                dataSourceFactory = baseDataSourceFactory,
                subtitleConfigurations = subtitleConfigurations
            )
        }

        if (!isHls && !isDash) {
            if (isLikelyHttpBdavStream(url = url, headers = sanitizedHeaders)) {
                return createBdavM2tsMediaSource(
                    mediaItem = mediaItem,
                    dataSourceFactory = baseDataSourceFactory
                )
            }

            val remoteBluraySource = resolveHttpBlurayDirectory(
                url = url,
                headers = sanitizedHeaders
            )
            if (remoteBluraySource != null) {
                return createBlurayMediaSource(
                    playlistName = remoteBluraySource.playlistName,
                    segmentUris = remoteBluraySource.segmentUris,
                    dataSourceFactory = baseDataSourceFactory,
                    subtitleConfigurations = subtitleConfigurations
                )
            }
        }

        if (BlurayPlaylistResolver.isLikelyBdavM2tsUrl(url)) {
            return createBdavM2tsMediaSource(
                mediaItem = mediaItem,
                dataSourceFactory = baseDataSourceFactory
            )
        }

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return defaultFactory.createMediaSource(mediaItem)
        }

        return when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
    }

    fun shutdown() = Unit

    private fun createDefaultExtractorsFactory(): ExtractorsFactory {
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    private fun createBdavExtractorsFactory(): ExtractorsFactory {
        val tsFlags =
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        return DefaultExtractorsFactory()
            .setTsExtractorFlags(tsFlags)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }

    private fun createBlurayMediaSource(
        source: BlurayPlaylistResolver.ResolvedBluraySource,
        dataSourceFactory: DataSource.Factory,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>
    ): MediaSource {
        return createBlurayMediaSource(
            playlistName = source.playlistFile.name,
            segmentUris = source.segments.map { segment -> Uri.fromFile(segment) },
            dataSourceFactory = dataSourceFactory,
            subtitleConfigurations = subtitleConfigurations
        )
    }

    private fun createBlurayMediaSource(
        playlistName: String,
        segmentUris: List<Uri>,
        dataSourceFactory: DataSource.Factory,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>
    ): MediaSource {
        val concatenatingMediaSource = ConcatenatingMediaSource()
        segmentUris.forEachIndexed { index, segmentUri ->
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(segmentUri)
                .setMediaId("$playlistName#$index")
            if (segmentUris.size == 1 && subtitleConfigurations.isNotEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
            }
            val segmentMediaItem = mediaItemBuilder
                .build()
            concatenatingMediaSource.addMediaSource(
                createBdavM2tsMediaSource(
                    mediaItem = segmentMediaItem,
                    dataSourceFactory = dataSourceFactory
                )
            )
        }
        return concatenatingMediaSource
    }

    private fun createBdavM2tsMediaSource(
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val bdavDataSourceFactory = BdavM2tsDataSourceFactory(dataSourceFactory)
        val extractorsFactory = createBdavExtractorsFactory()
        return ProgressiveMediaSource.Factory(bdavDataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItem)
    }

    private fun isLikelyHttpBdavStream(
        url: String,
        headers: Map<String, String>
    ): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        if (httpUrl.scheme !in listOf("http", "https")) return false

        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()

        return try {
            playbackHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                val body = response.body ?: return false
                val sample = body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                }
                if (sample.isEmpty()) return false

                val likelyTsMime =
                    contentType.contains("video/vnd.dlna.mpeg-tts") ||
                        contentType.contains("video/mp2t") ||
                        contentType.contains("mpeg-tts")
                if (!likelyTsMime) return false

                matchesBdavPacketPattern(sample)
            }
        } catch (error: Exception) {
            Log.w(
                TAG,
                "HTTP BDAV probe exception for url=${summarizeUrlForLog(url)}: ${error.javaClass.simpleName}: ${error.message}",
                error
            )
            false
        }
    }

    private fun matchesBdavPacketPattern(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        if ((bytes[4].toInt() and 0xFF) != 0x47) return false

        // Strong match when two sync bytes align at 192-byte distance (4 + 192).
        if (bytes.size >= 197 && (bytes[196].toInt() and 0xFF) == 0x47) {
            return true
        }
        // Soft fallback for short probes where second packet boundary is unavailable.
        return bytes.size < 197
    }

    private fun resolveHttpBlurayDirectory(
        url: String,
        headers: Map<String, String>
    ): RemoteBlurayResolution? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (httpUrl.scheme !in listOf("http", "https")) return null

        val rootProbe = fetchText(
            url = httpUrl.toString(),
            headers = headers,
            maxBytes = 256 * 1024
        ) ?: return null
        if (!looksLikeBlurayDirectoryHtml(rootProbe)) return null

        val rootUrl = if (httpUrl.encodedPath.endsWith("/")) {
            httpUrl
        } else {
            httpUrl.newBuilder().addPathSegment("").build()
        }
        val playlistDirUrl = rootUrl.resolve("BDMV/PLAYLIST/") ?: return null
        val playlistHtml = fetchText(
            url = playlistDirUrl.toString(),
            headers = headers,
            maxBytes = 512 * 1024
        ) ?: return null
        val playlistNames = extractPlaylistNames(playlistHtml)
        if (playlistNames.isEmpty()) {
            Log.w(TAG, "HTTP Blu-ray directory detected but no .mpls found at ${playlistDirUrl.encodedPath}")
            return null
        }

        val parsedPlaylists = playlistNames.mapNotNull { playlistName ->
            val playlistUrl = playlistDirUrl.resolve(playlistName) ?: return@mapNotNull null
            val bytes = fetchBytes(
                url = playlistUrl.toString(),
                headers = headers,
                maxBytes = 1024 * 1024
            ) ?: return@mapNotNull null
            parseMplsPlaylist(name = playlistName, data = bytes)
        }
        if (parsedPlaylists.isEmpty()) {
            Log.w(TAG, "Failed to parse all remote .mpls playlists under ${playlistDirUrl.encodedPath}")
            return null
        }

        val selected = parsedPlaylists.maxWithOrNull(
            compareBy<ParsedMplsPlaylist> { it.duration90kHz }
                .thenBy { it.clipIds.size }
                .thenBy { it.name }
        ) ?: return null

        val streamDirUrl = rootUrl.resolve("BDMV/STREAM/") ?: return null
        val segmentUris = selected.clipIds.mapNotNull { clipId ->
            streamDirUrl.resolve("$clipId.m2ts")?.let { resolved -> Uri.parse(resolved.toString()) }
        }
        if (segmentUris.isEmpty()) {
            Log.w(TAG, "Selected remote playlist=${selected.name} but produced zero segment URIs")
            return null
        }

        return RemoteBlurayResolution(
            playlistName = selected.name,
            segmentUris = segmentUris
        )
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int
    ): String? {
        val bytes = fetchBytes(url, headers, maxBytes) ?: return null
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(
                TAG,
                "HTTP probe text decode failed: url=${summarizeUrlForLog(url)} bytes=${bytes.size} error=${error.javaClass.simpleName}: ${error.message}",
                error
            )
            null
        }
    }

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int
    ): ByteArray? {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val request = requestBuilder.build()
        return try {
            playbackHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                val contentType = response.header("Content-Type").orEmpty()
                val contentLength = response.header("Content-Length").orEmpty()
                val location = response.header("Location").orEmpty()
                val body = response.body
                if (body == null) {
                    Log.w(
                        TAG,
                        "HTTP probe failed: empty body code=$responseCode type=$contentType len=$contentLength location=$location url=${summarizeUrlForLog(url)}"
                    )
                    return null
                }
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val toRead = minOf(buffer.size, maxBytes - total)
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    val bytes = output.toByteArray()
                    if (!response.isSuccessful) {
                        val hexPreview = toHexPreview(bytes, 24)
                        val asciiPreview = toAsciiPreview(bytes, 96)
                        val payloadType = classifyPayload(contentType, bytes)
                        Log.w(
                            TAG,
                            "HTTP probe failed: code=$responseCode type=$contentType len=$contentLength location=$location payload=$payloadType bytes=${bytes.size} hex=$hexPreview ascii=\"$asciiPreview\" url=${summarizeUrlForLog(url)}"
                        )
                        return null
                    }
                    bytes
                }
            }
        } catch (error: Exception) {
            Log.w(
                TAG,
                "HTTP probe exception for url=${summarizeUrlForLog(url)}: ${error.javaClass.simpleName}: ${error.message}",
                error
            )
            null
        }
    }

    private fun classifyPayload(contentType: String, bytes: ByteArray): String {
        val ct = contentType.lowercase(Locale.US)
        if (bytes.isEmpty()) return "empty"
        if (ct.contains("text/html")) return "html"
        if (ct.contains("application/json") || ct.contains("text/json")) return "json"
        if (ct.startsWith("text/")) return "text"
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return "matroska"
        }
        if (bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == '!'.code.toByte()) {
            return "html-like"
        }
        if (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) {
            return "json-like"
        }
        return "binary"
    }

    private fun toHexPreview(bytes: ByteArray, max: Int): String {
        if (bytes.isEmpty()) return "<empty>"
        val count = minOf(bytes.size, max)
        return bytes
            .take(count)
            .joinToString(" ") { value -> "%02X".format(value.toInt() and 0xFF) } +
            if (bytes.size > max) " ..." else ""
    }

    private fun toAsciiPreview(bytes: ByteArray, max: Int): String {
        if (bytes.isEmpty()) return "<empty>"
        val count = minOf(bytes.size, max)
        val builder = StringBuilder(count)
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 0xFF
            val char = b.toChar()
            builder.append(
                if (char in ' '..'~') char else '.'
            )
        }
        if (bytes.size > max) builder.append("...")
        return builder.toString()
    }

    private fun looksLikeBlurayDirectoryHtml(content: String): Boolean {
        val lower = content.lowercase(Locale.US)
        return (lower.contains("<html") || lower.contains("<a ")) &&
            lower.contains("bdmv") &&
            (lower.contains("certificate") || lower.contains("playlist"))
    }

    private fun extractPlaylistNames(content: String): List<String> {
        return Regex("""(?i)(\d{5}\.mpls)""")
            .findAll(content)
            .map { match -> match.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun parseMplsPlaylist(name: String, data: ByteArray): ParsedMplsPlaylist? {
        if (data.size < 32) return null
        val header = readAscii(data, 0, 4)
        if (header != "MPLS") return null

        val playlistStart = readUInt32(data, 8).toInt()
        if (playlistStart <= 0 || playlistStart + 10 > data.size) return null

        val sectionStart = playlistStart + 4
        val playItemCount = readUInt16(data, sectionStart + 2)
        var cursor = sectionStart + 6

        val clipIds = ArrayList<String>(playItemCount)
        var duration90kHz = 0L

        repeat(playItemCount) {
            if (cursor + 2 > data.size) return@repeat
            val itemLength = readUInt16(data, cursor)
            if (itemLength <= 0) return@repeat
            val itemStart = cursor + 2
            val itemEnd = itemStart + itemLength
            if (itemEnd > data.size || itemStart + 20 > data.size) return@repeat

            val clipId = readAscii(data, itemStart, 5)
            val codecId = readAscii(data, itemStart + 5, 4)
            if (
                clipId.length == 5 &&
                clipId.all { char -> char.isDigit() } &&
                codecId.equals("M2TS", ignoreCase = true)
            ) {
                clipIds += clipId
            }

            val inTime = readUInt32(data, itemStart + 12)
            val outTime = readUInt32(data, itemStart + 16)
            if (outTime > inTime) {
                duration90kHz += (outTime - inTime)
            }

            cursor = itemEnd
        }

        if (clipIds.isEmpty()) return null
        return ParsedMplsPlaylist(
            name = name,
            clipIds = clipIds,
            duration90kHz = duration90kHz
        )
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
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
        return String(data, offset, length, Charsets.US_ASCII).trim()
    }

    private fun summarizeUrlForLog(url: String): String {
        return summarizeUriForLog(runCatching { Uri.parse(url) }.getOrNull())
    }

    private fun summarizeUriForLog(uri: Uri?): String {
        if (uri == null) return "<null>"
        val scheme = uri.scheme
        val host = uri.host
        val path = uri.path.orEmpty()
        return when {
            !scheme.isNullOrBlank() && !host.isNullOrBlank() -> "$scheme://$host$path"
            !scheme.isNullOrBlank() && path.isNotBlank() -> "$scheme:$path"
            else -> uri.toString().substringBefore('?')
        }
    }

    private fun extractPath(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed != null && !parsed.path.isNullOrBlank()) {
            return parsed.path ?: url
        }
        return url.substringBefore('?')
    }

    companion object {
        private const val TAG = "PlayerMediaSourceFactory"
        private const val PROBE_TIMEOUT_MS = 4000
        private const val PROBE_BYTES = 1024
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
            val raw: Map<*, *> = headers ?: return emptyMap()
            if (raw.isEmpty()) return emptyMap()

            val sanitized = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (rawKey, rawValue) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                val value = (rawValue as? String)?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (key.equals("Range", ignoreCase = true)) return@forEach
                sanitized[key] = value
            }
            return sanitized
        }

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()

            return try {
                val parsed = headers.split("&").associate { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }.filterKeys { it.isNotEmpty() }
                sanitizeHeaders(parsed)
            } catch (_: Exception) {
                emptyMap()
            }
        }

        internal fun inferMimeType(url: String, filename: String?): String? {
            return inferMimeTypeFromPath(filename)
                ?: inferMimeTypeFromPath(url)
        }

        internal fun normalizeMimeType(contentType: String?): String? {
            val normalized = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                ?: return null

            return when (normalized) {
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8

                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        internal fun sniffManifestMimeType(snippet: String?): String? {
            val normalized = snippet
                ?.trimStart()
                ?.lowercase(Locale.US)
                ?: return null

            return when {
                normalized.startsWith("#extm3u") -> MimeTypes.APPLICATION_M3U8
                normalized.startsWith("<?xml") && normalized.contains("<mpd") -> MimeTypes.APPLICATION_MPD
                normalized.startsWith("<mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        suspend fun probeMimeType(
            url: String,
            headers: Map<String, String>,
            filename: String? = null
        ): String? {
            inferMimeType(url = url, filename = filename)?.let { return it }

            val sanitizedHeaders = sanitizeHeaders(headers)

            return withContext(Dispatchers.IO) {
                probeMimeTypeWithHead(url, sanitizedHeaders)
                    ?: probeMimeTypeWithRangeGet(url, sanitizedHeaders)
            }
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path
                ?.substringBefore('#')
                ?.substringBefore('?')
                ?.lowercase(Locale.US)
                ?.trim()
                ?: return null

            return when {
                normalized.endsWith(".m3u8") ||
                    normalized.contains("/playlist") ||
                    normalized.contains("/hls") ||
                    normalized.contains("m3u8") -> MimeTypes.APPLICATION_M3U8

                normalized.endsWith(".mpd") ||
                    normalized.contains("/dash") -> MimeTypes.APPLICATION_MPD

                else -> null
            }
        }

        private fun probeMimeTypeWithHead(url: String, headers: Map<String, String>): String? {
            val connection = openConnection(url = url, headers = headers, method = "HEAD")
            return try {
                connection.responseCode
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(url = connection.url?.toString().orEmpty(), filename = null)
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        private fun probeMimeTypeWithRangeGet(url: String, headers: Map<String, String>): String? {
            val connection = openConnection(
                url = url,
                headers = headers,
                method = "GET",
                range = "bytes=0-${PROBE_BYTES - 1}"
            )
            return try {
                connection.responseCode
                normalizeMimeType(connection.contentType)
                    ?: inferMimeType(url = connection.url?.toString().orEmpty(), filename = null)
                    ?: sniffManifestMimeType(readProbeSnippet(connection.inputStream))
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        private fun openConnection(
            url: String,
            headers: Map<String, String>,
            method: String,
            range: String? = null
        ): HttpURLConnection {
            return (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                headers.forEach { (key, value) ->
                    if (key.equals("Range", ignoreCase = true)) return@forEach
                    if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                    setRequestProperty(key, value)
                }
                range?.let { setRequestProperty("Range", it) }
            }
        }

        private fun readProbeSnippet(inputStream: InputStream?): String? {
            if (inputStream == null) return null
            val buffer = ByteArray(PROBE_BYTES)
            val read = inputStream.read(buffer)
            if (read <= 0) return null
            return String(buffer, 0, read, Charsets.UTF_8)
        }
    }
}
