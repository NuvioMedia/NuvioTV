package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AddonConfigServer(
    private val context: Context,
    private val webConfigMode: WebConfigMode,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingAddonChange) -> Unit,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8080
) : NanoHTTPD(port) {

    enum class WebConfigMode(
        val allowAddonManagement: Boolean,
        val allowCatalogManagement: Boolean
    ) {
        FULL(
            allowAddonManagement = true,
            allowCatalogManagement = true
        ),
        COLLECTIONS_ONLY(
            allowAddonManagement = false,
            allowCatalogManagement = false
        )
    }

    data class AddonInfo(
        val url: String,
        val name: String,
        val description: String?
    )

    data class CatalogInfo(
        val key: String,
        val disableKey: String,
        val catalogName: String,
        val addonName: String,
        val type: String,
        val isDisabled: Boolean
    )

    data class CollectionInfo(
        val id: String,
        val title: String,
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val focusGlowEnabled: Boolean = true,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<FolderInfo>
    )

    data class FolderInfo(
        val id: String,
        val title: String,
        val coverImageUrl: String?,
        val focusGifUrl: String?,
        val focusGifEnabled: Boolean = true,
        val coverEmoji: String?,
        val tileShape: String,
        val hideTitle: Boolean,
        val catalogSources: List<CatalogSourceInfo>
    )

    data class CatalogSourceInfo(
        val addonId: String,
        val type: String,
        val catalogId: String
    )

    data class PageState(
        val addons: List<AddonInfo>,
        val catalogs: List<CatalogInfo>,
        val collections: List<CollectionInfo> = emptyList(),
        val disabledCollectionKeys: List<String> = emptyList()
    )

    data class PendingAddonChange(
        val id: String = UUID.randomUUID().toString(),
        val proposedUrls: List<String>,
        val proposedCatalogOrderKeys: List<String> = emptyList(),
        val proposedDisabledCatalogKeys: List<String> = emptyList(),
        val proposedCollectionsJson: String? = null,
        val proposedDisabledCollectionKeys: List<String> = emptyList(),
        var status: ChangeStatus = ChangeStatus.PENDING
    )

    enum class ChangeStatus { PENDING, CONFIRMED, REJECTED }

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingAddonChange>()

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.REJECTED
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

    private fun handleAddonUpdate(session: IHTTPSession): Response {
        // Auto-reject any stale pending changes so a new request can proceed
        pendingChanges.values
            .filter { it.status == ChangeStatus.PENDING }
            .forEach { it.status = ChangeStatus.REJECTED }

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
            webConfigMode: WebConfigMode = WebConfigMode.FULL,
            currentPageStateProvider: () -> PageState,
            onChangeProposed: (PendingAddonChange) -> Unit,
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

internal fun sanitizePendingAddonChange(
    mode: AddonConfigServer.WebConfigMode,
    proposedChange: AddonConfigServer.PendingAddonChange,
    currentState: AddonConfigServer.PageState
): AddonConfigServer.PendingAddonChange {
    if (mode.allowAddonManagement && mode.allowCatalogManagement) {
        return proposedChange
    }

    return proposedChange.copy(
        proposedUrls = if (mode.allowAddonManagement) {
            proposedChange.proposedUrls
        } else {
            currentState.addons.map { it.url }
        },
        proposedCatalogOrderKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedCatalogOrderKeys
        } else {
            currentState.catalogs.map { it.key }
        },
        proposedDisabledCatalogKeys = if (mode.allowCatalogManagement) {
            proposedChange.proposedDisabledCatalogKeys
        } else {
            currentState.catalogs
                .filter { it.isDisabled }
                .map { it.disableKey }
        }
    )
}
