package com.nuvio.tv.core.storage

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import coil3.request.CachePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun tvMediaCache(context: Context): TvMediaCache =
    (context.applicationContext as? com.nuvio.tv.NuvioApplication)?.mediaCache ?: TvMediaCache(context.applicationContext)

/** Device-owned settings. Never synchronized through the account/profile transport. */
@Singleton
class TvMediaCache @Inject constructor(@param:ApplicationContext context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("telumia_media_cache_v1", Context.MODE_PRIVATE)
    val activeSettings: MediaCacheSettings by lazy { loadSettings() }
    internal val deviceSnapshot: MediaCacheDeviceSnapshot by lazy {
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            info.totalMem
        }.getOrNull()
        val free = runCatching { StatFs(app.cacheDir.absolutePath).availableBytes }.getOrNull()
        MediaCacheDeviceSnapshot(memory, free, occupiedCache.bytes)
    }
    val activeBudget: MediaCacheBudget by lazy { MediaCachePolicy.resolve(MediaCachePlatform.TV, activeSettings, deviceSnapshot) }
    internal val occupiedCache: MediaCacheOccupancy by lazy {
        OwnedMediaCacheOccupancy.measure(
            app.cacheDir.toPath(),
            listOf(listOf("image_cache"), listOf("badge_cache"), listOf("live_tv_guide")),
        )
    }
    val writesEnabled: Boolean get() = activeBudget.totalBytes > 0
    val requestPolicy: CachePolicy get() = if (writesEnabled) CachePolicy.ENABLED else CachePolicy.READ_ONLY

    fun loadSettings(): MediaCacheSettings = runCatching {
        if (preferences.getInt("schema", 1) != 1) return@runCatching MediaCacheSettings()
        val mode = MediaCacheMode.entries.firstOrNull { it.name == preferences.getString("mode", "AUTO") } ?: MediaCacheMode.AUTO
        val manual = if (preferences.contains("manual_bytes")) preferences.getLong("manual_bytes", MediaCachePlatform.TV.initialBytes)
            .coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES) else null
        MediaCacheSettings(mode, manual)
    }.getOrDefault(MediaCacheSettings())

    @Synchronized fun saveSettings(settings: MediaCacheSettings): Boolean {
        if (runCatching { preferences.getInt("schema", 1) }.getOrNull() != 1) return false
        val edit = preferences.edit().putInt("schema", 1).putString("mode", settings.mode.name)
        if (settings.manualBytes == null) edit.remove("manual_bytes")
        else edit.putLong("manual_bytes", settings.manualBytes.coerceIn(32L * MIB, MediaCachePolicy.MAX_CONFIGURED_BYTES))
        return edit.commit()
    }

    /** Read-only opening retains offline entries when storage drops below the reserve. */
    fun coilQuota(category: MediaCacheCategory): Long {
        val budget = if (writesEnabled) activeBudget else activeBudget.copy(totalBytes = activeBudget.requestedBytes)
        return budget.quota(category)
    }
}
