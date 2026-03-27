package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroupConfigServer(
    private val serverToken: String,
    private val webPageHtml: String,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingGroupChange) -> Unit,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8080
) : NanoHTTPD(port) {

    data class CatalogInfo(
        val key: String,
        val catalogName: String,
        val addonName: String,
        val type: String,
        val isSelected: Boolean
    )

    data class PageState(
        val availableCatalogs: List<CatalogInfo>,
        val catalogGroups: List<com.nuvio.tv.domain.model.CatalogGroup>,
        val mainGroups: List<com.nuvio.tv.domain.model.MainGroup>
    )

    data class PendingGroupChange(
        val id: String = UUID.randomUUID().toString(),
        val proposedCatalogGroups: List<com.nuvio.tv.domain.model.CatalogGroup>,
        val proposedMainGroups: List<com.nuvio.tv.domain.model.MainGroup>,
        var status: ChangeStatus = ChangeStatus.PENDING
    )

    enum class ChangeStatus { PENDING, CONFIRMED, REJECTED }

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingGroupChange>()

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.REJECTED
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        if (uri.startsWith("/api/")) {
            val reqToken = session.parameters["token"]?.firstOrNull()
            if (reqToken != serverToken) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}")
            }
        }

        return when {
            method == Method.GET && uri == "/" -> serveWebPage()
            method == Method.GET && uri == "/logo.png" -> serveLogo()
            method == Method.GET && uri == "/api/state" -> servePageState()
            method == Method.POST && uri == "/api/catalogs" -> handleUpdate(session)
            method == Method.GET && uri.startsWith("/api/status/") -> serveChangeStatus(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html", webPageHtml)
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

    private fun servePageState(): Response {
        val state = currentPageStateProvider()
        val json = gson.toJson(state)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleUpdate(session: IHTTPSession): Response {
        pendingChanges.values
            .filter { it.status == ChangeStatus.PENDING }
            .forEach { it.status = ChangeStatus.REJECTED }
            
        // Memory limit protection against DoS
        if (pendingChanges.size > 20) {
            val oldKeys = pendingChanges.filterValues { it.status != ChangeStatus.PENDING }.keys
            oldKeys.forEach { pendingChanges.remove(it) }
        }

        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val change: PendingGroupChange = try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            
            // Add fallback values for MainGroup so Moshi/Gson doesn't null them out and cause crashes
            val mainGroupsRaw = (parsed["mainGroups"] as? List<Map<String, Any>>)?.map { mg ->
                val mgMutable = mg.toMutableMap()
                if (!mgMutable.containsKey("posterType")) mgMutable["posterType"] = "Square"
                if (!mgMutable.containsKey("posterSize")) mgMutable["posterSize"] = "Default"
                mgMutable
            } ?: emptyList<Map<String, Any>>()

            val catalogGroupsJson = gson.toJson(parsed["catalogGroups"])
            val mainGroupsJson = gson.toJson(mainGroupsRaw)
            
            val cgType = object : TypeToken<List<com.nuvio.tv.domain.model.CatalogGroup>>() {}.type
            val mgType = object : TypeToken<List<com.nuvio.tv.domain.model.MainGroup>>() {}.type
            
            val cgs: List<com.nuvio.tv.domain.model.CatalogGroup> = gson.fromJson(catalogGroupsJson, cgType) ?: emptyList()
            val mgs: List<com.nuvio.tv.domain.model.MainGroup> = gson.fromJson(mainGroupsJson, mgType) ?: emptyList()

            PendingGroupChange(
                proposedCatalogGroups = cgs,
                proposedMainGroups = mgs
            )
        } catch (e: Exception) {
            val error = mapOf("error" to "Invalid request body")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(error)
            )
        }

        pendingChanges[change.id] = change
        onChangeProposed(change)

        val response = mapOf("status" to "pending_confirmation", "id" to change.id)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun serveChangeStatus(uri: String): Response {
        val id = uri.removePrefix("/api/status/")
        val change = pendingChanges[id]
        val status = change?.status?.name?.lowercase() ?: "not_found"
        val response = mapOf("status" to status)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
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
            serverToken: String,
            webPageHtml: String,
            currentPageStateProvider: () -> PageState,
            onChangeProposed: (PendingGroupChange) -> Unit,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8080,
            maxAttempts: Int = 10
        ): GroupConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = GroupConfigServer(
                        serverToken = serverToken,
                        webPageHtml = webPageHtml,
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
