package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import javax.inject.Inject
import javax.inject.Singleton

class ServerStreamSource(
    val name: String,
    val preferred: Boolean,
    private val loader: suspend () -> List<Stream>
) {
    suspend fun load(): List<Stream> = loader()
}

@Singleton
class ServerStreams @Inject constructor(
    private val repository: ServerRepository,
    private val matcher: ServerMatcher,
    /** Names and describes results with the stream templates when [DebridSettings.formatServerResults] is on. */
    private val formatter: DebridStreamFormatter? = null
) {
    val revision: Int
        get() = repository.uiState.value.revision

    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    fun canServe(type: String, videoId: String): Boolean {
        ServerItemRef.parse(videoId)?.let { ref -> return repository.connection(ref.connectionId)?.enabled == true }
        val connections = repository.enabledConnections().ifEmpty { return false }
        val request = matcher.request(type, videoId, season = null, episode = null) ?: return false
        return connections.any { matcher.supports(it, request.kind) }
    }

    /** [debridSettings] holds the stream templates; without it, or with them off for servers, results keep their usual text. */
    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
        debridSettings: DebridSettings? = null
    ): List<ServerStreamSource> {
        val templates = debridSettings?.takeIf { it.formatServerResults }
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = repository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection, preferred = true) { candidates(ref, templates) })
        }
        val connections = repository.enabledConnections().ifEmpty { return emptyList() }
        val request = matcher.request(type, videoId, season, episode) ?: return emptyList()
        return connections
            .filter { matcher.supports(it, request.kind) }
            .map { connection ->
                source(connection, preferred = connection.useCatalogMetadata) {
                    matcher.match(connection, request, forceRefresh).flatMap { candidates(it, templates) }
                }
            }
    }

    fun preferredSourceNames(type: String, videoId: String): Set<String> =
        sources(type, videoId, season = null, episode = null)
            .filter { it.preferred }
            .mapTo(mutableSetOf()) { it.name }

    private fun source(
        connection: ServerConnection,
        preferred: Boolean,
        loader: suspend () -> List<Stream>
    ): ServerStreamSource = ServerStreamSource(repository.sourceLabel(connection), preferred, loader)

    suspend fun candidates(ref: ServerItemRef, templates: DebridSettings? = null): List<Stream> {
        val connection = repository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = repository.sourceLabel(connection)
        val service = repository.provider(connection)?.displayName ?: connection.providerId
        return repository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
            .map { candidate ->
                // With templates, they name and describe the result; either keeps the usual text when its
                // template renders nothing.
                val formatted = if (templates == null) null else formatter?.let {
                    it.render(templates, ServerResultValues.of(candidate, service, connection.name, label, it::languageEmoji))
                }
                Stream(
                    name = formatted?.first?.ifBlank { null } ?: candidate.title,
                    title = null,
                    description = formatted?.second?.ifBlank { null } ?: candidate.description,
                    url = null,
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = StreamBehaviorHints(
                        notWebReady = null,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = null,
                        videoSize = candidate.sizeBytes,
                        filename = candidate.filename
                    ),
                    addonName = label,
                    addonLogo = null,
                    serverTarget = candidate.target
                )
            }
    }
}
