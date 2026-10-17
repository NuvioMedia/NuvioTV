package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal class FakeServerProvider(
    private val movieCount: Int = 120,
    private val transcodes: Boolean = false
) : ServerProvider {
    override val id: String = "fake"
    override val displayName: String = "Fake"
    override val minimumVersion: String = "1.0"
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE
    )

    val indexedIds = mutableMapOf<String, TrackingExternalIds>()
    val episodes = mutableMapOf<Pair<Int, Int>, ServerEpisode>()
    val reported = mutableListOf<ServerPlaybackEventType>()
    val playbackRequests = mutableListOf<ServerPlaybackRequest>()
    val searches = mutableListOf<Pair<ServerMediaKind, List<String>>>()
    val playedChanges = mutableListOf<Pair<String, Boolean>>()
    val failingPlayed = mutableSetOf<String>()
    val failingLibraries = mutableSetOf<String>()
    val userStates = mutableMapOf<String, List<ServerUserState>>()

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> = listOf(MOVIE_LIBRARY, SERIES_LIBRARY)

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        if (library.id in failingLibraries) throw ServerException(ServerFailure.UNREACHABLE)
        val ids = (start until minOf(start + limit, movieCount)).map { it.toString() }
        return ServerPage(ids.map { title(session, it) }, movieCount)
    }

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = ServerPage(
        listOf(title(session, "7"), title(session, SHOW_ID, ServerMediaKind.SERIES)),
        2
    )

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        searches += kind to libraries.map { it.id }
        return listOf(title(session, "7"))
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = listOf(title(session, "3"))

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val kind = if (itemId == SHOW_ID) ServerMediaKind.SERIES else ServerMediaKind.MOVIE
        return ServerItemDetails(
            meta = meta(
                id = ServerItemRef(session.connection.id, itemId).encode(),
                kind = kind,
                name = "Item $itemId",
                videos = if (kind == ServerMediaKind.SERIES) {
                    listOf(video(ServerItemRef(session.connection.id, EPISODE_ID).encode(), season = 1, episode = 1))
                } else {
                    emptyList()
                }
            ),
            externalIds = indexedIds[itemId] ?: TrackingExternalIds(),
            userStates = userStates[itemId].orEmpty()
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> = listOf(
        ServerCandidate(
            target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), mediaSourceId = "src-$itemId"),
            title = "Original",
            description = null,
            filename = "item-$itemId.mkv",
            sizeBytes = null
        )
    )

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        playbackRequests += request
        if (transcodes) {
            val audio = request.audioStreamIndex ?: 1
            val subtitle = request.subtitleStreamIndex
            return ServerPlaybackSession(
                target = request.target,
                mediaSourceId = request.target.mediaSourceId ?: "default",
                url = "https://fake.example/${request.target.item.itemId}/master.m3u8?AudioStreamIndex=$audio&SubtitleStreamIndex=$subtitle",
                headers = emptyMap(),
                subtitles = emptyList(),
                playSessionId = "ps$audio",
                playMethod = ServerPlayMethod.TRANSCODE,
                audioTracks = listOf(
                    ServerTrack(index = 1, label = "English", language = "eng", selected = audio == 1),
                    ServerTrack(index = 2, label = "Japanese", language = "jpn", selected = audio == 2)
                ),
                burnInSubtitles = listOf(
                    ServerTrack(index = 3, label = "English PGS", language = "eng", selected = subtitle == 3)
                )
            )
        }
        if (!request.capabilities.allowDirectPlay) throw ServerException(ServerFailure.UNSUPPORTED)
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = request.target.mediaSourceId ?: "default",
            url = "https://fake.example/${request.target.item.itemId}",
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY
        )
    }

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        synchronized(playedChanges) { playedChanges += itemId to played }
        if (itemId in failingPlayed) throw ServerException(ServerFailure.FORBIDDEN)
    }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        synchronized(reported) { reported += event.type }
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val entries = indexedIds.entries
            .filter { (itemId, _) -> (itemId == SHOW_ID) == (library.kind == ServerMediaKind.SERIES) }
            .map { ServerIndexEntry(it.key, it.value) }
        return ServerPage(entries.drop(start).take(limit), entries.size)
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? =
        episodes[season to episode]

    private fun title(session: ServerSession, itemId: String, kind: ServerMediaKind = ServerMediaKind.MOVIE) = ServerTitle(
        preview = MetaPreview(
            id = ServerItemRef(session.connection.id, itemId).encode(),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = "Item $itemId",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        externalIds = indexedIds[itemId] ?: TrackingExternalIds()
    )

    companion object {
        const val SHOW_ID = "500"
        const val EPISODE_ID = "901"
        val MOVIE_LIBRARY = ServerLibrary(id = "10", name = "Movies", kind = ServerMediaKind.MOVIE)
        val SERIES_LIBRARY = ServerLibrary(id = "20", name = "Shows", kind = ServerMediaKind.SERIES)
        val COLLECTION_LIBRARY = ServerLibrary(id = "30", name = "Featured", kind = ServerMediaKind.COLLECTION)
    }
}

internal class MemoryServerPersistence : ServerPersistence {
    val values = mutableMapOf<Int, String>()
    override fun read(profileId: Int): String? = values[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() = values.clear()
}

internal fun fakeServerRepository(
    provider: FakeServerProvider = FakeServerProvider(),
    connectionId: String = "cfake",
    libraries: List<ServerLibrary> = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.SERIES_LIBRARY)
): Pair<ServerRepository, ServerConnection> {
    val repository = ServerRepository(MemoryServerPersistence(), listOf(provider), CoroutineScope(Dispatchers.Unconfined))
    val connection = ServerConnection(
        id = connectionId,
        providerId = provider.id,
        name = "Box",
        address = "https://fake.example",
        remoteServerId = "server-1",
        remoteUserId = "user-1",
        userName = "viewer",
        credentialRef = "k$connectionId",
        libraries = libraries
    )
    repository.store(connection, token = "token")
    return repository to connection
}

internal fun meta(id: String, kind: ServerMediaKind, name: String, videos: List<Video> = emptyList(), imdbId: String? = null) = Meta(
    id = id,
    type = kind.domainType(),
    rawType = kind.contentType,
    name = name,
    poster = null,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList(),
    runtime = null,
    director = emptyList(),
    cast = emptyList(),
    videos = videos,
    country = null,
    awards = null,
    language = null,
    links = emptyList(),
    imdbId = imdbId
)

internal fun video(id: String, season: Int, episode: Int) = Video(
    id = id,
    title = "Episode $episode",
    released = null,
    thumbnail = null,
    season = season,
    episode = episode,
    overview = null
)
