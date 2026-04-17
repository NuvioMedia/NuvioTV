package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val collectionCount: Int = 0,
    val folderCount: Int = 0
)

@Singleton
class CollectionsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "collections"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val collectionsKey = stringPreferencesKey("collections_json")

    val collections: Flow<List<Collection>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                parseCollections(prefs[collectionsKey])
            }
        }

    suspend fun setCollections(collections: List<Collection>) {
        store().edit { prefs ->
            if (collections.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(collections.map { it.toSerializable() })
            }
        }
    }

    suspend fun addCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.add(collection)
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun updateCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            val index = current.indexOfFirst { it.id == collection.id }
            if (index >= 0) {
                current[index] = collection
            }
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun removeCollection(collectionId: String) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.removeAll { it.id == collectionId }
            if (current.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
            }
        }
    }

    fun generateId(): String = UUID.randomUUID().toString()

    fun exportToJson(collections: List<Collection>): String {
        return gson.toJson(collections.map { it.toSerializable() })
    }

    fun importFromJson(json: String): List<Collection> {
        return parseCollections(json)
    }

    suspend fun getCurrentCollections(): List<Collection> {
        val prefs = store().data.first()
        return parseCollections(prefs[collectionsKey])
    }

    suspend fun exportCurrentProfileJson(): String? {
        val prefs = store().data.first()
        return prefs[collectionsKey]
    }

    fun validateCollectionsJson(json: String): ValidationResult {
        if (json.isBlank()) return ValidationResult(false, "Empty input")
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val parsed = gson.fromJson<List<Map<String, Any?>>>(json, type)
                ?: return ValidationResult(false, "Invalid format: expected an array")
            if (parsed.isEmpty()) return ValidationResult(false, "Empty array: no collections found")

            var folderCount = 0
            val validShapes = setOf("POSTER", "LANDSCAPE", "SQUARE", "poster", "wide", "square")

            for ((i, item) in parsed.withIndex()) {
                val id = item["id"] as? String
                if (id.isNullOrBlank()) return ValidationResult(false, "Collection ${i + 1}: missing or invalid \"id\"")
                val title = item["title"] as? String
                    ?: return ValidationResult(false, "Collection \"$id\": missing or invalid \"title\"")
                val folders = item["folders"] as? List<*>
                    ?: return ValidationResult(false, "Collection \"$title\": \"folders\" must be an array")

                for ((j, f) in folders.withIndex()) {
                    val folder = f as? Map<*, *>
                        ?: return ValidationResult(false, "Collection \"$title\", folder ${j + 1}: invalid format")
                    val folderId = folder["id"] as? String
                    if (folderId.isNullOrBlank()) return ValidationResult(false, "Collection \"$title\", folder ${j + 1}: missing \"id\"")
                    val folderTitle = folder["title"] as? String
                        ?: return ValidationResult(false, "Collection \"$title\", folder \"$folderId\": missing \"title\"")
                    val sources = folder["catalogSources"] as? List<*>
                        ?: return ValidationResult(false, "Collection \"$title\", folder \"$folderTitle\": \"catalogSources\" must be an array")
                    val shape = folder["tileShape"] as? String
                    if (shape != null && shape !in validShapes) {
                        return ValidationResult(false, "Collection \"$title\", folder \"$folderTitle\": invalid tileShape \"$shape\"")
                    }
                    for ((k, s) in sources.withIndex()) {
                        val source = s as? Map<*, *>
                            ?: return ValidationResult(false, "Collection \"$title\", folder \"$folderTitle\", source ${k + 1}: invalid format")
                        if (source["addonId"] !is String || source["type"] !is String || source["catalogId"] !is String) {
                            return ValidationResult(false, "Collection \"$title\", folder \"$folderTitle\", source ${k + 1}: missing required fields")
                        }
                    }
                    folderCount++
                }
            }
            ValidationResult(true, collectionCount = parsed.size, folderCount = folderCount)
        } catch (e: Exception) {
            ValidationResult(false, "JSON parse error: ${e.message}")
        }
    }

    private fun parseCollections(json: String?): List<Collection> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SerializableCollection>>() {}.type
            val parsed = gson.fromJson<List<SerializableCollection>>(json, type).orEmpty()
            parsed.map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @androidx.annotation.Keep
    private data class SerializableCollection(
        val id: String,
        val title: String,
        val backdropImageUrl: String? = null,
        val pinToTop: Boolean = false,
        val focusGlowEnabled: Boolean? = null,
        val viewMode: String = "TABBED_GRID",
        val showAllTab: Boolean = true,
        val folders: List<SerializableFolder> = emptyList()
    )

    @androidx.annotation.Keep
    private data class SerializableFolder(
        val id: String,
        val title: String,
        val coverImageUrl: String? = null,
        val focusGifUrl: String? = null,
        val focusGifEnabled: Boolean? = null,
        val coverEmoji: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val catalogSources: List<SerializableCatalogSource> = emptyList()
    )

    @androidx.annotation.Keep
    private data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null
    )

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled,
        viewMode = viewMode.name,
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled,
                coverEmoji = folder.coverEmoji,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                catalogSources = folder.catalogSources.map { source ->
                    SerializableCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId,
                        genre = source.genre
                    )
                }
            )
        }
    )

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        backdropImageUrl = backdropImageUrl,
        pinToTop = pinToTop,
        focusGlowEnabled = focusGlowEnabled ?: true,
        viewMode = FolderViewMode.fromString(viewMode),
        showAllTab = showAllTab,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                focusGifUrl = folder.focusGifUrl,
                focusGifEnabled = folder.focusGifEnabled ?: true,
                coverEmoji = folder.coverEmoji,
                tileShape = PosterShape.fromString(folder.tileShape),
                hideTitle = folder.hideTitle,
                catalogSources = folder.catalogSources.map { source ->
                    CollectionCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId,
                        genre = source.genre
                    )
                }
            )
        }
    )
}
