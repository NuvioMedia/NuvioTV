package com.nuvio.tv.data.iptv

import android.content.Context
import android.util.Log
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class IptvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("addonPermissive") baseClient: OkHttpClient
) {
    companion object {
        private const val TAG = "IptvRepository"
        private const val PLAYLIST_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val EPG_MAX_AGE_MS = 12L * 60 * 60 * 1000
        private const val EPG_PAST_WINDOW_MS = 6L * 60 * 60 * 1000
        private const val EPG_FUTURE_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    }

    private class UserAgentTag(val value: String)

    private val client: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val tag = request.tag(UserAgentTag::class.java)
            if (tag != null) {
                chain.proceed(request.newBuilder().header("User-Agent", tag.value).build())
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var loadingSignature: String? = null
    private var loadedSignature: String? = null

    private val _content = MutableStateFlow(IptvContent())
    val content: StateFlow<IptvContent> = _content.asStateFlow()

    private val _status = MutableStateFlow<IptvLoadStatus>(IptvLoadStatus.Idle)
    val status: StateFlow<IptvLoadStatus> = _status.asStateFlow()

    private fun signatureOf(playlists: List<IptvPlaylist>): String =
        playlists.filter { it.enabled }.joinToString(";") {
            listOf(it.id, it.type.name, it.url, it.username, it.password, it.epgUrl, it.userAgent).joinToString("|")
        }

    @Synchronized
    fun ensureLoaded(playlists: List<IptvPlaylist>, force: Boolean = false) {
        val signature = signatureOf(playlists)
        if (!force && (signature == loadedSignature || (signature == loadingSignature && loadJob?.isActive == true))) {
            return
        }
        loadJob?.cancel()
        loadingSignature = signature
        loadJob = scope.launch {
            runLoad(playlists.filter { it.enabled }, force, signature)
        }
    }

    suspend fun deleteCache(playlistId: String) {
        withContext(Dispatchers.IO) {
            runCatching { playlistDir(playlistId).deleteRecursively() }
        }
    }

    private suspend fun runLoad(playlists: List<IptvPlaylist>, force: Boolean, signature: String) {
        if (playlists.isEmpty()) {
            _content.value = IptvContent(loadedAt = System.currentTimeMillis())
            _status.value = IptvLoadStatus.Idle
            markLoaded(signature)
            return
        }

        val channels = ArrayList<IptvChannel>()
        val epgSources = ArrayList<Pair<IptvPlaylist, List<String>>>()
        val errors = ArrayList<String>()

        for (playlist in playlists) {
            coroutineContext.ensureActive()
            _status.value = IptvLoadStatus.Loading(IptvLoadStage.PLAYLIST, playlist.name)
            try {
                val (playlistChannels, headerEpgUrls) = loadChannels(playlist, force)
                channels.addAll(playlistChannels)
                val epgUrls = playlist.epgUrl.split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { headerEpgUrls }
                epgSources.add(playlist to epgUrls)
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                Log.w(TAG, "Failed to load playlist ${playlist.name}", error)
                errors.add("${playlist.name}: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        val groups = buildGroups(channels, playlists)
        val previousEpg = _content.value.epg
        _content.value = IptvContent(
            channels = channels,
            groups = groups,
            epg = channels.mapTo(HashSet()) { it.key }.let { keys -> previousEpg.filterKeys { it in keys } },
            loadedAt = System.currentTimeMillis()
        )

        if (channels.isEmpty()) {
            _status.value = IptvLoadStatus.Error(errors.joinToString("\n").ifBlank { "No channels found" })
            markLoaded(signature)
            return
        }

        val now = System.currentTimeMillis()
        val windowStart = now - EPG_PAST_WINDOW_MS
        val windowEnd = now + EPG_FUTURE_WINDOW_MS
        val collected = HashMap<String, MutableList<IptvProgramme>>()
        for ((playlist, urls) in epgSources) {
            if (urls.isEmpty()) continue
            val playlistChannels = channels.filter { it.playlistId == playlist.id }
            urls.forEachIndexed { index, url ->
                coroutineContext.ensureActive()
                _status.value = IptvLoadStatus.Loading(IptvLoadStage.EPG, playlist.name)
                try {
                    val file = File(playlistDir(playlist.id), "epg_$index.xml")
                    fetchOrUseCache(url, file, playlist.userAgent, force, EPG_MAX_AGE_MS)
                    openMaybeGzip(file).use { input ->
                        val parser = Xml.newPullParser()
                        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
                        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
                        parser.setInput(input, null)
                        XmltvParser.parse(parser, playlistChannels, windowStart, windowEnd, collected)
                    }
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    Log.w(TAG, "Failed to load EPG $url", error)
                }
            }
        }

        val epg = HashMap<String, List<IptvProgramme>>(collected.size)
        collected.forEach { (key, list) -> epg[key] = XmltvParser.normalizeProgrammes(list) }
        _content.value = _content.value.copy(epg = epg, loadedAt = System.currentTimeMillis())
        _status.value = if (errors.isEmpty()) IptvLoadStatus.Idle else IptvLoadStatus.Error(errors.joinToString("\n"))
        markLoaded(signature)
    }

    @Synchronized
    private fun markLoaded(signature: String) {
        if (loadingSignature == signature) {
            loadedSignature = signature
        }
    }

    private fun loadChannels(playlist: IptvPlaylist, force: Boolean): Pair<List<IptvChannel>, List<String>> {
        val dir = playlistDir(playlist.id)
        return when (playlist.type) {
            IptvSourceType.M3U -> {
                val file = File(dir, "playlist.m3u")
                fetchOrUseCache(playlist.url.trim(), file, playlist.userAgent, force, PLAYLIST_MAX_AGE_MS)
                val result = BufferedReader(InputStreamReader(openMaybeGzip(file), Charsets.UTF_8), 64 * 1024).use { reader ->
                    M3uParser.parse(
                        reader = reader,
                        playlistId = playlist.id,
                        defaultUserAgent = playlist.userAgent.takeIf { it.isNotBlank() }
                    )
                }
                result.channels to result.epgUrls
            }
            IptvSourceType.XTREAM -> {
                val categoriesFile = File(dir, "xt_categories.json")
                val streamsFile = File(dir, "xt_streams.json")
                val refresh = force || isStale(streamsFile, PLAYLIST_MAX_AGE_MS) || !categoriesFile.exists()
                fetchOrUseCache(XtreamCodes.playerApiUrl(playlist, "get_live_categories"), categoriesFile, playlist.userAgent, refresh, PLAYLIST_MAX_AGE_MS)
                fetchOrUseCache(XtreamCodes.playerApiUrl(playlist, "get_live_streams"), streamsFile, playlist.userAgent, refresh, PLAYLIST_MAX_AGE_MS)
                val categories = runCatching { XtreamCodes.parseCategories(categoriesFile.readText()) }.getOrDefault(emptyList())
                val channels = try {
                    XtreamCodes.parseLiveStreams(streamsFile.readText(), playlist, categories)
                } catch (error: Exception) {
                    streamsFile.delete()
                    throw error
                }
                channels to listOf(XtreamCodes.epgUrl(playlist))
            }
        }
    }

    private fun buildGroups(channels: List<IptvChannel>, playlists: List<IptvPlaylist>): List<IptvGroup> {
        val multiple = playlists.size > 1
        val names = playlists.associate { it.id to it.name }
        val counts = LinkedHashMap<String, Int>()
        val labels = HashMap<String, String>()
        channels.forEach { channel ->
            val id = IptvGroupIds.forGroup(channel.playlistId, channel.group)
            counts[id] = (counts[id] ?: 0) + 1
            if (id !in labels) {
                val base = channel.group.ifBlank { "Uncategorized" }
                labels[id] = if (multiple) "$base · ${names[channel.playlistId].orEmpty()}" else base
            }
        }
        return counts.map { (id, count) -> IptvGroup(id = id, label = labels[id].orEmpty(), count = count) }
    }

    private fun playlistDir(playlistId: String): File {
        val safe = playlistId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(context.filesDir, "iptv"), safe).apply { mkdirs() }
    }

    private fun isStale(file: File, maxAgeMs: Long): Boolean =
        !file.exists() || file.length() == 0L || System.currentTimeMillis() - file.lastModified() > maxAgeMs

    private fun fetchOrUseCache(url: String, target: File, userAgent: String, force: Boolean, maxAgeMs: Long) {
        if (!force && !isStale(target, maxAgeMs)) return
        try {
            download(url, target, userAgent)
        } catch (error: IOException) {
            if (!target.exists() || target.length() == 0L) throw error
            Log.w(TAG, "Using cached copy after download failure", error)
        }
    }

    private fun download(url: String, target: File, userAgent: String) {
        val builder = Request.Builder().url(url)
        if (userAgent.isNotBlank()) builder.tag(UserAgentTag::class.java, UserAgentTag(userAgent.trim()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (temp.length() == 0L) {
                temp.delete()
                throw IOException("Empty response")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(FileInputStream(file), 64 * 1024)
        input.mark(4)
        val first = input.read()
        val second = input.read()
        input.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }
}
