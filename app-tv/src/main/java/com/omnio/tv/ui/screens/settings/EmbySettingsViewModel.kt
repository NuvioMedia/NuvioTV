package com.omnio.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.data.local.EmbyCredentials
import com.omnio.tv.data.local.EmbyCredentialsDataStore
import com.omnio.tv.data.remote.api.EmbyApi
import com.omnio.tv.data.remote.dto.emby.EmbyAuthByNameRequestDto
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
import java.util.UUID
import javax.inject.Inject

private const val TAG = "EmbySettingsVM"

data class EmbySettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
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

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username, testResult = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, testResult = null)
    }

    fun signIn() {
        val current = _uiState.value
        if (current.serverUrl.isBlank() || current.username.isBlank()) {
            _uiState.value = current.copy(
                testResult = "Server URL and username are required",
                isTestSuccess = false
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)

            val serverUrl = current.serverUrl.trim().trimEnd('/')
            val username = current.username.trim()
            val password = current.password
            val deviceId = embyCredentialsDataStore.cachedCredentials.deviceId
                .takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

            try {
                val unauthenticatedApi = buildTestApi(serverUrl, deviceId, token = null)

                val authResponse = unauthenticatedApi.authenticateByName(
                    EmbyAuthByNameRequestDto(username = username, pw = password)
                )
                if (!authResponse.isSuccessful) {
                    val message = when (authResponse.code()) {
                        401 -> "Invalid username or password"
                        else -> "Sign-in failed: ${authResponse.code()} ${authResponse.message()}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = message,
                        isTestSuccess = false
                    )
                    return@launch
                }

                val body = authResponse.body()
                if (body == null || body.accessToken.isBlank() || body.user.id.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "Sign-in succeeded but response was malformed",
                        isTestSuccess = false
                    )
                    return@launch
                }

                embyMediaService.clearMetadata()
                embyCredentialsDataStore.saveCredentials(
                    serverUrl = serverUrl,
                    apiKey = body.accessToken,
                    userId = body.user.id,
                    deviceId = deviceId
                )

                val serverName = runCatching {
                    val authedApi = buildTestApi(serverUrl, deviceId, token = body.accessToken)
                    authedApi.getSystemInfo().body()?.serverName
                }.getOrNull() ?: "Emby Server"

                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = "Connected to $serverName as ${body.user.name ?: username}",
                    isTestSuccess = true,
                    password = ""
                )
            } catch (error: Exception) {
                Log.e(TAG, "Sign-in error: ${error.message}", error)
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

    private fun buildTestApi(serverUrl: String, deviceId: String, token: String?): EmbyApi {
        val testClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url.toString()
                val rewrittenUrl = if (originalUrl.startsWith("http://localhost/")) {
                    originalUrl.replaceFirst("http://localhost", serverUrl)
                } else {
                    originalUrl
                }

                val authHeader = buildEmbyAuthorizationHeader(deviceId = deviceId, token = token)
                val builder = originalRequest.newBuilder()
                    .url(rewrittenUrl)
                    .header("X-Emby-Authorization", authHeader)
                if (!token.isNullOrBlank()) {
                    builder.header("X-Emby-Token", token)
                }
                chain.proceed(builder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(testClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(EmbyApi::class.java)
    }

    private fun buildEmbyAuthorizationHeader(deviceId: String, token: String?): String =
        buildString {
            append("MediaBrowser Client=\"OmnioTV\", Device=\"Android TV\"")
            append(", DeviceId=\"$deviceId\"")
            append(", Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) {
                append(", Token=\"$token\"")
            }
        }
}
