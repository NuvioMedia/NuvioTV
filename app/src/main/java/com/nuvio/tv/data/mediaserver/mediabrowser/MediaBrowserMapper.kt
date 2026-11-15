package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.time.Instant
import kotlin.math.roundToInt

internal class MediaBrowserMapper(
    private val baseUrl: String,
    private val connectionId: String
) {
    fun ref(itemId: String): String = ServerItemRef(connectionId, itemId).encode()

    fun preview(item: BaseItem): MetaPreview? {
        val kind = item.mediaKind() ?: return null
        return MetaPreview(
            id = ref(item.id),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.name.orEmpty(),
            poster = item.primaryImage(),
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = item.productionYear?.toString(),
            imdbRating = item.communityRating?.formatRating(),
            genres = item.genres,
            released = item.premiereDate?.take(10)
        )
    }

    fun title(item: BaseItem): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    fun resumeTitle(item: BaseItem): ServerTitle? {
        val preview = resumePreview(item) ?: return null
        val isEpisode = item.type.equals("Episode", ignoreCase = true)
        return ServerTitle(preview, if (isEpisode) TrackingExternalIds() else item.externalIds())
    }

    private fun resumePreview(item: BaseItem): MetaPreview? {
        if (!item.type.equals("Episode", ignoreCase = true)) return preview(item)
        val seriesId = item.seriesId ?: return null
        return MetaPreview(
            id = ref(seriesId),
            type = ContentType.SERIES,
            name = item.seriesName ?: item.name.orEmpty(),
            poster = item.seriesPrimaryImageTag?.let { image(seriesId, "Primary", it, maxHeight = 600) },
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    fun details(item: BaseItem, episodes: List<BaseItem>): Meta {
        val kind = item.mediaKind() ?: ServerMediaKind.MOVIE
        val cast = item.people.filter { it.type.equals("Actor", ignoreCase = true) && !it.name.isNullOrBlank() }
        return Meta(
            id = ref(item.id),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.name.orEmpty(),
            poster = item.primaryImage(),
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = item.releaseInfo(kind),
            status = item.status,
            imdbRating = item.communityRating?.formatRating(),
            genres = item.genres,
            runtime = item.runTimeTicks?.let { "${ticksToMinutes(it)} min" },
            director = item.people.filter { it.type.equals("Director", ignoreCase = true) }.mapNotNull { it.name },
            writer = item.people.filter { it.type.equals("Writer", ignoreCase = true) }.mapNotNull { it.name },
            cast = cast.mapNotNull { it.name },
            castMembers = cast.map { person ->
                MetaCastMember(
                    name = person.name.orEmpty(),
                    character = person.role,
                    photo = person.id?.let { id -> person.primaryImageTag?.let { image(id, "Primary", it, maxHeight = 300) } }
                )
            },
            videos = episodes.map(::video),
            ageRating = item.officialRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.externalIds().imdb,
            released = item.premiereDate?.take(10)
        )
    }

    fun userState(item: BaseItem): ServerUserState? {
        val data = item.userData ?: return null
        return ServerUserState(
            videoId = ref(item.id),
            positionMs = (data.playbackPositionTicks ?: 0L) / TICKS_PER_MS,
            durationMs = (item.runTimeTicks ?: 0L) / TICKS_PER_MS,
            played = data.played,
            lastPlayedEpochMs = data.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            season = item.parentIndexNumber,
            episode = item.indexNumber,
            title = item.name
        )
    }

    fun video(item: BaseItem): Video = Video(
        id = ref(item.id),
        title = item.name.orEmpty(),
        released = item.premiereDate,
        thumbnail = item.imageTags["Primary"]?.let { image(item.id, "Primary", it, maxWidth = 640) },
        season = item.parentIndexNumber,
        episode = item.indexNumber,
        overview = item.overview,
        runtime = item.runTimeTicks?.let(::ticksToMinutes),
        rating = item.communityRating,
        available = !item.isMissing
    )

    fun candidates(item: BaseItem): List<ServerCandidate> {
        if (item.isMissing) return emptyList()
        val itemRef = ServerItemRef(connectionId, item.id)
        val sources = item.mediaSources.filterNot { it.isPlaceholder }
        return sources.map { source ->
            val video = source.mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
            val audio = source.mediaStreams.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = source.id),
                title = source.name?.takeIf { it.isNotBlank() && sources.size > 1 }
                    ?: video?.let(::resolutionLabel)
                    ?: source.name.orEmpty(),
                description = listOfNotNull(
                    video?.displayTitle,
                    audio?.displayTitle,
                    source.container?.uppercase()
                ).joinToString(" • ").ifBlank { null },
                filename = source.path?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = source.size
            )
        }
    }

    fun image(itemId: String, type: String, tag: String, maxWidth: Int? = null, maxHeight: Int? = null): String =
        buildUrl(
            baseUrl,
            "/Items/${pathSegment(itemId)}/Images/$type",
            mapOf(
                "tag" to tag,
                "maxWidth" to maxWidth?.toString(),
                "maxHeight" to maxHeight?.toString(),
                "quality" to "90"
            )
        )

    private fun BaseItem.primaryImage(): String? =
        imageTags["Primary"]?.let { image(id, "Primary", it, maxHeight = 600) }

    private fun BaseItem.backdropImage(): String? =
        backdropImageTags.firstOrNull()?.let { image(id, "Backdrop/0", it, maxWidth = 1920) }
            ?: parentBackdropItemId?.let { parentId ->
                parentBackdropImageTags.firstOrNull()?.let { image(parentId, "Backdrop/0", it, maxWidth = 1920) }
            }

    private fun BaseItem.logoImage(): String? =
        imageTags["Logo"]?.let { image(id, "Logo", it, maxWidth = 800) }
            ?: parentLogoItemId?.let { parentId -> parentLogoImageTag?.let { image(parentId, "Logo", it, maxWidth = 800) } }

    private fun BaseItem.releaseInfo(kind: ServerMediaKind): String? {
        val start = productionYear ?: return null
        if (kind != ServerMediaKind.SERIES) return start.toString()
        val end = endDate?.take(4)?.toIntOrNull()
        return when {
            status.equals("Continuing", ignoreCase = true) -> "$start–"
            end != null && end != start -> "$start–$end"
            else -> start.toString()
        }
    }
}

internal fun BaseItem.mediaKind(): ServerMediaKind? = when {
    type.equals("Movie", ignoreCase = true) -> ServerMediaKind.MOVIE
    type.equals("Series", ignoreCase = true) -> ServerMediaKind.SERIES
    type.equals("BoxSet", ignoreCase = true) || type.equals("Folder", ignoreCase = true) -> ServerMediaKind.COLLECTION
    else -> null
}

internal fun BaseItem.externalIds(): TrackingExternalIds {
    val ids = providerIds.entries.fold(TrackingExternalIds()) { ids, (key, value) ->
        val id = value?.trim()?.takeIf { it.isNotEmpty() } ?: return@fold ids
        ids.mergeMissing(parseTrackingExternalIds("${providerNamespace(key)}:$id"))
    }
    return ids.copy(imdb = ids.imdb?.takeIf { it.startsWith("tt") })
}

private fun providerNamespace(key: String): String = when (val name = key.trim().lowercase()) {
    "myanimelist" -> "mal"
    else -> name
}

internal fun libraryKind(collectionType: String?, type: String? = null): ServerMediaKind? = when (collectionType?.lowercase()) {
    "movies" -> ServerMediaKind.MOVIE
    "tvshows" -> ServerMediaKind.SERIES
    "boxsets" -> ServerMediaKind.COLLECTION
    null, "", "mixed" -> ServerMediaKind.MIXED.takeIf { type.equals("CollectionFolder", ignoreCase = true) }
    else -> null
}

internal const val TICKS_PER_MS = 10_000L

private fun ticksToMinutes(ticks: Long): Int = (ticks / TICKS_PER_MS / 60_000L).toInt()

private fun Double.formatRating(): Float = ((this * 10).roundToInt() / 10.0).toFloat()

private fun resolutionLabel(stream: MediaStream): String? {
    val width = stream.width ?: 0
    val height = stream.height ?: 0
    return when {
        width >= 3200 || height >= 2000 -> "4K"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }
}
