package com.nuvio.tv.ui.screens.live

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class LiveChannel(
    val index: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val tvgId: String?,
    val headers: Map<String, String>,
    val sourceId: String = "",
    val sourceName: String = ""
) {
    val number: Int get() = index + 1
    val key: String get() = "$sourceId|$url"
}

data class EpgProgram(
    val startMs: Long,
    val stopMs: Long,
    val title: String
) {
    fun progress(nowMs: Long): Float =
        ((nowMs - startMs).toFloat() / (stopMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** One M3U playlist ("server"); channels are grouped by it in the UI. */
data class LiveSource(
    val id: String,
    val name: String,
    val m3uUrl: String,
    val epgUrl: String
)

data class SourceStatus(
    val loading: Boolean = false,
    val count: Int = 0,
    val error: String? = null
)

data class LiveTvState(
    val sources: List<LiveSource> = emptyList(),
    val status: Map<String, SourceStatus> = emptyMap(),
    val channels: List<LiveChannel> = emptyList(),
    val epg: Map<String, List<EpgProgram>> = emptyMap()
) {
    val loading: Boolean get() = status.values.any { it.loading }
    val firstError: String? get() = sources.firstNotNullOfOrNull { status[it.id]?.error }
}

object LiveTvRepository {
    private const val PREFS = "live_tv"
    private const val KEY_SOURCES = "sources"
    private const val KEY_LEGACY_M3U = "m3u_url"
    private const val KEY_LEGACY_EPG = "epg_url"
    private const val KEY_LAST = "last_channel_key"
    const val NOT_M3U = "not_m3u"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private val perSource = mutableMapOf<String, List<LiveChannel>>()
    private var initialized = false
    private var lastChannelKey: String? = null

    fun ensureLoaded(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastChannelKey = prefs.getString(KEY_LAST, null)
        var sources = readSources(prefs.getString(KEY_SOURCES, null))
        if (sources.isEmpty()) {
            // Migrate the single-playlist setting from the first version.
            val legacy = prefs.getString(KEY_LEGACY_M3U, "").orEmpty()
            if (legacy.isNotBlank()) {
                sources = listOf(LiveSource(newId(), defaultName(legacy), legacy, prefs.getString(KEY_LEGACY_EPG, "").orEmpty()))
                saveSources(context, sources)
            }
        }
        _state.update { it.copy(sources = sources) }
        reloadAll()
    }

    fun upsertSource(context: Context, source: LiveSource) {
        val cleaned = source.copy(
            name = source.name.trim().ifBlank { defaultName(source.m3uUrl) },
            m3uUrl = source.m3uUrl.trim(),
            epgUrl = source.epgUrl.trim()
        )
        val sources = _state.value.sources.let { list ->
            if (list.any { it.id == cleaned.id }) list.map { if (it.id == cleaned.id) cleaned else it } else list + cleaned
        }
        saveSources(context, sources)
        _state.update { it.copy(sources = sources) }
        load(cleaned)
    }

    fun removeSource(context: Context, id: String) {
        val sources = _state.value.sources.filterNot { it.id == id }
        saveSources(context, sources)
        synchronized(perSource) { perSource.remove(id) }
        _state.update { st ->
            st.copy(
                sources = sources,
                status = st.status - id,
                epg = st.epg.filterKeys { !it.startsWith("$id|") }
            )
        }
        rebuildChannels()
    }

    fun newSourceId(): String = newId()

    fun reloadAll() {
        _state.value.sources.forEach(::load)
    }

    fun rememberChannel(context: Context, channel: LiveChannel) {
        lastChannelKey = channel.key
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, channel.key).apply()
    }

    fun lastChannelIndex(): Int =
        _state.value.channels.indexOfFirst { it.key == lastChannelKey }.coerceAtLeast(0)

    private fun load(source: LiveSource) {
        if (source.m3uUrl.isBlank()) return
        setStatus(source.id, SourceStatus(loading = true))
        scope.launch {
            val parsed = runCatching {
                val text = fetch(source.m3uUrl).bufferedReader().use { it.readText() }
                parseM3u(text).also { (channels, _) ->
                    if (channels.isEmpty() || !text.contains("#EXTINF")) error(NOT_M3U)
                }
            }
            parsed.onFailure { e ->
                synchronized(perSource) { perSource.remove(source.id) }
                rebuildChannels()
                setStatus(source.id, SourceStatus(error = e.message ?: e.javaClass.simpleName))
            }
            val (raw, headerEpg) = parsed.getOrNull() ?: return@launch
            val channels = raw.map { it.copy(sourceId = source.id, sourceName = source.name) }
            synchronized(perSource) { perSource[source.id] = channels }
            rebuildChannels()
            setStatus(source.id, SourceStatus(count = channels.size))

            val epgUrl = source.epgUrl.ifBlank { headerEpg.orEmpty() }
            if (epgUrl.isNotBlank()) {
                val ids = channels.mapNotNull { it.tvgId?.takeIf(String::isNotBlank) }.toSet() +
                    channels.map { it.name }.toSet()
                runCatching { parseXmltv(fetch(epgUrl), ids) }
                    .onSuccess { epg ->
                        val prefixed = epg.mapKeys { (k, _) -> "${source.id}|$k" }
                        _state.update { st -> st.copy(epg = st.epg.filterKeys { !it.startsWith("${source.id}|") } + prefixed) }
                    }
            }
        }
    }

    private fun setStatus(id: String, status: SourceStatus) {
        _state.update { it.copy(status = it.status + (id to status)) }
    }

    private fun rebuildChannels() {
        val order = _state.value.sources.map { it.id }
        val combined = synchronized(perSource) { order.flatMap { perSource[it].orEmpty() } }
            .mapIndexed { i, ch -> ch.copy(index = i) }
        _state.update { it.copy(channels = combined) }
    }

    fun nowAndNext(
        channel: LiveChannel,
        nowMs: Long,
        epg: Map<String, List<EpgProgram>> = _state.value.epg
    ): Pair<EpgProgram?, EpgProgram?> {
        val list = channel.tvgId?.let { epg["${channel.sourceId}|$it"] }
            ?: epg["${channel.sourceId}|${channel.name}"]
            ?: return null to null
        val idx = list.indexOfFirst { nowMs in it.startMs until it.stopMs }
        if (idx < 0) return null to list.firstOrNull { it.startMs > nowMs }
        return list[idx] to list.getOrNull(idx + 1)
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    private fun defaultName(url: String): String =
        runCatching { URL(url).host.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "M3U"

    private fun readSources(json: String?): List<LiveSource> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            LiveSource(o.getString("id"), o.optString("name"), o.optString("m3u"), o.optString("epg"))
        }
    }.getOrDefault(emptyList())

    private fun saveSources(context: Context, sources: List<LiveSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("m3u", it.m3uUrl).put("epg", it.epgUrl)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    private fun fetch(url: String): InputStream {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NuvioTV")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("HTTP $code")
                current = URL(current, location)
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("HTTP $code")
            val raw = BufferedInputStream(conn.inputStream)
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            return if (gz) GZIPInputStream(raw) else raw
        }
        error("Too many redirects")
    }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    internal fun parseM3u(text: String): Pair<List<LiveChannel>, String?> {
        val channels = mutableListOf<LiveChannel>()
        var headerEpg: String? = null
        var pendingInfo: String? = null
        var pendingHeaders = mutableMapOf<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("#EXTM3U") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    headerEpg = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()
                }
                line.startsWith("#EXTINF") -> pendingInfo = line
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.removePrefix("#EXTVLCOPT:")
                    when {
                        opt.startsWith("http-user-agent=") -> pendingHeaders["User-Agent"] = opt.substringAfter('=')
                        opt.startsWith("http-referrer=") -> pendingHeaders["Referer"] = opt.substringAfter('=')
                        opt.startsWith("http-origin=") -> pendingHeaders["Origin"] = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                !line.contains("://") -> Unit
                else -> {
                    val info = pendingInfo
                    val attrs = info?.let { i -> attrRegex.findAll(i).associate { it.groupValues[1] to it.groupValues[2] } }.orEmpty()
                    val name = info?.let { i ->
                        // Title follows the last comma that is outside quoted attribute values.
                        var inQuote = false
                        var cut = -1
                        i.forEachIndexed { pos, c ->
                            if (c == '"') inQuote = !inQuote
                            if (c == ',' && !inQuote) cut = pos
                        }
                        if (cut >= 0) i.substring(cut + 1).trim() else null
                    }.orEmpty().ifBlank { attrs["tvg-name"] ?: line.substringAfterLast('/') }
                    channels += LiveChannel(
                        index = channels.size,
                        name = name,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "",
                        url = line,
                        tvgId = attrs["tvg-id"],
                        headers = pendingHeaders.toMap()
                    )
                    pendingInfo = null
                    pendingHeaders = mutableMapOf()
                }
            }
        }
        return channels to headerEpg
    }

    private fun parseXmltv(input: InputStream, wantedIds: Set<String>): Map<String, List<EpgProgram>> {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3_600_000L
        val to = now + 24 * 3_600_000L
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun parseTime(v: String?): Long? = v?.let {
            runCatching { format.parse(if (it.length > 14 && it[14] != ' ') it.substring(0, 14) + " " + it.substring(14) else it)?.time }
                .getOrNull()
                ?: runCatching { format.parse(it.take(14) + " +0000")?.time }.getOrNull()
        }

        val displayNames = mutableMapOf<String, String>() // channel id -> display-name
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        input.use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            var channelId: String? = null
            var progChannel: String? = null
            var progStart: Long? = null
            var progStop: Long? = null
            var inTitle = false
            var inDisplayName = false
            var title: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> channelId = parser.getAttributeValue(null, "id")
                        "display-name" -> inDisplayName = channelId != null
                        "programme" -> {
                            progChannel = parser.getAttributeValue(null, "channel")
                            progStart = parseTime(parser.getAttributeValue(null, "start"))
                            progStop = parseTime(parser.getAttributeValue(null, "stop"))
                            title = null
                        }
                        "title" -> inTitle = progChannel != null && title == null
                    }
                    XmlPullParser.TEXT -> {
                        if (inTitle) title = parser.text
                        if (inDisplayName) channelId?.let { id -> displayNames.putIfAbsent(id, parser.text.trim()) }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "display-name" -> inDisplayName = false
                        "channel" -> channelId = null
                        "programme" -> {
                            val id = progChannel
                            val s = progStart
                            val e = progStop
                            val t = title
                            if (id != null && s != null && e != null && t != null && e > from && s < to) {
                                val key = when {
                                    id in wantedIds -> id
                                    displayNames[id] in wantedIds -> displayNames[id]!!
                                    else -> null
                                }
                                if (key != null) result.getOrPut(key) { mutableListOf() } += EpgProgram(s, e, t.trim())
                            }
                            progChannel = null
                        }
                    }
                }
                event = parser.next()
            }
        }
        return result.mapValues { (_, v) -> v.sortedBy { it.startMs } }
    }
}
