package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

internal class SubtitleSyncFileStore(private val context: Context) {
    private val sessionDirectory = File(
        File(context.cacheDir, ROOT_DIRECTORY),
        UUID.randomUUID().toString()
    ).also { it.mkdirs() }
    private var generation = 0

    init {
        clearStaleSessions(context)
    }

    fun write(document: SrtDocument): Uri {
        sessionDirectory.mkdirs()
        val nextGeneration = ++generation
        val destination = File(sessionDirectory, "synced-$nextGeneration.srt")
        val temporary = File(sessionDirectory, "synced-$nextGeneration.tmp")
        temporary.writeText(document.encode(), Charsets.UTF_8)
        check(temporary.renameTo(destination)) { "Could not finalize synchronized subtitle file" }
        pruneSupersededGenerations(nextGeneration)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destination
        )
    }

    /**
     * Re-syncing during one session used to leave every previous file behind. The immediately
     * preceding generation is kept because the player may still be reading it until
     * `reloadAddonSubtitlesForSync` has swapped the media source over.
     */
    private fun pruneSupersededGenerations(currentGeneration: Int) {
        sessionDirectory.listFiles()?.forEach { file ->
            val fileGeneration = file.name
                .removePrefix("synced-")
                .substringBefore('.')
                .toIntOrNull() ?: return@forEach
            if (fileGeneration < currentGeneration - 1) file.delete()
        }
    }

    fun clear() {
        sessionDirectory.deleteRecursively()
    }

    companion object {
        private const val ROOT_DIRECTORY = "subtitle_sync"

        fun clearStaleSessions(context: Context, maxAgeMs: Long = 48L * 60L * 60L * 1000L) {
            val now = System.currentTimeMillis()
            File(context.cacheDir, ROOT_DIRECTORY).listFiles()?.forEach { directory ->
                if (now - directory.lastModified() > maxAgeMs) directory.deleteRecursively()
            }
        }
    }
}
