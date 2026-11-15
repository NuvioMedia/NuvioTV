package com.nuvio.tv.data.mdblist

import android.content.Context
import com.nuvio.tv.domain.model.MDBListRatings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

internal class MdbListRatingsDiskCache(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val ratings: MDBListRatings, val storedAtMs: Long)

    private val mutex = Mutex()
    private var entries = mutableMapOf<String, Entry>()
    private var loaded = false
    private var dirty = false
    private var writeJob: Job? = null

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "mdblist_cache")
        dir.mkdirs()
        return File(dir, "ratings_v1.json")
    }

    suspend fun load() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            try {
                val file = cacheFile()
                if (!file.exists()) { loaded = true; return }
                val json = JSONObject(file.readText())
                val map = mutableMapOf<String, Entry>()
                val cutoff = now() - TTL_MS
                for (key in json.keys()) {
                    val obj = json.optJSONObject(key) ?: continue
                    val storedAt = obj.optLong("t", 0L)
                    if (storedAt < cutoff) continue
                    map[key] = Entry(
                        ratings = parseRatings(obj),
                        storedAtMs = storedAt
                    )
                }
                entries = map
            } catch (_: Exception) { }
            loaded = true
        }
    }

    suspend fun get(key: String): MDBListRatings? {
        if (!loaded) load()
        val entry = entries[key] ?: return null
        if (now() - entry.storedAtMs > TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.ratings
    }

    fun put(key: String, ratings: MDBListRatings) {
        entries[key] = Entry(ratings, now())
        scheduleSave()
    }

    private fun scheduleSave() {
        dirty = true
        writeJob?.cancel()
        writeJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            if (!dirty) return@launch
            persist()
        }
    }

    private suspend fun persist() {
        mutex.withLock {
            try {
                val json = JSONObject()
                val cutoff = now() - TTL_MS
                for ((key, entry) in entries) {
                    if (entry.storedAtMs < cutoff) continue
                    json.put(key, serializeEntry(entry))
                }
                val file = cacheFile()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                dirty = false
            } catch (_: Exception) { }
        }
    }

    private fun serializeEntry(entry: Entry): JSONObject = JSONObject().apply {
        put("t", entry.storedAtMs)
        entry.ratings.trakt?.let { put("trakt", it) }
        entry.ratings.imdb?.let { put("imdb", it) }
        entry.ratings.tmdb?.let { put("tmdb", it) }
        entry.ratings.letterboxd?.let { put("letterboxd", it) }
        entry.ratings.tomatoes?.let { put("tomatoes", it) }
        entry.ratings.audience?.let { put("audience", it) }
        entry.ratings.metacritic?.let { put("metacritic", it) }
        entry.ratings.mal?.let { put("mal", it) }
        if (entry.ratings.tomatoesCertified) put("tc", true)
        if (entry.ratings.audienceCertified) put("ac", true)
    }

    private fun parseRatings(obj: JSONObject): MDBListRatings = MDBListRatings(
        trakt = obj.optDoubleOrNull("trakt"),
        imdb = obj.optDoubleOrNull("imdb"),
        tmdb = obj.optDoubleOrNull("tmdb"),
        letterboxd = obj.optDoubleOrNull("letterboxd"),
        tomatoes = obj.optDoubleOrNull("tomatoes"),
        audience = obj.optDoubleOrNull("audience"),
        metacritic = obj.optDoubleOrNull("metacritic"),
        mal = obj.optDoubleOrNull("mal"),
        tomatoesCertified = obj.optBoolean("tc", false),
        audienceCertified = obj.optBoolean("ac", false)
    )

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key)) optDouble(key).takeIf { !it.isNaN() } else null

    companion object {
        private const val TTL_MS = 12L * 60L * 60L * 1000L
        private const val SAVE_DEBOUNCE_MS = 2_000L
    }
}
