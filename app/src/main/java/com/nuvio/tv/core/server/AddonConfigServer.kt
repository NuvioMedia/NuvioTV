package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

class AddonConfigServer(
    private val context: Context,
    private val webConfigMode: AddonWebConfigMode,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingAddonChange) -> Unit,
    private val tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
    private val tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingAddonChange>()

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.REJECTED
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            method == Method.GET && uri == "/" -> serveWebPage()
            method == Method.GET && uri == "/logo.png" -> serveLogo()
            method == Method.GET && uri == "/api/state" -> servePageState()
            method == Method.GET && uri == "/api/addons" -> serveAddonList()
            method == Method.POST && uri == "/api/addons" -> handleAddonUpdate(session)
            method == Method.GET && uri == "/api/collections" -> serveCollections()
            method == Method.GET && uri == "/api/tmdb/metadata" -> serveTmdbMetadata(session)
            method == Method.GET && uri == "/api/tmdb/search" -> serveTmdbSearch(session)
            method == Method.GET && uri.startsWith("/api/status/") -> serveChangeStatus(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            AddonWebPage.getHtml(context, webConfigMode)
        )
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider?.invoke()
        return if (bytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveCollections(): Response {
        val collections = currentPageStateProvider().collections
        val json = gson.toJson(collections)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun serveAddonList(): Response {
        val addons = currentPageStateProvider().addons
        val json = gson.toJson(addons)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun servePageState(): Response {
        val state = currentPageStateProvider()
        val json = gson.toJson(state)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun serveTmdbMetadata(session: IHTTPSession): Response {
        val provider = tmdbMetadataProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB metadata unavailable")))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val tmdbId = session.parameters["id"]?.firstOrNull()?.trim()?.toIntOrNull()
        if (sourceType.isBlank() || tmdbId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid TMDB metadata request")))
        }
        val metadata = runCatching {
            provider(TmdbSourceMetadataRequest(sourceType = sourceType, tmdbId = tmdbId))
        }.getOrNull()
        return if (metadata != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(metadata))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB source not found")))
        }
    }

    private fun serveTmdbSearch(session: IHTTPSession): Response {
        val provider = tmdbSearchProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "TMDB search unavailable")))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val query = session.parameters["query"]?.firstOrNull()?.trim().orEmpty()
        if (sourceType.isBlank() || query.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid TMDB search request")))
        }
        val results = runCatching {
            provider(TmdbSourceSearchRequest(sourceType = sourceType, query = query))
        }.getOrElse { emptyList() }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(results))
    }

    private fun handleAddonUpdate(session: IHTTPSession): Response {
        // Auto-reject any stale pending changes so a new request can proceed
        pendingChanges.values
            .filter { it.status == AddonChangeStatus.PENDING }
            .forEach { it.status = AddonChangeStatus.REJECTED }

        // Parse request body
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val change: PendingAddonChange = try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            val urls = parseStringList(parsed["urls"])
            val catalogOrderKeys = parseStringList(parsed["catalogOrderKeys"])
            val disabledCatalogKeys = parseStringList(parsed["disabledCatalogKeys"])
            val collectionsRaw = parsed["collections"]
            val collectionsJson = if (collectionsRaw != null) gson.toJson(collectionsRaw) else null
            val disabledCollectionKeys = parseStringList(parsed["disabledCollectionKeys"])
            sanitizePendingAddonChange(
                mode = webConfigMode,
                proposedChange = PendingAddonChange(
                    proposedUrls = urls,
                    proposedCatalogOrderKeys = catalogOrderKeys,
                    proposedDisabledCatalogKeys = disabledCatalogKeys,
                    proposedCollectionsJson = collectionsJson,
                    proposedDisabledCollectionKeys = disabledCollectionKeys
                ),
                currentState = currentPageStateProvider()
            )
        } catch (e: Exception) {
            val error = mapOf("error" to "Invalid request body")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                gson.toJson(error)
            )
        }

        pendingChanges[change.id] = change
        onChangeProposed(change)

        val response = mapOf("status" to "pending_confirmation", "id" to change.id)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun serveChangeStatus(uri: String): Response {
        val id = uri.removePrefix("/api/status/")
        val change = pendingChanges[id]
        val status = change?.status?.name?.lowercase() ?: "not_found"
        val response = mapOf("status" to status)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun parseStringList(rawValue: Any?): List<String> {
        val values = rawValue as? List<*> ?: return emptyList()
        return values.asSequence()
            .mapNotNull { (it as? String)?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    companion object {
        fun startOnAvailablePort(
            context: Context,
            webConfigMode: AddonWebConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider: () -> PageState,
            onChangeProposed: (PendingAddonChange) -> Unit,
            tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
            tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8080,
            maxAttempts: Int = 10
        ): AddonConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AddonConfigServer(
                        context = context,
                        webConfigMode = webConfigMode,
                        currentPageStateProvider = currentPageStateProvider,
                        onChangeProposed = onChangeProposed,
                        tmdbMetadataProvider = tmdbMetadataProvider,
                        tmdbSearchProvider = tmdbSearchProvider,
                        logoProvider = logoProvider,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
