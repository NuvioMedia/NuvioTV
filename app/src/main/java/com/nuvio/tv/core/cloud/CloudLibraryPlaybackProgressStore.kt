package com.nuvio.tv.core.cloud

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class CloudLibraryFileProgress(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long
) {
    val resumePositionMs: Long
        get() = if (completed) 0L else positionMs.coerceAtLeast(0L)

    val isInProgress: Boolean
        get() = !completed && positionMs >= MIN_RESUME_POSITION_MS &&
            (durationMs <= 0L || positionMs.toFloat() / durationMs.toFloat() < COMPLETED_FRACTION)

    private companion object {
        const val MIN_RESUME_POSITION_MS = 1_000L
        const val COMPLETED_FRACTION = 0.90f
    }
}

/** Local-only Cloud file progress. It deliberately does not feed Continue Watching or sync. */
@Singleton
class CloudLibraryPlaybackProgressStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(item: CloudLibraryItem, file: CloudLibraryFile): CloudLibraryFileProgress? {
        val raw = preferences.getString(progressKey(item, file), null) ?: return null
        return runCatching {
            JSONObject(raw).let { json ->
                CloudLibraryFileProgress(
                    positionMs = json.optLong("positionMs", 0L),
                    durationMs = json.optLong("durationMs", 0L),
                    completed = json.optBoolean("completed", false),
                    updatedAtMs = json.optLong("updatedAtMs", 0L)
                )
            }
        }.getOrNull()
    }

    fun save(
        item: CloudLibraryItem,
        file: CloudLibraryFile,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        val value = JSONObject().apply {
            put("positionMs", positionMs.coerceAtLeast(0L))
            put("durationMs", durationMs.coerceAtLeast(0L))
            put("completed", completed)
            put("updatedAtMs", System.currentTimeMillis())
        }
        preferences.edit().putString(progressKey(item, file), value.toString()).apply()
    }

    private fun progressKey(item: CloudLibraryItem, file: CloudLibraryFile): String {
        val identity = "${item.stableKey}\u0000${file.stableKey}"
        return Base64.encodeToString(
            identity.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "cloud_library_playback_progress"
    }
}
