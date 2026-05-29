package com.nuvio.tv.core.cloud

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridServiceCredential
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.data.local.DebridSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class CloudLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DebridSettingsDataStore,
    torboxApi: TorboxCloudLibraryProviderApi,
    premiumizeApi: PremiumizeCloudLibraryProviderApi
) {
    private val providerApis: List<CloudLibraryProviderApi> = listOf(torboxApi, premiumizeApi)

    suspend fun refresh(): CloudLibraryUiState {
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryUiState(isLoaded = true, isEnabled = false)
        }

        val credentials = DebridProviders.configuredServices(settings)
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = context.getString(
                        R.string.cloud_library_error_provider_unavailable,
                        credential.provider.displayName
                    )
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message
                        )
                    }
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isEnabled = true,
            isRefreshing = false,
            providers = providerStates
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed(
                context.getString(R.string.cloud_library_error_disabled)
            )
        }
        val credential = DebridProviders.configuredServices(settings)
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file
        )
    }

    suspend fun connectedCloudCredentials(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        return settings
            .takeIf { it.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
    }
}
