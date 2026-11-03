package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.content.SharedPreferences
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live TV's saved sources, favorites, hidden categories and last channel, per profile. Small
 * values live in their own preferences file (read once, off the startup path); an imported
 * playlist is a file per source, because it can be megabytes and preferences keep everything in
 * memory and rewrite it on each save.
 */
internal class LiveTvStorage(context: Context, private val profileId: Int) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val playlistDir = File(context.applicationContext.filesDir, "live_tv")

    private fun key(base: String) = "${base}_$profileId"
    private fun string(base: String): String? = prefs.getString(key(base), null)?.takeIf(String::isNotBlank)
    private fun SharedPreferences.Editor.putOrRemove(base: String, value: String?): SharedPreferences.Editor =
        apply { if (value.isNullOrBlank()) remove(key(base)) else putString(key(base), value) }

    // region Sources

    /** The saved sources, in the order they were added. A single source saved by an older version is moved over. */
    fun sources(): List<LiveTvSource> {
        val saved = string(SOURCES) ?: return migrateLegacySource()
        return runCatching {
            val array = JSONArray(saved)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toSource() }
        }.getOrDefault(emptyList())
    }

    fun saveSources(sources: List<LiveTvSource>) {
        val array = JSONArray()
        sources.forEach { array.put(it.toJson()) }
        // An empty list is saved too, so an older single source is not moved over again.
        prefs.edit().putString(key(SOURCES), array.toString()).apply()
    }

    fun newSourceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun JSONObject.toSource(): LiveTvSource? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        val type = LiveTvSourceType.entries.firstOrNull { it.name == optString("type") } ?: return null
        return LiveTvSource(
            id = id,
            type = type,
            url = optString("url"),
            stalker = LiveTvStalkerSettings(optString("portal"), optString("mac"), optString("stalkerUser"), optString("stalkerPassword")),
            xtream = LiveTvXtreamSettings(optString("server"), optString("xtreamUser"), optString("xtreamPassword")),
            epgUrl = optString("epg"),
            name = optString("name"),
            userAgent = optString("ua"),
        )
    }

    private fun LiveTvSource.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("url", url)
        if (epgUrl.isNotBlank()) put("epg", epgUrl)
        if (name.isNotBlank()) put("name", name)
        if (userAgent.isNotBlank()) put("ua", userAgent)
        when (type) {
            LiveTvSourceType.M3u -> Unit
            LiveTvSourceType.Stalker -> {
                put("portal", stalker.portalUrl)
                put("mac", stalker.macAddress)
                put("stalkerUser", stalker.username)
                put("stalkerPassword", stalker.password)
            }
            LiveTvSourceType.Xtream -> {
                put("server", xtream.serverUrl)
                put("xtreamUser", xtream.username)
                put("xtreamPassword", xtream.password)
            }
        }
    }

    /** Versions before multiple sources kept one active source in separate keys. */
    private fun migrateLegacySource(): List<LiveTvSource> {
        val type = LiveTvSourceType.entries.firstOrNull { it.name == string(LEGACY_SOURCE_TYPE) }
        val url = string(LEGACY_SOURCE_URL).orEmpty()
        val id = "main"
        val source = when (type) {
            LiveTvSourceType.M3u -> {
                val legacyFile = File(playlistDir, "playlist_$profileId.m3u")
                if (legacyFile.isFile) legacyFile.renameTo(playlistFileFor(id))
                LiveTvSource(id, LiveTvSourceType.M3u, url).takeIf { url.isNotBlank() }
            }
            LiveTvSourceType.Xtream -> LiveTvSource(
                id, LiveTvSourceType.Xtream, url,
                xtream = LiveTvXtreamSettings(string(XTREAM_SERVER).orEmpty(), string(XTREAM_USER).orEmpty(), string(XTREAM_PASSWORD).orEmpty()),
            ).takeIf { it.xtream.isConfigured }
            LiveTvSourceType.Stalker -> LiveTvSource(
                id, LiveTvSourceType.Stalker, url,
                stalker = LiveTvStalkerSettings(string(STALKER_PORTAL).orEmpty(), string(STALKER_MAC).orEmpty(), string(STALKER_USER).orEmpty(), string(STALKER_PASSWORD).orEmpty()),
            ).takeIf { it.stalker.isConfigured }
            null -> null
        }
        val sources = listOfNotNull(source)
        saveSources(sources)
        prefs.edit().apply {
            listOf(
                LEGACY_SOURCE_TYPE, LEGACY_SOURCE_URL, STALKER_PORTAL, STALKER_MAC, STALKER_USER, STALKER_PASSWORD,
                XTREAM_SERVER, XTREAM_USER, XTREAM_PASSWORD,
            ).forEach { remove(key(it)) }
        }.apply()
        return sources
    }

    // endregion

    // region Imported playlists

    private fun playlistFileFor(sourceId: String) = File(playlistDir, "playlist_${profileId}_$sourceId.m3u")

    /** The imported playlist of [sourceId], or null. */
    fun playlistFile(sourceId: String): File? = playlistFileFor(sourceId).takeIf { it.isFile && it.length() > 0L }

    /** Saves an imported playlist for [sourceId] from [write]; call off the main thread. */
    fun savePlaylistFile(sourceId: String, write: (File) -> Unit): File {
        val target = playlistFileFor(sourceId)
        playlistDir.mkdirs()
        val temp = File(target.path + ".tmp")
        try {
            write(temp)
        } catch (error: Throwable) {
            // A failed or too large upload leaves no partial file behind.
            temp.delete()
            throw error
        }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        return target
    }

    fun deletePlaylistFile(sourceId: String) {
        playlistFileFor(sourceId).delete()
    }

    // endregion

    // Category names are saved one per line; the blank "Uncategorised" key gets a marker line.
    private fun decodeGroups(base: String): Sequence<String> =
        string(base)?.lineSequence()?.filter(String::isNotEmpty)?.map { if (it == UNGROUPED_LINE) LIVE_TV_UNGROUPED else it }
            ?: emptySequence()

    private fun encodeGroups(groups: Collection<String>): String =
        groups.joinToString("\n") { if (it == LIVE_TV_UNGROUPED) UNGROUPED_LINE else it }

    fun hiddenGroups(): Set<String> = decodeGroups(HIDDEN_GROUPS).toHashSet()

    fun saveHiddenGroups(groups: Set<String>) {
        prefs.edit().putOrRemove(HIDDEN_GROUPS, encodeGroups(groups)).apply()
    }

    // region Hidden channels

    private val hiddenChannelsFile get() = File(playlistDir, "hidden_channels_$profileId.bin")

    /**
     * Hidden channels, as [LiveTvChannel.hideKey]s in a small file of their own (8 bytes each), so
     * hiding a large category does not grow the preferences every other save rewrites. Call off
     * the main thread.
     */
    fun hiddenChannelKeys(): Set<Long> {
        // Older builds kept them as links in the preferences; those can't be matched to keys.
        if (prefs.contains(key(LEGACY_HIDDEN_CHANNELS))) prefs.edit().remove(key(LEGACY_HIDDEN_CHANNELS)).apply()
        val file = hiddenChannelsFile
        if (!file.isFile) return emptySet()
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = file.length().toInt() / Long.SIZE_BYTES
                HashSet<Long>(count * 2).apply { repeat(count) { add(input.readLong()) } }
            }
        }.getOrDefault(emptySet())
    }

    fun saveHiddenChannelKeys(keys: Set<Long>) {
        val target = hiddenChannelsFile
        if (keys.isEmpty()) {
            target.delete()
            return
        }
        playlistDir.mkdirs()
        val temp = File(target.path + ".tmp")
        runCatching {
            DataOutputStream(temp.outputStream().buffered()).use { out -> keys.forEach(out::writeLong) }
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    // endregion

    /** Names the viewer gave categories, by the playlist's name ("" is Uncategorised). */
    fun groupNames(): Map<String, String> = runCatching {
        val json = JSONObject(string(GROUP_NAMES) ?: return emptyMap())
        json.keys().asSequence().associateWith(json::getString).filterValues(String::isNotBlank)
    }.getOrDefault(emptyMap())

    fun saveGroupNames(names: Map<String, String>) {
        prefs.edit().putOrRemove(GROUP_NAMES, if (names.isEmpty()) null else JSONObject(names).toString()).apply()
    }

    /** Categories in the order the viewer put them; ones not in it follow, A to Z. */
    fun groupOrder(): List<String> = decodeGroups(GROUP_ORDER).toList()

    fun saveGroupOrder(groups: List<String>) {
        prefs.edit().putOrRemove(GROUP_ORDER, encodeGroups(groups)).apply()
    }

    /** Each source's own category order (see [LiveTvUiState.sourceGroupOrders]), by source identity. */
    fun sourceGroupOrders(): Map<String, List<String>> = runCatching {
        val json = JSONObject(string(SOURCE_GROUP_ORDER) ?: return emptyMap())
        json.keys().asSequence().associateWith { key ->
            val array = json.optJSONArray(key)
            (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it) }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun saveSourceGroupOrders(orders: Map<String, List<String>>) {
        val json = JSONObject()
        orders.forEach { (identity, groups) -> if (groups.isNotEmpty()) json.put(identity, JSONArray(groups)) }
        prefs.edit().putOrRemove(SOURCE_GROUP_ORDER, if (json.length() == 0) null else json.toString()).apply()
    }

    /** The viewer's own playlists, oldest first. */
    fun customLists(): List<LiveTvCustomList> = runCatching {
        val array = JSONArray(string(CUSTOM_LISTS) ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val urls = item.optJSONArray("urls")
            LiveTvCustomList(id, item.optString("name"), (0 until (urls?.length() ?: 0)).mapNotNull { urls?.optString(it)?.takeIf(String::isNotBlank) })
        }.sortedBy { it.id }
    }.getOrDefault(emptyList())

    fun saveCustomLists(lists: List<LiveTvCustomList>) {
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().put("id", list.id).put("name", list.name).put("urls", JSONArray(list.urls)))
        }
        prefs.edit().putOrRemove(CUSTOM_LISTS, if (lists.isEmpty()) null else array.toString()).apply()
    }

    fun favoriteUrls(): Set<String> =
        string(FAVORITES)?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toHashSet().orEmpty()

    fun saveFavoriteUrls(urls: Set<String>) {
        prefs.edit().putOrRemove(FAVORITES, urls.joinToString("\n")).apply()
    }

    /**
     * Sources the viewer removed here (by [LiveTvSource.identity]) that sync has not sent yet:
     * only these are deleted on the other devices (see reshaped/sync LiveTvSections).
     */
    fun syncRemovedSources(): Set<String> =
        string(SYNC_REMOVED)?.lineSequence()?.filter(String::isNotBlank)?.toHashSet().orEmpty()

    fun markSyncRemoved(identity: String) {
        prefs.edit().putOrRemove(SYNC_REMOVED, (syncRemovedSources() + identity).joinToString("\n")).apply()
    }

    /** Sync sent [identities]' removal; a source added back later is no longer removed. */
    fun clearSyncRemoved(identities: Collection<String>) {
        val left = syncRemovedSources() - identities.toSet()
        prefs.edit().putOrRemove(SYNC_REMOVED, left.joinToString("\n")).apply()
    }

    fun recentChannel(): LiveTvRecentChannel? {
        val url = string(RECENT_URL) ?: return null
        val name = string(RECENT_NAME) ?: return null
        return LiveTvRecentChannel(
            streamUrl = url,
            name = name,
            logoUrl = string(RECENT_LOGO),
            group = string(RECENT_GROUP).orEmpty(),
            tvgId = string(RECENT_TVG_ID),
        )
    }

    fun saveRecentChannel(channel: LiveTvRecentChannel) {
        prefs.edit().apply {
            putOrRemove(RECENT_URL, channel.streamUrl)
            putOrRemove(RECENT_NAME, channel.name)
            putOrRemove(RECENT_LOGO, channel.logoUrl)
            putOrRemove(RECENT_GROUP, channel.group)
            putOrRemove(RECENT_TVG_ID, channel.tvgId)
        }.apply()
    }

    companion object {
        const val PREFS = "nuvio_live_tv"
        private const val SOURCES = "sources"
        private const val SYNC_REMOVED = "sync_removed_sources"
        private const val HIDDEN_GROUPS = "hidden_groups"
        private const val GROUP_ORDER = "group_order"
        private const val SOURCE_GROUP_ORDER = "source_group_order"
        private const val GROUP_NAMES = "group_names"
        private const val LEGACY_HIDDEN_CHANNELS = "hidden_channel_urls"
        private const val UNGROUPED_LINE = "\uE000"
        private const val LEGACY_SOURCE_TYPE = "source_type"
        private const val LEGACY_SOURCE_URL = "source_url"
        private const val STALKER_PORTAL = "stalker_portal_url"
        private const val STALKER_MAC = "stalker_mac_address"
        private const val STALKER_USER = "stalker_username"
        private const val STALKER_PASSWORD = "stalker_password"
        private const val XTREAM_SERVER = "xtream_server_url"
        private const val XTREAM_USER = "xtream_username"
        private const val XTREAM_PASSWORD = "xtream_password"
        private const val FAVORITES = "favorite_channel_urls"
        private const val CUSTOM_LISTS = "custom_lists"
        private const val RECENT_URL = "recent_channel_url"
        private const val RECENT_NAME = "recent_channel_name"
        private const val RECENT_LOGO = "recent_channel_logo"
        private const val RECENT_GROUP = "recent_channel_group"
        private const val RECENT_TVG_ID = "recent_channel_tvg_id"
    }
}
