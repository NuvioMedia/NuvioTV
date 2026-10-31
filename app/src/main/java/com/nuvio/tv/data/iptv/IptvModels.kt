package com.nuvio.tv.data.iptv

import androidx.compose.runtime.Immutable

enum class IptvSourceType { M3U, XTREAM }

@Immutable
data class IptvPlaylist(
    val id: String,
    val name: String,
    val type: IptvSourceType,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val enabled: Boolean = true,
    val playlistRefreshDays: Int = 1,
    val epgRefreshDays: Int = 1
) {
    fun hasSameSource(other: IptvPlaylist): Boolean =
        type == other.type &&
            url == other.url &&
            username == other.username &&
            password == other.password &&
            epgUrl == other.epgUrl &&
            userAgent == other.userAgent
}

@Immutable
data class IptvChannel(
    val key: String,
    val playlistId: String,
    val name: String,
    val number: Int,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val catchupDays: Int = 0
)

@Immutable
data class IptvProgramme(
    val start: Long,
    val end: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val icon: String? = null
) {
    fun isLive(now: Long): Boolean = now in start until end
}

@Immutable
data class IptvGroup(
    val id: String,
    val label: String,
    val count: Int
)

@Immutable
data class IptvPlaylistUpdate(
    val channelsUpdatedAt: Long = 0L,
    val guideUpdatedAt: Long = 0L
)

@Immutable
data class IptvContent(
    val channels: List<IptvChannel> = emptyList(),
    val groups: List<IptvGroup> = emptyList(),
    val epg: Map<String, List<IptvProgramme>> = emptyMap(),
    val loadedAt: Long = 0L,
    val updates: Map<String, IptvPlaylistUpdate> = emptyMap()
)

sealed interface IptvLoadStatus {
    data object Idle : IptvLoadStatus
    data class Loading(val stage: IptvLoadStage, val playlistName: String? = null) : IptvLoadStatus
    data class Error(val message: String) : IptvLoadStatus
}

enum class IptvLoadStage { PLAYLIST, EPG }

object IptvGroupIds {
    const val ALL = "__all__"
    const val FAVORITES = "__favorites__"
    const val RECENT = "__recent__"

    fun forGroup(playlistId: String, group: String): String = "$playlistId::$group"
}
