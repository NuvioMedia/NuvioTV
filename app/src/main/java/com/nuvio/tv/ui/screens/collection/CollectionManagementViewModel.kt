package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ValidationResult
import com.nuvio.tv.domain.model.Collection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ImportMode { PASTE, FILE, URL }

data class CollectionManagementUiState(
    val collections: List<Collection> = emptyList(),
    val isLoading: Boolean = true,
    val showImportDialog: Boolean = false,
    val importText: String = "",
    val importError: String? = null,
    val exportedJson: String? = null,
    val importMode: ImportMode = ImportMode.FILE,
    val importUrl: String = "",
    val validationResult: ValidationResult? = null,
    val validatedJson: String? = null,
    val isLoadingImport: Boolean = false,
    val error: String? = null,
    val movingCollectionKey: String? = null
)

@HiltViewModel
class CollectionManagementViewModel @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionManagementUiState())
    val uiState: StateFlow<CollectionManagementUiState> = _uiState.asStateFlow()

    private var collectionMoveBaseline: List<Collection>? = null
    private var collectionDraftOrder: List<String>? = null
    private var pendingCollectionOrder: List<String>? = null
    private var collectionPersistGeneration: Int = 0

    init {
        viewModelScope.launch {
            collectionsDataStore.collections.collectLatest { collections ->
                val resolved = resolveObservedCollections(collections)
                _uiState.update {
                    it.copy(collections = resolved, isLoading = false)
                }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        pendingCollectionOrder = null
        viewModelScope.launch {
            collectionsDataStore.removeCollection(collectionId)
            collectionSyncService.triggerPush()
        }
    }

    fun enterCollectionMoveMode(collectionId: String) {
        val current = _uiState.value.collections
        if (current.size <= 1 || current.none { it.id == collectionId }) return
        pendingCollectionOrder = null
        collectionMoveBaseline = current
        collectionDraftOrder = current.map { it.id }
        _uiState.update { it.copy(movingCollectionKey = collectionId) }
    }

    fun moveCollectionDuringDrag(collectionId: String, direction: Int): Int? {
        val draft = collectionDraftOrder ?: return null
        val fromIndex = draft.indexOf(collectionId)
        val toIndex = fromIndex + direction
        if (fromIndex < 0 || toIndex !in draft.indices) return null
        val reordered = draft.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        collectionDraftOrder = reordered
        applyCollectionDraftOrder()
        return toIndex
    }

    fun confirmCollectionMove() {
        val draft = collectionDraftOrder
        val baseline = collectionMoveBaseline
        val baselineIds = baseline?.map { it.id }
        collectionDraftOrder = null
        collectionMoveBaseline = null
        _uiState.update { it.copy(movingCollectionKey = null) }
        if (draft == null || draft == baselineIds) return

        val ordered = orderCollectionsByIds(_uiState.value.collections, draft)
        val orderedIds = ordered.map { it.id }
        val generation = ++collectionPersistGeneration
        pendingCollectionOrder = orderedIds
        viewModelScope.launch {
            var wrote = false
            try {
                withContext(NonCancellable) {
                    collectionsDataStore.setCollections(ordered)
                    wrote = true
                    collectionSyncService.triggerPush()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                wrote = false
            }
            if (!wrote && generation == collectionPersistGeneration) {
                if (pendingCollectionOrder == orderedIds) {
                    pendingCollectionOrder = null
                }
                if (baseline != null && _uiState.value.movingCollectionKey == null) {
                    _uiState.update { it.copy(collections = baseline) }
                }
            }
        }
        viewModelScope.launch {
            delay(2_500L)
            if (generation == collectionPersistGeneration && pendingCollectionOrder == orderedIds) {
                pendingCollectionOrder = null
            }
        }
    }

    fun cancelCollectionMove() {
        val baseline = collectionMoveBaseline
        collectionDraftOrder = null
        collectionMoveBaseline = null
        pendingCollectionOrder = null
        _uiState.update {
            it.copy(
                movingCollectionKey = null,
                collections = baseline ?: it.collections
            )
        }
    }

    private fun applyCollectionDraftOrder() {
        val draft = collectionDraftOrder ?: return
        _uiState.update { state ->
            state.copy(collections = orderCollectionsByIds(state.collections, draft))
        }
    }

    private fun resolveObservedCollections(collections: List<Collection>): List<Collection> {
        val draft = collectionDraftOrder
        if (draft != null) return orderCollectionsByIds(collections, draft)
        val pending = pendingCollectionOrder
        if (pending != null) {
            val emittedIds = collections.map { it.id }
            if (emittedIds == pending || emittedIds.toSet() != pending.toSet()) {
                pendingCollectionOrder = null
                return collections
            }
            return orderCollectionsByIds(collections, pending)
        }
        return collections
    }

    private fun orderCollectionsByIds(
        collections: List<Collection>,
        orderIds: List<String>
    ): List<Collection> {
        val byId = collections.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val ordered = ArrayList<Collection>(collections.size)
        for (id in orderIds) {
            val collection = byId[id] ?: continue
            if (seen.add(id)) ordered.add(collection)
        }
        for (collection in collections) {
            if (seen.add(collection.id)) ordered.add(collection)
        }
        return ordered
    }

    fun exportCollections(): String {
        val json = collectionsDataStore.exportToJson(_uiState.value.collections)
        _uiState.update { it.copy(exportedJson = json) }
        return json
    }

    fun clearExported() {
        _uiState.update { it.copy(exportedJson = null) }
    }

    fun showImportDialog() {
        _uiState.update {
            it.copy(
                showImportDialog = true, importText = "", importError = null,
                importMode = ImportMode.FILE, importUrl = "",
                validationResult = null, validatedJson = null, isLoadingImport = false
            )
        }
    }

    fun hideImportDialog() {
        _uiState.update {
            it.copy(
                showImportDialog = false, importText = "", importError = null,
                importMode = ImportMode.FILE, importUrl = "",
                validationResult = null, validatedJson = null, isLoadingImport = false
            )
        }
    }

    fun updateImportText(text: String) {
        _uiState.update { it.copy(importText = text, importError = null) }
    }

    fun importCollections() {
        val json = _uiState.value.importText.trim()
        if (json.isBlank()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_paste_json_required)) }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_invalid_or_empty)) }
            return
        }
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val existingIds = current.map { it.id }.toSet()
            for (collection in imported) {
                if (collection.id in existingIds) {
                    val index = current.indexOfFirst { it.id == collection.id }
                    if (index >= 0) current[index] = collection
                } else {
                    current.add(collection)
                }
            }
            collectionsDataStore.setCollections(current)
            collectionSyncService.triggerPush()
            _uiState.update { it.copy(showImportDialog = false, importText = "", importError = null) }
        }
    }

    fun setImportMode(mode: ImportMode) {
        _uiState.update {
            it.copy(importMode = mode, importError = null, validationResult = null, validatedJson = null)
        }
    }

    fun updateImportUrl(url: String) {
        _uiState.update {
            it.copy(importUrl = url, importError = null, validationResult = null, validatedJson = null)
        }
    }

    fun validateJson(json: String) {
        val result = collectionsDataStore.validateCollectionsJson(json)
        _uiState.update {
            if (result.valid) {
                it.copy(validationResult = result, validatedJson = json, importError = null)
            } else {
                it.copy(validationResult = null, validatedJson = null, importError = result.error)
            }
        }
    }

    fun validateCurrentText() {
        validateJson(_uiState.value.importText.trim())
    }

    fun handleFileContent(content: String) {
        _uiState.update { it.copy(importText = content) }
        validateJson(content)
    }

    fun fetchUrl() {
        val url = _uiState.value.importUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(importError = context.getString(R.string.web_import_enter_url)) }
            return
        }
        _uiState.update { it.copy(isLoadingImport = true, importError = null) }
        viewModelScope.launch {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .dns(com.nuvio.tv.core.network.IPv4FirstDns())
                    .socketFactory(com.nuvio.tv.core.network.NoDelaySocketFactory)
                    .build()
                val request = okhttp3.Request.Builder().url(url).build()
                // Read the body on IO too: execute() only returns the headers.
                val (code, body) = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        response.code to if (response.isSuccessful) response.body?.string().orEmpty() else null
                    }
                }
                if (body == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingImport = false,
                            importError = context.getString(
                                R.string.collections_import_failed_fetch_http,
                                code
                            )
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(importText = body, isLoadingImport = false) }
                validateJson(body)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingImport = false,
                        importError = context.getString(
                            R.string.collections_import_failed_fetch_url,
                            e.message ?: context.getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    fun confirmImport() {
        val json = _uiState.value.validatedJson ?: _uiState.value.importText.trim()
        if (json.isBlank()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_no_data)) }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_invalid_or_empty)) }
            return
        }
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val existingIds = current.map { it.id }.toSet()
            for (collection in imported) {
                if (collection.id in existingIds) {
                    val index = current.indexOfFirst { it.id == collection.id }
                    if (index >= 0) current[index] = collection
                } else {
                    current.add(collection)
                }
            }
            collectionsDataStore.setCollections(current)
            collectionSyncService.triggerPush()
            _uiState.update {
                it.copy(
                    showImportDialog = false, importText = "", importError = null,
                    validationResult = null, validatedJson = null, importUrl = "",
                    importMode = ImportMode.FILE
                )
            }
        }
    }

    fun loadFromFile(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingImport = true, importError = null) }
            try {
                val content = withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        loadFromMediaStoreDownloads(context)
                    } else {
                        loadFromDownloadsDirectory()
                    }
                }
                if (content == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingImport = false,
                            importError = context.getString(R.string.collections_import_file_not_found_downloads)
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(importText = content, isLoadingImport = false) }
                validateJson(content)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingImport = false,
                        importError = context.getString(
                            R.string.collections_import_failed_read_file,
                            e.message ?: context.getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    fun getExportJson(): String {
        return collectionsDataStore.exportToJson(_uiState.value.collections)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun loadFromMediaStoreDownloads(context: android.content.Context): String? {
        val resolver = context.contentResolver
        val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("nuvio-collections.json")
        return resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                resolver.openInputStream(fileUri)?.bufferedReader()?.readText()
            } else null
        }
    }

    private fun loadFromDownloadsDirectory(): String? {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, "nuvio-collections.json")
        return if (file.exists()) file.readText() else null
    }
}
