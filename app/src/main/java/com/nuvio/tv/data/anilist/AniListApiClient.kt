package com.nuvio.tv.data.anilist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AniListApiClient(
    private val store: AniListAuthStore,
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val throttle = Mutex()
    private var lastRequestAtEpochMs = 0L
    private var blockedUntilEpochMs = 0L

    suspend fun viewer(scope: AniListAuthScope): AniListViewer {
        val data = execute(VIEWER_QUERY, buildJsonObject { }, scope)
        val viewer = data["Viewer"]?.takeUnless { it is JsonNull } ?: throw AniListApiException(200, message = "Missing viewer")
        return aniListJson.decodeFromJsonElement(AniListViewer.serializer(), viewer)
    }

    suspend fun viewerForToken(token: String): AniListViewer {
        val response = post(VIEWER_QUERY, buildJsonObject { }, token)
        val root = runCatching { aniListJson.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        if (response.status == 401 || (response.status == 400 && root?.errorMessage().isInvalidTokenMessage())) {
            throw AniListAuthException(AniListAuthError.INVALID_CALLBACK)
        }
        if (response.status == 429) throw AniListApiException(429, message = root?.errorMessage())
        val data = root?.get("data") as? JsonObject
        if (response.status !in 200..299 || data == null) throw AniListApiException(response.status, message = root?.errorMessage())
        val viewer = data["Viewer"]?.takeUnless { it is JsonNull } ?: throw AniListAuthException(AniListAuthError.INVALID_CALLBACK)
        return aniListJson.decodeFromJsonElement(AniListViewer.serializer(), viewer)
    }

    suspend fun collection(scope: AniListAuthScope, userId: Long): List<AniListEntry> {
        val data = execute(COLLECTION_QUERY, buildJsonObject { put("userId", userId) }, scope)
        val lists = (data["MediaListCollection"] as? JsonObject)?.get("lists") as? JsonArray ?: return emptyList()
        val entries = linkedMapOf<Long, AniListEntry>()
        lists.forEach { element ->
            val list = element as? JsonObject ?: return@forEach
            val isCustom = list["isCustomList"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
            if (isCustom) return@forEach
            (list["entries"] as? JsonArray)?.forEach { item ->
                val entry = runCatching { aniListJson.decodeFromJsonElement(AniListEntry.serializer(), item) }.getOrNull()
                if (entry != null && entry.media != null) entries[entry.mediaId] = entry
            }
        }
        return entries.values.toList()
    }

    suspend fun mediaById(scope: AniListAuthScope?, mediaId: Long): Pair<AniListMedia, AniListEntry?>? =
        decodeMediaWithEntry(execute(MEDIA_BY_ID_QUERY, buildJsonObject { put("id", mediaId) }, scope, allowNotFound = true))

    suspend fun mediaByMalId(scope: AniListAuthScope?, malId: Long): Pair<AniListMedia, AniListEntry?>? =
        decodeMediaWithEntry(execute(MEDIA_BY_MAL_QUERY, buildJsonObject { put("idMal", malId) }, scope, allowNotFound = true))

    suspend fun mediaPage(scope: AniListAuthScope?, ids: List<Long>): List<AniListMedia> {
        if (ids.isEmpty()) return emptyList()
        val data = execute(
            MEDIA_PAGE_QUERY,
            buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } },
            scope,
            allowNotFound = true
        )
        val media = ((data["Page"] as? JsonObject)?.get("media") as? JsonArray) ?: return emptyList()
        return media.mapNotNull { element ->
            runCatching { aniListJson.decodeFromJsonElement(AniListMedia.serializer(), element) }.getOrNull()
        }
    }

    suspend fun saveEntry(
        scope: AniListAuthScope,
        mediaId: Long,
        status: String? = null,
        progress: Int? = null,
        scoreRaw: Int? = null
    ): AniListEntry {
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            status?.let { put("status", it) }
            progress?.let { put("progress", it) }
            scoreRaw?.let { put("scoreRaw", it) }
        }
        val data = execute(SAVE_ENTRY_MUTATION, variables, scope)
        val saved = data["SaveMediaListEntry"]?.takeUnless { it is JsonNull }
            ?: throw AniListApiException(200, message = "AniList did not save the entry")
        return aniListJson.decodeFromJsonElement(AniListEntry.serializer(), saved)
    }

    suspend fun deleteEntry(scope: AniListAuthScope, entryId: Long): Boolean {
        val data = execute(DELETE_ENTRY_MUTATION, buildJsonObject { put("id", entryId) }, scope, allowNotFound = true)
        return (data["DeleteMediaListEntry"] as? JsonObject)?.get("deleted")?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
    }

    private fun decodeMediaWithEntry(data: JsonObject): Pair<AniListMedia, AniListEntry?>? {
        val element = data["Media"]?.takeUnless { it is JsonNull } as? JsonObject ?: return null
        val media = runCatching { aniListJson.decodeFromJsonElement(AniListMedia.serializer(), element) }.getOrNull()
            ?: return null
        val entry = element["mediaListEntry"]?.takeUnless { it is JsonNull }?.let { raw ->
            runCatching { aniListJson.decodeFromJsonElement(AniListEntry.serializer(), raw) }.getOrNull()
        }?.copy(media = media)
        return media to entry
    }

    private suspend fun execute(
        query: String,
        variables: JsonObject,
        scope: AniListAuthScope?,
        allowNotFound: Boolean = false
    ): JsonObject {
        var attempt = 0
        while (true) {
            attempt += 1
            val token = if (scope != null) {
                store.checkScope(scope)
                store.accessToken() ?: throw AniListAuthException(AniListAuthError.AUTHORIZATION_REVOKED)
            } else {
                store.accessToken()
            }
            awaitSlot()
            val response = post(query, variables, token)
            scope?.let(store::checkScope)
            val root = runCatching { aniListJson.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            val errorMessage = root?.errorMessage()
            if (response.status == 429) {
                val waitSeconds = (response.retryAfterSeconds ?: 60L).coerceIn(1L, 120L)
                blockedUntilEpochMs = now() + waitSeconds * 1_000L
                if (attempt < MAX_ATTEMPTS) {
                    delay(waitSeconds * 1_000L)
                    continue
                }
                throw AniListApiException(429, retryAtEpochMs = blockedUntilEpochMs, message = errorMessage)
            }
            if (response.status == 401 || (response.status == 400 && errorMessage.isInvalidTokenMessage())) {
                if (scope != null && token != null) {
                    store.clearAuth(scope, AniListAuthError.AUTHORIZATION_REVOKED, token)
                }
                throw AniListAuthException(AniListAuthError.AUTHORIZATION_REVOKED)
            }
            val data = root?.get("data") as? JsonObject
            if (response.status == 404 || (data == null && errorMessage?.contains("not found", ignoreCase = true) == true)) {
                if (allowNotFound) return data ?: JsonObject(emptyMap())
                throw AniListNotFoundException(errorMessage)
            }
            if (response.status >= 500 && attempt < MAX_ATTEMPTS) {
                delay(1_500L * attempt)
                continue
            }
            if (response.status !in 200..299 || data == null) {
                throw AniListApiException(response.status, message = errorMessage)
            }
            return data
        }
    }

    private suspend fun post(query: String, variables: JsonObject, token: String?): AniListRawResponse =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("query", query)
                put("variables", variables)
            }.toString()
            val request = Request.Builder()
                .url(AniListConfig.GRAPHQL_URL)
                .header("Accept", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                AniListRawResponse(
                    status = response.code,
                    body = response.body?.string().orEmpty(),
                    retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
                )
            }
        }

    private suspend fun awaitSlot() {
        throttle.withLock {
            val current = now()
            val waitUntil = maxOf(lastRequestAtEpochMs + MIN_REQUEST_SPACING_MS, blockedUntilEpochMs)
            if (waitUntil > current) delay(waitUntil - current)
            lastRequestAtEpochMs = now()
        }
    }

    private fun JsonObject.errorMessage(): String? =
        (this["errors"] as? JsonArray)?.firstNotNullOfOrNull { element ->
            ((element as? JsonObject)?.get("message"))?.let { message ->
                runCatching { message.jsonPrimitive.contentOrNull }.getOrNull()
            }
        }

    private fun String?.isInvalidTokenMessage(): Boolean =
        this != null && (contains("invalid token", ignoreCase = true) || contains("unauthorized", ignoreCase = true))

    private data class AniListRawResponse(
        val status: Int,
        val body: String,
        val retryAfterSeconds: Long?
    )

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MIN_REQUEST_SPACING_MS = 700L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val MEDIA_FIELDS = "id idMal siteUrl status(version: 2) format episodes duration seasonYear " +
            "bannerImage genres averageScore isAdult title { userPreferred romaji english native } " +
            "coverImage { extraLarge large medium color } startDate { year month day } " +
            "nextAiringEpisode { episode airingAt }"

        private val ENTRY_FIELDS = "id mediaId status progress repeat score(format: POINT_100) updatedAt createdAt"

        private val VIEWER_QUERY = "query { Viewer { id name siteUrl avatar { large medium } } }"

        private val COLLECTION_QUERY = "query (\$userId: Int) { MediaListCollection(userId: \$userId, type: ANIME, " +
            "forceSingleCompletedList: true) { lists { status isCustomList entries { $ENTRY_FIELDS " +
            "media { $MEDIA_FIELDS } } } } }"

        private val MEDIA_BY_ID_QUERY = "query (\$id: Int) { Media(id: \$id, type: ANIME) { $MEDIA_FIELDS " +
            "mediaListEntry { $ENTRY_FIELDS } } }"

        private val MEDIA_BY_MAL_QUERY = "query (\$idMal: Int) { Media(idMal: \$idMal, type: ANIME) { $MEDIA_FIELDS " +
            "mediaListEntry { $ENTRY_FIELDS } } }"

        private val MEDIA_PAGE_QUERY = "query (\$ids: [Int]) { Page(perPage: 50) { media(id_in: \$ids, type: ANIME) " +
            "{ $MEDIA_FIELDS } } }"

        private val SAVE_ENTRY_MUTATION = "mutation (\$mediaId: Int, \$status: MediaListStatus, \$progress: Int, " +
            "\$scoreRaw: Int) { SaveMediaListEntry(mediaId: \$mediaId, status: \$status, progress: \$progress, " +
            "scoreRaw: \$scoreRaw) { $ENTRY_FIELDS } }"

        private val DELETE_ENTRY_MUTATION = "mutation (\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }"
    }
}
