package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerLibraryRef
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.domain.model.CatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryServerShelf(
    val key: String,
    val title: String,
    val row: CatalogRow? = null,
    val failure: ServerFailure? = null
)

@HiltViewModel
class LibraryServersViewModel @Inject constructor(
    private val serverCatalog: ServerCatalog,
    repository: ServerRepository
) : ViewModel() {
    val servers: StateFlow<ServersUiState> = repository.uiState
    private val _shelves = MutableStateFlow<List<LibraryServerShelf>?>(null)
    val shelves: StateFlow<List<LibraryServerShelf>?> = _shelves.asStateFlow()
    private var loadJob: Job? = null
    private var loadedRevision: Int? = null
    var focusedShelfKey: String? = null
        private set
    val focusedIndexes = mutableMapOf<String, Int>()

    fun onItemFocused(shelfKey: String, index: Int) {
        focusedShelfKey = shelfKey
        focusedIndexes[shelfKey] = index
    }

    fun load() {
        val revision = servers.value.revision
        if (loadedRevision == revision && _shelves.value != null) return
        loadedRevision = revision
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _shelves.value = coroutineScope {
                serverCatalog.libraries().map { ref -> async { shelf(ref) } }.awaitAll()
            }
        }
    }

    private suspend fun shelf(ref: ServerLibraryRef): LibraryServerShelf {
        val key = "${ref.connection.id}:${ref.library.id}"
        return try {
            LibraryServerShelf(key, ref.title, row = serverCatalog.row(ref.connection.id, ref.library.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LibraryServerShelf(key, ref.title, failure = error.serverFailure())
        }
    }
}
