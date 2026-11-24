package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.floppy.AndroidFloppyAuthPersistence
import com.nuvio.tv.data.floppy.FloppyApiClient
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.local.ProfileDataStore
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
object FloppyModule {
    @Provides
    @Singleton
    fun authStore(persistence: AndroidFloppyAuthPersistence, profileDataStore: ProfileDataStore): FloppyAuthStore {
        val store = FloppyAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: FloppyAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun apiClient(): FloppyApiClient = FloppyApiClient(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
        BuildConfig.VERSION_NAME
    )
}
