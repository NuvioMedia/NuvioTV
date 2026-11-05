package com.nuvio.tv.data.local

import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.MetaPreview
import java.io.File
import java.time.LocalDate

/**
 * Snapshot persistente della riga "Ultime Uscite" (calendario mensile Trakt
 * già arricchito e shaping 25+30). Senza di essa la riga non può nascere
 * prima del round-trip Trakt del mese (~15-25s su una Fire TV Stick): con
 * lo snapshot la prima emissione del flusso dipinge la riga in pochi
 * secondi e il fetch di rete la aggiorna in background. Viene scartato
 * automaticamente se la chiave mese non è più quella corrente.
 */
internal class LatestReleaseMonthStore(
    private val file: File
) {
    internal data class Snapshot(
        val monthKey: String? = null,
        val savedAtMs: Long = 0L,
        val items: List<Entry> = emptyList()
    )

    internal data class Entry(
        val meta: MetaPreview? = null,
        val releaseDateIso: String? = null,
        val addonName: String = "Trakt",
        val notInCatalog: Boolean = false,
        val episodeLabel: String? = null
    )

    companion object {
        private const val TAG = "LatestReleaseMonthStore"
    }

    private val gson = Gson()

    @Volatile
    private var cached: Snapshot? = null

    fun load(monthKey: String): List<CalendarItem>? {
        cached?.let { snapshot ->
            if (snapshot.monthKey == monthKey) {
                // La conversione può esplodere (vedi catch qui sotto): non deve
                // mai propagarsi, altrimenti muore tutto il flusso del mese.
                runCatching { snapshot.toItems() }.getOrNull()?.let { return it }
                cached = null
            }
        }
        // Parsing E conversione sotto lo stesso try: con R8 la firma generica
        // di `items` può venire erasa, Gson restituisce LinkedTreeMap al posto
        // di Entry e il cast lancia ClassCastException. In quel caso si butta
        // via lo snapshot corrotto e la riga "Ultime Uscite" riparte dai dati
        // di rete, invece di non comparire affatto.
        return try {
            if (!file.exists()) return null
            val snapshot = gson.fromJson(file.readText(), Snapshot::class.java)
                ?: return null
            if (snapshot.monthKey != monthKey) return null
            snapshot.toItems().also { cached = snapshot }
        } catch (e: Exception) {
            Log.w(TAG, "Month snapshot unreadable, deleting file: $e")
            cached = null
            runCatching { file.delete() }
            null
        }
    }

    fun save(monthKey: String, items: List<CalendarItem>) {
        try {
            val snapshot = Snapshot(
                monthKey = monthKey,
                savedAtMs = System.currentTimeMillis(),
                items = items.map { item ->
                    Entry(
                        meta = item.meta,
                        releaseDateIso = item.releaseDate?.toString(),
                        addonName = item.addonName,
                        notInCatalog = item.notInCatalog,
                        episodeLabel = item.episodeLabel
                    )
                }
            )
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(snapshot))
            cached = snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write month snapshot: ${e.message}")
        }
    }

    private fun Snapshot.toItems(): List<CalendarItem> = items.mapNotNull { entry ->
        val meta = entry.meta ?: return@mapNotNull null
        CalendarItem(
            meta = meta,
            releaseDate = entry.releaseDateIso?.let { iso ->
                runCatching { LocalDate.parse(iso) }.getOrNull()
            },
            addonName = entry.addonName,
            notInCatalog = entry.notInCatalog,
            episodeLabel = entry.episodeLabel
        )
    }
}
