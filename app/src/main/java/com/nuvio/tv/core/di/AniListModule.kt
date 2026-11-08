package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.anilist.AndroidAniListAuthPersistence
import com.nuvio.tv.data.anilist.AndroidAniListSyncStorage
import com.nuvio.tv.data.anilist.AniListApiClient
import com.nuvio.tv.data.anilist.AniListAuthRepository
import com.nuvio.tv.data.anilist.AniListAuthStore
import com.nuvio.tv.data.anilist.AniListIdResolver
import com.nuvio.tv.data.anilist.AniListSyncRepository
import com.nuvio.tv.data.anilist.AniListTrackingHistoryWriter
import com.nuvio.tv.data.anilist.AniListTrackingLibraryProvider
import com.nuvio.tv.data.anilist.AniListTrackingProgressProvider
import com.nuvio.tv.data.anilist.AniListTrackingProvider
import com.nuvio.tv.data.anilist.AniListTrackingWrites
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.data.simkl.SimklApiConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AniListModule {
    @Provides
    @Singleton
    @Named("anilist")
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun authStore(persistence: AndroidAniListAuthPersistence, profileDataStore: ProfileDataStore): AniListAuthStore {
        val store = AniListAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: AniListAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun api(store: AniListAuthStore, @Named("anilist") client: OkHttpClient) = AniListApiClient(store, client)

    @Provides
    @Singleton
    fun resolver(
        api: AniListApiClient,
        simklIdResolver: SimklIdResolver,
        simklConfig: SimklApiConfiguration,
        @Named("anilist") client: OkHttpClient
    ) = AniListIdResolver(api, simklIdResolver, simklConfig, client)

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidAniListSyncStorage,
        store: AniListAuthStore,
        api: AniListApiClient,
        resolver: AniListIdResolver,
        profiles: ProfileManager,
        settings: TraktSettingsDataStore
    ) = AniListSyncRepository(
        storage, store, api, resolver, profiles.activeProfileId, settings.simklAnimeIdPreference,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun writes(
        sync: AniListSyncRepository,
        api: AniListApiClient,
        resolver: AniListIdResolver,
        store: AniListAuthStore
    ) = AniListTrackingWrites(sync, api, resolver, store)

    @Provides
    @Singleton
    fun authRepository(
        @ApplicationContext context: Context,
        store: AniListAuthStore,
        api: AniListApiClient,
        sync: AniListSyncRepository
    ) = AniListAuthRepository(context, store, api, sync, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @IntoSet
    fun trackingProvider(provider: AniListTrackingProvider): TrackingProvider = provider

    @Provides
    @IntoSet
    fun progressProvider(provider: AniListTrackingProgressProvider): TrackingProgressProvider = provider

    @Provides
    @IntoSet
    fun libraryProvider(provider: AniListTrackingLibraryProvider): TrackingLibraryProvider = provider

    @Provides
    @IntoSet
    fun historyWriter(writer: AniListTrackingHistoryWriter): TrackingHistoryWriter = writer
}
