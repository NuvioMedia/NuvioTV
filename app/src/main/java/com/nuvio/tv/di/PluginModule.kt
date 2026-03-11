package com.nuvio.tv.di

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.StreamCacheDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        streamCacheDataStore: StreamCacheDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager
    ): PluginManager {
        return PluginManager(dataStore, streamCacheDataStore, runtime, pluginSyncService, authManager)
    }
}
