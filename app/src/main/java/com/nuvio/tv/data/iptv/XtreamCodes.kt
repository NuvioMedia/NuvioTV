package com.nuvio.tv.data.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object XtreamCodes {

    fun normalizeServer(raw: String): String {
        var server = raw.trim()
        if (server.isEmpty()) return server
        if (!server.contains("://")) server = "http://$server"
        server = server.substringBefore("/player_api.php")
            .substringBefore("/get.php")
        return server.trimEnd('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun playerApiUrl(playlist: IptvPlaylist, action: String? = null): String {
        val base = "${normalizeServer(playlist.url)}/player_api.php?username=${encode(playlist.username)}&password=${encode(playlist.password)}"
        return if (action.isNullOrBlank()) base else "$base&action=$action"
    }

    fun epgUrl(playlist: IptvPlaylist): String =
        "${normalizeServer(playlist.url)}/xmltv.php?username=${encode(playlist.username)}&password=${encode(playlist.password)}"

    fun liveStreamUrl(playlist: IptvPlaylist, streamId: String, extension: String = "ts"): String =
        "${normalizeServer(playlist.url)}/live/${playlist.username}/${playlist.password}/$streamId.$extension"

    fun parseCategories(json: String): List<Pair<String, String>> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val array = JSONArray(trimmed)
        val result = ArrayList<Pair<String, String>>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("category_id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("category_name").trim()
            result.add(id to name)
        }
        return result
    }

    fun parseLiveStreams(
        json: String,
        playlist: IptvPlaylist,
        categories: List<Pair<String, String>>
    ): List<IptvChannel> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) {
            val message = runCatching {
                JSONObject(trimmed).optJSONObject("user_info")?.optString("message")
            }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Invalid Xtream Codes response")
        }
        val categoryNames = categories.toMap()
        val categoryOrder = categories.mapIndexed { index, pair -> pair.first to index }.toMap()
        val array = JSONArray(trimmed)
        val headers = if (playlist.userAgent.isNotBlank()) mapOf("User-Agent" to playlist.userAgent) else emptyMap()
        val parsed = ArrayList<Pair<Int, IptvChannel>>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val streamId = item.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            val name = item.optString("name").trim().ifEmpty { streamId }
            val categoryId = item.opt("category_id")?.toString().orEmpty()
            val number = item.optString("num").toIntOrNull() ?: (i + 1)
            val icon = item.optString("stream_icon").takeIf { it.startsWith("http", ignoreCase = true) }
            val epgId = item.optString("epg_channel_id").trim().takeIf { it.isNotEmpty() && it != "null" }
            val archive = if (item.optInt("tv_archive", 0) == 1) item.optInt("tv_archive_duration", 0) else 0
            val channel = IptvChannel(
                key = "${playlist.id}|xt|$streamId",
                playlistId = playlist.id,
                name = name,
                number = number,
                logo = icon,
                group = categoryNames[categoryId].orEmpty(),
                tvgId = epgId,
                tvgName = name,
                streamUrl = liveStreamUrl(playlist, streamId),
                headers = headers,
                catchupDays = archive
            )
            parsed.add((categoryOrder[categoryId] ?: Int.MAX_VALUE) to channel)
        }
        return parsed
            .sortedWith(compareBy<Pair<Int, IptvChannel>> { it.second.number }.thenBy { it.first })
            .map { it.second }
    }
}
