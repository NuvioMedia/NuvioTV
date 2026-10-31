package com.nuvio.tv.data.iptv

import android.content.Context
import android.util.Log
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.ConcurrentHashMap
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
        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24 * HOUR_MS
        private const val RETRY_BACKOFF_MS = HOUR_MS
        private const val EPG_PAST_WINDOW_MS = 6 * HOUR_MS
        private const val MIN_EPG_FUTURE_DAYS = 3
        private const val MAX_EPG_FUTURE_DAYS = 8
        private const val EPG_CACHE_FILE = "epg.bin"
    }

    private class UserAgentTag(val value: String)

    private class PlaylistState(
        val playlist: IptvPlaylist,
        var channels: List<IptvChannel>,
        var epg: Map<String, List<IptvProgramme>>,
        var meta: IptvCacheMeta
    )

    private class ChannelsFetch(
        val channels: List<IptvChannel>,
        val headerEpgUrls: List<String>,
        val fresh: Boolean
    )

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
    private var lastPlaylists: List<IptvPlaylist> = emptyList()
    private val metas = ConcurrentHashMap<String, IptvCacheMeta>()

    private val _content = MutableStateFlow(IptvContent())
    val content: StateFlow<IptvContent> = _content.asStateFlow()

    private val _status = MutableStateFlow<IptvLoadStatus>(IptvLoadStatus.Idle)
    val status: StateFlow<IptvLoadStatus> = _status.asStateFlow()

    private fun signatureOf(playlists: List<IptvPlaylist>): String =
        playlists.joinToString(";") {
            listOf(it.id, it.type.name, it.url, it.username, it.password, it.epgUrl, it.userAgent).joinToString("|")
        }

    fun ensureLoaded(playlists: List<IptvPlaylist>, force: Boolean = false) {
        request(playlists, if (force) playlists.mapTo(HashSet()) { it.id } else emptySet())
    }

    fun refreshPlaylist(playlists: List<IptvPlaylist>, playlistId: String) {
        request(playlists, setOf(playlistId))
    }

    fun refreshIfDue() {
        request(lastPlaylists, emptySet())
    }

    @Synchronized
    private fun request(playlists: List<IptvPlaylist>, forced: Set<String>) {
        lastPlaylists = playlists
        val enabled = playlists.filter { it.enabled }
        val signature = signatureOf(enabled)
        val running = loadJob?.isActive == true
        if (forced.isEmpty()) {
            if (running && signature == loadingSignature) return
            if (!running && signature == loadedSignature) {
                val now = System.currentTimeMillis()
                if (enabled.none { isRefreshDue(it, now) }) return
            }
        }
        val reuseMemory = !running && signature == loadedSignature
        loadJob?.cancel()
        loadingSignature = signature
        loadJob = scope.launch {
            runLoad(enabled, forced, signature, reuseMemory)
        }
    }

    suspend fun deleteCache(playlistId: String) {
        withContext(Dispatchers.IO) {
            metas.remove(playlistId)
            runCatching { playlistDir(playlistId).deleteRecursively() }
        }
    }

    private fun isRefreshDue(playlist: IptvPlaylist, now: Long): Boolean {
        val meta = metas[playlist.id] ?: return true
        return intervalDue(meta.playlistFetchedAt, meta.playlistAttemptAt, playlist.playlistRefreshDays, now) ||
            (epgUrlsFor(playlist, meta).isNotEmpty() &&
                intervalDue(meta.epgFetchedAt, meta.epgAttemptAt, playlist.epgRefreshDays, now))
    }

    private fun intervalDue(fetchedAt: Long, attemptAt: Long, days: Int, now: Long): Boolean {
        if (attemptAt > fetchedAt && now - attemptAt < RETRY_BACKOFF_MS) return false
        if (fetchedAt <= 0L) return true
        if (days <= 0) return false
        return now - fetchedAt >= days * DAY_MS
    }

    private suspend fun runLoad(
        playlists: List<IptvPlaylist>,
        forced: Set<String>,
        signature: String,
        reuseMemory: Boolean
    ) {
        if (playlists.isEmpty()) {
            _content.value = IptvContent(loadedAt = System.currentTimeMillis())
            _status.value = IptvLoadStatus.Idle
            markLoaded(signature)
            return
        }

        val startedAt = System.currentTimeMillis()
        val states = playlists.map { playlist ->
            val dir = playlistDir(playlist.id)
            cleanupTempFiles(dir, startedAt)
            if (reuseMemory) {
                val current = _content.value
                val channels = current.channels.filter { it.playlistId == playlist.id }
                val keys = channels.mapTo(HashSet()) { it.key }
                PlaylistState(
                    playlist = playlist,
                    channels = channels,
                    epg = current.epg.filterKeys { it in keys },
                    meta = metas[playlist.id] ?: IptvCache.readMeta(dir)
                )
            } else {
                val channels = IptvCache.readChannels(dir).orEmpty()
                PlaylistState(
                    playlist = playlist,
                    channels = channels,
                    epg = if (channels.isEmpty()) emptyMap() else IptvCache.readEpg(dir, startedAt - EPG_PAST_WINDOW_MS).orEmpty(),
                    meta = IptvCache.readMeta(dir)
                )
            }
        }
        states.forEach { metas[it.playlist.id] = it.meta }
        if (!reuseMemory && states.any { it.channels.isNotEmpty() }) {
            publish(states, playlists)
        }

        val errors = ArrayList<String>()
        for (state in states) {
            coroutineContext.ensureActive()
            val playlist = state.playlist
            val dir = playlistDir(playlist.id)
            val force = playlist.id in forced
            val now = System.currentTimeMillis()
            var meta = state.meta

            val playlistDue = force || state.channels.isEmpty() ||
                intervalDue(meta.playlistFetchedAt, meta.playlistAttemptAt, playlist.playlistRefreshDays, now)
            var channelsChanged = false
            if (playlistDue) {
                _status.value = IptvLoadStatus.Loading(IptvLoadStage.PLAYLIST, playlist.name)
                meta = meta.copy(playlistAttemptAt = now)
                try {
                    val fetch = fetchChannels(playlist, dir)
                    state.channels = fetch.channels
                    channelsChanged = true
                    meta = meta.copy(
                        headerEpgUrls = fetch.headerEpgUrls,
                        playlistFetchedAt = if (fetch.fresh) now else meta.playlistFetchedAt
                    )
                    IptvCache.writeChannels(dir, fetch.channels)
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    Log.w(TAG, "Failed to load playlist ${playlist.name}", error)
                    if (state.channels.isEmpty()) {
                        errors.add("${playlist.name}: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                saveMeta(state, dir, meta)
                publish(states, playlists)
            }

            if (state.channels.isEmpty()) continue
            val epgUrls = epgUrlsFor(playlist, meta)
            if (epgUrls.isEmpty()) continue

            val epgDue = force || intervalDue(meta.epgFetchedAt, meta.epgAttemptAt, playlist.epgRefreshDays, now)
            val needsParse = epgDue || channelsChanged || !File(dir, EPG_CACHE_FILE).exists()
            if (!needsParse) continue

            _status.value = IptvLoadStatus.Loading(IptvLoadStage.EPG, playlist.name)
            if (epgDue) meta = meta.copy(epgAttemptAt = now)
            val futureDays = (playlist.epgRefreshDays + 1).coerceIn(MIN_EPG_FUTURE_DAYS, MAX_EPG_FUTURE_DAYS)
            val windowStart = now - EPG_PAST_WINDOW_MS
            val windowEnd = now + futureDays * DAY_MS
            val collected = HashMap<String, MutableList<IptvProgramme>>()
            var anyFresh = false
            var anyParsed = false
            epgUrls.forEachIndexed { index, url ->
                coroutineContext.ensureActive()
                val file = File(dir, "epg_$index.xml")
                try {
                    if (epgDue || !file.exists() || file.length() == 0L) {
                        if (downloadOrKeep(url, file, playlist.userAgent)) anyFresh = true
                    }
                    openMaybeGzip(file).use { input ->
                        val parser = Xml.newPullParser()
                        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
                        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
                        parser.setInput(input, null)
                        XmltvParser.parse(parser, state.channels, windowStart, windowEnd, collected)
                    }
                    anyParsed = true
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    Log.w(TAG, "Failed to load EPG $url", error)
                }
            }
            dir.listFiles { file -> file.name.matches(Regex("epg_\\d+\\.xml")) }?.forEach { file ->
                val index = file.name.removePrefix("epg_").removeSuffix(".xml").toIntOrNull()
                if (index != null && index >= epgUrls.size) file.delete()
            }

            if (anyParsed) {
                val epg = HashMap<String, List<IptvProgramme>>(collected.size)
                collected.forEach { (key, list) -> epg[key] = XmltvParser.normalizeProgrammes(list) }
                state.epg = epg
                IptvCache.writeEpg(dir, epg)
            }
            if (anyFresh) meta = meta.copy(epgFetchedAt = now)
            saveMeta(state, dir, meta)
            publish(states, playlists)
        }

        publish(states, playlists)
        _status.value = when {
            states.all { it.channels.isEmpty() } ->
                IptvLoadStatus.Error(errors.joinToString("\n").ifBlank { "No channels found" })
            errors.isNotEmpty() -> IptvLoadStatus.Error(errors.joinToString("\n"))
            else -> IptvLoadStatus.Idle
        }
        markLoaded(signature)
    }

    private fun saveMeta(state: PlaylistState, dir: File, meta: IptvCacheMeta) {
        state.meta = meta
        metas[state.playlist.id] = meta
        runCatching { IptvCache.writeMeta(dir, meta) }
            .onFailure { Log.w(TAG, "Failed to write IPTV cache metadata", it) }
    }

    private fun publish(states: List<PlaylistState>, playlists: List<IptvPlaylist>) {
        val channels = ArrayList<IptvChannel>()
        val epg = HashMap<String, List<IptvProgramme>>()
        val updates = HashMap<String, IptvPlaylistUpdate>()
        states.forEach { state ->
            channels.addAll(state.channels)
            epg.putAll(state.epg)
            updates[state.playlist.id] = IptvPlaylistUpdate(
                channelsUpdatedAt = state.meta.playlistFetchedAt,
                guideUpdatedAt = state.meta.epgFetchedAt
            )
        }
        _content.value = IptvContent(
            channels = channels,
            groups = buildGroups(channels, playlists),
            epg = epg,
            loadedAt = System.currentTimeMillis(),
            updates = updates
        )
    }

    @Synchronized
    private fun markLoaded(signature: String) {
        if (loadingSignature == signature) {
            loadedSignature = signature
        }
    }

    private fun epgUrlsFor(playlist: IptvPlaylist, meta: IptvCacheMeta): List<String> {
        val custom = playlist.epgUrl.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (custom.isNotEmpty()) return custom
        return when (playlist.type) {
            IptvSourceType.M3U -> meta.headerEpgUrls
            IptvSourceType.XTREAM -> listOf(XtreamCodes.epgUrl(playlist))
        }
    }

    private suspend fun fetchChannels(playlist: IptvPlaylist, dir: File): ChannelsFetch {
        return when (playlist.type) {
            IptvSourceType.M3U -> {
                val file = File(dir, "playlist.m3u")
                val fresh = downloadOrKeep(playlist.url.trim(), file, playlist.userAgent)
                val result = BufferedReader(InputStreamReader(openMaybeGzip(file), Charsets.UTF_8), 64 * 1024).use { reader ->
                    M3uParser.parse(
                        reader = reader,
                        playlistId = playlist.id,
                        defaultUserAgent = playlist.userAgent.takeIf { it.isNotBlank() }
                    )
                }
                ChannelsFetch(result.channels, result.epgUrls, fresh)
            }
            IptvSourceType.XTREAM -> {
                val categoriesFile = File(dir, "xt_categories.json")
                val streamsFile = File(dir, "xt_streams.json")
                downloadOrKeep(XtreamCodes.playerApiUrl(playlist, "get_live_categories"), categoriesFile, playlist.userAgent)
                val fresh = downloadOrKeep(XtreamCodes.playerApiUrl(playlist, "get_live_streams"), streamsFile, playlist.userAgent)
                val categories = runCatching { XtreamCodes.parseCategories(categoriesFile.readText()) }.getOrDefault(emptyList())
                val channels = try {
                    XtreamCodes.parseLiveStreams(streamsFile.readText(), playlist, categories)
                } catch (error: Exception) {
                    streamsFile.delete()
                    throw error
                }
                ChannelsFetch(channels, emptyList(), fresh)
            }
        }
    }

    private fun buildGroups(channels: List<IptvChannel>, playlists: List<IptvPlaylist>): List<IptvGroup> {
        val multiple = playlists.count { it.enabled } > 1
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

    private suspend fun downloadOrKeep(url: String, target: File, userAgent: String): Boolean {
        return try {
            download(url, target, userAgent)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!target.exists() || target.length() == 0L) throw error
            Log.w(TAG, "Using cached copy after download failure", error)
            false
        }
    }

    private suspend fun download(url: String, target: File, userAgent: String) {
        val builder = Request.Builder().url(url)
        if (userAgent.isNotBlank()) builder.tag(UserAgentTag::class.java, UserAgentTag(userAgent.trim()))
        val call = client.newCall(builder.build())
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (temp.length() == 0L) throw IOException("Empty response")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            handle?.dispose()
            if (temp.exists()) temp.delete()
        }
    }

    private fun cleanupTempFiles(dir: File, now: Long) {
        dir.listFiles { file -> file.name.endsWith(".tmp") && now - file.lastModified() > HOUR_MS }
            ?.forEach { it.delete() }
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
