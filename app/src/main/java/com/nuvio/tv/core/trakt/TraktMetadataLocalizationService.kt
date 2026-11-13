package com.nuvio.tv.core.trakt

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Localizes Trakt library entry titles via TMDB using the app interface language.
 * No-op when the UI language is English or when no TMDB id is available.
 */
@Singleton
class TraktMetadataLocalizationService @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService
) {
    suspend fun localizeLibraryEntries(entries: List<LibraryEntry>): List<LibraryEntry> {
        if (entries.isEmpty()) return entries
        val language = TraktMetadataLanguage.resolveInterfaceLanguage()
        if (TraktMetadataLanguage.isEnglish(language)) return entries
        return localizeLibraryEntries(entries, language)
    }

    private suspend fun localizeLibraryEntries(
        entries: List<LibraryEntry>,
        language: String
    ): List<LibraryEntry> = coroutineScope {
        val semaphore = Semaphore(LOCALIZATION_CONCURRENCY)
        entries.map { entry ->
            async {
                val tmdbId = entry.tmdbId ?: parseContentIds(entry.id).tmdb ?: return@async entry
                val contentType = ContentType.fromString(entry.type)
                val localizedTitle = semaphore.withPermit {
                    tmdbMetadataService.fetchLocalizedTitle(
                        tmdbId = tmdbId,
                        contentType = contentType,
                        language = language
                    )
                } ?: return@async entry
                if (localizedTitle.equals(entry.name, ignoreCase = true)) entry
                else entry.copy(name = localizedTitle)
            }
        }.awaitAll()
    }

    companion object {
        private const val LOCALIZATION_CONCURRENCY = 6
    }
}
