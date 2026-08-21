package com.nuvio.tv.data.remote.supabase

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val ProfileBackgroundBucket = "membership-profile-backgrounds"
private const val ProfileBackgroundTag = "ProfileBackgrounds"

data class ProfileBackgroundCatalogItem(
    val id: String,
    val displayName: String,
    val imageFile: File? = null,
    val assetVersion: Int
)

@Singleton
class ProfileBackgroundRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val storage: Storage,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _catalog = MutableStateFlow<List<ProfileBackgroundCatalogItem>>(emptyList())
    val catalog: StateFlow<List<ProfileBackgroundCatalogItem>> = _catalog.asStateFlow()
    private var remoteCatalog = emptyList<SupabaseProfileBackgroundCatalogItem>()
    private var catalogLoadJob: Job? = null
    private var assetLoadJob: Job? = null

    fun ensureLoaded() {
        if (remoteCatalog.isNotEmpty() || catalogLoadJob?.isActive == true) return
        catalogLoadJob = scope.launch {
            try {
                remoteCatalog = postgrest.rpc("get_member_profile_background_catalog")
                    .decodeList()
                _catalog.value = remoteCatalog.map { item ->
                    ProfileBackgroundCatalogItem(
                        id = item.id,
                        displayName = item.displayName,
                        assetVersion = item.assetVersion
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(ProfileBackgroundTag, "Unable to load supporter profile backgrounds", error)
            }
        }
    }

    fun loadSelectedAndPreload(id: String) {
        ensureLoaded()
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            catalogLoadJob?.join()
            val selected = remoteCatalog.firstOrNull { it.id == id } ?: return@launch
            loadAndPublish(selected)
            coroutineScope {
                remoteCatalog
                    .filterNot { it.id == id }
                    .map { item -> launch { loadAndPublish(item) } }
                    .joinAll()
            }
        }
    }

    fun preloadImages() {
        ensureLoaded()
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            catalogLoadJob?.join()
            coroutineScope {
                remoteCatalog.map { item -> launch { loadAndPublish(item) } }.joinAll()
            }
        }
    }

    fun invalidateCache() {
        catalogLoadJob?.cancel()
        catalogLoadJob = null
        assetLoadJob?.cancel()
        assetLoadJob = null
        remoteCatalog = emptyList()
        _catalog.value = emptyList()
    }

    private suspend fun loadAndPublish(item: SupabaseProfileBackgroundCatalogItem) {
        try {
            val imageFile = cacheImage(item)
            _catalog.update { catalog ->
                catalog.map { background ->
                    if (background.id == item.id) background.copy(imageFile = imageFile) else background
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(ProfileBackgroundTag, "Unable to load supporter profile background ${item.id}", error)
        }
    }

    private suspend fun cacheImage(item: SupabaseProfileBackgroundCatalogItem): File = withContext(Dispatchers.IO) {
        val directory = context.cacheDir.resolve("member_profile_backgrounds")
        val imageFile = directory.resolve("${item.id}-v${item.assetVersion}.png")
        if (imageFile.isFile && imageFile.length() > 0L) return@withContext imageFile

        directory.mkdirs()
        val imageBytes = storage[ProfileBackgroundBucket].downloadAuthenticated(item.storagePath)
        val temporaryFile = directory.resolve(".${imageFile.name}.tmp")
        temporaryFile.writeBytes(imageBytes)
        if (!temporaryFile.renameTo(imageFile)) {
            temporaryFile.copyTo(imageFile, overwrite = true)
            temporaryFile.delete()
        }
        imageFile
    }
}
