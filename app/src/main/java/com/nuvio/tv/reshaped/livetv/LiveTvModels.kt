package com.nuvio.tv.reshaped.livetv

import androidx.compose.runtime.Immutable

/** A channel as the list shows it. [streamUrl] is also its identity (favorites, last watched). */
@Immutable
data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** Stalker only: the command a playable link is created from, per play. */
    val stalkerCommand: String? = null,
    /** The [LiveTvSource.id] this channel was listed by. */
    val sourceId: String = "",
    /** What hiding this channel stores: see [liveTvHideKey]. */
    val hideKey: Long = 0L,
    /** What its guide is kept under: see [liveTvGuideKey]. */
    val guideKey: String = "",
    /** The playlist's tvg-name when it differs from [name]: guides often list the channel by it. */
    val tvgName: String? = null,
    /** How past programmes of this channel can be played again; null when the provider keeps none. */
    val catchup: LiveTvCatchup? = null,
    /** The playlist's `tvg-shift`: minutes its guide's times are moved by (a guide in another time zone). */
    val guideShiftMinutes: Int = 0,
)

/**
 * A channel's catch-up (archive): how its provider builds the link to a past programme, and how
 * many days back it goes. Channels of one source share one instance.
 */
@Immutable
data class LiveTvCatchup(
    val kind: Kind,
    val days: Int,
    /** M3U `catchup-source`: the link template, or what is added to the live link. */
    val template: String? = null,
) {
    enum class Kind {
        /** Xtream panels: `/timeshift/user/pass/minutes/start/id.ts`, in the panel's time zone. */
        Xtream,
        /** `catchup-source` is the whole link (or added to the live link when it is not one). */
        Default,
        /** `catchup-source` is added to the live link. */
        Append,
        /** `?utc=start&lutc=now` on the live link. */
        Shift,
        /** Flussonic servers: `index-start-duration.m3u8` / `timeshift_abs-start.ts`. */
        Flussonic,
    }
}

/** The category key of channels the playlist gives no category; the screens call it "Uncategorised". */
const val LIVE_TV_UNGROUPED = ""

/**
 * A hidden channel is kept as a 64-bit hash of its source, category and name, not its link: the
 * link can be long, carries account details, and changes when a provider rotates tokens.
 */
fun liveTvHideKey(sourceId: String, group: String, name: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
    fun mix(text: String) {
        text.forEach { char ->
            hash = (hash xor char.code.toLong()) * 0x100000001b3L
        }
        hash = (hash xor 0x1fL) * 0x100000001b3L
    }
    mix(sourceId)
    mix(group)
    mix(name)
    return hash
}

/**
 * A playlist the viewer made from channels of any source: [urls] are [LiveTvChannel.streamUrl]s,
 * in the viewer's order. Lists sort by [id], which starts with when they were made.
 */
@Immutable
data class LiveTvCustomList(val id: String, val name: String, val urls: List<String>)

@Immutable
data class LiveTvRecentChannel(
    val streamUrl: String,
    val name: String,
    val logoUrl: String? = null,
    val group: String = "",
    val tvgId: String? = null,
) {
    val guideKey: String get() = liveTvGuideKey(tvgId, name)
}

@Immutable
data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val stopEpochMs: Long,
    /** The guide's description, kept only for programmes near now (see [LiveTvGuideWindow.detailsMs]). */
    val description: String? = null,
    /** The guide's picture for it (an http link), kept like [description]. */
    val image: String? = null,
)

enum class LiveTvSourceType { M3u, Stalker, Xtream }

@Immutable
data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

@Immutable
data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** One saved channel source. Several can be added; their channels show as one list. */
@Immutable
data class LiveTvSource(
    val id: String,
    val type: LiveTvSourceType,
    /** The M3U link or an imported file's name; the server or portal URL for the others. */
    val url: String = "",
    val stalker: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtream: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    /** A guide (XMLTV) link the viewer added; read before the source's own guide. */
    val epgUrl: String = "",
    /** The name the viewer gave the source; blank shows [label]'s default. */
    val name: String = "",
    /** A user agent the viewer gave (some providers require their own); blank uses the default. Not for portals. */
    val userAgent: String = "",
) {
    /** A short name for lists: the viewer's name for it, else the host of a link, or the imported file's name. */
    val label: String
        get() = name.trim().ifBlank {
            url.substringAfter("://", url).substringBefore('/').substringBefore('?')
                .substringAfterLast('@').ifBlank { url }
        }

    /** Two sources with the same identity are one: adding it again replaces it. */
    internal val identity: String
        get() = when (type) {
            LiveTvSourceType.M3u -> "m3u|${url.lowercase()}"
            LiveTvSourceType.Xtream -> "xtream|${xtream.serverUrl.lowercase()}|${xtream.username}"
            LiveTvSourceType.Stalker -> "stalker|${stalker.portalUrl.lowercase()}|${stalker.macAddress}"
        }
}

@Immutable
data class LiveTvUiState(
    val sources: List<LiveTvSource> = emptyList(),
    val channels: List<LiveTvChannel> = emptyList(),
    /** Sorted category names of [channels], hidden ones included, computed once per load rather than per frame. */
    val groups: List<String> = emptyList(),
    /** How many channels each category has. */
    val groupCounts: Map<String, Int> = emptyMap(),
    /** Categories the viewer chose not to see: their channels leave the list, search and zapping. */
    val hiddenGroups: Set<String> = emptySet(),
    /** Names the viewer gave categories, by the playlist's name. */
    val groupNames: Map<String, String> = emptyMap(),
    /**
     * Each source's own order of its categories, by [LiveTvSource.identity], when the viewer moved
     * one under that source: two sources with a category of the same name keep their own places.
     */
    val sourceGroupOrders: Map<String, List<String>> = emptyMap(),
    /** Single channels the viewer chose not to see ([LiveTvChannel.hideKey]), inside categories that stay. */
    val hiddenChannelKeys: Set<Long> = emptySet(),
    /** [channels] without hidden categories and channels: what All channels and zapping go through. */
    val shownChannels: List<LiveTvChannel> = emptyList(),
    /** How many channels each source listed. */
    val sourceCounts: Map<String, Int> = emptyMap(),
    /** Sources whose last load failed (their earlier channels, if any, stay listed). */
    val sourceErrors: Map<String, LiveTvError> = emptyMap(),
    /** [LiveTvChannel.guideKey] to the programme on air now. */
    val currentProgrammes: Map<String, LiveTvProgramme> = emptyMap(),
    /** [LiveTvChannel.guideKey] to the guide's logo, for channels the playlist gives none. */
    val guideLogos: Map<String, String> = emptyMap(),
    /** Goes up each time the kept guide is read again, so the programme guide redraws. */
    val guideVersion: Int = 0,
    val recentChannel: LiveTvRecentChannel? = null,
    val favoriteUrls: Set<String> = emptySet(),
    /** The viewer's own playlists, oldest first. */
    val customLists: List<LiveTvCustomList> = emptyList(),
    val isEpgLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    /** The last failed attempt to add a source. */
    val error: LiveTvError? = null,
    /** Goes up each time a source is added, so the Sources dialog can tell an add went through. */
    val addedCount: Int = 0,
    /** How each source's guide did, for the Sources dialog; a source missing here has not been read yet. */
    val sourceGuides: Map<String, LiveTvSourceGuide> = emptyMap(),
) {
    val hasSource: Boolean get() = sources.isNotEmpty()

    /** The channel's logo, or the guide's when the playlist has none. */
    fun logoFor(channel: LiveTvChannel): String? =
        channel.logoUrl?.takeIf(String::isNotBlank) ?: guideLogos[channel.guideKey]

    /** [source]'s categories among [groups] (those it has: [own]), in its own order, then the shared one. */
    fun groupsOf(source: LiveTvSource, own: Set<String>, groups: List<String> = this.groups): List<String> =
        liveTvSourceGroups(sourceGroupOrders[source.identity], groups, own)

    /** Categories the list shows. */
    val visibleGroups: List<String>
        get() = if (hiddenGroups.isEmpty()) groups else groups.filterNot(hiddenGroups::contains)
}

/** How a source's guide did: none offered, loading, read for [channels] channels, or failed. */
@Immutable
/** How a source's guide did; [retrying] when what shows is from an older or incomplete read. */
data class LiveTvSourceGuide(val state: State, val channels: Int = 0, val retrying: Boolean = false) {
    enum class State { None, Loading, Loaded, Failed }

    companion object {
        val None = LiveTvSourceGuide(State.None)
        val Loading = LiveTvSourceGuide(State.Loading)
        val Failed = LiveTvSourceGuide(State.Failed)
    }
}

/** [own] in [custom]'s order (a source's own), then in [shared]'s; only those [shared] lists. */
internal fun liveTvSourceGroups(custom: List<String>?, shared: List<String>, own: Set<String>): List<String> {
    if (custom.isNullOrEmpty()) return shared.filter { it in own }
    val listed = shared.toHashSet()
    val placed = HashSet<String>()
    val result = ArrayList<String>()
    custom.forEach { if (it in own && it in listed && placed.add(it)) result += it }
    shared.forEach { if (it in own && placed.add(it)) result += it }
    return result
}
