package com.omnio.tv.di

import com.omnio.tv.domain.auth.AuthManager
import com.omnio.tv.core.plugin.PluginManager
import com.omnio.tv.core.plugin.PluginRuntime
import com.omnio.tv.core.sync.PluginSyncService
import com.omnio.tv.data.local.PluginDataStore
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
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager
    ): PluginManager {
        return PluginManager(dataStore, runtime, pluginSyncService, authManager)
    }
}
