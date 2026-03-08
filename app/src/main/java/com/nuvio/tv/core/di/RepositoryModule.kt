package com.nuvio.tv.core.di

import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.CatalogRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.data.repository.RecommendationRepositoryImpl
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.data.repository.SubtitleRepositoryImpl
import com.nuvio.tv.data.repository.SyncRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.RecommendationRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.SubtitleRepository
import com.nuvio.tv.domain.repository.SyncRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository

    @Binds
    @Singleton
    abstract fun bindRecommendationRepository(impl: RecommendationRepositoryImpl): RecommendationRepository
}
