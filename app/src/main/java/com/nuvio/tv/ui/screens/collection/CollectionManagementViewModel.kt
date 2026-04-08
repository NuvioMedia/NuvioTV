package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ValidationResult
import com.nuvio.tv.domain.model.Collection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    val importMode: ImportMode = ImportMode.PASTE,
    val importUrl: String = "",
    val validationResult: ValidationResult? = null,
    val validatedJson: String? = null,
    val isLoadingImport: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class CollectionManagementViewModel @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionManagementUiState())
    val uiState: StateFlow<CollectionManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collectionsDataStore.collections.collectLatest { collections ->
                _uiState.update {
                    it.copy(collections = collections, isLoading = false)
                }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionsDataStore.removeCollection(collectionId)
        }
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        viewModelScope.launch {
            val current = _uiState.value.collections.toMutableList()
            val item = current.removeAt(index)
            current.add(index - 1, item)
            collectionsDataStore.setCollections(current)
        }
    }

    fun moveDown(index: Int) {
        val current = _uiState.value.collections
        if (index >= current.size - 1) return
        viewModelScope.launch {
            val mutableList = current.toMutableList()
            val item = mutableList.removeAt(index)
            mutableList.add(index + 1, item)
            collectionsDataStore.setCollections(mutableList)
        }
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
                importMode = ImportMode.PASTE, importUrl = "",
                validationResult = null, validatedJson = null, isLoadingImport = false
            )
        }
    }

    fun hideImportDialog() {
        _uiState.update {
            it.copy(
                showImportDialog = false, importText = "", importError = null,
                importMode = ImportMode.PASTE, importUrl = "",
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
            _uiState.update { it.copy(importError = "Paste a collections JSON to import") }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = "Invalid format or empty collections") }
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
            _uiState.update { it.copy(importError = "Please enter a URL") }
            return
        }
        _uiState.update { it.copy(isLoadingImport = true, importError = null) }
        viewModelScope.launch {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(url).build()
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                if (!response.isSuccessful) {
                    _uiState.update {
                        it.copy(isLoadingImport = false, importError = "Failed to fetch: HTTP ${response.code}")
                    }
                    return@launch
                }
                val body = response.body?.string() ?: ""
                _uiState.update { it.copy(importText = body, isLoadingImport = false) }
                validateJson(body)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingImport = false, importError = "Failed to fetch URL: ${e.message}")
                }
            }
        }
    }

    fun confirmImport() {
        val json = _uiState.value.validatedJson ?: _uiState.value.importText.trim()
        if (json.isBlank()) {
            _uiState.update { it.copy(importError = "No data to import") }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = "Invalid format or empty collections") }
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
            _uiState.update {
                it.copy(
                    showImportDialog = false, importText = "", importError = null,
                    validationResult = null, validatedJson = null, importUrl = "",
                    importMode = ImportMode.PASTE
                )
            }
        }
    }

    fun loadFromFile(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingImport = true, importError = null) }
            try {
                val content = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
                    val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf("nuvio-collections.json")
                    resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                            val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                            resolver.openInputStream(fileUri)?.bufferedReader()?.readText()
                        } else null
                    }
                }
                if (content == null) {
                    _uiState.update { it.copy(isLoadingImport = false, importError = "File not found in Downloads.\nExport collections first to create it.") }
                    return@launch
                }
                _uiState.update { it.copy(importText = content, isLoadingImport = false) }
                validateJson(content)
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingImport = false, importError = "Failed to read file: ${e.message}") }
            }
        }
    }

    fun getExportJson(): String {
        return collectionsDataStore.exportToJson(_uiState.value.collections)
    }
}
