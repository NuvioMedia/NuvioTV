package com.omnio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.data.local.EmbyCredentials
import com.omnio.tv.data.local.EmbyCredentialsDataStore
import com.omnio.tv.data.remote.api.EmbyApi
import com.omnio.tv.data.repository.EmbyMediaService
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject

data class EmbySettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isTestSuccess: Boolean = false
)

@HiltViewModel
class EmbySettingsViewModel @Inject constructor(
    private val embyCredentialsDataStore: EmbyCredentialsDataStore,
    private val embyMediaService: EmbyMediaService,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    val credentials: StateFlow<EmbyCredentials> = embyCredentialsDataStore.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmbyCredentials())

    private val _uiState = MutableStateFlow(EmbySettingsUiState())
    val uiState: StateFlow<EmbySettingsUiState> = _uiState.asStateFlow()

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url, testResult = null)
    }

    fun updateApiKey(key: String) {
        _uiState.value = _uiState.value.copy(apiKey = key, testResult = null)
    }

    fun testConnection() {
        val current = _uiState.value
        if (current.serverUrl.isBlank() || current.apiKey.isBlank()) {
            _uiState.value = current.copy(
                testResult = "Server URL and API key are required",
                isTestSuccess = false
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)

            val serverUrl = current.serverUrl.trim().trimEnd('/')
            val apiKey = current.apiKey.trim()

            try {
                val testClient = okHttpClient.newBuilder()
                    .addInterceptor { chain ->
                        val originalRequest = chain.request()
                        val originalUrl = originalRequest.url.toString()
                        val rewrittenUrl = if (originalUrl.startsWith("http://localhost/")) {
                            originalUrl.replaceFirst("http://localhost", serverUrl)
                        } else {
                            originalUrl
                        }

                        val authHeader = buildEmbyAuthorizationHeader(apiKey = apiKey, deviceId = "")
                        val request = originalRequest.newBuilder()
                            .url(rewrittenUrl)
                            .header("X-Emby-Authorization", authHeader)
                            .header("X-Emby-Token", apiKey)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val testApi = Retrofit.Builder()
                    .baseUrl("http://localhost/")
                    .client(testClient)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(EmbyApi::class.java)

                val systemInfoResponse = testApi.getSystemInfo()
                if (!systemInfoResponse.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "Connection failed: ${systemInfoResponse.code()} ${systemInfoResponse.message()}",
                        isTestSuccess = false
                    )
                    return@launch
                }

                val serverName = systemInfoResponse.body()?.serverName ?: "Emby Server"
                val usersResponse = testApi.getUsers()
                if (!usersResponse.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "Connected to $serverName but failed to fetch users",
                        isTestSuccess = false
                    )
                    return@launch
                }

                val users = usersResponse.body() ?: emptyList()
                val adminUser = users.firstOrNull { it.policy?.isAdministrator == true } ?: users.firstOrNull()
                if (adminUser == null) {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "No users found on $serverName",
                        isTestSuccess = false
                    )
                    return@launch
                }

                embyMediaService.clearMetadata()
                embyCredentialsDataStore.saveCredentials(
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    userId = adminUser.id
                )

                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = "Connected to $serverName as ${adminUser.name ?: "Unknown User"}",
                    isTestSuccess = true
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = "Connection error: ${error.message}",
                    isTestSuccess = false
                )
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            embyCredentialsDataStore.clearCredentials()
            embyMediaService.clearMetadata()
            _uiState.value = EmbySettingsUiState()
        }
    }

    private fun buildEmbyAuthorizationHeader(apiKey: String, deviceId: String): String {
        return "MediaBrowser Client=\"OmnioTV\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"1.0.0\", Token=\"$apiKey\""
    }
}
