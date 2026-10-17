package com.nuvio.tv.data.mediaserver.mediabrowser

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
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.domain.model.Subtitle
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.DeserializationStrategy

internal data class Endpoint(
    val path: String,
    val query: Map<String, String> = emptyMap()
)

internal abstract class MediaBrowserProvider(
    authorizationHeader: String,
    private val apiPath: String,
    http: OkHttpClient,
    identity: ServerClientIdentity
) : ServerProvider {
    override val capabilities: Set<ServerCapability> = ServerCapability.entries.toSet()

    private val client = MediaBrowserClient(authorizationHeader, http, identity)
    private val aioStreamsServers = ConcurrentHashMap<String, Boolean>()
    private val libraryGenres = ConcurrentHashMap<String, String>()

    protected abstract fun viewsEndpoint(userId: String): Endpoint

    protected abstract fun itemsEndpoint(userId: String): Endpoint

    protected abstract fun itemEndpoint(userId: String, itemId: String): Endpoint

    protected abstract fun resumeEndpoint(userId: String): Endpoint

    protected abstract fun playedEndpoint(userId: String, itemId: String): Endpoint

    internal open fun isSupported(info: PublicInfo): Boolean = isSupportedVersion(info.version)

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn {
        val normalized = normalizeServerAddress(address, apiPath) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val (baseUrl, info) = publicInfo(normalized)
        if (!isSupported(info)) throw ServerException(ServerFailure.UNSUPPORTED)
        val response = client.execute(
            method = "POST",
            baseUrl = baseUrl + apiPath,
            path = "/Users/AuthenticateByName",
            token = null,
            body = client.json.encodeToString(AuthRequest.serializer(), AuthRequest(username, password))
        )
        val result = client.json.decodeFromString(AuthResult.serializer(), response.body)
        val user = result.user ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val serverId = result.serverId ?: info.id ?: throw ServerException(ServerFailure.FAILED)
        return ServerSignIn(
            address = baseUrl,
            serverName = info.serverName?.takeIf { it.isNotBlank() } ?: baseUrl.toHttpUrl().host,
            serverId = serverId,
            userId = user.id,
            userName = user.name ?: username,
            token = token
        )
    }

    private suspend fun publicInfo(baseUrl: String): Pair<String, PublicInfo> {
        val path = "/System/Info/Public"
        val response = client.execute("GET", baseUrl + apiPath, path, token = null, allowRedirect = true)
        if (response.status in 300..399) {
            val redirected = response.location
                ?.substringBefore(apiPath + path)
                ?.let { normalizeServerAddress(it, apiPath) }
                ?.takeIf { it != baseUrl }
                ?: throw ServerException(ServerFailure.FAILED)
            val retried = client.execute("GET", redirected + apiPath, path, token = null)
            return redirected to client.json.decodeFromString(PublicInfo.serializer(), retried.body)
        }
        return baseUrl to client.json.decodeFromString(PublicInfo.serializer(), response.body)
    }

    override suspend fun signOut(session: ServerSession) {
        client.execute("POST", session.apiRoot, "/Sessions/Logout", session.token)
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session, viewsEndpoint(session.userId), ItemsResult.serializer())
            .items
            .mapNotNull { view ->
                val kind = libraryKind(view.collectionType, view.type) ?: return@mapNotNull null
                ServerLibrary(id = view.id, name = view.name.orEmpty(), kind = kind)
            }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val key = "${session.connection.id}:${library.id}"
        var result = libraryItems(session, library, start, limit, libraryGenres[key])
        if (result.items.isEmpty() && start == 0 && !libraryGenres.containsKey(key) && library.kind != ServerMediaKind.COLLECTION) {
            val genre = firstGenre(session, library).orEmpty()
            libraryGenres[key] = genre
            if (genre.isNotEmpty()) result = libraryItems(session, library, start, limit, genre)
        }
        val mapper = mapper(session)
        return ServerPage(result.items.mapNotNull(mapper::title), result.totalRecordCount)
    }

    private suspend fun libraryItems(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
        genreId: String?
    ): ItemsResult = get(
        session,
        itemsEndpoint(session.userId),
        ItemsResult.serializer(),
        itemQuery(library) + mapOf(
            "genreIds" to genreId?.takeIf { it.isNotEmpty() },
            "sortBy" to if (library.kind == ServerMediaKind.COLLECTION) "SortName" else "DateCreated,SortName",
            "sortOrder" to if (library.kind == ServerMediaKind.COLLECTION) "Ascending" else "Descending",
            "startIndex" to start.toString(),
            "limit" to limit.toString(),
            "enableTotalRecordCount" to "true"
        )
    )

    private suspend fun firstGenre(session: ServerSession, library: ServerLibrary): String? =
        get(
            session,
            Endpoint("/Genres"),
            ItemsResult.serializer(),
            mapOf("parentId" to library.id, "userId" to session.userId, "limit" to "1")
        ).items.firstOrNull()?.id

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val result = get(
            session,
            itemsEndpoint(session.userId),
            ItemsResult.serializer(),
            mapOf(
                "parentId" to collectionId,
                "fields" to LIST_FIELDS,
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop,Logo",
                "startIndex" to start.toString(),
                "limit" to limit.toString(),
                "enableTotalRecordCount" to "true"
            )
        )
        return ServerPage(result.items.mapNotNull(mapper(session)::title), result.totalRecordCount)
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val scopes = if (isAioStreams(session)) listOf(null) else libraries
        val items = coroutineScope {
            scopes.map { library ->
                async {
                    get(
                        session,
                        itemsEndpoint(session.userId),
                        ItemsResult.serializer(),
                        mapOf(
                            "parentId" to library?.id,
                            "recursive" to "true",
                            "includeItemTypes" to kind.itemType(),
                            "searchTerm" to query,
                            "limit" to limit.toString(),
                            "fields" to LIST_FIELDS,
                            "imageTypeLimit" to "1",
                            "enableImageTypes" to "Primary,Backdrop,Logo"
                        )
                    ).items
                }
            }.awaitAll().flatten()
        }
        return items.mapNotNull(mapper(session)::title).distinctBy { it.preview.id }.take(limit)
    }

    private suspend fun isAioStreams(session: ServerSession): Boolean {
        aioStreamsServers[session.apiRoot]?.let { return it }
        val detected = try {
            publicInfo(session.connection.address).second.aioStreams != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        aioStreamsServers[session.apiRoot] = detected
        return detected
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val result = get(
            session,
            resumeEndpoint(session.userId),
            ItemsResult.serializer(),
            mapOf(
                "limit" to limit.toString(),
                "mediaTypes" to "Video",
                "fields" to LIST_FIELDS,
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop,Logo,Thumb"
            )
        )
        val libraries = session.connection.selectedLibraries
        return result.items
            .mapNotNull(mapper(session)::resumeTitle)
            .filter { title ->
                val kind = ServerMediaKind.fromContentType(title.preview.apiType)
                kind != null && libraries.any { it.holds(kind) }
            }
            .distinctBy { it.preview.id }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId, DETAIL_FIELDS)
        val episodes = if (item.mediaKind() == ServerMediaKind.SERIES) {
            get(
                session,
                Endpoint("/Shows/${pathSegment(itemId)}/Episodes"),
                ItemsResult.serializer(),
                mapOf(
                    "userId" to session.userId,
                    "fields" to "Overview,ProviderIds",
                    "enableUserData" to "true"
                )
            ).items
        } else {
            emptyList()
        }
        val mapper = mapper(session)
        return ServerItemDetails(
            meta = mapper.details(item, episodes),
            externalIds = item.externalIds(),
            userStates = (if (episodes.isEmpty()) listOf(item) else episodes).mapNotNull(mapper::userState)
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(item(session, itemId, "MediaSources"))

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        val endpoint = playedEndpoint(session.userId, itemId)
        client.execute(
            method = if (played) "POST" else "DELETE",
            baseUrl = session.apiRoot,
            path = endpoint.path,
            token = session.token,
            query = endpoint.query
        )
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val result = get(
            session,
            itemsEndpoint(session.userId),
            ItemsResult.serializer(),
            mapOf(
                "parentId" to library.id,
                "recursive" to "true",
                "includeItemTypes" to library.kind.itemType(),
                "fields" to "ProviderIds",
                "enableImages" to "false",
                "enableUserData" to "false",
                "startIndex" to start.toString(),
                "limit" to limit.toString(),
                "enableTotalRecordCount" to "true"
            )
        )
        return ServerPage(
            items = result.items.map { ServerIndexEntry(itemId = it.id, ids = it.externalIds()) },
            totalCount = result.totalRecordCount
        )
    }

    override suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? {
        val matches = get(
            session,
            Endpoint("/Shows/${pathSegment(seriesItemId)}/Episodes"),
            ItemsResult.serializer(),
            mapOf(
                "userId" to session.userId,
                "season" to season.toString(),
                "fields" to "PremiereDate",
                "enableImages" to "false",
                "enableUserData" to "false"
            )
        ).items.filter { item ->
            item.parentIndexNumber == season &&
                item.indexNumber == episode &&
                (item.indexNumberEnd == null || item.indexNumberEnd == episode) &&
                !item.isMissing
        }
        return matches.singleOrNull()?.let { ServerEpisode(itemId = it.id, premiereDate = it.premiereDate) }
    }

    override suspend fun preparePlayback(
        session: ServerSession,
        request: ServerPlaybackRequest
    ): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        val body = PlaybackInfoRequest(
            userId = session.userId,
            mediaSourceId = request.target.mediaSourceId,
            audioStreamIndex = request.audioStreamIndex,
            subtitleStreamIndex = request.subtitleStreamIndex,
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
            enableDirectPlay = request.capabilities.allowDirectPlay,
            deviceProfile = deviceProfile(request.capabilities)
        )
        val response = client.execute(
            method = "POST",
            baseUrl = session.apiRoot,
            path = "/Items/${pathSegment(itemId)}/PlaybackInfo",
            token = session.token,
            query = mapOf(
                "userId" to session.userId,
                "mediaSourceId" to body.mediaSourceId,
                "audioStreamIndex" to body.audioStreamIndex?.toString(),
                "subtitleStreamIndex" to body.subtitleStreamIndex?.toString(),
                "maxStreamingBitrate" to body.maxStreamingBitrate.toString(),
                "enableDirectPlay" to body.enableDirectPlay.toString()
            ),
            body = client.json.encodeToString(PlaybackInfoRequest.serializer(), body)
        )
        val info = client.json.decodeFromString(PlaybackInfoResult.serializer(), response.body)
        return playbackSession(session, request, info, client.deviceId)
    }

    internal fun playbackSession(
        session: ServerSession,
        request: ServerPlaybackRequest,
        info: PlaybackInfoResult,
        deviceId: String
    ): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        when (info.errorCode) {
            null -> Unit
            "NotAllowed" -> throw ServerException(ServerFailure.FORBIDDEN)
            else -> throw ServerException(ServerFailure.UNSUPPORTED, info.errorCode)
        }
        val source = info.mediaSources.firstOrNull { it.id == request.target.mediaSourceId }
            ?: info.mediaSources.firstOrNull { !it.isPlaceholder }
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val (url, method) = when {
            source.supportsDirectPlay && request.capabilities.allowDirectPlay -> buildUrl(
                session.apiRoot,
                "/Videos/${pathSegment(itemId)}/stream",
                mapOf(
                    "static" to "true",
                    "mediaSourceId" to source.id,
                    "playSessionId" to info.playSessionId,
                    "deviceId" to deviceId,
                    "tag" to source.eTag,
                    API_KEY to session.token
                )
            ) to ServerPlayMethod.DIRECT_PLAY

            source.transcodingUrl != null -> withApiKey(session.resolve(source.transcodingUrl), session.token) to
                if (source.supportsDirectStream) ServerPlayMethod.DIRECT_STREAM else ServerPlayMethod.TRANSCODE

            else -> throw ServerException(ServerFailure.UNSUPPORTED)
        }
        val subtitles = source.mediaStreams
            .filter { it.type.equals("Subtitle", ignoreCase = true) && it.deliveryMethod.equals("External", ignoreCase = true) }
            .mapNotNull { stream ->
                val deliveryUrl = stream.deliveryUrl ?: return@mapNotNull null
                Subtitle(
                    id = "${source.id}:${stream.index ?: deliveryUrl}",
                    url = withApiKey(session.resolve(deliveryUrl), session.token),
                    lang = stream.language ?: "und",
                    addonName = stream.displayTitle ?: displayName,
                    addonLogo = null,
                    isStreamProvided = true
                )
            }
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = source.id,
            url = url,
            headers = emptyMap(),
            subtitles = subtitles,
            playSessionId = info.playSessionId,
            playMethod = method,
            audioTracks = audioTracks(source, url, request.audioStreamIndex),
            burnInSubtitles = burnInSubtitles(source, url),
            transcodeReasons = transcodeReasons(url)
        )
    }

    private fun audioTracks(source: MediaSource, url: String, requestedIndex: Int?): List<ServerTrack> {
        val selected = queryValue(url, "AudioStreamIndex")?.toIntOrNull() ?: requestedIndex ?: source.defaultAudioStreamIndex
        return source.mediaStreams
            .filter { it.type.equals("Audio", ignoreCase = true) }
            .mapNotNull { it.track(selected) }
    }

    private fun burnInSubtitles(source: MediaSource, url: String): List<ServerTrack> {
        val selected = queryValue(url, "SubtitleStreamIndex")?.toIntOrNull()
        return source.mediaStreams
            .filter { it.type.equals("Subtitle", ignoreCase = true) && it.deliveryMethod.equals("Encode", ignoreCase = true) }
            .mapNotNull { it.track(selected) }
    }

    private fun MediaStream.track(selected: Int?): ServerTrack? {
        val index = index ?: return null
        return ServerTrack(
            index = index,
            label = displayTitle ?: language ?: index.toString(),
            language = language,
            selected = index == selected
        )
    }

    private fun transcodeReasons(url: String): List<String> =
        queryValue(url, "TranscodeReasons")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    override suspend fun report(
        session: ServerSession,
        playback: ServerPlaybackSession,
        event: ServerPlaybackEvent
    ) {
        val path = when (event.type) {
            ServerPlaybackEventType.START -> "/Sessions/Playing"
            ServerPlaybackEventType.STOP -> "/Sessions/Playing/Stopped"
            else -> "/Sessions/Playing/Progress"
        }
        val report = PlaybackReport(
            itemId = playback.target.item.itemId,
            mediaSourceId = playback.mediaSourceId,
            playSessionId = playback.playSessionId,
            positionTicks = event.positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
            isPaused = event.isPaused,
            playMethod = playback.playMethod.wireName,
            eventName = when (event.type) {
                ServerPlaybackEventType.PAUSE -> "Pause"
                ServerPlaybackEventType.RESUME -> "Unpause"
                ServerPlaybackEventType.PROGRESS -> "TimeUpdate"
                else -> null
            }
        )
        client.execute(
            method = "POST",
            baseUrl = session.apiRoot,
            path = path,
            token = session.token,
            body = client.json.encodeToString(PlaybackReport.serializer(), report)
        )
    }

    internal fun isSupportedVersion(version: String?): Boolean {
        val actual = version?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
        if (actual.firstOrNull() == null) return false
        for ((index, minimum) in minimumVersion.split('.').map(String::toInt).withIndex()) {
            val part = actual.getOrNull(index) ?: 0
            if (part != minimum) return part > minimum
        }
        return true
    }

    private suspend fun item(session: ServerSession, itemId: String, fields: String): BaseItem =
        get(session, itemEndpoint(session.userId, itemId), BaseItem.serializer(), mapOf("fields" to fields))

    private suspend fun <T> get(
        session: ServerSession,
        endpoint: Endpoint,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap()
    ): T = client.get(session.apiRoot, endpoint.path, deserializer, session.token, endpoint.query + query)

    private fun mapper(session: ServerSession) = MediaBrowserMapper(session.apiRoot, session.connection.id)

    private fun itemQuery(library: ServerLibrary): Map<String, String?> = mapOf(
        "parentId" to library.id,
        "recursive" to (library.kind != ServerMediaKind.COLLECTION).toString(),
        "includeItemTypes" to library.kind.itemType().takeUnless { library.kind == ServerMediaKind.COLLECTION },
        "fields" to LIST_FIELDS,
        "imageTypeLimit" to "1",
        "enableImageTypes" to "Primary,Backdrop,Logo"
    )

    private val ServerSession.userId: String
        get() = connection.remoteUserId

    private val ServerSession.apiRoot: String
        get() = connection.address + apiPath

    private fun ServerSession.resolve(serverUrl: String): String =
        if (apiPath.isNotEmpty() && serverUrl.startsWith(apiPath, ignoreCase = true)) {
            connection.address + serverUrl
        } else {
            apiRoot + serverUrl
        }

    private fun ServerMediaKind.itemType(): String = when (this) {
        ServerMediaKind.MOVIE -> "Movie"
        ServerMediaKind.SERIES -> "Series"
        ServerMediaKind.COLLECTION -> "BoxSet"
        ServerMediaKind.MIXED -> "Movie,Series"
    }

    private fun deviceProfile(capabilities: ServerPlayerCapabilities) = DeviceProfile(
        name = "Nuvio",
        maxStreamingBitrate = MAX_STREAMING_BITRATE,
        directPlayProfiles = if (capabilities.allowDirectPlay) {
            listOf(DirectPlayProfile())
        } else {
            listOf(
                DirectPlayProfile(
                    container = "mp4,m4v,mkv,webm,mov",
                    videoCodec = "h264,hevc,vp8,vp9,av1",
                    audioCodec = "aac,mp3,ac3,eac3,flac,opus,vorbis"
                )
            )
        },
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = "ts",
                videoCodec = "hevc,h264",
                audioCodec = "ac3,eac3,aac,mp3",
                protocol = "hls"
            )
        ),
        subtitleProfiles = EMBEDDED_SUBTITLES.map { SubtitleProfile(format = it, method = "Embed") } +
            TEXT_SUBTITLES.map { SubtitleProfile(format = it, method = "External") } +
            IMAGE_SUBTITLES.map { SubtitleProfile(format = it, method = "Encode") }
    )

    private fun withApiKey(url: String, token: String): String {
        if (queryValue(url, API_KEY) != null || queryValue(url, "ApiKey") != null) return url
        return url + (if ('?' in url) "&" else "?") + "$API_KEY=$token"
    }

    private fun queryValue(url: String, name: String): String? =
        url.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")

    private companion object {
        const val API_KEY = "api_key"
        const val MAX_STREAMING_BITRATE = 1_000_000_000L
        const val LIST_FIELDS = "Overview,Genres,ProviderIds,PremiereDate"
        const val DETAIL_FIELDS = "Overview,Genres,ProviderIds,People,Studios,PremiereDate,EndDate"
        val TEXT_SUBTITLES = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")
        val IMAGE_SUBTITLES = listOf("pgssub", "dvdsub", "dvbsub")
        val EMBEDDED_SUBTITLES = TEXT_SUBTITLES + "mov_text" + IMAGE_SUBTITLES
    }
}
