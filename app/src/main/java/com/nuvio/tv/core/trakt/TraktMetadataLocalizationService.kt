package com.nuvio.tv.core.trakt

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktMetadataLanguageSource
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktMetadataLocalizationService @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    suspend fun localizePreviews(items: List<MetaPreview>): List<MetaPreview> {
        val language = resolveLanguage() ?: return items
        if (TraktMetadataLanguage.isEnglish(language)) return items
        return localizePreviews(items, language)
    }

    suspend fun localizeLibraryEntries(entries: List<LibraryEntry>): List<LibraryEntry> {
        val language = resolveLanguage() ?: return entries
        if (TraktMetadataLanguage.isEnglish(language)) return entries
        return localizeLibraryEntries(entries, language)
    }

    suspend fun resolveLanguage(): String? {
        val source = traktSettingsDataStore.metadataLanguageSource.first()
        val tmdbLanguage = tmdbSettingsDataStore.settings.first().language
        return TraktMetadataLanguage.resolve(source, tmdbLanguage)
    }

    private suspend fun localizePreviews(
        items: List<MetaPreview>,
        language: String
    ): List<MetaPreview> = coroutineScope {
        val semaphore = Semaphore(LOCALIZATION_CONCURRENCY)
        items.map { item ->
            async {
                val tmdbId = item.resolveTmdbIdForLocalization() ?: return@async item
                val localizedTitle = semaphore.withPermit {
                    tmdbMetadataService.fetchLocalizedTitle(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = language
                    )
                } ?: return@async item
                if (localizedTitle.equals(item.name, ignoreCase = true)) item
                else item.copy(name = localizedTitle)
            }
        }.awaitAll()
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

    private fun MetaPreview.resolveTmdbIdForLocalization(): Int? {
        return tmdbId ?: parseContentIds(id).tmdb
    }

    companion object {
        private const val LOCALIZATION_CONCURRENCY = 6
    }
}
