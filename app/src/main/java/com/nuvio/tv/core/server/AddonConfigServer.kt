package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AddonConfigServer(
    private val context: Context,
    private val webConfigMode: AddonWebConfigMode,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingAddonChange) -> Unit,
    private val playerSettingsDataStore: PlayerSettingsDataStore? = null,
    private val tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
    private val tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
    private val traktMetadataProvider: ((TraktSourceMetadataRequest) -> TraktSourceMetadataInfo?)? = null,
    private val traktSearchProvider: ((TraktSourceSearchRequest) -> List<TraktSourceSearchResultInfo>)? = null,
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
            method == Method.GET && uri == "/ai-keys" -> serveSubtitleAiConfigPage()
            method == Method.POST && uri == "/api/ai-keys" -> handleSubtitleAiConfigUpdate(session)
            method == Method.GET && uri == "/api/collections" -> serveCollections()
            method == Method.GET && uri == "/api/tmdb/metadata" -> serveTmdbMetadata(session)
            method == Method.GET && uri == "/api/tmdb/search" -> serveTmdbSearch(session)
            method == Method.GET && uri == "/api/trakt/metadata" -> serveTraktMetadata(session)
            method == Method.GET && uri == "/api/trakt/search" -> serveTraktSearch(session)
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

    private fun serveSubtitleAiConfigPage(): Response {
        val settings = playerSettingsDataStore?.let { dataStore ->
            runBlocking { dataStore.playerSettings.first() }
        }
        val provider = settings?.subtitleAiProvider ?: "GROQ"
        val model = settings?.subtitleAiModel ?: "llama3-8b-8192"
        val html = buildString {
            append("<!doctype html><html><head><meta charset='utf-8'>")
            append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<title>Subtitle AI Keys</title>")
            append("<style>")
            append("body{font-family:system-ui,-apple-system,sans-serif;background:#0f1115;color:#f5f7fb;margin:0;padding:24px;}")
            append(".card{max-width:720px;margin:0 auto;background:#171a21;border:1px solid #2a2f3a;border-radius:18px;padding:24px;}")
            append("h1{margin:0 0 8px;font-size:24px;}p{color:#b6bdcb;line-height:1.5;}")
            append("label{display:block;margin:16px 0 8px;font-weight:600;}")
            append("input,select{width:100%;box-sizing:border-box;padding:14px 16px;border-radius:12px;border:1px solid #2f3542;background:#0f1218;color:#f5f7fb;font-size:16px;}")
            append("input::placeholder{color:#70798a;}button{margin-top:20px;width:100%;padding:14px 16px;border:none;border-radius:12px;background:#3ea6ff;color:#001120;font-weight:700;font-size:16px;}")
            append(".status{margin-top:14px;min-height:22px;color:#8fd19e;}.error{color:#ff8a8a;}")
            append("</style></head><body><div class='card'>")
            append("<h1>Subtitle AI configuration</h1>")
            append("<p>Store the provider, model, and API keys for automatic subtitle sync.</p>")
            append("<label for='provider'>Provider</label>")
            append("<select id='provider'><option value='GROQ'${if (provider.equals("GROQ", ignoreCase = true)) " selected" else ""}>Groq</option><option value='GEMINI'${if (provider.equals("GEMINI", ignoreCase = true)) " selected" else ""}>Gemini</option></select>")
            append("<label for='model'>Model</label>")
            append("<input id='model' value='${model.replace("'", "&#39;")}' placeholder='llama-3.1-8b-instant or gemini-3.5-flash'>")
            append("<label for='groqKey'>Groq API key</label>")
            append("<input id='groqKey' type='password' placeholder='gsk_...'>")
            append("<label for='geminiKey'>Gemini API key</label>")
            append("<input id='geminiKey' type='password' placeholder='AIza...'>")
            append("<button onclick='save()'>Save keys</button>")
            append("<div id='status' class='status'></div>")
            append("<script>")
            append("async function save(){const status=document.getElementById('status');status.className='status';status.textContent='Saving...';try{const res=await fetch('/api/ai-keys',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({provider:document.getElementById('provider').value,model:document.getElementById('model').value,groqKey:document.getElementById('groqKey').value,geminiKey:document.getElementById('geminiKey').value})});const json=await res.json();if(!res.ok){throw new Error(json.error||'Save failed')}status.textContent='Saved successfully';document.getElementById('groqKey').value='';document.getElementById('geminiKey').value='';}catch(err){status.className='status error';status.textContent=err.message||'Save failed';}}")
            append("</script></div></body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleSubtitleAiConfigUpdate(session: IHTTPSession): Response {
        val settingsStore = playerSettingsDataStore
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Subtitle AI settings unavailable")))

        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""
        return try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            val provider = parsed["provider"]?.toString().orEmpty()
            val model = parsed["model"]?.toString().orEmpty()
            val groqKey = parsed["groqKey"]?.toString().orEmpty()
            val geminiKey = parsed["geminiKey"]?.toString().orEmpty()

            runBlocking {
                settingsStore.setSubtitleAiProvider(
                    com.nuvio.tv.data.local.SubtitleAiProvider.fromValue(provider)
                )
                settingsStore.setSubtitleAiModel(model)
                settingsStore.setSubtitleAiGroqKey(groqKey)
                settingsStore.setSubtitleAiGeminiKey(geminiKey)
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                gson.toJson(mapOf("status" to "saved"))
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                gson.toJson(mapOf("error" to (e.message ?: "Invalid request")))
            )
        }
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

    private fun serveTraktMetadata(session: IHTTPSession): Response {
        val provider = traktMetadataProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt metadata unavailable")))
        val input = session.parameters["input"]?.firstOrNull()?.trim().orEmpty()
        if (input.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid Trakt metadata request")))
        }
        val metadata = runCatching {
            provider(TraktSourceMetadataRequest(input = input))
        }.getOrNull()
        return if (metadata?.traktListId != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(metadata))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt list not found")))
        }
    }

    private fun serveTraktSearch(session: IHTTPSession): Response {
        val provider = traktSearchProvider
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Trakt search unavailable")))
        val query = session.parameters["query"]?.firstOrNull()?.trim().orEmpty()
        if (query.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", gson.toJson(mapOf("error" to "Invalid Trakt search request")))
        }
        val results = runCatching {
            provider(TraktSourceSearchRequest(query = query))
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
            val followAddonsOrder = parsed["followAddonsOrder"] as? Boolean
            sanitizePendingAddonChange(
                mode = webConfigMode,
                proposedChange = PendingAddonChange(
                    proposedUrls = urls,
                    proposedCatalogOrderKeys = catalogOrderKeys,
                    proposedDisabledCatalogKeys = disabledCatalogKeys,
                    proposedCollectionsJson = collectionsJson,
                    proposedDisabledCollectionKeys = disabledCollectionKeys,
                    proposedFollowAddonsOrder = followAddonsOrder
                ),
                currentState = currentPageStateProvider()
            )
        } catch (e: Exception) {
            val error = mapOf("error" to context.getString(com.nuvio.tv.R.string.web_error_invalid_request_body))
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
            traktMetadataProvider: ((TraktSourceMetadataRequest) -> TraktSourceMetadataInfo?)? = null,
            traktSearchProvider: ((TraktSourceSearchRequest) -> List<TraktSourceSearchResultInfo>)? = null,
            logoProvider: (() -> ByteArray?)? = null,
            playerSettingsDataStore: PlayerSettingsDataStore? = null,
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
                        traktMetadataProvider = traktMetadataProvider,
                        traktSearchProvider = traktSearchProvider,
                        logoProvider = logoProvider,
                        playerSettingsDataStore = playerSettingsDataStore,
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
