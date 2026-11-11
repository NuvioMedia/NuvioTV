package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerEpisode
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import com.nuvio.tv.data.mediaserver.verifiedTitleMatches
import com.nuvio.tv.domain.model.Subtitle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

/**
 * Plex Media Server. Sign-in goes through plex.tv with a code (see [account]), then a server and a Plex Home user are
 * chosen; the saved token is the one plex.tv hands out for that server and user.
 */
class PlexProvider internal constructor(
    http: OkHttpClient,
    identity: ServerClientIdentity,
    private val now: () -> Long
) : ServerProvider {
    constructor(http: OkHttpClient, identity: ServerClientIdentity) : this(http, identity, System::currentTimeMillis)

    override val id: String = ID
    override val displayName: String = "Plex"
    override val minimumVersion: String = ""
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE,
        ServerCapability.TRANSCODING
    )

    private val client = PlexClient(http, identity)
    internal val account = PlexAccountClient(client, now)
    private val plays = ConcurrentHashMap<String, PlexPlay>()
    private val items = ConcurrentHashMap<String, Cached<MetadataWire>>()
    private val leaves = ConcurrentHashMap<String, Cached<List<MetadataWire>>>()

    // Sign-in

    /** The server's own id at [address]; null when nothing that answers as Plex is there. */
    suspend fun machineId(address: String): String? = try {
        container(address, null, IDENTITY_PATH).machineIdentifier?.takeIf { it.isNotBlank() }
    } catch (_: ServerException) {
        null
    }

    suspend fun reach(server: PlexServer): PlexServer = account.reach(server, ::machineId)

    /** The connection for [user] on [server]; [server] must have been reached and carry [user]'s server token. */
    fun signIn(server: PlexServer, user: PlexAccount): ServerSignIn = ServerSignIn(
        address = server.address ?: throw ServerException(ServerFailure.UNREACHABLE),
        serverName = server.name,
        serverId = server.machineId,
        userId = user.id,
        userName = user.name,
        token = server.accessToken
    )

    /** [server] as the Plex Home [user] sees it, with that user's own token; it keeps the address found for [owner]. */
    suspend fun serverFor(user: PlexAccount, owner: PlexAccount, server: PlexServer): PlexServer {
        if (user.uuid == owner.uuid) return server
        return account.servers(user.token).firstOrNull { it.machineId == server.machineId }
            ?.reached(server.reach, server.address)
            ?: throw ServerException(ServerFailure.FORBIDDEN)
    }

    // Browsing

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session, SECTIONS_PATH).directories.mapNotNull { section ->
            val kind = plexKind(section.type) ?: return@mapNotNull null
            section.key.takeIf { it.isNotBlank() }?.let { ServerLibrary(it, section.title.ifBlank { it }, kind) }
        }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val reply = get(session, sectionPath(library), sectionQuery(library) + page(start, limit) + ("sort" to "addedAt:desc"))
        val mapper = mapper(session)
        return ServerPage(reply.metadata.mapNotNull(mapper::title), reply.totalSize)
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val found = coroutineScope {
            libraries.filter { it.holds(kind) }.map { library ->
                async {
                    get(session, sectionPath(library), sectionQuery(library) + page(0, limit) + ("title" to query.trim())).metadata
                }
            }.awaitAll().flatten()
        }
        val mapper = mapper(session)
        return found.mapNotNull(mapper::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId, fresh = true)
        val mapper = mapper(session)
        val episodes = if (item.type == "show") allLeaves(session, itemId, fresh = true) else emptyList()
        return ServerItemDetails(
            meta = mapper.details(item, episodes),
            externalIds = item.externalIds(),
            userStates = (if (item.type == "show") episodes else listOf(item)).map(mapper::userState)
        )
    }

    /** Items started and not finished. Plex's On Deck also lists the next unwatched episode, which the app works out itself. */
    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val query = page(0, limit * 2) + ("includeGuids" to "1")
        val reply = try {
            get(session, CONTINUE_WATCHING_PATH, query)
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND) throw error
            get(session, ON_DECK_PATH, query)
        }
        val sections = session.connection.selectedLibraries.map { it.id }.toSet()
        val mapper = mapper(session)
        return reply.metadata.ifEmpty { reply.hubs.flatMap { it.metadata } }
            .filter { (it.viewOffset ?: 0L) > 0L }
            .filter { item -> item.librarySectionId == null || item.librarySectionId.toString() in sections }
            .mapNotNull(mapper::resumeTitle)
            .distinctBy { it.preview.id }
            .take(limit)
    }

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        get(session, if (played) SCROBBLE_PATH else UNSCROBBLE_PATH, mapOf("key" to itemId, "identifier" to LIBRARY_IDENTIFIER), decode = false)
        forget(session)
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val reply = get(session, sectionPath(library), sectionQuery(library) + page(start, limit) + ("sort" to "titleSort"))
        val mapper = mapper(session)
        return ServerPage(reply.metadata.map(mapper::indexEntry), reply.totalSize)
    }

    /** A title search in [library], by name and year first; the matcher compares the ids of what comes back. */
    override suspend fun lookupTitle(session: ServerSession, library: ServerLibrary, query: ServerTitleQuery): List<ServerIndexEntry>? {
        val names = listOfNotNull(query.name, query.originalName).map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) return null
        val mapper = mapper(session)
        val found = mutableListOf<ServerIndexEntry>()
        val years = listOfNotNull(query.year?.toString()) + listOf(null)
        for (name in names) {
            for (year in years) {
                val reply = get(
                    session,
                    sectionPath(library),
                    sectionQuery(library) + page(0, TITLE_LOOKUP_LIMIT) + mapOf("title" to name, "year" to year)
                )
                found += reply.metadata.filter { plexKind(it.type) == library.kind }.map(mapper::indexEntry)
                val distinct = found.distinctBy { it.itemId }
                if (verifiedTitleMatches(distinct, query).isNotEmpty()) return distinct
            }
        }
        return found.distinctBy { it.itemId }
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? =
        allLeaves(session, seriesItemId)
            .filter { it.parentIndex == season && it.index == episode }
            .singleOrNull()
            ?.let { ServerEpisode(it.ratingKey, it.originallyAvailableAt) }

    // Playback

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(item(session, itemId))

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val ratingKey = request.target.item.itemId
        val item = item(session, ratingKey)
        val mediaIndex = item.media.indexOfFirst { media -> media.parts.any { it.id.toString() == request.target.mediaSourceId } }
            .coerceAtLeast(0)
        val media = item.media.getOrNull(mediaIndex) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val part = media.parts.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (request.audioStreamIndex != null || request.subtitleStreamIndex != null) {
            chooseStreams(session, part.id, request.audioStreamIndex, request.subtitleStreamIndex)
        }
        val playSession = UUID.randomUUID().toString()
        val headers = client.headers + mapOf(PlexClient.TOKEN_HEADER to session.token, SESSION_HEADER to playSession)
        val audioTracks = part.streams.filter { it.streamType == STREAM_AUDIO }.map { stream ->
            ServerTrack(stream.id.toInt(), stream.label(), stream.languageCode, request.audioStreamIndex?.let { it.toLong() == stream.id } ?: stream.selected)
        }
        val target = ServerPlaybackTarget(request.target.item, part.id.toString())
        val playback = if (request.capabilities.allowDirectPlay) {
            val path = part.key.takeIf { it.startsWith("/") } ?: "$PARTS_PATH/${part.id}/file"
            checkReadable(session, path)
            ServerPlaybackSession(
                target = target,
                mediaSourceId = part.id.toString(),
                url = buildUrl(session.connection.address, path, mapOf(SESSION_HEADER to playSession)),
                headers = headers,
                subtitles = externalSubtitles(session, part),
                playSessionId = playSession,
                playMethod = ServerPlayMethod.DIRECT_PLAY,
                audioTracks = audioTracks
            )
        } else {
            val query = transcodeQuery(item, mediaIndex, playSession, burnSubtitles = request.subtitleStreamIndex != null)
            val decision = decide(session, query)
            val decided = decision.metadata.firstOrNull()?.media?.firstOrNull()?.parts?.firstOrNull()?.streams.orEmpty()
            val video = decided.filter { it.streamType == STREAM_VIDEO }
            val method = if (video.isNotEmpty() && video.all { it.decision == "copy" }) {
                ServerPlayMethod.DIRECT_STREAM
            } else {
                ServerPlayMethod.TRANSCODE
            }
            ServerPlaybackSession(
                target = target,
                mediaSourceId = part.id.toString(),
                url = buildUrl(session.connection.address, TRANSCODE_START_PATH, query),
                headers = headers + (PROFILE_EXTRA_HEADER to PROFILE_EXTRA),
                subtitles = externalSubtitles(session, part),
                playSessionId = playSession,
                playMethod = method,
                audioTracks = audioTracks,
                burnInSubtitles = part.streams.filter { it.streamType == STREAM_SUBTITLE }.map { stream ->
                    ServerTrack(stream.id.toInt(), stream.label(), stream.languageCode, request.subtitleStreamIndex?.toLong() == stream.id)
                },
                transcodeReasons = if (method == ServerPlayMethod.DIRECT_STREAM) {
                    emptyList()
                } else {
                    listOfNotNull(decision.transcodeDecisionText).filter { it.isNotBlank() }
                }
            )
        }
        plays[playSession] = PlexPlay(
            ratingKey = ratingKey,
            key = item.key.ifBlank { "$METADATA_PATH/$ratingKey" },
            durationMs = item.duration ?: media.duration ?: 0L,
            transcoded = playback.playMethod != ServerPlayMethod.DIRECT_PLAY,
            preparedAtMs = now()
        )
        plays.entries.removeIf { (_, play) -> now() - play.preparedAtMs > PLAY_EXPIRY_MS }
        return playback
    }

    /** Plex refuses the file itself, not the browsing, when it blocks playback away from home without a pass. */
    private suspend fun checkReadable(session: ServerSession, path: String) {
        try {
            client.execute("GET", session.connection.address, path, session.token, range = 0L..0L)
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.FORBIDDEN) throw ServerException(ServerFailure.REMOTE_PLAYBACK_BLOCKED)
        }
    }

    private suspend fun decide(session: ServerSession, query: Map<String, String?>): MediaContainerWire {
        val reply = try {
            client.execute(
                "GET",
                session.connection.address,
                TRANSCODE_DECISION_PATH,
                session.token,
                query,
                extraHeaders = mapOf(PROFILE_EXTRA_HEADER to PROFILE_EXTRA)
            )
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.FORBIDDEN) throw ServerException(ServerFailure.REMOTE_PLAYBACK_BLOCKED)
            throw error
        }
        val container = decode(reply.body) ?: return MediaContainerWire()
        if ((container.generalDecisionCode ?: 0) >= DECISION_FAILED_FROM) {
            throw ServerException(ServerFailure.UNSUPPORTED, container.generalDecisionText)
        }
        return container
    }

    private fun transcodeQuery(item: MetadataWire, mediaIndex: Int, playSession: String, burnSubtitles: Boolean): Map<String, String?> = mapOf(
        "path" to item.key.ifBlank { "$METADATA_PATH/${item.ratingKey}" },
        "mediaIndex" to mediaIndex.toString(),
        "partIndex" to "0",
        "protocol" to "hls",
        "fastSeek" to "1",
        "directPlay" to "0",
        "directStream" to "1",
        "directStreamAudio" to "1",
        "subtitleSize" to "100",
        "audioBoost" to "100",
        "subtitles" to if (burnSubtitles) "burn" else "none",
        "session" to playSession,
        SESSION_HEADER to playSession,
        "copyts" to "1",
        "hasMDE" to "1"
    )

    /** Plex keeps the chosen audio and subtitle on the file for the user, so both are set before asking for a stream. */
    private suspend fun chooseStreams(session: ServerSession, partId: Long, audio: Int?, subtitle: Int?) {
        client.execute(
            "PUT",
            session.connection.address,
            "$PARTS_PATH/$partId",
            session.token,
            mapOf("audioStreamID" to audio?.toString(), "subtitleStreamID" to subtitle?.toString(), "allParts" to "1")
        )
    }

    private fun externalSubtitles(session: ServerSession, part: PartWire): List<Subtitle> =
        part.streams.filter { stream ->
            stream.streamType == STREAM_SUBTITLE && stream.key?.startsWith("/") == true && stream.codec?.lowercase() in TEXT_SUBTITLES
        }.map { stream ->
            Subtitle(
                id = "plex:${stream.id}",
                url = buildUrl(session.connection.address, stream.key!!),
                lang = stream.languageCode?.takeIf { it.isNotBlank() } ?: "und",
                addonName = stream.label(),
                addonLogo = null,
                isStreamProvided = true,
                headers = mapOf(PlexClient.TOKEN_HEADER to session.token)
            )
        }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        val playSession = playback.playSessionId ?: return
        val play = plays[playSession] ?: return
        val state = when {
            event.type == ServerPlaybackEventType.STOP -> "stopped"
            event.isPaused || event.type == ServerPlaybackEventType.PAUSE -> "paused"
            else -> "playing"
        }
        val sessionHeader = mapOf(SESSION_HEADER to playSession)
        try {
            client.execute(
                "GET",
                session.connection.address,
                TIMELINE_PATH,
                session.token,
                mapOf(
                    "ratingKey" to play.ratingKey,
                    "key" to play.key,
                    "state" to state,
                    "time" to event.positionMs.coerceAtLeast(0L).toString(),
                    "duration" to play.durationMs.takeIf { it > 0 }?.toString(),
                    "hasMDE" to "1"
                ),
                extraHeaders = sessionHeader
            )
        } finally {
            if (event.type == ServerPlaybackEventType.STOP) {
                plays.remove(playSession)
                forget(session)
                if (play.transcoded) {
                    runCatching {
                        client.execute(
                            "GET",
                            session.connection.address,
                            TRANSCODE_STOP_PATH,
                            session.token,
                            mapOf("session" to playSession),
                            extraHeaders = sessionHeader
                        )
                    }
                }
            }
        }
    }

    // Requests

    private suspend fun item(session: ServerSession, ratingKey: String, fresh: Boolean = false): MetadataWire {
        val key = "${session.connection.id}|$ratingKey"
        if (!fresh) items[key]?.takeIf { now() - it.atMs < ITEM_TTL_MS }?.let { return it.value }
        val item = get(
            session,
            "$METADATA_PATH/${pathSegment(ratingKey)}",
            mapOf("includeGuids" to "1", "includeExtras" to "0")
        ).metadata.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (items.size >= MAX_CACHED) items.clear()
        items[key] = Cached(now(), item)
        return item
    }

    /** Every episode of a show in one call, kept briefly for the next-episode and matching lookups around a play. */
    private suspend fun allLeaves(session: ServerSession, showKey: String, fresh: Boolean = false): List<MetadataWire> {
        val key = "${session.connection.id}|$showKey"
        if (!fresh) leaves[key]?.takeIf { now() - it.atMs < LEAVES_TTL_MS }?.let { return it.value }
        val episodes = get(
            session,
            "$METADATA_PATH/${pathSegment(showKey)}/allLeaves",
            mapOf("excludeElements" to LEAVES_EXCLUDED, "excludeFields" to "tagline")
        ).metadata.filter { it.ratingKey.isNotBlank() }
        if (leaves.size >= MAX_CACHED) leaves.clear()
        leaves[key] = Cached(now(), episodes)
        return episodes
    }

    private fun forget(session: ServerSession) {
        val prefix = "${session.connection.id}|"
        items.keys.removeIf { it.startsWith(prefix) }
        leaves.keys.removeIf { it.startsWith(prefix) }
    }

    private fun sectionPath(library: ServerLibrary) = "$SECTIONS_PATH/${pathSegment(library.id)}/all"

    private fun sectionQuery(library: ServerLibrary): Map<String, String?> =
        mapOf("type" to plexTypeNumber(library.kind), "includeGuids" to "1")

    private fun page(start: Int, size: Int): Map<String, String?> = mapOf(
        "X-Plex-Container-Start" to start.coerceAtLeast(0).toString(),
        "X-Plex-Container-Size" to size.coerceAtLeast(1).toString()
    )

    private fun mapper(session: ServerSession) = PlexMapper(session.connection.address, session.connection.id)

    private suspend fun get(
        session: ServerSession,
        path: String,
        query: Map<String, String?> = emptyMap(),
        decode: Boolean = true
    ): MediaContainerWire {
        val reply = client.execute("GET", session.connection.address, path, session.token, query)
        if (!decode) return MediaContainerWire()
        return decode(reply.body) ?: throw ServerException(ServerFailure.FAILED)
    }

    private suspend fun container(address: String, token: String?, path: String): MediaContainerWire =
        decode(client.execute("GET", address, path, token).body) ?: throw ServerException(ServerFailure.FAILED)

    private fun decode(body: String): MediaContainerWire? = try {
        client.json.decodeFromString(ContainerReply.serializer(), body).container
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Cached<T>(val atMs: Long, val value: T)

    private class PlexPlay(
        val ratingKey: String,
        val key: String,
        val durationMs: Long,
        val transcoded: Boolean,
        val preparedAtMs: Long
    )

    internal companion object {
        const val ID = "plex"
        const val IDENTITY_PATH = "/identity"
        const val SECTIONS_PATH = "/library/sections"
        const val METADATA_PATH = "/library/metadata"
        const val PARTS_PATH = "/library/parts"
        const val CONTINUE_WATCHING_PATH = "/hubs/continueWatching/items"
        const val ON_DECK_PATH = "/library/onDeck"
        const val SCROBBLE_PATH = "/:/scrobble"
        const val UNSCROBBLE_PATH = "/:/unscrobble"
        const val TIMELINE_PATH = "/:/timeline"
        const val TRANSCODE_DECISION_PATH = "/video/:/transcode/universal/decision"
        const val TRANSCODE_START_PATH = "/video/:/transcode/universal/start.m3u8"
        const val TRANSCODE_STOP_PATH = "/video/:/transcode/universal/stop"
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val SESSION_HEADER = "X-Plex-Session-Identifier"
        const val PROFILE_EXTRA_HEADER = "X-Plex-Client-Profile-Extra"

        /** What the player takes from a Plex conversion: HLS in MPEG-TS with H.264 or HEVC, and the common audio codecs. */
        const val PROFILE_EXTRA =
            "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264,hevc&audioCodec=aac,ac3,eac3,mp3&replace=true)" +
                "+add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=ac3,eac3)" +
                "+add-direct-play-profile(type=videoProfile&container=mkv,mp4,mov,ts,avi,webm&videoCodec=*&audioCodec=*)"
        const val DECISION_FAILED_FROM = 2000
        const val PLAY_EXPIRY_MS = 6 * 60 * 60_000L
        const val ITEM_TTL_MS = 30_000L
        const val LEAVES_TTL_MS = 60_000L
        const val MAX_CACHED = 32
        const val TITLE_LOOKUP_LIMIT = 50
        val TEXT_SUBTITLES = setOf("srt", "ass", "ssa", "vtt", "webvtt", "smi")
        const val LEAVES_EXCLUDED = "Media,Genre,Country,Director,Writer,Role,Producer,Similar,Collection,Field,Mood,Style,Rating"
    }
}

private fun StreamWire.label(): String =
    extendedDisplayTitle?.takeIf { it.isNotBlank() }
        ?: displayTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }
        ?: codec?.uppercase()
        ?: id.toString()
