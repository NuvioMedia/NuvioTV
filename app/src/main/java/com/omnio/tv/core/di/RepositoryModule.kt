package com.omnio.tv.core.di

import com.omnio.tv.data.repository.AddonRepositoryImpl
import com.omnio.tv.data.repository.CatalogRepositoryImpl
import com.omnio.tv.data.repository.LibraryRepositoryImpl
import com.omnio.tv.data.repository.MetaRepositoryImpl
import com.omnio.tv.data.repository.StreamRepositoryImpl
import com.omnio.tv.data.repository.SubtitleRepositoryImpl
import com.omnio.tv.data.repository.SyncRepositoryImpl
import com.omnio.tv.data.repository.WatchProgressRepositoryImpl
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.CatalogRepository
import com.omnio.tv.domain.repository.LibraryRepository
import com.omnio.tv.domain.repository.MetaRepository
import com.omnio.tv.domain.repository.StreamRepository
import com.omnio.tv.domain.repository.SubtitleRepository
import com.omnio.tv.domain.repository.SyncRepository
import com.omnio.tv.domain.repository.WatchProgressRepository
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
}
