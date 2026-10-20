package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.wetrakr.AndroidWeTrakrAuthPersistence
import com.nuvio.tv.data.wetrakr.AndroidWeTrakrSyncStorage
import com.nuvio.tv.data.wetrakr.AndroidWeTrakrUploadMemory
import com.nuvio.tv.data.wetrakr.WeTrakrUploadMemory
import com.nuvio.tv.data.wetrakr.OkHttpWeTrakrEngine
import com.nuvio.tv.data.wetrakr.WeTrakrApiClient
import com.nuvio.tv.data.wetrakr.WeTrakrAuthRepository
import com.nuvio.tv.data.wetrakr.WeTrakrAuthStore
import com.nuvio.tv.data.wetrakr.WeTrakrConfiguration
import com.nuvio.tv.data.wetrakr.WeTrakrHttpClient
import com.nuvio.tv.data.wetrakr.WeTrakrLocalUploader
import com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object WeTrakrModule {
    @Provides
    @Singleton
    fun configuration(): WeTrakrConfiguration = WeTrakrConfiguration(
        clientId = BuildConfig.WETRAKR_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun http(configuration: WeTrakrConfiguration): WeTrakrHttpClient = WeTrakrHttpClient(
        OkHttpWeTrakrEngine(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(),
            configuration
        )
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidWeTrakrAuthPersistence, profileDataStore: ProfileDataStore): WeTrakrAuthStore {
        val store = WeTrakrAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: WeTrakrAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(http: WeTrakrHttpClient, configuration: WeTrakrConfiguration, store: WeTrakrAuthStore) =
        WeTrakrAuthRepository(http, configuration, store)

    @Provides
    @Singleton
    fun api(http: WeTrakrHttpClient, auth: WeTrakrAuthRepository, store: WeTrakrAuthStore) =
        WeTrakrApiClient(http, auth, store)

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidWeTrakrSyncStorage,
        auth: WeTrakrAuthStore,
        api: WeTrakrApiClient,
        profiles: ProfileManager
    ) = WeTrakrSyncRepository(
        storage, auth, api, profiles.activeProfileId,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun localUploader(
        api: WeTrakrApiClient,
        sync: WeTrakrSyncRepository,
        auth: WeTrakrAuthStore,
        memory: AndroidWeTrakrUploadMemory
    ) = WeTrakrLocalUploader(api, sync, auth, memory, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun uploadMemory(memory: AndroidWeTrakrUploadMemory): WeTrakrUploadMemory = memory
}
