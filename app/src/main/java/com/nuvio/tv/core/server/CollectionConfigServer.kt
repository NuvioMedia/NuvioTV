package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CollectionConfigServer(
    private val context: Context,
    private val currentStateProvider: () -> CollectionPageState,
    private val onChangeProposed: (PendingCollectionChange) -> Unit,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8100
) : NanoHTTPD(port) {

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

    data class AvailableCatalogInfo(
        val addonId: String,
        val addonName: String,
        val type: String,
        val catalogId: String,
        val catalogName: String
    )

    data class CollectionPageState(
        val collections: List<CollectionInfo>,
        val availableCatalogs: List<AvailableCatalogInfo>
    )

    data class PendingCollectionChange(
        val id: String = UUID.randomUUID().toString(),
        val proposedCollectionsJson: String,
        var status: ChangeStatus = ChangeStatus.PENDING
    )

    enum class ChangeStatus { PENDING, CONFIRMED, REJECTED }

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingCollectionChange>()

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
            method == Method.GET && uri == "/api/state" -> serveState()
            method == Method.POST && uri == "/api/collections" -> handleCollectionUpdate(session)
            method == Method.GET && uri.startsWith("/api/status/") -> serveChangeStatus(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", CollectionWebPage.getHtml(context))
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

    private fun serveState(): Response {
        val state = currentStateProvider()
        val json = gson.toJson(state)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun handleCollectionUpdate(session: IHTTPSession): Response {
        pendingChanges.values
            .filter { it.status == ChangeStatus.PENDING }
            .forEach { it.status = ChangeStatus.REJECTED }

        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val change: PendingCollectionChange = try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            val collectionsRaw = parsed["collections"]
            val collectionsJson = if (collectionsRaw != null) gson.toJson(collectionsRaw) else "[]"
            PendingCollectionChange(proposedCollectionsJson = collectionsJson)
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

    companion object {
        fun startOnAvailablePort(
            context: Context,
            currentStateProvider: () -> CollectionPageState,
            onChangeProposed: (PendingCollectionChange) -> Unit,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8100,
            maxAttempts: Int = 10
        ): CollectionConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = CollectionConfigServer(context, currentStateProvider, onChangeProposed, logoProvider, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) { }
            }
            return null
        }
    }
}
