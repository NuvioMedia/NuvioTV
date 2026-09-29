package com.nuvio.tv.core.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class CloudLibraryPlaybackSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, CloudLibraryPlaybackContext>()

    fun create(context: CloudLibraryPlaybackContext): String {
        val token = UUID.randomUUID().toString()
        sessions[token] = context
        preferences.edit()
            .putString(sessionKey(token), context.toJson().toString())
            .putLong(timestampKey(token), System.currentTimeMillis())
            .apply()
        trimOldSessions()
        return token
    }

    fun load(token: String?): CloudLibraryPlaybackContext? {
        if (token.isNullOrBlank()) return null
        sessions[token]?.let { return it }
        val raw = preferences.getString(sessionKey(token), null) ?: return null
        return runCatching { JSONObject(raw).toPlaybackContext() }
            .getOrNull()
            ?.also { sessions[token] = it }
    }

    fun update(token: String, context: CloudLibraryPlaybackContext) {
        sessions[token] = context
        preferences.edit()
            .putString(sessionKey(token), context.toJson().toString())
            .putLong(timestampKey(token), System.currentTimeMillis())
            .apply()
    }

    private fun trimOldSessions() {
        val tokens = preferences.all.keys
            .filter { it.startsWith(SESSION_PREFIX) }
            .map { it.removePrefix(SESSION_PREFIX) }
        if (tokens.size <= MAX_SESSIONS) return
        val expired = tokens
            .sortedBy { preferences.getLong(timestampKey(it), 0L) }
            .take(tokens.size - MAX_SESSIONS)
        preferences.edit().apply {
            expired.forEach { token ->
                sessions.remove(token)
                remove(sessionKey(token))
                remove(timestampKey(token))
            }
        }.apply()
    }

    private fun CloudLibraryPlaybackContext.toJson(): JSONObject = JSONObject().apply {
        put("currentFileKey", currentFileKey)
        put("providerId", item.providerId)
        put("providerName", item.providerName)
        put("itemId", item.id)
        put("itemType", item.type.name)
        put("itemName", item.name)
        item.status?.let { put("status", it) }
        item.sizeBytes?.let { put("sizeBytes", it) }
        item.progressFraction?.let { put("progressFraction", it.toDouble()) }
        put("files", JSONArray().apply {
            item.files.forEach { file ->
                put(JSONObject().apply {
                    file.id?.let { put("id", it) }
                    put("name", file.name)
                    file.sizeBytes?.let { put("sizeBytes", it) }
                    file.mimeType?.let { put("mimeType", it) }
                    put("playable", file.playable)
                })
            }
        })
    }

    private fun JSONObject.toPlaybackContext(): CloudLibraryPlaybackContext {
        val filesJson = getJSONArray("files")
        val files = buildList {
            for (index in 0 until filesJson.length()) {
                val file = filesJson.getJSONObject(index)
                add(
                    CloudLibraryFile(
                        id = file.optString("id").takeIf { it.isNotBlank() },
                        name = file.getString("name"),
                        sizeBytes = file.optLongOrNull("sizeBytes"),
                        mimeType = file.optString("mimeType").takeIf { it.isNotBlank() },
                        playable = file.optBoolean("playable", true)
                    )
                )
            }
        }
        return CloudLibraryPlaybackContext(
            item = CloudLibraryItem(
                providerId = getString("providerId"),
                providerName = getString("providerName"),
                id = getString("itemId"),
                type = CloudLibraryItemType.valueOf(getString("itemType")),
                name = getString("itemName"),
                status = optString("status").takeIf { it.isNotBlank() },
                sizeBytes = optLongOrNull("sizeBytes"),
                progressFraction = optDoubleOrNull("progressFraction")?.toFloat(),
                files = files
            ),
            currentFileKey = getString("currentFileKey")
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        takeIf { has(key) && !isNull(key) }?.optDouble(key)

    private fun sessionKey(token: String) = "$SESSION_PREFIX$token"
    private fun timestampKey(token: String) = "$TIMESTAMP_PREFIX$token"

    private companion object {
        const val PREFERENCES_NAME = "cloud_library_playback_sessions"
        const val SESSION_PREFIX = "session:"
        const val TIMESTAMP_PREFIX = "timestamp:"
        const val MAX_SESSIONS = 8
    }
}
