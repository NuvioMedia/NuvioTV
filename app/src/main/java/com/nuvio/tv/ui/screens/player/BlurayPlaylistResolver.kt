package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object BlurayPlaylistResolver {
    private const val TAG = "BlurayResolver"

    data class ResolvedBluraySource(
        val playlistFile: File,
        val segments: List<File>
    )

    private data class BlurayLocation(
        val bdmvDir: File,
        val explicitPlaylist: File?
    )

    private data class ParsedPlaylist(
        val file: File,
        val clipIds: List<String>,
        val duration90kHz: Long
    )

    fun resolve(url: String): ResolvedBluraySource? {
        val likelyBluray = looksLikeBlurayPath(url)

        val input = toLocalFile(url)
        if (input == null) {
            if (likelyBluray) {
                Log.w(TAG, "resolve: failed to map URL to local file path: $url")
            }
            return null
        }
        val location = locateBluray(input)
        if (location == null) {
            if (likelyBluray || input.name.contains("bdmv", ignoreCase = true)) {
                Log.w(TAG, "resolve: path is not a supported BDMV entry point: ${input.absolutePath}")
            }
            return null
        }

        val playlists = selectPlaylists(location)
        if (playlists.isEmpty()) {
            Log.w(TAG, "resolve: no .mpls playlists found in ${location.bdmvDir.absolutePath}")
            return null
        }

        val parsedPlaylists = playlists.mapNotNull(::parsePlaylist)
        if (parsedPlaylists.isEmpty()) {
            Log.w(TAG, "resolve: playlist parse failed for all .mpls files in ${location.bdmvDir.absolutePath}")
            return null
        }

        val selected = parsedPlaylists.maxWithOrNull(
            compareBy<ParsedPlaylist> { it.duration90kHz }
                .thenBy { it.clipIds.size }
                .thenBy { it.file.name }
        ) ?: return null

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
        val segments = selected.clipIds.mapNotNull { clipId ->
            streamLookup["$clipId.m2ts".lowercase(Locale.US)]
                ?: run {
                    missingClipIds += clipId
                    null
                }
        }

        if (missingClipIds.isNotEmpty()) {
            val preview = missingClipIds.take(8).joinToString(",")
            val suffix = if (missingClipIds.size > 8) ", ..." else ""
            Log.w(
                TAG,
                "resolve: missing ${missingClipIds.size} referenced clip(s) in STREAM: $preview$suffix"
            )
        }

        if (segments.isEmpty()) {
            Log.w(TAG, "resolve: selected playlist=${selected.file.name} resolved zero playable segments")
            return null
        }

        return ResolvedBluraySource(
            playlistFile = selected.file,
            segments = segments
        )
    }

    fun isLikelyBdavM2tsUrl(url: String): Boolean {
        val path = extractPath(url).lowercase(Locale.US)
        return path.endsWith(".m2ts") || path.endsWith(".mts") || path.endsWith(".m2t")
    }

    private fun toLocalFile(url: String): File? {
        if (url.isBlank()) return null
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val candidate = when {
            parsed == null || parsed.scheme.isNullOrEmpty() -> File(url)
            parsed.scheme.equals("file", ignoreCase = true) -> {
                val path = parsed.path ?: return null
                File(path)
            }
            else -> return null
        }
        if (candidate.exists()) return candidate

        val decodedPath = runCatching { Uri.decode(candidate.path) }.getOrNull().orEmpty()
        if (decodedPath.isNotBlank()) {
            val decoded = File(decodedPath)
            if (decoded.exists()) return decoded
        }
        return if (candidate.exists()) candidate else null
    }

    private fun locateBluray(input: File): BlurayLocation? {
        if (input.isDirectory) {
            if (input.name.equals("BDMV", ignoreCase = true)) {
                return BlurayLocation(bdmvDir = input, explicitPlaylist = null)
            }
            val bdmv = findChildDirectory(input, "BDMV")
            if (bdmv != null) return BlurayLocation(bdmvDir = bdmv, explicitPlaylist = null)
            return null
        }

        val parent = input.parentFile ?: return null
        return when {
            input.name.equals("index.bdmv", ignoreCase = true) ||
                input.name.equals("movieobject.bdmv", ignoreCase = true) -> {
                if (parent.name.equals("BDMV", ignoreCase = true)) {
                    BlurayLocation(parent, explicitPlaylist = null)
                } else {
                    null
                }
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

    private fun parsePlaylist(file: File): ParsedPlaylist? {
        val data = runCatching { file.readBytes() }
            .getOrElse { error ->
                Log.w(TAG, "parsePlaylist: failed to read ${file.absolutePath}: ${error.message}")
                return null
            }
        if (data.size < 32) {
            return null
        }
        val header = readAscii(data, 0, 4)
        if (header != "MPLS") {
            return null
        }

        val playlistStart = readUInt32(data, 8).toInt()
        if (playlistStart <= 0 || playlistStart + 10 > data.size) {
            return null
        }

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

        if (clipIds.isEmpty()) {
            return null
        }
        return ParsedPlaylist(file = file, clipIds = clipIds, duration90kHz = duration90kHz)
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
        return String(data, offset, length, StandardCharsets.US_ASCII).trim()
    }

    private fun findChildDirectory(parent: File, expectedName: String): File? {
        return parent.listFiles()?.firstOrNull { candidate ->
            candidate.isDirectory && candidate.name.equals(expectedName, ignoreCase = true)
        }
    }

    private fun extractPath(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed != null && !parsed.path.isNullOrBlank()) {
            return parsed.path ?: url
        }
        return url.substringBefore('?')
    }

    private fun looksLikeBlurayPath(url: String): Boolean {
        val path = extractPath(url).lowercase(Locale.US)
        return path.contains("bdmv") || path.endsWith(".mpls") || path.endsWith(".m2ts")
    }
}
