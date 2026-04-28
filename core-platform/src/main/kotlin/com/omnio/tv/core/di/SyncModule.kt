package com.omnio.tv.core.di

import com.omnio.tv.core.sync.AddonSyncServiceImpl
import com.omnio.tv.core.sync.LibrarySyncServiceImpl
import com.omnio.tv.core.sync.WatchProgressSyncServiceImpl
import com.omnio.tv.core.sync.WatchedItemsSyncServiceImpl
import com.omnio.tv.domain.sync.AddonSyncService
import com.omnio.tv.domain.sync.LibrarySyncService
import com.omnio.tv.domain.sync.WatchProgressSyncService
import com.omnio.tv.domain.sync.WatchedItemsSyncService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindAddonSyncService(impl: AddonSyncServiceImpl): AddonSyncService

    @Binds
    @Singleton
    abstract fun bindLibrarySyncService(impl: LibrarySyncServiceImpl): LibrarySyncService

    @Binds
    @Singleton
    abstract fun bindWatchedItemsSyncService(impl: WatchedItemsSyncServiceImpl): WatchedItemsSyncService

    @Binds
    @Singleton
    abstract fun bindWatchProgressSyncService(impl: WatchProgressSyncServiceImpl): WatchProgressSyncService
}
