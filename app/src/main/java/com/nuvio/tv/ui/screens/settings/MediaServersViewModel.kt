package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServersUiState
import com.nuvio.tv.data.mediaserver.serverFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerSignInState(
    val connecting: Boolean = false,
    val error: ServerFailure? = null
)

@HiltViewModel
class MediaServersViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {
    val uiState: StateFlow<ServersUiState> = repository.uiState

    val providers: List<ServerProvider>
        get() = repository.providers

    private val mutableSignIn = MutableStateFlow(ServerSignInState())
    val signIn: StateFlow<ServerSignInState> = mutableSignIn.asStateFlow()

    private val mutableRefreshing = MutableStateFlow<String?>(null)
    val refreshing: StateFlow<String?> = mutableRefreshing.asStateFlow()

    init {
        repository.ensureLoaded()
    }

    fun provider(connection: ServerConnection): ServerProvider? = repository.provider(connection)

    fun resetSignIn() {
        mutableSignIn.value = ServerSignInState()
    }

    fun connect(
        provider: ServerProvider,
        address: String,
        username: String,
        password: String,
        onConnected: (ServerConnection) -> Unit
    ) {
        if (mutableSignIn.value.connecting) return
        mutableSignIn.value = ServerSignInState(connecting = true)
        viewModelScope.launch {
            try {
                val connection = repository.connect(provider, address, username.trim(), password)
                mutableSignIn.value = ServerSignInState()
                onConnected(connection)
            } catch (error: CancellationException) {
                mutableSignIn.value = ServerSignInState()
                throw error
            } catch (error: ServerException) {
                mutableSignIn.value = ServerSignInState(error = error.failure)
            } catch (_: Exception) {
                mutableSignIn.value = ServerSignInState(error = ServerFailure.FAILED)
            }
        }
    }

    fun refreshLibraries(connectionId: String, onFailure: (ServerFailure) -> Unit) {
        if (mutableRefreshing.value != null) return
        mutableRefreshing.value = connectionId
        viewModelScope.launch {
            try {
                repository.refreshLibraries(connectionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(error.serverFailure())
            } finally {
                mutableRefreshing.value = null
            }
        }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) = repository.setEnabled(connectionId, enabled)

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) = repository.setCatalogMetadata(connectionId, enabled)

    fun setImportWatchState(connectionId: String, enabled: Boolean) = repository.setImportWatchState(connectionId, enabled)

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) =
        repository.setLibrarySelected(connectionId, libraryId, selected)

    fun remove(connectionId: String) = repository.remove(connectionId)
}
